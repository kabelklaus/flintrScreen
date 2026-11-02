package com.flintr.screen.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.flintr.screen.MainActivity
import com.flintr.screen.engine.AirPlayEngine
import com.flintr.screen.render.AudioRenderer
import com.flintr.screen.render.VideoRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.NetworkInterface
import java.security.SecureRandom

/** Keeps the AirPlay receiver running and advertised while the app is alive. */
class ReceiverService : Service() {

    enum class Status { STOPPED, READY, CONNECTED, ERROR }

    private var engine: AirPlayEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val nsdRegistrations = mutableListOf<NsdManager.RegistrationListener>()
    private val audio = AudioRenderer()
    // Runs on native RAOP threads: only touch thread-safe state here, never engine.stop().
    private val listener = object : AirPlayEngine.Listener {
        private var connections = 0

        override fun onVideoFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) =
            VideoRenderer.feedFrame(data, ntpTimeNs, isH265)
        override fun onVideoSize(width: Int, height: Int) = VideoRenderer.setResolution(width, height)
        override fun onAudio(compressionType: Int, samplesPerFrame: Int, usingScreen: Boolean) {
            if (!audio.start()) Log.w(TAG, "audio output did not start")
            audio.setFormat(compressionType, samplesPerFrame)
        }
        override fun onClientVolume(db: Float) = audio.setClientGain(AudioRenderer.gainFromDb(db))
        override fun onClientVolumeQuery(): Float = AudioRenderer.dbFromGain(audio.clientGain)
        // The next audio SETUP fires onAudio again, which reopens the output.
        override fun onAudioTeardown() = audio.stop()
        override fun onStatus(status: String) {
            if (status == "mirror:false") VideoRenderer.stopSession()
            val open = synchronized(this) {
                when (status) {
                    "connection:init" -> ++connections
                    "connection:destroy" -> { connections = (connections - 1).coerceAtLeast(0); connections }
                    else -> return
                }.also { open ->
                    // Under the lock so a racing new session's start/volume isn't undone.
                    // Senders may drop without a TEARDOWN: release the output when the last one goes.
                    if (open == 0) audio.stop()
                    // Forget the previous sender's volume on both edges: the old RTP thread can
                    // still deliver a late volume change after connection:destroy.
                    if (open == 0 || (open == 1 && status == "connection:init")) audio.setClientGain(1f)
                }
            }
            _status.value = if (open > 0) Status.CONNECTED else Status.READY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "flintrScreen", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        if (engine == null && !startReceiver()) {
            shutdown()
            _status.value = Status.ERROR
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        if (_status.value != Status.ERROR) _status.value = Status.STOPPED
        super.onDestroy()
    }

    private fun startReceiver(): Boolean {
        // No timeout on purpose: the receiver must stay reachable until the service is destroyed.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flintrScreen:receiver")
            .apply { setReferenceCounted(false); acquire() }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION") // HIGH_PERF is a no-op from API 34, but effective on the Android 11 target box
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "flintrScreen:receiver")
            .apply { setReferenceCounted(false); acquire() }
        multicastLock = wifi.createMulticastLock("flintrScreen:mdns")
            .apply { setReferenceCounted(false); acquire() }

        // Persistent key: senders keep their pairing trust across restarts.
        val keyFile = filesDir.resolve("airplay.pem").absolutePath
        val e = AirPlayEngine(keyFile, hwAddr(), listener, audio)
        val am = getSystemService(AudioManager::class.java)
        val port = e.start(
            DEVICE_NAME,
            sampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            framesPerBurst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0,
        )
        if (port < 0) {
            Log.e(TAG, "engine start failed")
            return false
        }
        engine = e
        e.mdnsServices().forEach { registerMdns(it, port) }
        _status.value = Status.READY
        Log.i(TAG, "receiver up on port $port")
        return true
    }

    private fun shutdown() {
        val nsd = getSystemService(NsdManager::class.java)
        nsdRegistrations.forEach {
            try { nsd.unregisterService(it) } catch (_: IllegalArgumentException) {}
        }
        nsdRegistrations.clear()
        engine?.stop()
        engine = null
        multicastLock?.release()
        multicastLock = null
        wifiLock?.release()
        wifiLock = null
        wakeLock?.release()
        wakeLock = null
    }

    private fun registerMdns(s: AirPlayEngine.MdnsService, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = s.name
            serviceType = s.type
            this.port = port
            s.txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "${s.type} registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "${s.type} registration failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        nsdRegistrations += l
    }

    private fun promoteToForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("flintrScreen aktiv — wartet auf Verbindung")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    // Same identity scheme as the reference: real wlan/eth MAC if readable, otherwise a
    // random locally-administered MAC generated once and persisted, so senders see a stable deviceid.
    private fun hwAddr(): ByteArray {
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }
                .firstNotNullOfOrNull { it.hardwareAddress?.takeIf(::isUsableMac) }
                ?.let { return it }
        } catch (e: Exception) {
            Log.w(TAG, "hardware address unavailable", e)
        }
        val prefs = getSharedPreferences("receiver", Context.MODE_PRIVATE)
        prefs.getString(PREF_MAC, null)?.let(::macFromString)?.takeIf(::isUsableMac)?.let { return it }
        val mac = ByteArray(6).also { SecureRandom().nextBytes(it) }
        mac[0] = ((mac[0].toInt() and 0xF0) or 0x0A).toByte()
        prefs.edit { putString(PREF_MAC, mac.joinToString(":") { "%02x".format(it) }) }
        return mac
    }

    private fun isUsableMac(mac: ByteArray): Boolean =
        mac.size == 6 && mac.any { it != 0.toByte() } &&
            !(mac[0] == 0x02.toByte() && mac.drop(1).all { it == 0.toByte() })

    private fun macFromString(s: String): ByteArray? =
        s.split(":").map { it.toIntOrNull(16)?.toByte() ?: return null }.toByteArray()

    companion object {
        private const val TAG = "ReceiverService"
        private const val CHANNEL_ID = "receiver"
        private const val NOTIFICATION_ID = 1
        private const val PREF_MAC = "fallback_mac"
        const val DEVICE_NAME = "flintrScreen"

        private val _status = MutableStateFlow(Status.STOPPED)
        val status: StateFlow<Status> = _status

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
    }
}

package com.flintr.screen.engine

import androidx.annotation.Keep
import com.flintr.screen.render.AudioRenderer
import java.nio.ByteBuffer

/**
 * Kotlin facade over libairplay_native (UxPlay). The native side looks up the
 * callback methods below by exact name and signature (android_raop_callbacks.c,
 * log_sink.h), so they must not be renamed, removed or minified.
 *
 * Listener methods run on native RAOP threads. Do not call [stop] from inside
 * them: raop_destroy tears down those threads and can deadlock.
 */
@Keep
class AirPlayEngine(
    private val keyFile: String,
    private val hwAddr: ByteArray,
    private val listener: Listener,
    private val audio: AudioRenderer,
) {
    interface Listener {
        fun onVideoFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean)
        /** Encoded stream size; arrives before the keyframe it describes, on the same thread. */
        fun onVideoSize(width: Int, height: Int)
        /** Fires on every audio stream SETUP, including the one after an [onAudioTeardown]. */
        fun onAudio(compressionType: Int, samplesPerFrame: Int, usingScreen: Boolean)
        /** Sender volume in dB: -30..0, -144 = mute. */
        fun onClientVolume(db: Float)
        /** Volume in dB reported back to the sender on SETUP. */
        fun onClientVolumeQuery(): Float
        /** The sender tore down the audio stream (the session itself may continue). */
        fun onAudioTeardown()
        fun onStatus(status: String)
    }

    private var handle = 0L

    /**
     * Starts the RAOP server; returns the bound port or -1. [sampleRate] and
     * [framesPerBurst] are the AudioManager output properties (0 = unknown); Oboe's
     * OpenSL ES backend needs them for low-latency sizing.
     */
    @Synchronized
    fun start(name: String, port: Int = 0, sampleRate: Int = 0, framesPerBurst: Int = 0): Int {
        if (handle != 0L) return -1
        nativeSetDefaultStreamValues(sampleRate, framesPerBurst)
        val h = nativeInit(this, hwAddr, name, keyFile, nohold = false, requirePin = false)
        if (h == 0L) return -1
        // Same order as the reference: the audio engine is configured before RAOP accepts senders.
        audio.attachEngine(this, h)
        val bound = nativeStart(h, port)
        if (bound < 0) {
            audio.detachEngine()
            nativeDestroy(h)
            return -1
        }
        handle = h
        return bound
    }

    class MdnsService(val name: String, val type: String, val txt: Map<String, String>)

    /**
     * Records to advertise for the running server. The native dnssd shim only
     * builds them; the caller must register them with NsdManager.
     */
    @Synchronized
    fun mdnsServices(): List<MdnsService> {
        val h = handle
        if (h == 0L) return emptyList()
        return listOfNotNull(
            nativeGetRaopServiceName(h)?.let { MdnsService(it, "_raop._tcp", nativeGetRaopTxtRecords(h).orEmpty()) },
            nativeGetServerName(h)?.let { MdnsService(it, "_airplay._tcp", nativeGetAirplayTxtRecords(h).orEmpty()) },
        )
    }

    @Synchronized
    fun stop() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        audio.detachEngine()
        nativeStop(h)
        nativeDestroy(h)
    }

    // ---- callbacks invoked from native threads ----

    fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) =
        listener.onVideoFrame(data, ntpTimeNs, isH265)

    // srcW/srcH is the sender's screen, w/h the encoded stream (raop_rtp_mirror.c).
    fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) =
        listener.onVideoSize(w.toInt(), h.toInt())

    fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) =
        listener.onAudio(ct, spf, usingScreen)

    fun onConnectionInit() = listener.onStatus("connection:init")
    fun onConnectionDestroy() = listener.onStatus("connection:destroy")
    fun onConnectionReset(reason: Int) = listener.onStatus("connection:reset:$reason")
    fun onMirrorRunning(running: Boolean) = listener.onStatus("mirror:$running")
    fun onLog(msg: String) = listener.onStatus("log:$msg")

    fun onClientVolume(db: Float) = listener.onClientVolume(db)
    fun onClientVolumeQuery(): Float = listener.onClientVolumeQuery()
    fun onAudioTeardown() = listener.onAudioTeardown()

    // Not used yet; present so native GetMethodID lookups succeed.
    fun onDisplayPin(pin: String) {}
    fun onMetadata(data: ByteArray) {}
    fun onCoverArt(data: ByteArray) {}
    fun onProgress(start: Long, curr: Long, end: Long) {}
    fun onDacpId(dacpId: String, activeRemote: String) {}
    fun onVideoPlay(location: String, startPositionSeconds: Float) {}
    fun onVideoScrub(positionSeconds: Float) {}
    fun onVideoRate(rate: Float) {}
    fun onVideoStop() {}
    fun onVideoSessionPoll() {}

    // ---- audio control for AudioRenderer; the JNI externals stay private because
    // `internal` names are mangled in bytecode and JNI would no longer resolve them ----

    internal fun audioConfigure(h: Long, c: AudioRenderer.Config) = nativeServerAudioConfigure(
        h, c.cushionMs, c.percentilePct, c.oboeBufferFrames,
        c.forceSwAlac, c.forceSwAac, c.realtimePriority, c.lowLatency, c.benchmarkLog,
    )
    internal fun audioStart(h: Long) = nativeServerAudioStart(h)
    internal fun audioStop(h: Long) = nativeServerAudioStop(h)
    internal fun audioSetClientGain(h: Long, gain: Float) = nativeServerAudioSetClientGain(h, gain)
    internal fun audioFormat(h: Long, ct: Int, spf: Int) = nativeServerAudioFormat(h, ct, spf)

    // ---- JNI surface (native_bridge.cpp) ----

    private external fun nativeInit(
        callback: AirPlayEngine,
        hwAddr: ByteArray,
        name: String,
        keyFile: String,
        nohold: Boolean,
        requirePin: Boolean,
    ): Long

    private external fun nativeStart(handle: Long, port: Int): Int
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)

    private external fun nativeSetDisplaySize(handle: Long, w: Int, h: Int, fps: Int)
    private external fun nativeSetPlist(handle: Long, key: String, value: Int)
    private external fun nativeSetH265Enabled(handle: Long, enabled: Boolean)
    private external fun nativeSetCodecs(handle: Long, alac: Boolean, aac: Boolean)
    private external fun nativeSetHlsEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetLang(handle: Long, lang: String)
    private external fun nativeSetFeature(handle: Long, bit: Int, enabled: Boolean)
    private external fun nativeUpdatePlaybackInfo(
        handle: Long, position: Float, duration: Float, rate: Float, readyToPlay: Boolean,
    )

    private external fun nativeGetRaopTxtRecords(handle: Long): Map<String, String>?
    private external fun nativeGetAirplayTxtRecords(handle: Long): Map<String, String>?
    private external fun nativeGetRaopServiceName(handle: Long): String?
    private external fun nativeGetServerName(handle: Long): String?

    private external fun nativeSetDefaultStreamValues(sampleRate: Int, framesPerBurst: Int)
    private external fun nativeServerAudioConfigure(
        handle: Long, cushionMs: Int, percentilePct: Int, oboeBufferFrames: Int,
        forceSwAlac: Boolean, forceSwAac: Boolean, realtimePriority: Boolean,
        lowLatency: Boolean, benchmarkLog: Boolean,
    ): Boolean
    private external fun nativeServerAudioStart(handle: Long): Boolean
    private external fun nativeServerAudioStop(handle: Long)
    private external fun nativeServerAudioSetClientGain(handle: Long, gain: Float)
    private external fun nativeServerAudioFormat(handle: Long, ct: Int, spf: Int)
    private external fun nativeServerAudioDebug(handle: Long, buf: ByteBuffer): Boolean

    companion object {
        init {
            System.loadLibrary("airplay_native")
        }
    }
}

package com.flintr.screen.engine

import androidx.annotation.Keep
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
) {
    interface Listener {
        fun onVideoFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean)
        fun onAudio(compressionType: Int, samplesPerFrame: Int, usingScreen: Boolean)
        fun onStatus(status: String)
    }

    private var handle = 0L

    /** Starts the RAOP server; returns the bound port or -1. */
    @Synchronized
    fun start(name: String, port: Int = 0): Int {
        if (handle != 0L) return -1
        val h = nativeInit(this, hwAddr, name, keyFile, nohold = false, requirePin = false)
        if (h == 0L) return -1
        val bound = nativeStart(h, port)
        if (bound < 0) {
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
        nativeStop(h)
        nativeDestroy(h)
    }

    // ---- callbacks invoked from native threads ----

    fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) =
        listener.onVideoFrame(data, ntpTimeNs, isH265)

    fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) =
        listener.onAudio(ct, spf, usingScreen)

    fun onConnectionInit() = listener.onStatus("connection:init")
    fun onConnectionDestroy() = listener.onStatus("connection:destroy")
    fun onConnectionReset(reason: Int) = listener.onStatus("connection:reset:$reason")
    fun onMirrorRunning(running: Boolean) = listener.onStatus("mirror:$running")
    fun onLog(msg: String) = listener.onStatus("log:$msg")

    // Not used yet; present so native GetMethodID lookups succeed.
    fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {}
    fun onClientVolume(db: Float) {}
    fun onClientVolumeQuery(): Float = 0f
    fun onAudioTeardown() {}
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

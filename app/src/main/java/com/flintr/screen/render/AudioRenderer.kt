package com.flintr.screen.render

import com.flintr.screen.engine.AirPlayEngine
import kotlin.math.log10
import kotlin.math.pow

/**
 * Control layer for the native audio engine (decode + Oboe output live entirely in
 * libairplay_native; no audio data passes through Kotlin). Mirrors the reference
 * AudioRenderer. Own lock, never the engine's: native RAOP threads call in here
 * while AirPlayEngine.stop() holds its lock and joins them.
 */
class AudioRenderer {

    /** Mirrors the native AudioConfig struct; values are the reference defaults. */
    data class Config(
        val cushionMs: Int = 0,           // 0 = adaptive tuner
        val percentilePct: Int = 95,
        val oboeBufferFrames: Int = 0,    // 0 = auto
        val forceSwAlac: Boolean = false,
        val forceSwAac: Boolean = false,
        val realtimePriority: Boolean = true,
        val lowLatency: Boolean = true,
        val benchmarkLog: Boolean = false,
    )

    @Volatile var config = Config(); private set
    @Volatile var clientGain = 1f; private set

    private var engine: AirPlayEngine? = null
    private var handle = 0L

    // Must run between nativeInit and nativeStart: without configure the native
    // engine has no timeline/output and silently drops all audio.
    @Synchronized
    internal fun attachEngine(engine: AirPlayEngine, handle: Long) {
        this.engine = engine
        this.handle = handle
        pushConfig()
        engine.audioSetClientGain(handle, clientGain)
    }

    // Must run before nativeStop/nativeDestroy so late callbacks become no-ops.
    @Synchronized
    internal fun detachEngine() {
        engine = null
        handle = 0L
    }

    /** Opens the output stream; idempotent natively. Returns false if no output is active. */
    @Synchronized
    fun start(): Boolean = engine?.audioStart(handle) ?: false

    /** Releases the Oboe stream while idle; the engine itself lives until server destroy. */
    @Synchronized
    fun stop() {
        engine?.audioStop(handle)
    }

    /**
     * Linear gain; the native mixer only attenuates (>= 1 is unity). NaN is ignored and
     * the range limited to 0..1, so a bad value can't reach the native int16 conversion.
     */
    @Synchronized
    fun setClientGain(gain: Float) {
        if (gain.isNaN()) return
        clientGain = gain.coerceIn(0f, 1f)
        engine?.audioSetClientGain(handle, clientGain)
    }

    @Synchronized
    fun updateConfig(newConfig: Config) {
        config = newConfig
        pushConfig()
    }

    @Synchronized
    fun setFormat(ct: Int, spf: Int) {
        engine?.audioFormat(handle, ct, spf)
    }

    private fun pushConfig() {
        engine?.audioConfigure(handle, config)
    }

    companion object {
        const val CT_ALAC = 2
        const val CT_AAC_LC = 4
        const val CT_AAC_ELD = 8

        // AirPlay sender volume: -30..0 dB, -144 = mute. Same curve as the reference.
        // NaN (the sender's value is parsed with %f) stays NaN, which setClientGain ignores.
        fun gainFromDb(db: Float): Float {
            if (db.isNaN()) return db
            if (db <= -30f) return 0f
            return 10f.pow(db.coerceAtMost(0f) / 20f)
        }

        fun dbFromGain(gain: Float): Float =
            if (gain <= 0f) -144f else (20f * log10(gain)).coerceIn(-30f, 0f)
    }
}

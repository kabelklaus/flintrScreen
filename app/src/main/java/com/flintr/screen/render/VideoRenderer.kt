package com.flintr.screen.render

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decodes mirrored H.264/H.265 access units straight into the SurfaceView's surface.
 *
 * All codec state lives on one HandlerThread (MediaCodec runs in async mode on it), so
 * [feedFrame] only posts and returns: the native RAOP thread never waits on the decoder,
 * and frames are dropped instead of queued when the decoder falls behind. A decoder only
 * starts on a frame carrying parameter sets (keyframe), because anything else would
 * reference frames it never saw and render as corruption.
 */
object VideoRenderer {
    private const val TAG = "VideoRenderer"
    private const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
    // Sender default stream size; the real size comes in-band with the SPS.
    private const val WIDTH = 1920
    private const val HEIGHT = 1080
    private const val MAX_INPUT_SIZE = WIDTH * HEIGHT * 3 / 4
    private const val MAX_POSTED_BYTES = 8 * 1024 * 1024
    private const val MAX_READY = 8
    private const val SURFACE_RELEASE_TIMEOUT_MS = 1000L

    private class Frame(val data: ByteArray, val ptsUs: Long, val h265: Boolean)

    private val handler = Handler(HandlerThread("flintr-video").apply { start() }.looper)
    private val postedBytes = AtomicInteger()

    // Handler-thread only.
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var codecH265 = false
    private var codecParams: ByteArray? = null
    private val ready = ArrayDeque<Frame>()
    private val freeInputs = ArrayDeque<Int>()

    private val _active = MutableStateFlow(false)
    /** True while decoded frames are being shown. */
    val active: StateFlow<Boolean> = _active

    private val _aspectRatio = MutableStateFlow(16f / 9f)
    /** Display aspect ratio of the current stream. */
    val aspectRatio: StateFlow<Float> = _aspectRatio

    /** Called on native RAOP threads; never blocks. */
    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        if (data.size > MAX_INPUT_SIZE) {
            Log.w(TAG, "dropping oversized frame (${data.size} bytes)")
            return
        }
        // Bounded backlog in case the decoder thread stalls (e.g. slow codec setup).
        if (postedBytes.addAndGet(data.size) > MAX_POSTED_BYTES) {
            postedBytes.addAndGet(-data.size)
            return
        }
        val frame = Frame(data, ntpTimeNs / 1000, isH265)
        handler.post {
            postedBytes.addAndGet(-data.size)
            accept(frame)
        }
    }

    fun setSurface(s: Surface) {
        handler.post {
            if (surface === s) return@post
            stopCodec()
            surface = s
        }
    }

    /**
     * Blocks (bounded) until the codec has let go of [s]: SurfaceHolder requires that the
     * surface is no longer drawn to once surfaceDestroyed returns.
     */
    fun clearSurface(s: Surface) {
        val done = CountDownLatch(1)
        handler.post {
            if (surface === s) {
                stopCodec()
                surface = null
                _active.value = false
            }
            done.countDown()
        }
        if (!done.await(SURFACE_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "surface release timed out")
    }

    /** Mirroring ended: free the decoder and fall back to the idle screen. */
    fun stopSession() {
        handler.post {
            stopCodec()
            _active.value = false
        }
    }

    private fun accept(f: Frame) {
        val params = parameterSets(f.data, f.h265)
        if (params != null && (codec == null || f.h265 != codecH265 || !params.contentEquals(codecParams))) {
            // Senders only send parameter sets on (re)connect and size changes, so this is the
            // one place a decoder can (re)start without referencing frames it never saw.
            if (!startCodec(f.h265)) return
            codecParams = params
        } else if (codec == null || f.h265 != codecH265) {
            return
        }
        // Dropping costs a few corrupted frames; stopping would freeze until the next reconnect.
        if (ready.size >= MAX_READY) {
            Log.w(TAG, "decoder not keeping up, dropping frame")
            return
        }
        ready.addLast(f)
        queueReady()
    }

    private fun queueReady() {
        val c = codec ?: return
        try {
            while (ready.isNotEmpty() && freeInputs.isNotEmpty()) {
                val f = ready.removeFirst()
                val idx = freeInputs.first()
                val buf = c.getInputBuffer(idx)
                if (buf == null || buf.capacity() < f.data.size) {
                    Log.w(TAG, "dropping frame of ${f.data.size} bytes, input buffer too small")
                    continue
                }
                freeInputs.removeFirst()
                buf.clear()
                buf.put(f.data)
                c.queueInputBuffer(idx, 0, f.data.size, f.ptsUs, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "queueing input failed, waiting for next keyframe", e)
            fail()
        }
    }

    /** Unrecoverable codec state: back to the idle screen until the sender restarts the stream. */
    private fun fail() {
        stopCodec()
        _active.value = false
    }

    private fun startCodec(h265: Boolean): Boolean {
        stopCodec()
        val s = surface ?: return false
        val mime = if (h265) HEVC else AVC
        for (info in decodersFor(mime)) {
            val c = try {
                MediaCodec.createByCodecName(info.name)
            } catch (e: Exception) {
                Log.w(TAG, "cannot create ${info.name}", e)
                continue
            }
            try {
                c.setCallback(Callbacks, handler)
                c.configure(format(mime, info), s, null, 0)
                c.start()
            } catch (e: Exception) {
                Log.w(TAG, "${info.name} rejected configuration", e)
                c.release()
                continue
            }
            codec = c
            codecH265 = h265
            Log.i(TAG, "decoding $mime with ${info.name}")
            return true
        }
        Log.e(TAG, "no usable decoder for $mime")
        return false
    }

    private fun stopCodec() {
        ready.clear()
        freeInputs.clear()
        codecParams = null
        val c = codec ?: return
        codec = null
        try {
            c.stop()
        } catch (e: Exception) {
            Log.w(TAG, "codec stop failed", e)
        }
        try {
            c.release()
        } catch (e: Exception) {
            Log.w(TAG, "codec release failed", e)
        }
    }

    private fun format(mime: String, info: MediaCodecInfo) = MediaFormat.createVideoFormat(mime, WIDTH, HEIGHT).apply {
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        val caps = info.getCapabilitiesForType(mime)
        if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)) {
            setInteger(MediaFormat.KEY_MAX_WIDTH, WIDTH)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, HEIGHT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        ) {
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
    }

    /** Hardware decoders first, software only as fallback. */
    private fun decodersFor(mime: String): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && !it.name.endsWith(".secure") }
            .filter { it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .sortedBy { isSoftware(it) }

    private fun isSoftware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isSoftwareOnly
        val name = info.name.lowercase()
        return name.startsWith("omx.google.") || name.startsWith("c2.android.") || ".sw." in name
    }

    /**
     * The non-slice prefix of the access unit if it carries SPS (H.264) or VPS/SPS (H.265),
     * i.e. a fresh decoder can start from it; null otherwise. Scans Annex-B start codes only
     * up to the first slice.
     */
    private fun parameterSets(d: ByteArray, h265: Boolean): ByteArray? {
        var found = false
        var i = 0
        while (i + 4 < d.size) {
            if (d[i] == 0.toByte() && d[i + 1] == 0.toByte() && d[i + 2] == 1.toByte()) {
                val header = d[i + 3].toInt()
                val slice = if (h265) {
                    val type = (header shr 1) and 0x3F
                    if (type == 32 || type == 33) found = true
                    type < 32
                } else {
                    val type = header and 0x1F
                    if (type == 7) found = true
                    type in 1..5
                }
                if (slice) return if (found) d.copyOf(i) else null
                i += 3
            } else {
                i++
            }
        }
        return if (found) d else null
    }

    private object Callbacks : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            if (c !== codec) return
            freeInputs.addLast(index)
            queueReady()
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (c !== codec) return
            try {
                // Render immediately: lowest latency, the decoder drops late frames itself.
                c.releaseOutputBuffer(index, info.size > 0)
            } catch (e: Exception) {
                Log.w(TAG, "rendering failed, waiting for next keyframe", e)
                fail()
                return
            }
            _active.value = true
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            if (c !== codec) return
            val w = cropSpan(format, "crop-left", "crop-right", MediaFormat.KEY_WIDTH)
            val h = cropSpan(format, "crop-top", "crop-bottom", MediaFormat.KEY_HEIGHT)
            if (w > 0 && h > 0) _aspectRatio.value = w.toFloat() / h
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            if (c !== codec) return
            Log.w(TAG, "codec error, waiting for next keyframe", e)
            fail()
        }

        private fun cropSpan(f: MediaFormat, lo: String, hi: String, size: String): Int =
            if (f.containsKey(lo) && f.containsKey(hi)) f.getInteger(hi) - f.getInteger(lo) + 1
            else if (f.containsKey(size)) f.getInteger(size) else 0
    }
}

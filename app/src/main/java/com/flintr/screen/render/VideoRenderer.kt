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
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    // Advertised display size (UxPlay default); used until the sender reports its own.
    private val DEFAULT_SIZE = Size(1920, 1080)
    private const val MIN_DIM = 16
    private const val MAX_DIM = 8192
    private const val MIN_INPUT_SIZE = 4 * 1024 * 1024
    private const val MAX_SPS_BYTES = 512
    private const val MAX_POSTED_BYTES = 8 * 1024 * 1024
    // Frames waiting for a codec input buffer. Codec setup on the Amlogic box takes long
    // enough that a handful of frames pile up, and every drop breaks the reference chain
    // until the next keyframe, so the bound is generous and only guards memory.
    private const val MAX_READY = 64
    private const val MAX_READY_BYTES = 8 * 1024 * 1024
    private const val STATS_INTERVAL_MS = 2000L
    private const val SURFACE_RELEASE_TIMEOUT_MS = 1000L

    private class Frame(val data: ByteArray, val ptsUs: Long, val h265: Boolean)
    private data class Size(val w: Int, val h: Int) {
        override fun toString() = "${w}x$h"
    }

    private val handler = Handler(HandlerThread("flintr-video").apply { start() }.looper)
    private val postedBytes = AtomicInteger()

    // Counters for the periodic stats line; the atomic ones are bumped on native threads.
    // skipped: no running codec for the frame (waiting for a keyframe, or no decoder/surface).
    // flushed: frames still waiting for input when the codec was stopped or restarted.
    private val received = AtomicInteger()
    private val droppedOversize = AtomicInteger()
    private val droppedBacklog = AtomicInteger()
    private val statsTicking = AtomicBoolean()
    private var droppedSmallBuf = 0
    private var skipped = 0
    private var flushed = 0
    private var queued = 0
    private var decoded = 0
    private var backlogWarned = false
    private var statsLastReceived = 0

    // Handler-thread only.
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var codecH265 = false
    private var codecParams: ByteArray? = null
    private var codecSize: Size? = null
    private var reportedSize: Size? = null
    private val ready = ArrayDeque<Frame>()
    private var readyBytes = 0
    private val freeInputs = ArrayDeque<Int>()

    private val _active = MutableStateFlow(false)
    /** True while decoded frames are being shown. */
    val active: StateFlow<Boolean> = _active

    private val _aspectRatio = MutableStateFlow(16f / 9f)
    /** Display aspect ratio of the current stream. */
    val aspectRatio: StateFlow<Float> = _aspectRatio

    /** Called on native RAOP threads; never blocks. */
    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        received.incrementAndGet()
        // Started from here so the stats also show frames that never reach the handler.
        if (statsTicking.compareAndSet(false, true)) handler.postDelayed(::statsTick, STATS_INTERVAL_MS)
        if (data.size > MAX_POSTED_BYTES) {
            if (droppedOversize.getAndIncrement() == 0) Log.w(TAG, "dropping oversized frame (${data.size} bytes)")
            return
        }
        // Bounded backlog in case the decoder thread stalls (e.g. slow codec setup). Parameter-set
        // frames get extra room: losing one can leave the decoder without a start until the next size change.
        val posted = postedBytes.addAndGet(data.size)
        if (posted > MAX_POSTED_BYTES && (posted > 2 * MAX_POSTED_BYTES || parameterSets(data, isH265) == null)) {
            postedBytes.addAndGet(-data.size)
            droppedBacklog.incrementAndGet()
            return
        }
        val frame = Frame(data, ntpTimeNs / 1000, isH265)
        handler.post {
            postedBytes.addAndGet(-data.size)
            accept(frame)
        }
    }

    /**
     * Stream size announced by the sender. Called on the same native thread as [feedFrame],
     * before the keyframe it describes, so the posted order matches the stream order. The
     * size is only a fallback for when the SPS cannot be parsed; the codec is (re)started
     * on the next parameter-set frame, as a restart on anything else would show corruption.
     */
    fun setResolution(w: Int, h: Int) {
        if (w !in MIN_DIM..MAX_DIM || h !in MIN_DIM..MAX_DIM) {
            Log.w(TAG, "ignoring reported stream size ${w}x$h")
            return
        }
        handler.post {
            reportedSize = Size(w, h)
            Log.i(TAG, "sender reports stream size ${w}x$h")
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
            reportedSize = null
            _active.value = false
            logStats()
            resetStats()
        }
    }

    private fun accept(f: Frame) {
        val params = parameterSets(f.data, f.h265)
        if (params != null) {
            // The SPS is what the decoder will actually see. Without it, take the larger of the
            // reported and default size: a too-big configuration only costs memory.
            val size = spsSize(params, f.h265)
                ?: (reportedSize ?: DEFAULT_SIZE).let { Size(maxOf(it.w, DEFAULT_SIZE.w), maxOf(it.h, DEFAULT_SIZE.h)) }
            if (codec == null || f.h265 != codecH265 || !params.contentEquals(codecParams) || size != codecSize) {
                // Senders only send parameter sets on (re)connect and size changes, so this is the
                // one place a decoder can (re)start without referencing frames it never saw.
                if (!startCodec(f.h265, size)) {
                    skipped++
                    return
                }
                codecParams = params
            }
        } else if (codec == null || f.h265 != codecH265) {
            skipped++
            return
        }
        if (ready.size >= MAX_READY || readyBytes + f.data.size > MAX_READY_BYTES) {
            if (!backlogWarned) Log.w(TAG, "decoder not keeping up, dropping frames")
            backlogWarned = true
            if (params == null) {
                // Dropping costs corrupted frames until the next keyframe; stopping would freeze until the next reconnect.
                droppedBacklog.incrementAndGet()
                return
            }
            // Never drop a parameter-set frame: it may be the only one until the next size change.
            droppedBacklog.addAndGet(ready.size)
            ready.clear()
            readyBytes = 0
        }
        ready.addLast(f)
        readyBytes += f.data.size
        queueReady()
    }

    /** One stats line per interval while frames arrive. */
    private fun statsTick() {
        val recv = received.get()
        if (recv == statsLastReceived) {
            statsTicking.set(false)
            // A frame counted between the read and the reset would otherwise go unreported.
            if (received.get() == recv || !statsTicking.compareAndSet(false, true)) return
        }
        statsLastReceived = recv
        logStats()
        handler.postDelayed(::statsTick, STATS_INTERVAL_MS)
    }

    private fun logStats() {
        Log.i(
            TAG,
            "stats: recv=${received.get()} queued=$queued decoded=$decoded " +
                "drop(backlog/oversize/smallbuf)=${droppedBacklog.get()}/${droppedOversize.get()}/$droppedSmallBuf " +
                "size=${codecSize ?: "-"} skipped=$skipped flushed=$flushed",
        )
    }

    private fun resetStats() {
        received.set(0)
        droppedOversize.set(0)
        droppedBacklog.set(0)
        droppedSmallBuf = 0
        skipped = 0
        flushed = 0
        queued = 0
        decoded = 0
        backlogWarned = false
        statsLastReceived = 0
    }

    private fun queueReady() {
        val c = codec ?: return
        var inHand = false
        try {
            while (ready.isNotEmpty() && freeInputs.isNotEmpty()) {
                val f = ready.removeFirst()
                readyBytes -= f.data.size
                inHand = true
                val idx = freeInputs.first()
                val buf = c.getInputBuffer(idx)
                if (buf == null) freeInputs.removeFirst()
                if (buf == null || buf.capacity() < f.data.size) {
                    droppedSmallBuf++
                    inHand = false
                    Log.w(TAG, "dropping frame of ${f.data.size} bytes, input buffer too small (${buf?.capacity()})")
                    continue
                }
                freeInputs.removeFirst()
                buf.clear()
                buf.put(f.data)
                c.queueInputBuffer(idx, 0, f.data.size, f.ptsUs, 0)
                inHand = false
                queued++
            }
        } catch (e: Exception) {
            Log.w(TAG, "queueing input failed, waiting for next keyframe", e)
            if (inHand) flushed++
            fail()
        }
    }

    /** Unrecoverable codec state: back to the idle screen until the sender restarts the stream. */
    private fun fail() {
        stopCodec()
        _active.value = false
    }

    private fun startCodec(h265: Boolean, size: Size): Boolean {
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
                c.configure(format(mime, info, size), s, null, 0)
                c.start()
            } catch (e: Exception) {
                Log.w(TAG, "${info.name} rejected configuration for $size", e)
                c.release()
                continue
            }
            codec = c
            codecH265 = h265
            codecSize = size
            Log.i(TAG, "decoding $mime $size with ${info.name} (sender reported ${reportedSize ?: "-"})")
            return true
        }
        Log.e(TAG, "no usable decoder for $mime")
        return false
    }

    private fun stopCodec() {
        flushed += ready.size
        ready.clear()
        readyBytes = 0
        freeInputs.clear()
        codecParams = null
        codecSize = null
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

    private fun format(mime: String, info: MediaCodecInfo, size: Size) = MediaFormat.createVideoFormat(mime, size.w, size.h).apply {
        // Larger frames are already dropped in feedFrame, so more input space is never used.
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(MIN_INPUT_SIZE, size.w * size.h * 3 / 4).coerceAtMost(MAX_POSTED_BYTES))
        val caps = info.getCapabilitiesForType(mime)
        if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)) {
            // Every parameter-set change (rotation included) restarts the codec, so the adaptive
            // bound only has to cover this stream. A blanket 4096x4096 would make adaptive
            // decoders size their output buffers for it, which a 2 GB box cannot afford.
            setInteger(MediaFormat.KEY_MAX_WIDTH, size.w)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, size.h)
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

    /**
     * Coded (uncropped, so never smaller than the picture) size from the SPS in [params], or
     * null if there is none or it does not parse. Sender data: every read is bounds-checked.
     */
    private fun spsSize(params: ByteArray, h265: Boolean): Size? {
        val rbsp = spsRbsp(params, h265) ?: return null
        val size = try {
            if (h265) hevcSpsSize(BitReader(rbsp)) else avcSpsSize(BitReader(rbsp))
        } catch (_: IndexOutOfBoundsException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
        if (size == null || size.w !in MIN_DIM..MAX_DIM || size.h !in MIN_DIM..MAX_DIM) {
            Log.w(TAG, "unparseable SPS, using reported size")
            return null
        }
        return size
    }

    /** SPS payload after the NAL header, with emulation-prevention bytes removed. */
    private fun spsRbsp(d: ByteArray, h265: Boolean): ByteArray? {
        var i = 0
        while (i + 3 < d.size) {
            if (d[i] == 0.toByte() && d[i + 1] == 0.toByte() && d[i + 2] == 1.toByte()) {
                val header = d[i + 3].toInt()
                val sps = if (h265) ((header shr 1) and 0x3F) == 33 else (header and 0x1F) == 7
                i += 3
                if (!sps) continue
                val start = i + if (h265) 2 else 1
                val out = ByteArrayOutputStream()
                var zeros = 0
                var j = start
                // Real SPS are well under this; the cap keeps a forged one from costing a frame-sized copy.
                while (j < d.size && out.size() < MAX_SPS_BYTES) {
                    val b = d[j].toInt() and 0xFF
                    if (zeros >= 2 && b <= 1) break // next start code
                    if (zeros >= 2 && b == 3) {
                        zeros = 0
                        j++
                        continue
                    }
                    zeros = if (b == 0) zeros + 1 else 0
                    out.write(b)
                    j++
                }
                return out.toByteArray()
            } else {
                i++
            }
        }
        return null
    }

    private fun avcSpsSize(r: BitReader): Size {
        val profile = r.bits(8)
        r.skip(16) // constraint flags, level
        r.ue() // seq_parameter_set_id
        var chroma = 1
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = r.ue()
            if (chroma == 3) r.skip(1)
            r.ue() // bit_depth_luma
            r.ue() // bit_depth_chroma
            r.skip(1)
            if (r.bit()) repeat(if (chroma == 3) 12 else 8) { list ->
                if (r.bit()) {
                    var last = 8
                    var next = 8
                    repeat(if (list < 6) 16 else 64) {
                        if (next != 0) next = (last + r.se() + 256) % 256
                        if (next != 0) last = next
                    }
                }
            }
        }
        r.ue() // log2_max_frame_num
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.skip(1)
                r.se()
                r.se()
                repeat(r.ue().also { check(it <= 255) }) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.skip(1)
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        check(widthMbs <= MAX_DIM / 16 && heightMapUnits <= MAX_DIM / 16) { "implausible size" }
        val frameMbsOnly = r.bit()
        return Size(widthMbs * 16, heightMapUnits * 16 * if (frameMbsOnly) 1 else 2)
    }

    private fun hevcSpsSize(r: BitReader): Size {
        r.skip(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.bits(3)
        r.skip(1)
        r.skip(96) // general profile_tier_level
        val subProfile = BooleanArray(maxSubLayersMinus1)
        val subLevel = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            subProfile[i] = r.bit()
            subLevel[i] = r.bit()
        }
        if (maxSubLayersMinus1 > 0) r.skip(2 * (8 - maxSubLayersMinus1))
        for (i in 0 until maxSubLayersMinus1) {
            if (subProfile[i]) r.skip(88)
            if (subLevel[i]) r.skip(8)
        }
        r.ue() // sps_seq_parameter_set_id
        if (r.ue() == 3) r.skip(1) // chroma_format_idc, separate_colour_plane
        return Size(r.ue(), r.ue())
    }

    /** MSB-first Exp-Golomb reader; throws IndexOutOfBounds past the end. */
    private class BitReader(private val d: ByteArray) {
        private var pos = 0

        fun bit(): Boolean {
            val b = (d[pos shr 3].toInt() shr (7 - (pos and 7))) and 1
            pos++
            return b == 1
        }

        fun bits(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or (if (bit()) 1 else 0) }
            return v
        }

        fun skip(n: Int) {
            pos += n
        }

        fun ue(): Int {
            var zeros = 0
            while (!bit()) {
                check(++zeros < 31) { "exp-golomb overflow" }
            }
            return (1 shl zeros) - 1 + bits(zeros)
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
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
            if (info.size > 0) decoded++
            _active.value = true
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            if (c !== codec) return
            val w = cropSpan(format, "crop-left", "crop-right", MediaFormat.KEY_WIDTH)
            val h = cropSpan(format, "crop-top", "crop-bottom", MediaFormat.KEY_HEIGHT)
            Log.i(TAG, "decoder output ${w}x$h")
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

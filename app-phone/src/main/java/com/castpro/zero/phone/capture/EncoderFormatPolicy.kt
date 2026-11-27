package com.castpro.zero.phone.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log

enum class EncoderTier {
    GAMING_LOW_LATENCY,
    LOW_LATENCY_COMPAT,
    CBR_SAFE,
    BASELINE
}

data class EncoderFormatSpec(
    val tier: EncoderTier,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val fps: Int,
    val iFrameIntervalSec: Int = 1,
    val maxBFrames: Int = 0,
    val lowLatency: Int? = null,
    val priority: Int? = null,
    val operatingRate: Int? = null,
    val maxFpsToEncoder: Float? = null,
    val cbrMode: Boolean = false,
    val prependHeaderToSyncFrames: Int? = null,
    val repeatPreviousFrameAfterUs: Long? = null,
    val includeColorMetadata: Boolean = true
)

object EncoderFormatPolicy {
    private const val TAG = "CPZ-EncPolicy"
    private const val KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder"
    private const val KEY_PREP_SPS_PPS = "prepend-sps-pps-to-idr-frames"

    data class Dimensions(val width: Int, val height: Int)

    fun alignDimensions(w: Int, h: Int, widthAlignment: Int, heightAlignment: Int): Dimensions {
        require(w >= 2 && h >= 2)
        val wa = maxOf(2, widthAlignment)
        val ha = maxOf(2, heightAlignment)
        return Dimensions(((w + wa - 1) / wa) * wa, ((h + ha - 1) / ha) * ha)
    }

    fun alignedDimensions(c: MediaCodecInfo.VideoCapabilities, w: Int, h: Int): Dimensions =
        alignDimensions(w, h, c.widthAlignment, c.heightAlignment)

    fun specsFor(w: Int, h: Int, bitrate: Int, fps: Int): List<EncoderFormatSpec> = listOf(
        EncoderFormatSpec(
            tier = EncoderTier.GAMING_LOW_LATENCY,
            width = w,
            height = h,
            bitrate = bitrate,
            fps = fps,
            iFrameIntervalSec = 1,
            maxBFrames = 0,
            lowLatency = 1,
            priority = 0,
            operatingRate = maxOf(fps * 2, 120),
            maxFpsToEncoder = fps.toFloat(),
            cbrMode = true,
            prependHeaderToSyncFrames = 1,
            repeatPreviousFrameAfterUs = 100_000L,
            includeColorMetadata = true
        ),
        EncoderFormatSpec(
            tier = EncoderTier.LOW_LATENCY_COMPAT,
            width = w,
            height = h,
            bitrate = bitrate,
            fps = fps,
            iFrameIntervalSec = 1,
            maxBFrames = 0,
            lowLatency = 1,
            priority = 0,
            operatingRate = fps,
            maxFpsToEncoder = fps.toFloat(),
            cbrMode = true,
            prependHeaderToSyncFrames = 1,
            repeatPreviousFrameAfterUs = null,
            includeColorMetadata = true
        ),
        EncoderFormatSpec(
            tier = EncoderTier.CBR_SAFE,
            width = w,
            height = h,
            bitrate = bitrate,
            fps = fps,
            iFrameIntervalSec = 1,
            maxBFrames = 0,
            lowLatency = null,
            priority = 0,
            operatingRate = null,
            maxFpsToEncoder = fps.toFloat(),
            cbrMode = false,
            prependHeaderToSyncFrames = 1,
            repeatPreviousFrameAfterUs = null,
            includeColorMetadata = false
        ),
        EncoderFormatSpec(
            tier = EncoderTier.BASELINE,
            width = w,
            height = h,
            bitrate = bitrate,
            fps = fps,
            iFrameIntervalSec = 1,
            maxBFrames = 0,
            lowLatency = null,
            priority = null,
            operatingRate = null,
            maxFpsToEncoder = null,
            cbrMode = false,
            prependHeaderToSyncFrames = null,
            repeatPreviousFrameAfterUs = null,
            includeColorMetadata = false
        )
    )

    fun toMediaFormat(spec: EncoderFormatSpec): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, spec.iFrameIntervalSec)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, spec.maxBFrames)
            spec.priority?.let { setInteger(MediaFormat.KEY_PRIORITY, it) }
            spec.operatingRate?.let { setInteger(MediaFormat.KEY_OPERATING_RATE, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                spec.lowLatency?.let { setInteger(MediaFormat.KEY_LOW_LATENCY, it) }
            }
            spec.maxFpsToEncoder?.let { setFloat(KEY_MAX_FPS_TO_ENCODER, it) }
            if (spec.cbrMode) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            spec.prependHeaderToSyncFrames?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, it)
                } else {
                    setInteger(KEY_PREP_SPS_PPS, it)
                }
            }
            spec.repeatPreviousFrameAfterUs?.let {
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, it)
            }
            if (spec.includeColorMetadata) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }

    fun build(w: Int, h: Int, bitrate: Int, fps: Int, color: Boolean = true): MediaFormat =
        toMediaFormat(specsFor(w, h, bitrate, fps).first().copy(includeColorMetadata = color))

    fun configureWithFallback(codec: MediaCodec, specs: List<EncoderFormatSpec>): EncoderFormatSpec {
        var lastError: Throwable? = null
        for (spec in specs) {
            try {
                codec.configure(toMediaFormat(spec), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return spec
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Encoder tier ${spec.tier} rejected, trying fallback: ${e.message}")
            }
        }
        throw IllegalStateException("No H.264 encoder format tier succeeded", lastError)
    }

    fun configureWithFallback(codec: MediaCodec, primary: MediaFormat, fallback: MediaFormat) {
        try {
            codec.configure(primary, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (_: Exception) {
            codec.configure(fallback, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }
}

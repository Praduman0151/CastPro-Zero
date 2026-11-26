package com.castpro.zero.phone.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

object EncoderFormatPolicy {
    data class Dimensions(val width: Int, val height: Int)
    fun alignedDimensions(capabilities: MediaCodecInfo.VideoCapabilities, width: Int, height: Int): Dimensions {
        require(width >= 2 && height >= 2)
        val wa = maxOf(2, capabilities.widthAlignment)
        val ha = maxOf(2, capabilities.heightAlignment)
        val alignedWidth = ((width + wa - 1) / wa) * wa
        val alignedHeight = ((height + ha - 1) / ha) * ha
        return Dimensions(alignedWidth, alignedHeight)
    }

    fun build(width: Int, height: Int, bitrate: Int, fps: Int, includeOptionalColor: Boolean = true): MediaFormat {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (includeOptionalColor) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }
    }

    fun configureWithFallback(codec: MediaCodec, primary: MediaFormat, fallback: MediaFormat) {
        try { codec.configure(primary, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        catch (_: IllegalArgumentException) { codec.configure(fallback, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
    }
}

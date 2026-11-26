package com.castpro.zero.phone.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** CameraX Y-plane scanner backed only by the bundled ZXing core library. */
class OfflineQrAnalyzer(private val onQr: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader()
    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.first()
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val data = ByteArray(image.width * image.height)
            val row = ByteArray(rowStride)
            var target = 0
            for (y in 0 until image.height) {
                val start = y * rowStride
                val count = minOf(rowStride, buffer.limit() - start)
                if (count <= 0) break
                buffer.position(start)
                buffer.get(row, 0, count)
                for (x in 0 until image.width) {
                    val index = x * pixelStride
                    if (index < count && target < data.size) data[target++] = row[index]
                }
            }
            if (target == data.size) {
                val source = PlanarYUVLuminanceSource(data, image.width, image.height, 0, 0,
                    image.width, image.height, false)
                val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
                onQr(result.text)
            }
        } catch (_: Exception) {
            reader.reset()
        } finally {
            image.close()
        }
    }
}

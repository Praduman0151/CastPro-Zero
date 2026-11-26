package com.castpro.zero.phone.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** CameraX analyzer using the bundled ZXing core; no ML Kit or Play Services. */
class OfflineQrAnalyzer(private val onQr: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader()
    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.first()
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining()); buffer.get(data)
            val source = PlanarYUVLuminanceSource(data, image.width, image.height, 0, 0, image.width, image.height, false)
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            onQr(result.text)
        } catch (_: Exception) {
        } finally { image.close() }
    }
}

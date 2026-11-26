package com.castpro.zero.tv

import android.graphics.Bitmap
import com.castpro.zero.protocol.PairingPayloadCodec
import com.castpro.zero.protocol.TvPairingPayload
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QrCode {
    fun pairingBitmap(ip: String, tcpPort: Int, udpPort: Int, token: String, expiry: Long, size: Int = 512): Bitmap {
        val value = TvPairingPayload(ipAddress = ip, tcpPort = tcpPort, udpPort = udpPort,
            pairingToken = token, expiryEpochSeconds = expiry)
        val matrix = QRCodeWriter().encode(PairingPayloadCodec.encode(value), BarcodeFormat.QR_CODE,
            size, size, mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until size) for (x in 0 until size)
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
}
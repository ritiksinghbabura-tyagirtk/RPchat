package com.rtk.privatechat.security

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject

object IdentityQr {
    fun create(
        publicId: String,
        username: String,
        signingPublicKey: String,
        exchangePublicKey: String,
        fingerprint: String
    ): Bitmap {
        val payload = JSONObject().apply {
            put("type", "RTK_PRIVATE_IDENTITY")
            put("version", 1)
            put("user_id", publicId)
            put("username", username)
            put("signing_public_key", signingPublicKey)
            put("exchange_public_key", exchangePublicKey)
            put("fingerprint", fingerprint)
        }.toString()

        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            640,
            640
        )

        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        for (x in 0 until 640) {
            for (y in 0 until 640) {
                bitmap.setPixel(
                    x, y,
                    if (matrix[x, y]) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE
                )
            }
        }
        return bitmap
    }

    /**
     * Contact QR contains only the public RTK ID.
     * Never put session tokens or private keys in this QR.
     */
    fun createContactQr(publicId: String): Bitmap {
        require(Regex("^RTK-[0-9A-F]{16}$").matches(publicId)) {
            "Invalid RTK ID"
        }

        val size = 640
        val matrix = QRCodeWriter().encode(
            publicId,
            BarcodeFormat.QR_CODE,
            size,
            size
        )

        val bitmap = Bitmap.createBitmap(
            size,
            size,
            Bitmap.Config.ARGB_8888
        )

        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(
                    x,
                    y,
                    if (matrix[x, y])
                        android.graphics.Color.BLACK
                    else
                        android.graphics.Color.WHITE
                )
            }
        }

        return bitmap
    }

}

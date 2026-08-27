package dev.yukaribox.vpn.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

/** QR encode/decode over bitmaps via ZXing core — no camera, no permissions. */
object QrTools {

    private const val MAX_DECODE_DIM = 2048

    /** Render [text] as a QR bitmap (dark modules in black on white). */
    fun encode(text: String, sizePx: Int = 768): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                pixels[y * sizePx + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.RGB_565)
    }

    /** Decode a QR code from a gallery image. Returns null when nothing is found. */
    fun decodeFromUri(context: Context, uri: Uri): String? {
        return try {
            val bitmap = loadScaled(context, uri) ?: return null
            // inSampleSize halves until below 2*MAX, so the decoded bitmap can still be
            // up to ~2*MAX per side. Reject anything past MAX before the getPixels
            // allocation (width*height ints) so a crafted huge image can't OOM the app.
            if (bitmap.width > MAX_DECODE_DIM || bitmap.height > MAX_DECODE_DIM) {
                Logs.w("QR", "image too large after downscale: ${bitmap.width}x${bitmap.height}")
                bitmap.recycle()
                return null
            }
            val result = decodeBitmap(bitmap)
            bitmap.recycle()
            result
        } catch (e: Exception) {
            Logs.e("QR", "decode failed", e)
            null
        }
    }

    private fun loadScaled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DECODE_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun decodeBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
        return try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: Exception) {
            null
        }
    }
}

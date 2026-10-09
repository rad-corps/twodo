package app.intack.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finds a QR code in an image, e.g. a screenshot of a friend's share screen. */
object QrImage {
    private const val MAX_SIDE = 2000

    suspend fun decode(context: Context, uri: Uri): String? = withContext(Dispatchers.Default) {
        val source = load(context, uri) ?: return@withContext null
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
        // Different binarizers suit different images; inverted covers light-on-dark codes.
        for (candidate in listOf(source, source.invert())) {
            for (bitmap in listOf(BinaryBitmap(HybridBinarizer(candidate)), BinaryBitmap(GlobalHistogramBinarizer(candidate)))) {
                runCatching { return@withContext MultiFormatReader().decode(bitmap, hints).text }
            }
        }
        null
    }

    private fun load(context: Context, uri: Uri): LuminanceSource? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        RGBLuminanceSource(bitmap.width, bitmap.height, pixels).also { bitmap.recycle() }
    }.onFailure { Log.w("Intack", "Couldn't read image $uri", it) }.getOrNull()
}

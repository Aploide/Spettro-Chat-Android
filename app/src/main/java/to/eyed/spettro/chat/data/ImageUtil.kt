package to.eyed.spettro.chat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Image attachment processing: downscale to a sane long edge and re-encode
 * as JPEG so payloads stay small, then wrap as an OpenAI-style data URL.
 */
object ImageUtil {
    private const val MAX_EDGE = 1568
    private const val JPEG_QUALITY = 85

    suspend fun toDataUrl(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return@runCatching null
            val longest = maxOf(bmp.width, bmp.height)
            if (longest > MAX_EDGE) {
                val scale = MAX_EDGE.toFloat() / longest
                bmp = Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

    fun decodeDataUrl(dataUrl: String): Bitmap? = runCatching {
        val b64 = dataUrl.substringAfter("base64,", "")
        if (b64.isEmpty()) return@runCatching null
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

package to.eyed.spettro.chat.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.spettro.chat.data.store.StoredFile

/**
 * Document attachment processing: PDFs go through PdfBox text extraction,
 * everything else is treated as plain text. Only the extracted text is kept —
 * the original file never leaves its provider — capped so one attachment
 * can't swallow the model's context window.
 */
object DocumentUtil {
    /** Refuse anything bigger before reading it into memory. */
    private const val MAX_FILE_BYTES = 25L * 1024 * 1024

    /** ~15k tokens per file at 4 chars/token. */
    const val MAX_TEXT_CHARS = 60_000

    class UnsupportedFileException(message: String) : Exception(message)

    /**
     * Reads a document from [uri] into a [StoredFile]. Throws with a
     * user-presentable message when the file can't be used.
     */
    suspend fun extract(context: Context, uri: Uri): StoredFile = withContext(Dispatchers.IO) {
        val (name, size) = queryMeta(context, uri)
        if (size > MAX_FILE_BYTES) {
            throw UnsupportedFileException("$name is too large to attach (max 25 MB).")
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw UnsupportedFileException("Couldn't read $name.")
        val mime = context.contentResolver.getType(uri) ?: ""
        val isPdf = mime == "application/pdf" ||
            name.endsWith(".pdf", ignoreCase = true) ||
            bytes.size >= 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()

        val text = if (isPdf) pdfText(context, name, bytes) else plainText(name, bytes)
        if (text.isBlank()) {
            throw UnsupportedFileException("$name has no readable text.")
        }
        StoredFile(name = name, text = cap(text))
    }

    private fun pdfText(context: Context, name: String, bytes: ByteArray): String {
        // Loads PdfBox's font/glyph assets once; cheap after the first call.
        PDFBoxResourceLoader.init(context.applicationContext)
        val doc = try {
            PDDocument.load(bytes)
        } catch (e: Exception) {
            throw UnsupportedFileException("$name doesn't look like a valid PDF.")
        }
        doc.use {
            if (it.isEncrypted) {
                throw UnsupportedFileException("$name is password-protected.")
            }
            val text = runCatching { PDFTextStripper().getText(it) }.getOrDefault("")
            if (text.isBlank()) {
                throw UnsupportedFileException(
                    "$name has no extractable text — it may be a scanned document. " +
                        "Attach its pages as images instead.",
                )
            }
            return text
        }
    }

    private fun plainText(name: String, bytes: ByteArray): String {
        // NUL bytes near the start mean binary, not text.
        val probe = bytes.take(8_192)
        if (probe.any { it == 0.toByte() }) {
            throw UnsupportedFileException("$name is a binary file — only PDFs and text files can be attached.")
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun cap(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length <= MAX_TEXT_CHARS) return trimmed
        return trimmed.take(MAX_TEXT_CHARS) + "\n\n[…file truncated at 60,000 characters]"
    }

    private fun queryMeta(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) cursor.getString(nameIdx)?.let { name = it }
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }
        return name to size
    }
}

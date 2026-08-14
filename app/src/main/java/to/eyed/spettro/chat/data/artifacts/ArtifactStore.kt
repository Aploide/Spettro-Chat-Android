package to.eyed.spettro.chat.data.artifacts

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * A pointer to one generated file, carried inside a tool result as an
 * `artifact://<kind>/<fileName>` line. The transcript UI parses these lines
 * back out of stored tool outputs to render chips and inline HTML cards, so
 * artifacts survive app restarts without a schema change.
 */
data class ArtifactRef(val kind: String, val fileName: String) {
    val line: String get() = "${ArtifactStore.REF_PREFIX}$kind/$fileName"

    companion object {
        const val KIND_FILE = "file"
        const val KIND_PDF = "pdf"
        const val KIND_HTML = "html"

        /** Every artifact reference found in a tool output. */
        fun parseAll(output: String): List<ArtifactRef> =
            Regex("""artifact://(file|pdf|html)/([A-Za-z0-9._\- ]+)""")
                .findAll(output)
                .map { ArtifactRef(it.groupValues[1], it.groupValues[2].trim()) }
                .distinct()
                .toList()
    }
}

/**
 * Where model-generated files live: `filesDir/artifacts`, app-private,
 * handed to other apps (open/share) through the existing FileProvider.
 * Nothing here is ever written outside the sandbox without the user tapping
 * share themselves.
 */
class ArtifactStore(private val context: Context) {
    companion object {
        const val REF_PREFIX = "artifact://"
        private const val DIR = "artifacts"

        /** Per-file cap: a runaway generator must not fill the phone. */
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
    }

    fun dir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun file(ref: ArtifactRef): File = File(dir(), ref.fileName)

    /**
     * Writes [bytes] under a sanitized, collision-free version of [name] and
     * returns the reference. Throws IllegalArgumentException on empty input
     * or an oversized payload — callers surface the message as a tool error.
     */
    fun create(name: String, bytes: ByteArray, kind: String): ArtifactRef {
        require(bytes.isNotEmpty()) { "the file has no content" }
        require(bytes.size <= MAX_FILE_BYTES) {
            "the file is too large (${bytes.size / 1024} KB; max ${MAX_FILE_BYTES / 1024 / 1024} MB)"
        }
        val safe = sanitize(name, kind)
        var candidate = safe
        var n = 2
        while (File(dir(), candidate).exists()) {
            val dot = safe.lastIndexOf('.')
            candidate = safe.substring(0, dot) + "-" + n + safe.substring(dot)
            n++
        }
        File(dir(), candidate).writeBytes(bytes)
        return ArtifactRef(kind, candidate)
    }

    fun createText(name: String, text: String, kind: String = ArtifactRef.KIND_FILE): ArtifactRef =
        create(name, text.toByteArray(Charsets.UTF_8), kind)

    /** Total bytes currently stored (settings surface). */
    fun totalBytes(): Long = dir().listFiles()?.sumOf { it.length() } ?: 0L

    fun count(): Int = dir().listFiles()?.size ?: 0

    fun clear() {
        dir().listFiles()?.forEach { it.delete() }
    }

    fun uriFor(ref: ArtifactRef): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file(ref))

    fun mimeFor(ref: ArtifactRef): String {
        val ext = ref.fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "md" -> "text/markdown"
                "json" -> "application/json"
                "csv" -> "text/csv"
                else -> "application/octet-stream"
            }
    }

    /** ACTION_VIEW in whatever app handles the type; false if none does. */
    fun open(ref: ArtifactRef): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(ref), mimeFor(ref))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** The system share sheet for one artifact. */
    fun share(ref: ArtifactRef) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND)
                .setType(mimeFor(ref))
                .putExtra(Intent.EXTRA_STREAM, uriFor(ref))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(
                Intent.createChooser(send, ref.fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Keeps letters, digits, dot, dash, underscore, space; enforces an
     * extension matching the artifact kind's defaults.
     */
    private fun sanitize(name: String, kind: String): String {
        var base = name.trim().replace(Regex("""[^A-Za-z0-9._\- ]"""), "_").trim('.', ' ')
        if (base.isEmpty()) base = "artifact"
        base = base.take(80)
        val hasExt = base.contains('.') && base.substringAfterLast('.').length in 1..8
        return when {
            kind == ArtifactRef.KIND_PDF && !base.endsWith(".pdf", true) ->
                base.removeSuffix(".") + ".pdf"
            kind == ArtifactRef.KIND_HTML && !(base.endsWith(".html", true) || base.endsWith(".htm", true)) ->
                base.removeSuffix(".") + ".html"
            !hasExt -> "$base.txt"
            else -> base
        }
    }
}

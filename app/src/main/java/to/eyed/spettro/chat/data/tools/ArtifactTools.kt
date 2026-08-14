package to.eyed.spettro.chat.data.tools

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Base64
import to.eyed.spettro.chat.data.artifacts.ArtifactRef
import to.eyed.spettro.chat.data.artifacts.ArtifactStore
import java.io.ByteArrayOutputStream

/**
 * The file-producing tools: create-file (any text-based file), generate-pdf
 * (a real paginated PDF laid out from markdown-lite text), and render-html
 * (a self-contained page the transcript shows inline in a sandboxed
 * WebView). All three write into [ArtifactStore] and hand back an
 * `artifact://` reference the UI turns into chips and cards.
 */
internal class ArtifactTools(private val artifacts: ArtifactStore) {

    fun createFile(argumentsJson: String): ToolResult {
        val name = ToolArgs.string(argumentsJson, "filename")
            ?: return ToolResult("create-file requires a filename (with extension)", isError = true)
        val content = ToolArgs.string(argumentsJson, "content")
            ?: return ToolResult("create-file requires content", isError = true)
        val isBase64 = ToolArgs.obj(argumentsJson)?.get("base64")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content == "true"
        return try {
            val bytes = if (isBase64) {
                Base64.decode(content.replace(Regex("\\s"), ""), Base64.DEFAULT)
            } else {
                content.toByteArray(Charsets.UTF_8)
            }
            val ref = artifacts.create(name, bytes, ArtifactRef.KIND_FILE)
            ToolResult(
                "Created ${ref.fileName} (${bytes.size} bytes). ${ref.line}\n" +
                    "The user sees it attached to this message and can open or share it — " +
                    "no need to repeat its full content in your answer.",
            )
        } catch (e: IllegalArgumentException) {
            ToolResult("create-file: ${e.message}", isError = true)
        }
    }

    fun generatePdf(argumentsJson: String): ToolResult {
        val content = ToolArgs.string(argumentsJson, "content")
            ?: return ToolResult("generate-pdf requires content", isError = true)
        val title = ToolArgs.string(argumentsJson, "title")
        val name = ToolArgs.string(argumentsJson, "filename")
            ?: (title?.take(40) ?: "document")
        return try {
            val bytes = renderPdf(title, content)
            val ref = artifacts.create(name, bytes, ArtifactRef.KIND_PDF)
            ToolResult(
                "Generated ${ref.fileName} (${bytes.size / 1024} KB). ${ref.line}\n" +
                    "The user sees it attached to this message and can open or share it.",
            )
        } catch (e: IllegalArgumentException) {
            ToolResult("generate-pdf: ${e.message}", isError = true)
        } catch (e: Exception) {
            ToolResult("generate-pdf failed: ${e.message?.take(200)}", isError = true)
        }
    }

    fun renderHtml(argumentsJson: String): ToolResult {
        val html = ToolArgs.string(argumentsJson, "html")
            ?: return ToolResult("render-html requires html", isError = true)
        val title = ToolArgs.string(argumentsJson, "title") ?: "Preview"
        return try {
            val doc = if (html.contains("<html", ignoreCase = true)) html else wrapFragment(html)
            val ref = artifacts.createText("${title.take(40)}.html", doc, ArtifactRef.KIND_HTML)
            ToolResult(
                "Rendered \"$title\". ${ref.line}\n" +
                    "The page is displayed to the user inline in the chat (sandboxed: JavaScript " +
                    "runs, network access is blocked). Do not paste the HTML into your answer.",
            )
        } catch (e: IllegalArgumentException) {
            ToolResult("render-html: ${e.message}", isError = true)
        }
    }

    /** A minimal shell for fragments, styled to sit well in the dark chat. */
    private fun wrapFragment(fragment: String): String = """
        <!doctype html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body { margin: 14px; background: #101014; color: #e8e8ea;
                 font-family: system-ui, sans-serif; font-size: 15px; line-height: 1.5; }
          a { color: #9ab4ff; } table { border-collapse: collapse; }
          td, th { border: 1px solid #333; padding: 6px 10px; }
        </style></head><body>
        $fragment
        </body></html>
    """.trimIndent()

    // --- PDF layout ---

    /**
     * Lays [content] out onto A4 pages with a markdown-lite reading: `#`
     * headings, `-`/`*` bullets, ``` code blocks in monospace, `---` rules,
     * everything else word-wrapped paragraphs.
     */
    private fun renderPdf(title: String?, content: String): ByteArray {
        val pageW = 595
        val pageH = 842
        val margin = 52f
        val maxWidth = pageW - 2 * margin

        val body = Paint().apply { textSize = 11.5f; color = 0xFF111111.toInt() }
        val mono = Paint().apply {
            textSize = 10f; color = 0xFF111111.toInt()
            typeface = Typeface.MONOSPACE
        }
        fun heading(size: Float) = Paint().apply {
            textSize = size; color = 0xFF000000.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val doc = PdfDocument()
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
        var canvas = page.canvas
        var y = margin
        var pageNo = 1

        fun newPage() {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            canvas = page.canvas
            y = margin
        }

        fun ensure(height: Float) {
            if (y + height > pageH - margin) newPage()
        }

        fun drawWrapped(text: String, paint: Paint, indent: Float = 0f, bullet: String? = null) {
            val lineHeight = paint.textSize * 1.45f
            var first = true
            var rest = text.trim()
            while (rest.isNotEmpty()) {
                val avail = maxWidth - indent
                val count = paint.breakText(rest, true, avail, null)
                // Back off to the last space so words stay whole.
                var cut = count
                if (cut < rest.length) {
                    val lastSpace = rest.lastIndexOf(' ', cut - 1)
                    if (lastSpace > cut / 2) cut = lastSpace
                }
                if (cut <= 0) cut = minOf(1, rest.length)
                ensure(lineHeight)
                if (first && bullet != null) canvas.drawText(bullet, margin + indent - 14f, y + paint.textSize, paint)
                canvas.drawText(rest.take(cut).trim(), margin + indent, y + paint.textSize, paint)
                y += lineHeight
                rest = rest.drop(cut).trim()
                first = false
            }
        }

        if (!title.isNullOrBlank()) {
            drawWrapped(title, heading(20f))
            y += 10f
        }

        var inCode = false
        for (rawLine in content.lines()) {
            val line = rawLine.trimEnd()
            when {
                line.trimStart().startsWith("```") -> {
                    inCode = !inCode
                    y += 4f
                }
                inCode -> drawWrapped(line.ifEmpty { " " }, mono)
                line.isBlank() -> y += body.textSize * 0.9f
                line == "---" || line == "***" -> {
                    ensure(14f)
                    y += 6f
                    canvas.drawRect(margin, y, margin + maxWidth, y + 0.8f, body)
                    y += 8f
                }
                line.startsWith("### ") -> { y += 6f; drawWrapped(line.drop(4), heading(12.5f)) }
                line.startsWith("## ") -> { y += 8f; drawWrapped(line.drop(3), heading(14.5f)) }
                line.startsWith("# ") -> { y += 10f; drawWrapped(line.drop(2), heading(17f)) }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                    drawWrapped(line.trimStart().drop(2), body, indent = 16f, bullet = "•")
                Regex("""^\d{1,2}\. """).containsMatchIn(line.trimStart()) -> {
                    val t = line.trimStart()
                    val dot = t.indexOf(". ")
                    drawWrapped(t.drop(dot + 2), body, indent = 20f, bullet = t.take(dot + 1))
                }
                else -> drawWrapped(line, body)
            }
        }

        doc.finishPage(page)
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}

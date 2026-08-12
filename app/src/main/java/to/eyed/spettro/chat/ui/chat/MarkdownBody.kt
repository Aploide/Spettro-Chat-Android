package to.eyed.spettro.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import to.eyed.spettro.chat.ui.components.Hairline
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.MonoBody
import to.eyed.spettro.chat.ui.theme.whiteA

/**
 * Assistant markdown in the monochrome language: white headings, ink-100
 * body, mono code on ink-900 cards, white underlined links (never blue).
 *
 * Streaming strategy: the source is split into stable block-level chunks so
 * only the growing last chunk re-parses while tokens arrive.
 */
@Composable
fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    val chunks = remember(text) { MarkdownChunker.chunks(text) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chunks.forEachIndexed { index, chunk ->
            key(index) { MarkdownChunkView(chunk) }
        }
    }
}

@Composable
private fun MarkdownChunkView(chunk: String) {
    val body = MaterialTheme.typography.bodyLarge.copy(color = Ink.I100)
    Markdown(
        content = chunk,
        colors = markdownColor(
            text = Ink.I100,
            codeBackground = Ink.I900,
            inlineCodeBackground = whiteA(0.10f),
            dividerColor = whiteA(0.10f),
            tableBackground = whiteA(0.015f),
        ),
        typography = markdownTypography(
            h1 = body.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = Ink.White),
            h2 = body.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = Ink.White),
            h3 = body.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink.White),
            h4 = body.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink.I300),
            h5 = body.copy(fontWeight = FontWeight.SemiBold, color = Ink.White),
            h6 = body.copy(fontWeight = FontWeight.Medium, color = Ink.White),
            text = body,
            code = MonoBody.copy(color = Ink.I100),
            inlineCode = MonoBody.copy(color = Ink.White, fontSize = 13.sp),
            quote = body.copy(color = Ink.I300),
            paragraph = body,
            ordered = body,
            bullet = body,
            list = body,
            textLink = TextLinkStyles(
                style = SpanStyle(
                    color = Ink.White,
                    textDecoration = TextDecoration.Underline,
                ),
                pressedStyle = SpanStyle(color = Ink.White, textDecoration = TextDecoration.Underline),
            ),
            table = body.copy(fontSize = 14.sp),
        ),
        dimens = markdownDimens(codeBackgroundCornerSize = 16.dp),
        components = markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(model.content, model.node) { code, language, _ ->
                    MonoCodeBlock(code = code, language = language)
                }
            },
            codeBlock = { model ->
                MarkdownCodeBlock(model.content, model.node) { code, language, _ ->
                    MonoCodeBlock(code = code, language = language)
                }
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Code card: mono uppercase language bar, hairline seam, scrolling code. */
@Composable
private fun MonoCodeBlock(code: String, language: String?) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.I900)
            .border(1.dp, whiteA(0.10f), shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(whiteA(0.03f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = (language?.takeIf { it.isNotBlank() } ?: "text").uppercase(),
                style = to.eyed.spettro.chat.ui.theme.EyebrowMono,
                color = Ink.I500,
            )
        }
        Hairline()
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                text = code.trimEnd('\n'),
                style = MonoBody,
                color = Ink.I100,
                softWrap = false,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/**
 * Splits markdown into independently renderable chunks on blank lines,
 * keeping fenced code blocks (even unterminated, mid-stream) and list runs
 * intact so numbering and fences never break across chunks.
 */
internal object MarkdownChunker {
    fun chunks(source: String): List<String> {
        val chunks = mutableListOf<String>()
        val current = mutableListOf<String>()
        var inFence = false
        val lines = source.split("\n")

        fun flush() {
            if (current.any { it.isNotBlank() }) {
                chunks.add(current.joinToString("\n"))
            }
            current.clear()
        }

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                current.add(line)
                continue
            }
            if (trimmed.isEmpty() && !inFence) {
                val prevIsItem = current.lastOrNull()?.let(::isListItem) ?: false
                var nextIsItem = false
                for (i in (index + 1) until lines.size) {
                    if (lines[i].isNotBlank()) {
                        nextIsItem = isListItem(lines[i])
                        break
                    }
                }
                if (prevIsItem && nextIsItem) current.add(line) else flush()
                continue
            }
            current.add(line)
        }
        flush()
        return chunks
    }

    private fun isListItem(line: String): Boolean {
        val t = line.trim()
        if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") || t.startsWith("> ")) {
            return true
        }
        val mark = t.indexOfFirst { it == '.' || it == ')' }
        if (mark <= 0 || mark > 3) return false
        return t.substring(0, mark).all { it.isDigit() }
    }
}

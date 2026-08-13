package to.eyed.spettro.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import to.eyed.spettro.chat.data.ImageUtil
import to.eyed.spettro.chat.data.store.StoredMessage
import to.eyed.spettro.chat.data.tools.AskAnswer
import to.eyed.spettro.chat.data.tools.AskForm
import to.eyed.spettro.chat.ui.components.GhostIconButton
import to.eyed.spettro.chat.ui.components.LiquidMark
import to.eyed.spettro.chat.ui.components.copyToClipboard
import to.eyed.spettro.chat.ui.components.surfaceHigh
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.vm.StreamState
import to.eyed.spettro.chat.vm.ToolRunUi

@Composable
fun MessagesList(
    messages: List<StoredMessage>,
    stream: StreamState,
    askForm: AskForm?,
    listState: LazyListState,
    animations: Boolean,
    isTemporary: Boolean,
    onRegenerate: () -> Unit,
    onSubmitAnswers: (List<AskAnswer>) -> Unit,
    onDeclineQuestions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty() && stream is StreamState.Idle) {
        EmptyState(isTemporary, modifier)
        return
    }
    // Tapping a tool row opens its full response here — a sheet, so the
    // transcript itself stays clean.
    var inspectedTool by remember { mutableStateOf<ToolRunUi?>(null) }
    inspectedTool?.let { ToolDetailSheet(it) { inspectedTool = null } }

    // Reversed layout: index 0 sits at the visual bottom, so the newest
    // content stays pinned while a reply streams in - the standard chat
    // pattern, no scroll math needed.
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.Bottom),
    ) {
        // First item renders at the visual bottom: the form sits closest to
        // the input, right where the user is about to act.
        if (askForm != null) {
            item(key = "askform") {
                QuestionCard(askForm, onSubmitAnswers, onDeclineQuestions)
            }
        }
        when (stream) {
            is StreamState.Thinking -> item(key = "thinking") {
                ThinkingIndicator(stream.reasoning, stream.tools, animations) { inspectedTool = it }
            }
            is StreamState.Streaming -> item(key = "streaming") {
                AssistantMessage(
                    text = stream.text,
                    reasoning = stream.reasoning,
                    tools = stream.tools,
                    streaming = true,
                    showActions = false,
                    onRegenerate = {},
                    animations = animations,
                    onInspectTool = { inspectedTool = it },
                )
            }
            is StreamState.RateLimited -> item(key = "ratelimited") {
                RateLimitNotice(stream.retryAfterSeconds)
            }
            is StreamState.Compacting -> item(key = "compacting") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThinkingIndicator("", emptyList(), animations)
                    Spacer(Modifier.width(12.dp))
                    Text("Compacting conversation…", fontSize = 14.sp, color = Ink.I300)
                }
            }
            else -> Unit
        }
        items(messages.size, key = { idx -> "${messages.size - 1 - idx}-${messages[messages.size - 1 - idx].at}" }) { idx ->
            val i = messages.size - 1 - idx
            val msg = messages[i]
            val isLast = i == messages.size - 1
            if (msg.role == "user") {
                UserBubble(msg)
            } else {
                AssistantMessage(
                    text = msg.content,
                    reasoning = msg.thinking,
                    tools = remember(msg.tools) {
                        msg.tools.map {
                            ToolRunUi(it.name, it.label, running = false, failed = !it.ok, output = it.output)
                        }
                    },
                    streaming = false,
                    showActions = isLast && stream is StreamState.Idle,
                    onRegenerate = onRegenerate,
                    onInspectTool = { inspectedTool = it },
                )
            }
        }
    }
}

@Composable
private fun UserBubble(msg: StoredMessage) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (msg.images.isNotEmpty()) {
            val bitmaps = remember(msg.images) {
                msg.images.mapNotNull { ImageUtil.decodeDataUrl(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                bitmaps.forEach { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Attached image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(160.dp)
                            .clip(RoundedCornerShape(Radii.row)),
                    )
                }
            }
            if (msg.content.isNotBlank()) Spacer(Modifier.height(8.dp))
        }
        if (msg.content.isNotBlank()) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .surfaceHigh(RoundedCornerShape(Radii.card))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                SelectionContainer {
                    Text(msg.content, color = Ink.White, fontSize = 15.sp, lineHeight = 22.sp)
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    text: String,
    reasoning: String,
    tools: List<ToolRunUi>,
    streaming: Boolean,
    showActions: Boolean,
    onRegenerate: () -> Unit,
    animations: Boolean = true,
    onInspectTool: (ToolRunUi) -> Unit = {},
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        if (reasoning.isNotBlank()) {
            ReasoningPanel(reasoning, live = streaming && text.isBlank())
            Spacer(Modifier.height(10.dp))
        }
        if (tools.isNotEmpty()) {
            ToolActivityList(tools, animations, onInspectTool)
            Spacer(Modifier.height(10.dp))
        }
        MarkdownBody(text)
        if (streaming && text.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            BlinkingCaret(animations)
        }
        AnimatedVisibility(showActions, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GhostIconButton(
                    Lucide.Copy,
                    "Copy message",
                    onClick = { copyToClipboard(context, text, "Spettro message") },
                    size = 32.dp,
                    iconSize = 15.dp,
                    tint = Ink.I500,
                )
                GhostIconButton(
                    Lucide.RefreshCw,
                    "Regenerate",
                    onClick = onRegenerate,
                    size = 32.dp,
                    iconSize = 15.dp,
                    tint = Ink.I500,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(isTemporary: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LiquidMark(28.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            if (isTemporary) "Temporary chat" else "Where should we begin?",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink.White,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (isTemporary) "This conversation won't be saved.\nClose it and it's gone." else "Ask anything. Think out loud.",
            fontSize = 14.sp,
            color = Ink.I500,
            textAlign = TextAlign.Center,
        )
    }
}

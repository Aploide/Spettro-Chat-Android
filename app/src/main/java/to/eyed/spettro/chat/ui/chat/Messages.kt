package to.eyed.spettro.chat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.data.store.StoredMessage
import to.eyed.spettro.chat.ui.components.GhostIconButton
import to.eyed.spettro.chat.ui.components.LiquidMark
import to.eyed.spettro.chat.ui.components.LiquidThinking
import to.eyed.spettro.chat.ui.components.glass
import to.eyed.spettro.chat.ui.components.glassStrong
import to.eyed.spettro.chat.ui.theme.EyebrowMono
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.ui.theme.whiteA
import to.eyed.spettro.chat.vm.StreamState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Spettro message", text))
}

@Composable
fun MessagesList(
    messages: List<StoredMessage>,
    stream: StreamState,
    listState: LazyListState,
    onRegenerate: () -> Unit,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty() && stream is StreamState.Idle) {
        EmptyState(onSuggestion, modifier)
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 16.dp, bottom = 140.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "date") { DateChip(messages.firstOrNull()?.at) }
        items(messages.size, key = { i -> "$i-${messages[i].at}" }) { i ->
            val msg = messages[i]
            val isLast = i == messages.size - 1
            if (msg.role == "user") {
                UserBubble(msg)
            } else {
                AssistantMessage(
                    text = msg.content,
                    reasoning = msg.thinking,
                    streaming = false,
                    showActions = isLast && stream is StreamState.Idle,
                    onRegenerate = onRegenerate,
                )
            }
        }
        when (stream) {
            is StreamState.Thinking -> item(key = "thinking") {
                ThinkingIndicator(stream.reasoning)
            }
            is StreamState.Streaming -> item(key = "streaming") {
                AssistantMessage(
                    text = stream.text,
                    reasoning = stream.reasoning,
                    streaming = true,
                    showActions = false,
                    onRegenerate = {},
                )
            }
            is StreamState.RateLimited -> item(key = "ratelimited") {
                RateLimitNotice(stream.retryAfterSeconds)
            }
            else -> Unit
        }
    }
}

@Composable
private fun DateChip(firstAt: Long?) {
    val label = remember(firstAt) {
        val fmt = SimpleDateFormat("MMM d · HH:mm", Locale.getDefault())
        fmt.format(Date(firstAt ?: System.currentTimeMillis()))
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.glass(CircleShape).padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(label.uppercase(), style = EyebrowMono, color = Ink.I500)
        }
    }
}

@Composable
private fun UserBubble(msg: StoredMessage) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .glassStrong(
                    RoundedCornerShape(
                        topStart = Radii.card, topEnd = Radii.card,
                        bottomStart = Radii.card, bottomEnd = 8.dp,
                    ),
                    refraction = true,
                )
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(msg.content, color = Ink.White, fontSize = 15.sp, lineHeight = 22.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            remember(msg.at) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.at)) },
            style = EyebrowMono,
            color = Ink.I500,
        )
    }
}

@Composable
private fun AssistantMessage(
    text: String,
    reasoning: String,
    streaming: Boolean,
    showActions: Boolean,
    onRegenerate: () -> Unit,
) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(32.dp).glass(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            LiquidMark(10.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (reasoning.isNotBlank()) {
                ReasoningPanel(reasoning, live = streaming && text.isBlank())
                Spacer(Modifier.height(8.dp))
            }
            MarkdownBody(text)
            if (streaming) {
                Spacer(Modifier.height(4.dp))
                BlinkingCaret()
            }
            AnimatedVisibility(showActions, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                Row(Modifier.padding(top = 4.dp)) {
                    GhostIconButton(
                        Icons.Rounded.ContentCopy,
                        "Copy message",
                        onClick = { copyToClipboard(context, text) },
                        size = 30.dp,
                        iconSize = 13.dp,
                    )
                    GhostIconButton(
                        Icons.Rounded.Refresh,
                        "Regenerate",
                        onClick = onRegenerate,
                        size = 30.dp,
                        iconSize = 13.dp,
                    )
                }
            }
        }
    }
}

/** Collapsible reasoning ("Deep Thought") panel above the answer. */
@Composable
private fun ReasoningPanel(reasoning: String, live: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(Radii.row))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { expanded = !expanded }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (live) "Reasoning" else "Reasoned",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Ink.I300,
            )
            if (live) {
                Spacer(Modifier.width(6.dp))
                PulsingDots()
            }
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                null,
                Modifier.size(14.dp),
                tint = Ink.I500,
            )
        }
        AnimatedVisibility(expanded || live, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(
                if (live) reasoning.takeLast(600) else reasoning,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = Ink.I500,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ThinkingIndicator(reasoning: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LiquidThinking(size = 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Thinking", fontSize = 14.sp, color = Ink.I100)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reasoning", fontSize = 12.sp, color = Ink.I500)
                Spacer(Modifier.width(6.dp))
                PulsingDots()
            }
            if (reasoning.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    reasoning.takeLast(300),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Ink.I500,
                    maxLines = 4,
                )
            }
        }
    }
}

@Composable
private fun PulsingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            val alpha by t.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(600, delayMillis = i * 200),
                    RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(Modifier.size(3.dp).alpha(alpha).background(Ink.I500, CircleShape))
        }
    }
}

@Composable
private fun BlinkingCaret() {
    val t = rememberInfiniteTransition(label = "caret")
    val alpha by t.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
        label = "caretAlpha",
    )
    Box(
        Modifier
            .size(width = 3.dp, height = 16.dp)
            .alpha(alpha)
            .background(Ink.White, CircleShape),
    )
}

@Composable
private fun RateLimitNotice(seconds: Int) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.glass(CircleShape).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Waiting out a rate limit — retrying in ${seconds}s",
                style = EyebrowMono,
                color = Ink.I300,
            )
        }
    }
}

@Composable
private fun EmptyState(onSuggestion: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(56.dp).glass(RoundedCornerShape(Radii.card)),
            contentAlignment = Alignment.Center,
        ) {
            LiquidMark(16.dp)
        }
        Spacer(Modifier.height(16.dp))
        Text("Where should we begin?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink.White)
        Spacer(Modifier.height(6.dp))
        Text(
            "Ask anything. Think out loud.",
            fontSize = 12.sp,
            color = Ink.I500,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Explain a concept", "Draft a spec", "Review my code").forEach { s ->
                Box(
                    Modifier
                        .glass(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSuggestion(s) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(s, fontSize = 11.sp, color = Ink.I100)
                }
            }
        }
    }
}

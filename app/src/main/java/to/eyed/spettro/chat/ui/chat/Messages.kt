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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.data.store.StoredMessage
import to.eyed.spettro.chat.ui.components.GhostIconButton
import to.eyed.spettro.chat.ui.components.LiquidMark
import to.eyed.spettro.chat.ui.components.surfaceHigh
import to.eyed.spettro.chat.ui.components.surfaceLow
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.vm.StreamState

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Spettro message", text))
}

@Composable
fun MessagesList(
    messages: List<StoredMessage>,
    stream: StreamState,
    listState: LazyListState,
    animations: Boolean,
    onRegenerate: () -> Unit,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty() && stream is StreamState.Idle) {
        EmptyState(onSuggestion, modifier)
        return
    }
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
        when (stream) {
            is StreamState.Thinking -> item(key = "thinking") {
                ThinkingIndicator(stream.reasoning, animations)
            }
            is StreamState.Streaming -> item(key = "streaming") {
                AssistantMessage(
                    text = stream.text,
                    reasoning = stream.reasoning,
                    streaming = true,
                    showActions = false,
                    onRegenerate = {},
                    animations = animations,
                )
            }
            is StreamState.RateLimited -> item(key = "ratelimited") {
                RateLimitNotice(stream.retryAfterSeconds)
            }
            is StreamState.Compacting -> item(key = "compacting") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThinkingIndicator("", animations)
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
                    streaming = false,
                    showActions = isLast && stream is StreamState.Idle,
                    onRegenerate = onRegenerate,
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
                msg.images.mapNotNull { to.eyed.spettro.chat.data.ImageUtil.decodeDataUrl(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                bitmaps.forEach { bmp ->
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Attached image",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
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
                Text(msg.content, color = Ink.White, fontSize = 15.sp, lineHeight = 22.sp)
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    text: String,
    reasoning: String,
    streaming: Boolean,
    showActions: Boolean,
    onRegenerate: () -> Unit,
    animations: Boolean = true,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        if (reasoning.isNotBlank()) {
            ReasoningPanel(reasoning, live = streaming && text.isBlank())
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
                    Icons.Rounded.ContentCopy,
                    "Copy message",
                    onClick = { copyToClipboard(context, text) },
                    size = 32.dp,
                    iconSize = 15.dp,
                    tint = Ink.I500,
                )
                GhostIconButton(
                    Icons.Rounded.Refresh,
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

/** Collapsible reasoning panel above the answer, quiet and flat. */
@Composable
private fun ReasoningPanel(reasoning: String, live: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .surfaceLow(RoundedCornerShape(Radii.row))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (live) "Thinking" else "Thought for a moment",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Ink.I300,
            )
            if (live) {
                Spacer(Modifier.width(8.dp))
                PulsingDots(true)
            }
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                null,
                Modifier.size(16.dp),
                tint = Ink.I500,
            )
        }
        AnimatedVisibility(expanded || live, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(
                if (live) reasoning.takeLast(600) else reasoning,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Ink.I500,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** ChatGPT-style pulsing dot while waiting for the first token. */
@Composable
private fun ThinkingIndicator(reasoning: String, animations: Boolean) {
    if (reasoning.isNotBlank()) {
        ReasoningPanel(reasoning, live = true)
        return
    }
    val t = rememberInfiniteTransition(label = "pulse")
    val scale by t.animateFloat(
        initialValue = if (animations) 0.85f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "pulseScale",
    )
    Box(
        Modifier
            .padding(vertical = 6.dp)
            .size(14.dp)
            .scale(scale)
            .background(Ink.White, CircleShape),
    )
}

@Composable
private fun PulsingDots(animations: Boolean) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            val alpha by t.animateFloat(
                initialValue = if (animations) 0.2f else 0.7f,
                targetValue = if (animations) 1f else 0.7f,
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
private fun BlinkingCaret(animations: Boolean) {
    val t = rememberInfiniteTransition(label = "caret")
    val alpha by t.animateFloat(
        initialValue = 1f,
        targetValue = if (animations) 0.2f else 1f,
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
        Box(
            Modifier
                .surfaceLow(CircleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                "Waiting out a rate limit — retrying in ${seconds}s",
                fontSize = 12.sp,
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
        LiquidMark(28.dp)
        Spacer(Modifier.height(20.dp))
        Text("Where should we begin?", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink.White)
        Spacer(Modifier.height(8.dp))
        Text(
            "Ask anything. Think out loud.",
            fontSize = 14.sp,
            color = Ink.I500,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("Explain a concept", "Draft a spec", "Review my code").forEach { s ->
                Box(
                    Modifier
                        .surfaceLow(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSuggestion(s) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(s, fontSize = 13.sp, color = Ink.I100)
                }
            }
        }
    }
}

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleQuestion
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Wrench
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import to.eyed.spettro.chat.data.tools.AskAnswer
import to.eyed.spettro.chat.data.tools.AskForm
import to.eyed.spettro.chat.data.tools.ToolRegistry
import to.eyed.spettro.chat.ui.components.GhostIconButton
import to.eyed.spettro.chat.ui.components.LiquidMark
import to.eyed.spettro.chat.ui.components.surfaceHigh
import to.eyed.spettro.chat.ui.components.surfaceLow
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.vm.StreamState
import to.eyed.spettro.chat.vm.ToolRunUi

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Spettro message", text))
}

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
                androidx.compose.foundation.text.selection.SelectionContainer {
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
                    onClick = { copyToClipboard(context, text) },
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
                if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                null,
                Modifier.size(16.dp),
                tint = Ink.I500,
            )
        }
        AnimatedVisibility(expanded || live, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            if (live && !expanded) {
                // Full text in a window that follows the tail — no truncation,
                // so earlier thinking never visibly "slides" away.
                val scroll = rememberScrollState()
                LaunchedEffect(reasoning.length) {
                    scroll.scrollTo(Int.MAX_VALUE)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .heightIn(max = 180.dp)
                        .verticalScroll(scroll),
                ) {
                    Text(reasoning, fontSize = 13.sp, lineHeight = 19.sp, color = Ink.I500)
                }
            } else {
                Text(
                    reasoning,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Ink.I500,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

/**
 * One row per tool call: icon, quiet label, pulsing dots while running.
 * A finished row is tappable and opens the full response in a sheet.
 */
@Composable
private fun ToolActivityList(
    tools: List<ToolRunUi>,
    animations: Boolean,
    onInspect: (ToolRunUi) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tools.forEach { run ->
            val inspectable = !run.running && run.output.isNotBlank()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = inspectable,
                ) { onInspect(run) },
            ) {
                Icon(
                    toolIcon(run),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (run.failed) Ink.I300 else Ink.I500,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    run.label,
                    fontSize = 13.sp,
                    color = if (run.running) Ink.I300 else Ink.I500,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (run.running) {
                    Spacer(Modifier.width(8.dp))
                    PulsingDots(animations)
                }
                if (inspectable) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Lucide.ChevronRight, null, Modifier.size(13.dp), tint = Ink.I500)
                }
            }
        }
    }
}

internal fun toolIcon(run: ToolRunUi) = when {
    run.failed -> Lucide.CircleAlert
    run.name == ToolRegistry.WEB_SEARCH -> Lucide.Globe
    run.name == ToolRegistry.WEB_FETCH -> Lucide.Link
    run.name == ToolRegistry.CURRENT_TIME -> Lucide.Clock
    run.name == ToolRegistry.DEVICE_INFO -> Lucide.Smartphone
    run.name == ToolRegistry.ASK_USER -> Lucide.MessageCircleQuestion
    run.name == ToolRegistry.COMMENT -> Lucide.Info
    else -> Lucide.Wrench
}

/** ChatGPT-style pulsing dot while waiting for the first token. */
@Composable
private fun ThinkingIndicator(
    reasoning: String,
    tools: List<ToolRunUi>,
    animations: Boolean,
    onInspectTool: (ToolRunUi) -> Unit = {},
) {
    if (reasoning.isNotBlank() || tools.isNotEmpty()) {
        Column {
            if (reasoning.isNotBlank()) {
                ReasoningPanel(reasoning, live = true)
            }
            if (tools.isNotEmpty()) {
                if (reasoning.isNotBlank()) Spacer(Modifier.height(10.dp))
                ToolActivityList(tools, animations, onInspectTool)
            }
        }
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

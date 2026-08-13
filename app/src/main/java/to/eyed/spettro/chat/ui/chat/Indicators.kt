package to.eyed.spettro.chat.ui.chat

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import to.eyed.spettro.chat.ui.components.surfaceLow
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.vm.ToolRunUi

// The small live pieces of a streaming turn: the reasoning panel, the
// waiting dot, the caret, and the rate-limit notice.

/** Collapsible reasoning panel above the answer, quiet and flat. */
@Composable
internal fun ReasoningPanel(reasoning: String, live: Boolean) {
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

/** ChatGPT-style pulsing dot while waiting for the first token. */
@Composable
internal fun ThinkingIndicator(
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
internal fun PulsingDots(animations: Boolean) {
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
internal fun BlinkingCaret(animations: Boolean) {
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
internal fun RateLimitNotice(seconds: Int) {
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

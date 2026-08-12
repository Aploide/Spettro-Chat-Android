package to.eyed.spettro.chat.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.ui.components.snappySpring
import to.eyed.spettro.chat.ui.components.surfaceRaised
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii

/**
 * The composer: a flat rounded pill with an auto-resizing input and a
 * circular send button that turns solid white when there's content. While a
 * reply streams the send button becomes a stop button.
 */
@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val canvas = MaterialTheme.colorScheme.background
    Column(modifier.fillMaxWidth()) {
        // Fade from transparent to the canvas above the bar
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to canvas)),
        )
        Column(Modifier.background(canvas).padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .surfaceRaised(RoundedCornerShape(Radii.inputBar))
                    .padding(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = TextStyle(color = Ink.White, fontSize = 16.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(Ink.White),
                    maxLines = 7,
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            if (value.isEmpty()) {
                                Text("Message Spettro…", color = Ink.I500, fontSize = 16.sp)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp, max = 160.dp),
                )
                Spacer(Modifier.size(6.dp))
                SendButton(
                    hasContent = value.isNotBlank(),
                    isStreaming = isStreaming,
                    onSend = onSend,
                    onStop = onStop,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Spettro can make mistakes. Verify important information.",
                color = Ink.I500,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SendButton(
    hasContent: Boolean,
    isStreaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val active = hasContent || isStreaming
    val bg by animateColorAsState(if (active) Ink.White else Ink.SurfaceHigh, tween(200), label = "sendBg")
    val scale by animateFloatAsState(if (active) 1f else 0.94f, snappySpring(), label = "sendScale")
    Box(
        Modifier
            .padding(bottom = 2.dp)
            .scale(scale)
            .size(40.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = active,
            ) { if (isStreaming) onStop() else onSend() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isStreaming) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
            contentDescription = if (isStreaming) "Stop" else "Send",
            Modifier.size(18.dp),
            tint = if (active) Ink.Pitch else Ink.I500,
        )
    }
}

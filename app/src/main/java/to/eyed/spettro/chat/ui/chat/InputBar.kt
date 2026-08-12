package to.eyed.spettro.chat.ui.chat

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.ui.components.GhostIconButton
import to.eyed.spettro.chat.ui.components.snappySpring
import to.eyed.spettro.chat.ui.components.surfaceRaised
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii

/** One pending attachment: the wire data URL plus a decoded thumbnail. */
data class PendingImage(val dataUrl: String, val thumbnail: Bitmap?)

/**
 * The composer: text field on top, controls row below it - attach (+), the
 * model/effort chip that opens the model sheet, and the send/stop button.
 */
@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<PendingImage>,
    onAddImage: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    canAttach: Boolean,
    modelName: String?,
    effortLabel: String?,
    onOpenModelSheet: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val canvas = MaterialTheme.colorScheme.background
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to canvas)),
        )
        Column(Modifier.background(canvas).padding(start = 12.dp, end = 12.dp, bottom = 14.dp)) {
            if (attachments.isNotEmpty()) {
                LazyRow(Modifier.padding(bottom = 8.dp)) {
                    items(attachments.size) { i ->
                        Box(Modifier.padding(end = 8.dp)) {
                            val thumb = attachments[i].thumbnail
                            Box(
                                Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(Radii.control))
                                    .background(Ink.SurfaceHigh),
                            ) {
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb.asImageBitmap(),
                                        contentDescription = "Attached image",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(64.dp),
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.65f))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { onRemoveImage(i) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Close, "Remove image", Modifier.size(12.dp), tint = Ink.White)
                            }
                        }
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .surfaceRaised(RoundedCornerShape(Radii.card)),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = TextStyle(color = Ink.White, fontSize = 16.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(Ink.White),
                    maxLines = 7,
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            if (value.isEmpty()) {
                                Text("Message Spettro…", color = Ink.I500, fontSize = 16.sp)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp, max = 170.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canAttach) {
                        GhostIconButton(
                            Icons.Rounded.Add,
                            "Attach image",
                            onClick = onAddImage,
                            size = 38.dp,
                            iconSize = 21.dp,
                            tint = Ink.I100,
                        )
                        Spacer(Modifier.width(2.dp))
                    }
                    // Model + effort chip, opens the model sheet.
                    if (modelName != null) {
                        Row(
                            Modifier
                                .clip(CircleShape)
                                .background(Ink.SurfaceHigh)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onOpenModelSheet,
                                )
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                modelName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Ink.I100,
                                maxLines = 1,
                            )
                            if (effortLabel != null) {
                                Spacer(Modifier.width(6.dp))
                                Text(effortLabel, fontSize = 13.sp, color = Ink.I500, maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    SendButton(
                        hasContent = value.isNotBlank() || attachments.isNotEmpty(),
                        isStreaming = isStreaming,
                        onSend = onSend,
                        onStop = onStop,
                    )
                }
            }
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
            .scale(scale)
            .size(38.dp)
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

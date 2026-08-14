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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Images
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.X
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import to.eyed.spettro.chat.data.store.StoredFile
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
    files: List<StoredFile>,
    onCapturePhoto: () -> Unit,
    onPickPhotos: () -> Unit,
    onPickFiles: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onRemoveFile: (Int) -> Unit,
    /** Photo options need a vision model; documents attach on any model. */
    canAttachImages: Boolean,
    modelName: String?,
    effortLabel: String?,
    /** Active skill chip: emoji + name, or null when no skill is applied. */
    skillChip: Pair<String, String>?,
    onOpenSkillPicker: () -> Unit,
    onClearSkill: () -> Unit,
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
            if (files.isNotEmpty()) {
                LazyRow(Modifier.padding(bottom = 8.dp)) {
                    items(files.size) { i ->
                        FileChip(name = files[i].name, onRemove = { onRemoveFile(i) })
                    }
                }
            }
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
                                Icon(Lucide.X, "Remove image", Modifier.size(12.dp), tint = Ink.White)
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
                    // Attach menu: camera and photo library on vision models,
                    // documents everywhere.
                    var attachMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        GhostIconButton(
                            Lucide.Plus,
                            "Attach",
                            onClick = { attachMenuOpen = true },
                            size = 38.dp,
                            iconSize = 21.dp,
                            tint = Ink.I100,
                        )
                        DropdownMenu(
                            expanded = attachMenuOpen,
                            onDismissRequest = { attachMenuOpen = false },
                            shape = RoundedCornerShape(Radii.card),
                            containerColor = Ink.SurfaceHigh,
                        ) {
                            if (canAttachImages) {
                                AttachMenuItem(Lucide.Camera, "Camera") {
                                    attachMenuOpen = false
                                    onCapturePhoto()
                                }
                                AttachMenuItem(Lucide.Images, "Photos") {
                                    attachMenuOpen = false
                                    onPickPhotos()
                                }
                            }
                            AttachMenuItem(Lucide.FileText, "Files") {
                                attachMenuOpen = false
                                onPickFiles()
                            }
                        }
                    }
                    Spacer(Modifier.width(2.dp))
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
                    // Skill chip: shows the active skill; X clears it, tap picks another.
                    Spacer(Modifier.width(6.dp))
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(Ink.SurfaceHigh)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onOpenSkillPicker,
                            )
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (skillChip == null) {
                            Text("Skills", fontSize = 13.sp, color = Ink.I500, maxLines = 1)
                        } else {
                            Text(
                                "${skillChip.first} ${skillChip.second}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Ink.I100,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onClearSkill,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Lucide.X, "Clear skill", Modifier.size(12.dp), tint = Ink.I500)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    SendButton(
                        hasContent = value.isNotBlank() || attachments.isNotEmpty() || files.isNotEmpty(),
                        isStreaming = isStreaming,
                        onSend = onSend,
                        onStop = onStop,
                    )
                }
            }
        }
    }
}

/** One row of the attach menu: icon + label, in the app's flat style. */
@Composable
private fun AttachMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp)
            .widthIn(min = 132.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(17.dp), tint = Ink.I100)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink.White)
    }
}

/** A pending document in the composer: icon, name, and a remove button. */
@Composable
private fun FileChip(name: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(Radii.control))
            .background(Ink.SurfaceHigh)
            .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.FileText, contentDescription = null, Modifier.size(15.dp), tint = Ink.I100)
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Ink.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRemove,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.X, "Remove file", Modifier.size(12.dp), tint = Ink.I500)
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
            if (isStreaming) Lucide.Square else Lucide.ArrowUp,
            contentDescription = if (isStreaming) "Stop" else "Send",
            Modifier.size(18.dp),
            tint = if (active) Ink.Pitch else Ink.I500,
        )
    }
}

package to.eyed.spettro.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.SquareCheck
import to.eyed.spettro.chat.ui.components.surfaceHigh
import to.eyed.spettro.chat.ui.components.surfaceLow
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii

// The selectable rows of the ask-user form: an option card and the
// free-text "Other" card. Selection is signalled by a border, never fill.

@Composable
internal fun OptionRow(
    label: String,
    description: String,
    preview: String,
    recommended: Boolean,
    multiSelect: Boolean,
    selected: Boolean,
    previewExpanded: Boolean,
    onTogglePreview: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Radii.row)
    Column(
        Modifier
            .fillMaxWidth()
            .surfaceLow(shape)
            .then(if (selected) Modifier.border(1.5.dp, Ink.White, shape) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    multiSelect && selected -> Lucide.SquareCheck
                    multiSelect -> Lucide.Square
                    selected -> Lucide.CircleCheck
                    else -> Lucide.Circle
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (selected) Ink.White else Ink.I500,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Ink.White,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (recommended) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.surfaceHigh(CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text(
                                "Recommended",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = Ink.I100,
                            )
                        }
                    }
                }
                if (description.isNotBlank()) {
                    Text(
                        description,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = Ink.I500,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (preview.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                // Separate tap target: the chevron expands, the card selects.
                Icon(
                    if (previewExpanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = "Toggle preview",
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onTogglePreview,
                        )
                        .padding(4.dp),
                    tint = Ink.I500,
                )
            }
        }
        AnimatedVisibility(
            previewExpanded && preview.isNotBlank(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .surfaceHigh(RoundedCornerShape(8.dp))
                    .padding(10.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                // Verbatim and clipped-not-wrapped, like the CLI preview pane.
                Text(
                    preview,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Ink.I100,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
internal fun OtherRow(
    bare: Boolean,
    open: Boolean,
    text: String,
    onOpen: () -> Unit,
    onTextChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(Radii.row)
    val selected = text.isNotBlank()
    Column(
        Modifier
            .fillMaxWidth()
            .surfaceLow(shape)
            .then(if (selected) Modifier.border(1.5.dp, Ink.White, shape) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !open,
                onClick = onOpen,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (!bare) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Pencil, null, Modifier.size(15.dp), tint = if (selected) Ink.White else Ink.I500)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (open) "Or answer in your own words" else "Other…",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) Ink.White else Ink.I300,
                )
            }
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = TextStyle(color = Ink.White, fontSize = 14.sp, lineHeight = 20.sp),
                cursorBrush = SolidColor(Ink.White),
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (bare) 0.dp else 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text("Type your answer", fontSize = 14.sp, color = Ink.I500)
                        }
                        inner()
                    }
                },
            )
        }
    }
}

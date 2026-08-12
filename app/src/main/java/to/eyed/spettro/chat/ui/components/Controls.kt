package to.eyed.spettro.chat.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.ui.theme.whiteA

// spring(stiffness 400, damping 40, mass 1) ~ critically damped
fun <T> snappySpring() = spring<T>(dampingRatio = 1f, stiffness = 400f)
fun <T> softSpring() = spring<T>(dampingRatio = 0.95f, stiffness = 260f)

/** 36x36 ghost icon button: transparent at rest, glass on press. */
@Composable
fun GhostIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 16.dp,
    tint: Color = Ink.I500,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, snappySpring(), label = "scale")
    val bg by animateColorAsState(if (pressed) whiteA(0.06f) else Color.Transparent, tween(200), label = "bg")
    val color by animateColorAsState(if (pressed) Color.White else tint, tween(200), label = "tint")
    Box(
        modifier
            .size(size)
            .scale(scale)
            .clip(RoundedCornerShape(Radii.control))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = color)
    }
}

/** Full-width white primary CTA. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, snappySpring(), label = "scale")
    val shape = RoundedCornerShape(Radii.row)
    Row(
        modifier
            .scale(scale)
            .then(if (enabled) Modifier.whiteGlow(shape) else Modifier)
            .clip(shape)
            .background(if (enabled) Color.White else whiteA(0.06f))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = if (enabled) Ink.Pitch else whiteA(0.25f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (trailing != null) trailing()
    }
}

/** Secondary glass button. */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, snappySpring(), label = "scale")
    Box(
        modifier
            .scale(scale)
            .glass(RoundedCornerShape(Radii.control))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** The 44x24 monochrome toggle. */
@Composable
fun GlassToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val trackBg by animateColorAsState(if (checked) whiteA(0.25f) else whiteA(0.06f), tween(300), label = "track")
    val trackBorder by animateColorAsState(if (checked) whiteA(0.40f) else whiteA(0.15f), tween(300), label = "border")
    val knobColor by animateColorAsState(if (checked) Color.White else Ink.I500, tween(300), label = "knob")
    val knobX by animateDpAsState(if (checked) 22.dp else 2.dp, snappySpring(), label = "x")
    Box(
        modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(CircleShape)
            .background(trackBg)
            .border(1.dp, trackBorder, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = knobX, y = 2.dp)
                .size(18.dp)
                .then(if (checked) Modifier.whiteGlow(CircleShape) else Modifier)
                .clip(CircleShape)
                .background(knobColor),
        )
    }
}

/** Segmented control with a sliding white pill (2+ options). */
@Composable
fun SegmentedPill(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .glass(RoundedCornerShape(Radii.row))
            .padding(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            val textColor by animateColorAsState(
                if (active) Ink.Pitch else Ink.I500,
                tween(200),
                label = "segText",
            )
            val bg by animateColorAsState(
                if (active) Color.White else Color.Transparent,
                snappySpring(),
                label = "segBg",
            )
            Box(
                Modifier
                    .weight(1f)
                    .then(if (active) Modifier.whiteGlow(RoundedCornerShape(Radii.control)) else Modifier)
                    .clip(RoundedCornerShape(Radii.control))
                    .background(bg)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Small hairline divider. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(whiteA(0.08f)))
}

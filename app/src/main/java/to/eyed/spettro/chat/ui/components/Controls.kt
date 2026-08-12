package to.eyed.spettro.chat.ui.components

import androidx.compose.animation.animateColorAsState
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

/** Circular ghost icon button: transparent at rest, subtle fill on press. */
@Composable
fun GhostIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 17.dp,
    tint: Color = Ink.I300,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, snappySpring(), label = "scale")
    val bg by animateColorAsState(if (pressed) Ink.SurfaceHigh else Color.Transparent, tween(150), label = "bg")
    Box(
        modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = tint)
    }
}

/** Full-width white pill CTA. */
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
    Row(
        modifier
            .scale(scale)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Ink.SurfaceLow)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp, horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = if (enabled) Ink.Pitch else whiteA(0.25f),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (trailing != null) trailing()
    }
}

/** Secondary flat pill button. */
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
            .clip(CircleShape)
            .background(Ink.SurfaceHigh)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** The 44x24 monochrome toggle. */
@Composable
fun GlassToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val trackBg by animateColorAsState(if (checked) Ink.White else Ink.SurfaceHigh, tween(250), label = "track")
    val knobColor by animateColorAsState(if (checked) Ink.Pitch else Ink.I500, tween(250), label = "knob")
    val knobX by animateDpAsState(if (checked) 22.dp else 3.dp, snappySpring(), label = "x")
    Box(
        modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(CircleShape)
            .background(trackBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = knobX, y = 3.dp)
                .size(18.dp)
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
            .surfaceLow(CircleShape)
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
                    .clip(CircleShape)
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

/** Hairline divider. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(whiteA(0.08f)))
}

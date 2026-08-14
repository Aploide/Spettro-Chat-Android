package to.eyed.spettro.chat.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.ui.theme.Ink

/**
 * The subscription-tier label, ported from the Spettro remote app
 * (PlanBadge.kt there, PlanBadge.swift before that) so the same plan reads as
 * the same color in every Spettro front-end: bold uppercase text in the tier
 * color, and for the max plan an animated rainbow cycling through the TUI's
 * palette. This app is always dark, so only the dark variants are kept.
 */
@Composable
fun PlanBadge(
    plan: String?,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp,
) {
    val normalized = plan?.trim()?.lowercase()?.ifEmpty { null }
    if (normalized == "max") {
        RainbowText(text = "MAX", fontSize = fontSize, modifier = modifier)
        return
    }

    val tierColor = when (normalized) {
        null -> Ink.I500
        "lite" -> Ink.I100
        "plus" -> Color(0xFF86EFAC)
        "pro" -> Color(0xFFC4B5FD)
        else -> Color(0xFF9CA3AF) // free and unknown tiers
    }
    Text(
        text = (normalized ?: "no plan").uppercase(),
        modifier = modifier,
        color = tierColor,
        style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    )
}

/** Seconds for the rainbow palette to travel one full cycle. */
private const val RAINBOW_PERIOD_MILLIS = 3000

/** The TUI's six-color rainbow (`rainbow[(i+frame)%len]` in renderPlanLabel). */
private val Rainbow = listOf(
    Color(0xFFFF6B6B),
    Color(0xFFFF9E4F),
    Color(0xFFFFD93D),
    Color(0xFF6BCB77),
    Color(0xFF4D96FF),
    Color(0xFFC77DFF),
)

/**
 * Bold text filled with the scrolling rainbow. The palette scrolls rather
 * than hue-rotating so all six colors stay visible at every instant — the
 * gradient repeats seamlessly because the first color is appended to close
 * the cycle, and the shader tiles it.
 */
@Composable
private fun RainbowText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "rainbow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(RAINBOW_PERIOD_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rainbowPhase",
    )
    val brush = object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            // One palette cycle spans the label's width; sliding by exactly
            // one width lands on the identical repeat, so the loop is
            // seamless.
            val originX = -size.width * phase
            return LinearGradientShader(
                from = Offset(originX, 0f),
                to = Offset(originX + size.width, 0f),
                colors = Rainbow + Rainbow.first(),
                tileMode = TileMode.Repeated,
            )
        }
    }
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            brush = brush,
        ),
    )
}

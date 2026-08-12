package to.eyed.spettro.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.whiteA

// The three glass recipes from the design system. Backdrop blur is emulated
// with translucent fills on the pure-black canvas (blurred black is black);
// the signature inset top highlight and refraction band are drawn for real.

/** `.glass` - 4% white fill, border white/10. */
fun Modifier.glass(shape: Shape, refraction: Boolean = false): Modifier =
    glassBase(shape, fill = whiteA(0.04f), border = whiteA(0.10f), topHighlight = 0.08f, refraction = refraction)

/** `.glass-strong` - 6% white fill, border white/12. */
fun Modifier.glassStrong(shape: Shape, refraction: Boolean = false): Modifier =
    glassBase(shape, fill = whiteA(0.06f), border = whiteA(0.12f), topHighlight = 0.10f, refraction = refraction)

/** `.glass-overlay` - 80% ink fill, border white/14. Menus, sheets, modals. */
fun Modifier.glassOverlay(shape: Shape, refraction: Boolean = false): Modifier =
    glassBase(shape, fill = Ink.I900.copy(alpha = 0.97f), border = whiteA(0.14f), topHighlight = 0.12f, refraction = refraction)

fun Modifier.glassBase(
    shape: Shape,
    fill: Color,
    border: Color,
    topHighlight: Float,
    refraction: Boolean = false,
): Modifier {
    var m = clip(shape).background(fill)
    if (refraction) m = m.refraction()
    return m
        .drawWithContent {
            drawContent()
            // Inset top highlight: inset 0 1px 0 rgba(255,255,255,a)
            val inset = 16.dp.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.2f to whiteA(topHighlight),
                    0.8f to whiteA(topHighlight),
                    1f to Color.Transparent,
                ),
                topLeft = Offset(inset, 0f),
                size = Size((size.width - inset * 2).coerceAtLeast(0f), 1.dp.toPx()),
            )
        }
        .border(1.dp, border, shape)
}

/**
 * The refraction overlay: a top specular bloom plus a 115-degree diagonal
 * light band peaking at 9% white.
 */
fun Modifier.refraction(): Modifier = drawBehind {
    drawRect(
        brush = Brush.radialGradient(
            0f to whiteA(0.09f),
            0.6f to Color.Transparent,
            1f to Color.Transparent,
            center = Offset(size.width * 0.5f, -size.height * 0.1f),
            radius = (size.width * 0.7f).coerceAtLeast(1f),
        ),
    )
    val dx = size.width
    drawRect(
        brush = Brush.linearGradient(
            0.30f to Color.Transparent,
            0.46f to whiteA(0.045f),
            0.50f to whiteA(0.09f),
            0.54f to whiteA(0.045f),
            0.70f to Color.Transparent,
            start = Offset(0f, 0f),
            end = Offset(dx, dx * 0.47f), // ~115 degree band
        ),
    )
}

/** A soft white glow behind active/primary elements (shadow-glow-white). */
fun Modifier.whiteGlow(shape: Shape, alpha: Float = 0.12f): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val bounds = outline.bounds
    drawRect(
        brush = Brush.radialGradient(
            0f to whiteA(alpha),
            1f to Color.Transparent,
            center = bounds.center,
            radius = (maxOf(bounds.width, bounds.height) * 0.85f).coerceAtLeast(1f),
        ),
        topLeft = Offset(bounds.left - 24.dp.toPx(), bounds.top - 24.dp.toPx()),
        size = Size(bounds.width + 48.dp.toPx(), bounds.height + 48.dp.toPx()),
    )
}

package to.eyed.spettro.chat.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import to.eyed.spettro.chat.ui.theme.whiteA
import kotlin.math.PI
import kotlin.math.sin

private class Particle(val x: Float, val y: Float, val r: Float, val maxAlpha: Float, val period: Float, val phase: Float)

/**
 * Ambient monochrome background: drifting blurred orbs, a dot grid, floating
 * particles, and a vignette. Deterministic PRNG (seed 1337) like the mockup.
 */
@Composable
fun MeshBackground(modifier: Modifier = Modifier, particleCount: Int = 42) {
    val particles = remember {
        var s = 1337L
        fun rand(): Float {
            s = (s * 16807L) % 2147483647L
            return (s - 1).toFloat() / 2147483646f
        }
        List(particleCount) {
            Particle(
                x = rand(),
                y = rand(),
                r = 1f + rand() * 2.5f,
                maxAlpha = 0.15f + rand() * 0.5f,
                period = 6f + rand() * 10f,
                phase = rand() * 8f,
            )
        }
    }
    val t by rememberInfiniteTransition(label = "mesh").animateFloat(
        initialValue = 0f,
        targetValue = 48f, // seconds-ish master clock, loops evenly for 16s drift
        animationSpec = infiniteRepeatable(tween(48000, easing = LinearEasing), RepeatMode.Restart),
        label = "clock",
    )
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        fun orb(cx: Float, cy: Float, radius: Float, alpha: Float, delay: Float) {
            val p = ((t + delay) % 16f) / 16f
            val ang = p * 2f * PI.toFloat()
            val ox = sin(ang) * w * 0.04f
            val oy = sin(ang * 2f) * h * 0.03f
            drawCircle(
                brush = Brush.radialGradient(
                    0f to whiteA(alpha),
                    1f to Color.Transparent,
                    center = Offset(cx + ox, cy + oy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx + ox, cy + oy),
            )
        }
        orb(w * 0.2f, h * 0.15f, w * 0.7f, 0.045f, 0f)
        orb(w * 0.85f, h * 0.85f, w * 0.65f, 0.035f, 8f)
        orb(w * 0.5f, h * 0.5f, w * 0.4f, 0.03f, 4f)

        // Dot grid, 22px spacing at 40% opacity
        val step = 22f * density
        var gx = step / 2
        while (gx < w) {
            var gy = step / 2
            while (gy < h) {
                drawCircle(whiteA(0.07f * 0.4f), radius = 1f * density, center = Offset(gx, gy))
                gy += step
            }
            gx += step
        }

        // Particles: fade in/out while drifting up 28px
        for (p in particles) {
            val cycle = ((t + p.phase) % p.period) / p.period
            val alpha = p.maxAlpha * sin(cycle * PI.toFloat())
            val dy = -28f * density * sin(cycle * PI.toFloat())
            drawCircle(
                whiteA(alpha.coerceAtLeast(0f)),
                radius = p.r * density,
                center = Offset(p.x * w, p.y * h + dy),
            )
        }

        // Vignette
        drawRect(
            brush = Brush.radialGradient(
                0.35f to Color.Transparent,
                1f to Color(0xBF000000),
                center = Offset(w / 2, h / 2),
                radius = maxOf(w, h) * 0.75f,
            ),
        )
    }
}

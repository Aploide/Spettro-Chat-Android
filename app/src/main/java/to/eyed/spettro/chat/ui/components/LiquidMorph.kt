package to.eyed.spettro.chat.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.eyed.spettro.chat.ui.theme.whiteA
import kotlin.math.PI
import kotlin.math.sin

// The liquid-morph brand mark: a white shape whose border-radius oscillates
// organically (7s ease-in-out loop in the reference).

@Composable
private fun morphShape(phaseOffset: Float): androidx.compose.foundation.shape.RoundedCornerShape {
    val t by rememberInfiniteTransition(label = "morph").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart),
        label = "morphT",
    )
    fun wave(seed: Float): Float {
        val v = sin((2f * PI.toFloat() * (t + phaseOffset) + seed).toDouble()).toFloat()
        return 0.38f + 0.24f * (v * 0.5f + 0.5f) // 38%..62%
    }
    return androidx.compose.foundation.shape.RoundedCornerShape(
        topStartPercent = (wave(0.0f) * 100).toInt(),
        topEndPercent = (wave(1.7f) * 100).toInt(),
        bottomEndPercent = (wave(3.1f) * 100).toInt(),
        bottomStartPercent = (wave(4.6f) * 100).toInt(),
    )
}

/** Small solid white morphing square - the assistant avatar mark. */
@Composable
fun LiquidMark(size: Dp, modifier: Modifier = Modifier, color: Color = Color.White) {
    Box(modifier.size(size).clip(morphShape(0f)).background(color))
}

/** The large 3-layer thinking blob. */
@Composable
fun LiquidThinking(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(modifier.size(size)) {
        Box(
            Modifier.fillMaxSize()
                .clip(morphShape(0f))
                .background(whiteA(0.08f))
                .border(1.dp, whiteA(0.25f), morphShape(0f)),
        )
        Box(
            Modifier.fillMaxSize().padding(6.dp)
                .blur(2.dp)
                .clip(morphShape(0.36f))
                .background(whiteA(0.15f)),
        )
        Box(
            Modifier.fillMaxSize().padding(14.dp)
                .blur(1.dp)
                .clip(morphShape(0.64f))
                .background(whiteA(0.60f)),
        )
    }
}

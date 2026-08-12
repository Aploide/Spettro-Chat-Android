package to.eyed.spettro.chat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Corner radii: 12 controls, 16 rows, 24 cards, 28 input bar, 32+ sheets.
object Radii {
    val control = 12.dp
    val row = 16.dp
    val card = 24.dp
    val inputBar = 28.dp
    val sheet = 32.dp
}

private val MonochromeScheme = darkColorScheme(
    primary = Ink.White,
    onPrimary = Ink.Pitch,
    primaryContainer = whiteA(0.10f),
    onPrimaryContainer = Ink.White,
    secondary = Ink.I300,
    onSecondary = Ink.Pitch,
    secondaryContainer = whiteA(0.06f),
    onSecondaryContainer = Ink.I100,
    tertiary = Ink.I500,
    onTertiary = Ink.Pitch,
    background = Ink.Pitch,
    onBackground = Ink.I100,
    surface = Ink.Pitch,
    onSurface = Ink.I100,
    surfaceVariant = Ink.I850,
    onSurfaceVariant = Ink.I500,
    surfaceContainer = Ink.I900,
    surfaceContainerLow = Ink.I900,
    surfaceContainerHigh = Ink.I850,
    surfaceContainerHighest = Ink.I800,
    surfaceTint = Color.Transparent,
    error = Ink.White,
    onError = Ink.Pitch,
    outline = whiteA(0.10f),
    outlineVariant = whiteA(0.06f),
    scrim = Color(0xB3000000),
    inverseSurface = Ink.I100,
    inverseOnSurface = Ink.Pitch,
    inversePrimary = Ink.Pitch,
)

private val SpettroShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(Radii.control),
    medium = RoundedCornerShape(Radii.row),
    large = RoundedCornerShape(Radii.card),
    extraLarge = RoundedCornerShape(Radii.sheet),
)

@Composable
fun SpettroChatTheme(charcoal: Boolean = false, content: @Composable () -> Unit) {
    // "Pitch is pure #000. Charcoal lifts surfaces to #0a0a0a."
    val canvas = if (charcoal) Ink.I900 else Ink.Pitch
    MaterialTheme(
        colorScheme = MonochromeScheme.copy(background = canvas, surface = canvas),
        typography = Typography,
        shapes = SpettroShapes,
    ) {
        Surface(color = canvas, contentColor = Ink.I100, content = content)
    }
}

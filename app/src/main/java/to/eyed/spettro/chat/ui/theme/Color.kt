package to.eyed.spettro.chat.ui.theme

import androidx.compose.ui.graphics.Color

// Spettro Chat is strictly monochrome: every color has equal RGB channels.
// Ink scale from the design reference (spettro-chat-screens).
object Ink {
    val Pitch = Color(0xFF000000) // app background
    val I900 = Color(0xFF0A0A0A) // overlay glass base, code blocks
    val I850 = Color(0xFF121212) // elevated panels
    val I800 = Color(0xFF1A1A1A)
    val I700 = Color(0xFF262626) // hairline borders, dividers
    val I500 = Color(0xFF737373) // secondary text, icons at rest
    val I300 = Color(0xFFA3A3A3) // tertiary labels
    val I100 = Color(0xFFE5E5E5) // body text on dark glass
    val White = Color(0xFFFFFFFF) // primary text, CTAs, active states
}

// White-alpha ladder used for glass fills and borders.
fun whiteA(alpha: Float): Color = Color.White.copy(alpha = alpha)

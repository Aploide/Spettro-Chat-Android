package to.eyed.spettro.chat.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldQuestion
import to.eyed.spettro.chat.engine.ConsentDecision
import to.eyed.spettro.chat.engine.ConsentRequest
import to.eyed.spettro.chat.ui.components.GlassButton
import to.eyed.spettro.chat.ui.components.PrimaryButton
import to.eyed.spettro.chat.ui.components.surfaceCard
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii

/**
 * The mandatory approval card shown when the model asks for personal data
 * (calendar, contacts, reminders, location, MCP servers). Nothing runs until
 * the user picks: Allow once, Always allow, or Deny.
 */
@Composable
fun ConsentCard(
    request: ConsentRequest,
    onDecision: (ConsentDecision) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .surfaceCard(RoundedCornerShape(Radii.card), fill = Ink.Surface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.ShieldQuestion, null, Modifier.size(16.dp), tint = Ink.I300)
            Spacer(Modifier.width(8.dp))
            Text(
                "PERMISSION REQUEST",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                color = Ink.I500,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(request.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink.White)
        Spacer(Modifier.height(6.dp))
        Text(request.detail, fontSize = 13.sp, lineHeight = 18.sp, color = Ink.I300)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                text = "Allow once",
                onClick = { onDecision(ConsentDecision.AllowOnce) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            GlassButton(
                text = "Always allow",
                onClick = { onDecision(ConsentDecision.AlwaysAllow) },
            )
            Spacer(Modifier.width(8.dp))
            GlassButton(
                text = "Deny",
                onClick = { onDecision(ConsentDecision.Deny) },
                textColor = Ink.Danger,
            )
        }
    }
}

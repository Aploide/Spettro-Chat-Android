package to.eyed.spettro.chat.ui.pricing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.X
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.ui.components.GhostIconButton
import to.eyed.spettro.chat.ui.components.PrimaryButton
import to.eyed.spettro.chat.ui.components.SegmentedPill
import to.eyed.spettro.chat.ui.components.surfaceCard
import to.eyed.spettro.chat.ui.components.surfaceHigh
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii

private data class Tier(
    val name: String,
    val monthly: Int,
    val yearly: Int,
    val tagline: String,
    val features: List<String>,
    val highlight: Boolean = false,
)

private val TIERS = listOf(
    Tier("Free", 0, 0, "Taste the glass", listOf("Spettro Air model", "20 messages / day", "7-day chat history", "Community support")),
    Tier("Lite", 8, 80, "Everyday thinking", listOf("Spettro Pro model", "200 messages / day", "Unlimited history", "File attachments", "Email support")),
    Tier("Plus", 20, 200, "The sweet spot", listOf("All Pro features", "Deep Thought mode", "Web search built in", "Voice conversations", "Priority queue"), highlight = true),
    Tier("Pro", 60, 600, "For heavy builders", listOf("All Plus features", "Spettro Ultra access", "5× rate limits", "API credits included", "Early features")),
    Tier("Max", 200, 2000, "No ceilings", listOf("All Pro features", "20× rate limits", "Dedicated capacity", "Custom instructions vault", "White-glove support")),
)

/**
 * Plans overview. Checkout happens on spettro.app/pricing (the backend has
 * no in-app purchase API), so every CTA hands off to the browser.
 */
@Composable
fun PricingScreen(
    currentPlan: String,
    onUpgrade: () -> Unit,
    onClose: () -> Unit,
) {
    var yearly by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Pricing", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink.I500)
            Spacer(Modifier.weight(1f))
            GhostIconButton(Lucide.X, "Close", onClose)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose your plan",
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            color = Ink.White,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentedPill(
                options = listOf("Monthly", "Yearly"),
                selected = if (yearly) 1 else 0,
                onSelect = { yearly = it == 1 },
                modifier = Modifier.width(220.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(CircleShape).background(Ink.SurfaceHigh).padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("−17%", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Ink.I300)
            }
        }
        Spacer(Modifier.height(20.dp))

        TIERS.forEach { tier ->
            TierCard(
                tier = tier,
                yearly = yearly,
                isCurrent = tier.name.equals(currentPlan, ignoreCase = true),
                onUpgrade = onUpgrade,
            )
            Spacer(Modifier.height(16.dp))
        }

        Text(
            "Cancel anytime. No hidden fees.\nSubscriptions are managed at spettro.app.",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Ink.I500,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TierCard(tier: Tier, yearly: Boolean, isCurrent: Boolean, onUpgrade: () -> Unit) {
    val shape = RoundedCornerShape(Radii.card)
    Box(Modifier.fillMaxWidth().widthIn(max = 448.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .then(
                    if (tier.highlight) Modifier.surfaceCard(shape, fill = Ink.Surface)
                    else Modifier.surfaceCard(shape),
                )
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tier.name.uppercase(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.8.sp,
                    color = if (tier.highlight) Ink.I100 else Ink.I500,
                )
                if (isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(CircleShape).background(Ink.SurfaceHigh).padding(horizontal = 10.dp, vertical = 3.dp),
                    ) {
                        Text("Current", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Ink.I300)
                    }
                }
            }
            Text(tier.tagline, fontSize = 12.sp, color = Ink.I500)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                val price = if (yearly) tier.yearly / 12 else tier.monthly
                Text(
                    "$$price",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                    color = Ink.White,
                )
                Spacer(Modifier.width(6.dp))
                Text("/ month", fontSize = 12.sp, color = Ink.I500, modifier = Modifier.padding(bottom = 6.dp))
            }
            if (yearly && tier.yearly > 0) {
                Text("$${tier.yearly}/yr — two months free", fontSize = 11.sp, color = Ink.I300)
            }
            Spacer(Modifier.height(16.dp))
            tier.features.forEach { feature ->
                Row(
                    Modifier.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Ink.SurfaceHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.Check, null, Modifier.size(9.dp), tint = Ink.White)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(feature, fontSize = 13.sp, color = Ink.I100)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (tier.highlight) {
                PrimaryButton(
                    text = if (tier.monthly == 0) "Start Free" else "Get ${tier.name}",
                    onClick = onUpgrade,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .surfaceHigh(RoundedCornerShape(999.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onUpgrade() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (tier.monthly == 0) "Start Free" else "Get ${tier.name}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.White,
                    )
                }
            }
        }
        if (tier.highlight) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .clip(CircleShape)
                    .background(Ink.White)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Sparkles, null, Modifier.size(10.dp), tint = Ink.Pitch)
                Spacer(Modifier.width(4.dp))
                Text(
                    "POPULAR",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = Ink.Pitch,
                )
            }
        }
    }
}

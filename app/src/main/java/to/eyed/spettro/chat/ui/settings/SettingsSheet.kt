package to.eyed.spettro.chat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.data.api.Account
import to.eyed.spettro.chat.ui.components.GlassButton
import to.eyed.spettro.chat.ui.components.GlassToggle
import to.eyed.spettro.chat.ui.components.Hairline
import to.eyed.spettro.chat.ui.components.surfaceHigh
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.ui.theme.whiteA

private val TABS = listOf("General", "Personalization", "Account", "Data Controls")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    account: Account?,
    email: String,
    plan: String,
    theme: String,
    streamingAnimations: Boolean,
    hapticFeedback: Boolean,
    onSetTheme: (String) -> Unit,
    onSetStreamingAnimations: (Boolean) -> Unit,
    onSetHapticFeedback: (Boolean) -> Unit,
    onManageSubscription: () -> Unit,
    onDeleteAllChats: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ink.SurfaceLow,
        contentColor = Ink.I100,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink.White)
            }
            Hairline()

            // Tab rail
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TABS.forEachIndexed { i, label ->
                    val active = i == tab
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(Radii.control))
                            .background(if (active) Ink.SurfaceHigh else Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { tab = i }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (active) Ink.White else Ink.I500,
                        )
                    }
                }
            }
            Hairline()

            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                when (tab) {
                    0 -> {
                        SettingRow("Streaming animations", "Fade tokens in as they arrive.") {
                            GlassToggle(streamingAnimations, onSetStreamingAnimations)
                        }
                        RowDivider()
                        SettingRow("Haptic feedback", "Subtle taps on send and receive.") {
                            GlassToggle(hapticFeedback, onSetHapticFeedback)
                        }
                    }
                    1 -> {
                        SettingRow("Theme", "Pitch is pure #000. Charcoal lifts surfaces to #0a0a0a.") {
                            MiniPill(
                                options = listOf("Pitch", "Charcoal"),
                                selected = if (theme == "charcoal") 1 else 0,
                                onSelect = { onSetTheme(if (it == 1) "charcoal" else "pitch") },
                            )
                        }
                    }
                    2 -> {
                        SettingRow("Signed in as", email.ifBlank { "—" }) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Ink.SurfaceHigh)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    "${plan.ifBlank { "free" }.replaceFirstChar { it.uppercase() }} plan",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Ink.I300,
                                )
                            }
                        }
                        if (account != null && account.creditLimit > 0) {
                            RowDivider()
                            SettingRow(
                                "Credits",
                                String.format(
                                    java.util.Locale.US,
                                    "%.2f used of %.2f — %.2f remaining",
                                    account.creditsUsed, account.creditLimit, account.remaining,
                                ),
                            ) {}
                            if (account.planStatus.isNotBlank() && account.planStatus != "active") {
                                RowDivider()
                                SettingRow("Plan status", account.planStatus) {}
                            }
                        }
                        RowDivider()
                        SettingRow("Subscription", "Plans and billing are managed on spettro.app.") {
                            GlassButton("Manage", onClick = onManageSubscription)
                        }
                        RowDivider()
                        SettingRow("Sign out", "Removes your session key from this device.") {
                            GlassButton("Sign out", onClick = onSignOut, textColor = Ink.I300)
                        }
                    }
                    3 -> {
                        SettingRow("Chat history", "Conversations are stored only on this device.") {}
                        RowDivider()
                        var confirming by remember { mutableStateOf(false) }
                        SettingRow("Delete all chats", if (confirming) "Tap again to confirm. Irreversible." else "Irreversible. Everything, gone.") {
                            // Destructive = inverted white, no red (monochrome rule).
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(Radii.control))
                                    .background(if (confirming) Ink.White else whiteA(0.85f))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        if (confirming) {
                                            onDeleteAllChats()
                                            confirming = false
                                        } else {
                                            confirming = true
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    if (confirming) "Confirm" else "Delete",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Ink.Pitch,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingRow(title: String, description: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink.White)
            Spacer(Modifier.height(2.dp))
            Text(description, fontSize = 11.sp, lineHeight = 15.sp, color = Ink.I500)
        }
        Spacer(Modifier.width(16.dp))
        trailing()
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(whiteA(0.06f)))
}

@Composable
private fun MiniPill(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.surfaceHigh(RoundedCornerShape(Radii.control)).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) Ink.White else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (active) Ink.Pitch else Ink.I500,
                )
            }
        }
    }
}

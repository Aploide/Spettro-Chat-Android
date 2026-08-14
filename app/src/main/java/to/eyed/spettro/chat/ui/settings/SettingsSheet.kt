package to.eyed.spettro.chat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.ui.theme.whiteA

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    account: Account?,
    email: String,
    plan: String,
    streamingAnimations: Boolean,
    hapticFeedback: Boolean,
    onSetStreamingAnimations: (Boolean) -> Unit,
    onSetHapticFeedback: (Boolean) -> Unit,
    onManageSubscription: () -> Unit,
    onExportChats: () -> Unit,
    onImportChats: () -> Unit,
    onDeleteAllChats: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ink.SurfaceLow,
        contentColor = Ink.I100,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink.White)
            }
            Hairline()

            // Sectioned flat list - too little here to justify tabs.
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                SectionHeader("Account")
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

                SectionDivider()
                SectionHeader("Customization")
                SettingRow("Streaming animations", "Fade tokens in as they arrive.") {
                    GlassToggle(streamingAnimations, onSetStreamingAnimations)
                }
                RowDivider()
                SettingRow("Haptic feedback", "Subtle taps on send and receive.") {
                    GlassToggle(hapticFeedback, onSetHapticFeedback)
                }

                SectionDivider()
                SectionHeader("Chats")
                SettingRow("Export chats", "Save every chat to a single file you can move to another device.") {
                    GlassButton("Export", onClick = onExportChats)
                }
                RowDivider()
                SettingRow("Import chats", "Merge chats from a previously exported file. Nothing here is overwritten by older copies.") {
                    GlassButton("Import", onClick = onImportChats)
                }

                SectionDivider()
                SectionHeader("Danger zone", color = Ink.Danger)
                var confirming by remember { mutableStateOf(false) }
                SettingRow(
                    "Delete all chats",
                    if (confirming) "Tap again to confirm. Irreversible." else "Chats are stored only on this device.",
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(Radii.control))
                            .background(if (confirming) Ink.Danger else Ink.DangerDim)
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
                            color = if (confirming) Ink.White else Ink.Danger,
                        )
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
private fun SectionHeader(title: String, color: Color = Ink.I500) {
    Text(
        title.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        color = color,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(whiteA(0.06f)))
}

/** Heavier break between setting groups. */
@Composable
private fun SectionDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .height(1.dp)
            .background(whiteA(0.10f)),
    )
}

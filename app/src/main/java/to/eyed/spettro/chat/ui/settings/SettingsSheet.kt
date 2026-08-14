package to.eyed.spettro.chat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.data.api.Account
import to.eyed.spettro.chat.ui.components.GlassButton
import to.eyed.spettro.chat.ui.components.GlassToggle
import to.eyed.spettro.chat.ui.components.Hairline
import to.eyed.spettro.chat.ui.components.PlanBadge
import to.eyed.spettro.chat.ui.components.surfaceCard
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.ui.theme.whiteA
import kotlin.math.roundToInt

// Subscription-state colors shared with the Spettro remote app (its
// diffAdded/diffRemoved plus the TUI's amber): green for healthy, amber for
// billing hiccups, red for gone.
private val StatusGood = Color(0xFF40C977)
private val StatusWarn = Color(0xFFF59E0B)
private val StatusBad = Color(0xFFFA423E)

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
    /** Persisted "always allow" grants for sensitive tools: consentKey → display label. */
    toolGrants: List<Pair<String, String>>,
    onRevokeConsent: (String) -> Unit,
    mcpServerCount: Int,
    onOpenMcpServers: () -> Unit,
    skillCount: Int,
    onOpenSkills: () -> Unit,
    memoryCount: Int,
    onOpenMemory: () -> Unit,
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

            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SectionHeader("Account")
                SettingsCard {
                    AccountHeader(email = email, plan = plan)
                    if (account != null && account.creditLimit > 0) {
                        CardDivider()
                        UsageRows(account)
                    }
                    CardDivider()
                    SettingRow("Subscription", "Plans and billing are managed on spettro.app.") {
                        GlassButton("Manage", onClick = onManageSubscription)
                    }
                    CardDivider()
                    SettingRow("Sign out", "Removes your session key from this device.") {
                        GlassButton("Sign out", onClick = onSignOut, textColor = Ink.I300)
                    }
                }

                SectionHeader("Customization")
                SettingsCard {
                    SettingRow("Streaming animations", "Fade tokens in as they arrive.") {
                        GlassToggle(streamingAnimations, onSetStreamingAnimations)
                    }
                    CardDivider()
                    SettingRow("Haptic feedback", "Subtle taps on send and receive.") {
                        GlassToggle(hapticFeedback, onSetHapticFeedback)
                    }
                }

                SectionHeader("Connectors")
                SettingsCard {
                    SettingRow(
                        "MCP servers",
                        if (mcpServerCount == 0) "Connect remote tool servers the assistant can call."
                        else "$mcpServerCount ${if (mcpServerCount == 1) "server" else "servers"} configured.",
                    ) {
                        GlassButton("Manage", onClick = onOpenMcpServers)
                    }
                    CardDivider()
                    SettingRow(
                        "Skills",
                        "$skillCount available — reusable instructions applied per chat or via /slash.",
                    ) {
                        GlassButton("Manage", onClick = onOpenSkills)
                    }
                    CardDivider()
                    SettingRow(
                        "Memory",
                        if (memoryCount == 0) "Facts Spettro remembers about you across chats."
                        else "$memoryCount ${if (memoryCount == 1) "fact" else "facts"} remembered across chats.",
                    ) {
                        GlassButton("Manage", onClick = onOpenMemory)
                    }
                }

                SectionHeader("Tool permissions")
                SettingsCard {
                    if (toolGrants.isEmpty()) {
                        SettingRow(
                            "No standing approvals",
                            "When you pick \"Always allow\" on a permission card, the grant appears here.",
                        ) {}
                    } else {
                        toolGrants.forEachIndexed { i, (key, label) ->
                            if (i > 0) CardDivider()
                            SettingRow(label, "Allowed without asking again.") {
                                GlassButton("Revoke", onClick = { onRevokeConsent(key) }, textColor = Ink.Danger)
                            }
                        }
                    }
                }

                SectionHeader("Your data")
                SettingsCard {
                    SettingRow(
                        "Export everything",
                        "Chats, skills, memory, MCP servers, and settings in one file you can move to " +
                            "another device. Your sign-in is never included.",
                    ) {
                        GlassButton("Export", onClick = onExportChats)
                    }
                    CardDivider()
                    SettingRow(
                        "Import a backup",
                        "Merge a Spettro backup (or an old chats-only export). Nothing here is overwritten by older copies.",
                    ) {
                        GlassButton("Import", onClick = onImportChats)
                    }
                }

                SectionHeader("Danger zone", color = Ink.Danger)
                SettingsCard {
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
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Avatar, email, and the tier badge in the remote app's colors. */
@Composable
private fun AccountHeader(email: String, plan: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Ink.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                email.trim().firstOrNull()?.uppercase() ?: "?",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink.White,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                email.ifBlank { "Signed in" },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Ink.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text("Signed in on this device", fontSize = 11.sp, color = Ink.I500)
        }
        Spacer(Modifier.width(12.dp))
        PlanBadge(plan = plan.ifBlank { "free" })
    }
}

/** Remaining usage as a percentage plus a bar, and the subscription state. */
@Composable
private fun UsageRows(account: Account) {
    val fraction = (account.remaining / account.creditLimit).coerceIn(0.0, 1.0)
    // Never round a non-empty balance down to a flat 0%: "0% left" when there
    // is still credit is worse than being one point optimistic (same rule as
    // the remote app).
    val percent = if (fraction > 0) maxOf(1, (fraction * 100).roundToInt()) else 0
    val barColor = if (fraction < 0.10) StatusBad else Ink.White

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Usage",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Ink.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$percent% left",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = barColor,
            )
        }
        Spacer(Modifier.height(10.dp))
        // Track + fill, flat and hairline-bordered like everything else here.
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(whiteA(0.10f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.toFloat())
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(barColor),
            )
        }
        val status = account.planStatus.trim().lowercase()
        if (status.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            val statusColor = when (status) {
                "active", "trialing" -> StatusGood
                "past_due", "incomplete", "paused" -> StatusWarn
                else -> StatusBad // canceled, unpaid, expired, …
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.width(7.dp))
                Text(
                    status.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                )
            }
        }
    }
}

/** One visual group of rows: a flat raised card with a hairline border. */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .surfaceCard(RoundedCornerShape(Radii.row), fill = Ink.I850),
    ) {
        content()
    }
}

@Composable
private fun SettingRow(title: String, description: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
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
        modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 8.dp),
    )
}

/** Divider between rows inside a card, inset to align with the text. */
@Composable
private fun CardDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(whiteA(0.06f)),
    )
}

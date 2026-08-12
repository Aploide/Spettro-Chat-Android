package to.eyed.spettro.chat.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import to.eyed.spettro.chat.data.api.ModelInfo
import to.eyed.spettro.chat.ui.components.GhostIconButton
import to.eyed.spettro.chat.ui.components.Hairline
import to.eyed.spettro.chat.ui.components.snappySpring
import to.eyed.spettro.chat.ui.components.surfaceCard
import to.eyed.spettro.chat.ui.components.surfaceHigh
import to.eyed.spettro.chat.ui.components.surfaceLow
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.vm.ThinkingLevel

/** Prettify a raw model id ("deepseek-v4-flash" -> "Deepseek V4 Flash"). */
fun modelDisplayName(id: String): String =
    id.split('-', '_', '/').joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

@Composable
fun TopNav(
    models: List<ModelInfo>,
    selectedModelId: String,
    onSelectModel: (String) -> Unit,
    thinking: ThinkingLevel,
    onSelectThinking: (ThinkingLevel) -> Unit,
    showThinking: Boolean,
    email: String,
    plan: String,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onManageSubscription: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var openMenu by remember { mutableStateOf<String?>(null) }
    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GhostIconButton(Icons.Rounded.Menu, "Open sidebar", onOpenDrawer, size = 40.dp, iconSize = 20.dp, tint = Ink.I100)

            // Model selector
            Box {
                val selected = models.firstOrNull { it.id == selectedModelId }
                val rotation by animateFloatAsState(if (openMenu == "model") 180f else 0f, snappySpring(), label = "chev")
                Row(
                    Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { openMenu = if (openMenu == "model") null else "model" }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        selected?.let { modelDisplayName(it.id) } ?: "No model",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Ink.I100,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 170.dp),
                    )
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        null,
                        Modifier.size(18.dp).rotate(rotation),
                        tint = Ink.I500,
                    )
                }
                GlassMenu(
                    visible = openMenu == "model",
                    onDismiss = { openMenu = null },
                    header = "Model",
                ) {
                    if (models.isEmpty()) {
                        Text(
                            "Your plan has no models enabled yet.",
                            fontSize = 12.sp,
                            color = Ink.I500,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    models.forEach { m ->
                        MenuRow(
                            title = modelDisplayName(m.id),
                            subtitle = buildString {
                                if (m.contextWindow > 0) append("${m.contextWindow / 1000}k context")
                                if (m.reasoning) append(" · reasoning")
                                if (m.vision) append(" · vision")
                            }.trim(' ', '·'),
                            selected = m.id == selectedModelId,
                            onClick = {
                                onSelectModel(m.id)
                                openMenu = null
                            },
                        )
                    }
                }
            }

            // Thinking selector (only for reasoning models)
            if (showThinking) {
                Box {
                    Row(
                        Modifier
                            .surfaceLow(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { openMenu = if (openMenu == "thinking") null else "thinking" }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ThinkingBars(thinking.bars)
                        Text(thinking.label, fontSize = 13.sp, color = Ink.I100, maxLines = 1)
                    }
                    GlassMenu(
                        visible = openMenu == "thinking",
                        onDismiss = { openMenu = null },
                        header = "Thinking level",
                    ) {
                        ThinkingLevel.entries.forEach { level ->
                            MenuRow(
                                title = level.label,
                                subtitle = level.description,
                                selected = level == thinking,
                                leading = { ThinkingBars(level.bars) },
                                onClick = {
                                    onSelectThinking(level)
                                    openMenu = null
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Avatar + user menu
            Box {
                Avatar(email = email, onClick = { openMenu = if (openMenu == "user") null else "user" })
                GlassMenu(
                    visible = openMenu == "user",
                    onDismiss = { openMenu = null },
                    header = null,
                    alignEnd = true,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            email.substringBefore("@").replaceFirstChar { it.uppercase() }.ifBlank { "Account" },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Ink.White,
                        )
                        Text(email, fontSize = 12.sp, color = Ink.I500)
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .clip(CircleShape)
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
                    Hairline(Modifier.padding(horizontal = 12.dp))
                    Spacer(Modifier.height(4.dp))
                    MenuActionRow(Icons.Rounded.Settings, "Settings") {
                        openMenu = null
                        onOpenSettings()
                    }
                    MenuActionRow(Icons.Rounded.CreditCard, "Manage subscription") {
                        openMenu = null
                        onManageSubscription()
                    }
                    Hairline(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    MenuActionRow(Icons.AutoMirrored.Rounded.Logout, "Sign out", dim = true) {
                        openMenu = null
                        onSignOut()
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/** The 3-bar signal meter (heights 7/10/13). */
@Composable
fun ThinkingBars(filled: Int) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .width(4.dp)
                    .height((7 + i * 3).dp)
                    .clip(CircleShape)
                    .background(if (i < filled) Ink.White else Ink.SurfaceHigh),
            )
        }
    }
}

@Composable
private fun Avatar(email: String, onClick: () -> Unit) {
    val initials = remember(email) {
        email.substringBefore("@")
            .split('.', '-', '_')
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercaseChar() }
            .joinToString("")
            .ifBlank { "SP" }
    }
    Box(
        Modifier
            .size(36.dp)
            .surfaceHigh(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.White)
    }
}

/** Anchored flat dropdown menu. */
@Composable
fun GlassMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    header: String?,
    alignEnd: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    Popup(
        alignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
        offset = IntOffset(0, 130),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .width(300.dp)
                .surfaceCard(RoundedCornerShape(Radii.card), fill = Ink.Surface)
                .padding(6.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (header != null) {
                Text(
                    header,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ink.I500,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            content()
        }
    }
}

@Composable
fun MenuRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.row))
            .background(if (selected) Ink.SurfaceHigh else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink.White)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 12.sp, color = Ink.I500, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = Ink.White)
        }
    }
}

@Composable
fun MenuActionRow(
    icon: ImageVector,
    label: String,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.row))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = if (dim) Ink.I500 else Ink.I100)
        Text(label, fontSize = 14.sp, color = if (dim) Ink.I500 else Ink.I100)
    }
}

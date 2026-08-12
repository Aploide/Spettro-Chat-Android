package to.eyed.spettro.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import to.eyed.spettro.chat.data.api.ModelInfo
import to.eyed.spettro.chat.ui.components.GhostIconButton
import to.eyed.spettro.chat.ui.components.Hairline
import to.eyed.spettro.chat.ui.components.glass
import to.eyed.spettro.chat.ui.components.glassOverlay
import to.eyed.spettro.chat.ui.components.snappySpring
import to.eyed.spettro.chat.ui.theme.EyebrowMono
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.ui.theme.whiteA
import to.eyed.spettro.chat.vm.ThinkingLevel

/** Prettify a raw model id ("deepseek-v4-flash" -> "Deepseek V4 Flash"). */
fun modelDisplayName(id: String): String =
    id.split('-', '_', '/').joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

fun modelTag(m: ModelInfo): String =
    m.ownedBy.ifBlank { m.id.substringBefore('-') }.uppercase().take(8)

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
    Column(modifier.background(Ink.Pitch.copy(alpha = 0.94f))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GhostIconButton(Icons.Rounded.Menu, "Open sidebar", onOpenDrawer)

            // Model selector
            Box {
                val selected = models.firstOrNull { it.id == selectedModelId }
                ModelChip(
                    label = selected?.let { modelDisplayName(it.id) } ?: "No model",
                    tag = selected?.let { modelTag(it) },
                    open = openMenu == "model",
                    onClick = { openMenu = if (openMenu == "model") null else "model" },
                )
                GlassMenu(
                    visible = openMenu == "model",
                    onDismiss = { openMenu = null },
                    header = "MODEL",
                ) {
                    if (models.isEmpty()) {
                        Text(
                            "Your plan has no models enabled yet.",
                            fontSize = 11.sp,
                            color = Ink.I500,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    models.forEach { m ->
                        MenuRow(
                            title = modelDisplayName(m.id),
                            subtitle = buildString {
                                append(m.ownedBy.ifBlank { "spettro" })
                                if (m.contextWindow > 0) append(" · ${m.contextWindow / 1000}k context")
                                if (m.reasoning) append(" · reasoning")
                                if (m.vision) append(" · vision")
                            },
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
                    ThinkingChip(
                        level = thinking,
                        open = openMenu == "thinking",
                        onClick = { openMenu = if (openMenu == "thinking") null else "thinking" },
                    )
                    GlassMenu(
                        visible = openMenu == "thinking",
                        onDismiss = { openMenu = null },
                        header = "THINKING LEVEL",
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
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            email.substringBefore("@").replaceFirstChar { it.uppercase() }.ifBlank { "Account" },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Ink.White,
                        )
                        Text(email, fontSize = 11.sp, color = Ink.I500)
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(whiteA(0.10f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                "${plan.ifBlank { "free" }.uppercase()} PLAN",
                                style = EyebrowMono,
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
        Hairline()
    }
}

@Composable
private fun ModelChip(label: String, tag: String?, open: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (open) 180f else 0f, snappySpring(), label = "chev")
    Row(
        Modifier
            .glass(RoundedCornerShape(Radii.control))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Ink.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
        if (tag != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(whiteA(0.10f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(tag, style = EyebrowMono, color = Ink.I300)
            }
        }
        Icon(
            Icons.Rounded.KeyboardArrowDown,
            null,
            Modifier.size(13.dp).rotate(rotation),
            tint = Ink.I500,
        )
    }
}

@Composable
private fun ThinkingChip(level: ThinkingLevel, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .glass(RoundedCornerShape(Radii.control))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ThinkingBars(level.bars)
        Text(level.label, fontSize = 13.sp, color = Ink.I100, maxLines = 1)
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
                    .background(if (i < filled) Ink.White else whiteA(0.15f)),
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
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(whiteA(0.25f), whiteA(0.05f))))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.White)
    }
}

/** Anchored glass dropdown. */
@Composable
fun GlassMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    header: String?,
    alignEnd: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (!visible) return
    Popup(
        alignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(0, 130),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.97f),
        ) {
            Column(
                Modifier
                    .width(288.dp)
                    .glassOverlay(RoundedCornerShape(Radii.row), refraction = true)
                    .padding(6.dp)
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = 288.dp),
            ) {
                if (header != null) {
                    Text(
                        header,
                        style = EyebrowMono,
                        color = Ink.I500,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                content()
            }
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
            .clip(RoundedCornerShape(Radii.control))
            .background(if (selected) whiteA(0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink.White)
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.sp, color = Ink.I500, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = Ink.White)
        }
    }
}

@Composable
fun MenuActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.control))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (dim) Ink.I500 else Ink.I100)
        Text(label, fontSize = 13.sp, color = if (dim) Ink.I500 else Ink.I100)
    }
}

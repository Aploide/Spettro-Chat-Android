package to.eyed.spettro.chat.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.data.api.ModelInfo
import to.eyed.spettro.chat.ui.components.GhostIconButton
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.vm.ThinkingLevel

// Curated tiers, Claude-style: four highlighted models, everything else
// behind "Other models". Matching is by normalized model id.
private data class Tier(val subtitle: String, val matches: (String) -> Boolean)

private val TIERS = listOf(
    Tier("Fastest, for quick replies") { it.contains("superfast") },
    Tier("Great for everyday chats") { it.contains("minimax") && it.contains("m3") },
    Tier("Balanced power for most work") { it.contains("supersmart") },
    Tier("For your hardest problems") { it.contains("kimi") && it.contains("k3") },
)

private fun norm(id: String) = id.lowercase()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    models: List<ModelInfo>,
    selectedModelId: String,
    onSelectModel: (String) -> Unit,
    thinking: ThinkingLevel,
    onSelectThinking: (ThinkingLevel) -> Unit,
    chatHasImages: Boolean,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf("main") }
    val selected = models.firstOrNull { it.id == selectedModelId }

    // Resolve each tier to the first matching model; keep list order stable.
    val tierModels = TIERS.mapNotNull { tier ->
        models.firstOrNull { tier.matches(norm(it.id)) }?.let { tier to it }
    }
    val curatedIds = tierModels.map { it.second.id }.toSet()
    val otherModels = models.filter { it.id !in curatedIds }
    val showTiers = tierModels.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ink.I850,
        contentColor = Ink.I100,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                if (targetState == "main") {
                    (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 4 } + fadeOut())
                } else {
                    (slideInHorizontally { it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 4 } + fadeOut())
                }
            },
            label = "sheetPage",
        ) { current ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (current) {
                    "main" -> {
                        SheetHeader(
                            title = "Select model",
                            leadingIcon = Icons.Rounded.Close,
                            onLeading = onDismiss,
                        )
                        if (showTiers) {
                            tierModels.forEach { (tier, model) ->
                                ModelCard(
                                    title = modelDisplayName(model.id),
                                    subtitle = tier.subtitle,
                                    selected = model.id == selectedModelId,
                                    blocked = chatHasImages && !model.vision,
                                    onClick = { onSelectModel(model.id) },
                                )
                                Spacer(Modifier.height(2.dp))
                            }
                        } else {
                            // No curated matches on this plan: list everything.
                            models.forEach { model ->
                                ModelCard(
                                    title = modelDisplayName(model.id),
                                    subtitle = null,
                                    selected = model.id == selectedModelId,
                                    blocked = chatHasImages && !model.vision,
                                    onClick = { onSelectModel(model.id) },
                                )
                                Spacer(Modifier.height(2.dp))
                            }
                        }
                        if (selected?.reasoning == true) {
                            Spacer(Modifier.height(10.dp))
                            NavCard(
                                icon = Icons.Rounded.Speed,
                                title = "Effort",
                                value = thinking.label,
                                onClick = { page = "effort" },
                            )
                        }
                        if (showTiers && otherModels.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            NavCard(
                                icon = Icons.Rounded.MoreHoriz,
                                title = "Other models",
                                value = null,
                                onClick = { page = "models" },
                            )
                        }
                        Spacer(Modifier.height(28.dp))
                    }

                    "models" -> {
                        SheetHeader(
                            title = "Other models",
                            leadingIcon = Icons.AutoMirrored.Rounded.ArrowBack,
                            onLeading = { page = "main" },
                        )
                        otherModels.forEach { model ->
                            ModelCard(
                                title = modelDisplayName(model.id),
                                subtitle = null,
                                selected = model.id == selectedModelId,
                                blocked = chatHasImages && !model.vision,
                                onClick = { onSelectModel(model.id) },
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        Spacer(Modifier.height(28.dp))
                    }

                    "effort" -> {
                        SheetHeader(
                            title = "Effort",
                            leadingIcon = Icons.AutoMirrored.Rounded.ArrowBack,
                            onLeading = { page = "main" },
                        )
                        ThinkingLevel.entries.forEach { level ->
                            EffortCard(
                                level = level,
                                selected = level == thinking,
                                onClick = {
                                    onSelectThinking(level)
                                    page = "main"
                                },
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        Spacer(Modifier.height(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(
    title: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onLeading: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        GhostIconButton(
            leadingIcon,
            title,
            onClick = onLeading,
            size = 36.dp,
            iconSize = 18.dp,
            tint = Ink.I100,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Text(
            title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink.White,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun ModelCard(
    title: String,
    subtitle: String?,
    selected: Boolean,
    blocked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.Surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !blocked,
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .alpha(if (blocked) 0.4f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Ink.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = if (blocked) "Unavailable — this chat contains images" else subtitle
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, fontSize = 13.sp, color = Ink.I500, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = Ink.White)
        }
    }
}

@Composable
private fun EffortCard(level: ThinkingLevel, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.Surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(level.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink.White)
                if (level.isDefault) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(Ink.SurfaceHigh)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text("Default", fontSize = 11.sp, color = Ink.I300)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(level.description, fontSize = 13.sp, color = Ink.I500)
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = Ink.White)
        }
    }
}

@Composable
private fun NavCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.Surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Ink.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(17.dp), tint = Ink.I100)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink.White)
            if (value != null) {
                Text(value, fontSize = 13.sp, color = Ink.I500)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(20.dp), tint = Ink.I500)
    }
}

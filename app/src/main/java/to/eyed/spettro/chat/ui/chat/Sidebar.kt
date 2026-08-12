package to.eyed.spettro.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Unarchive
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.data.store.Conversation
import to.eyed.spettro.chat.ui.components.GhostIconButton
import to.eyed.spettro.chat.ui.components.Hairline
import to.eyed.spettro.chat.ui.components.glass
import to.eyed.spettro.chat.ui.components.whiteGlow
import to.eyed.spettro.chat.ui.theme.EyebrowMono
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.ui.theme.whiteA
import java.util.Calendar

private fun groupLabel(updatedAt: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = updatedAt }
    val startOfToday = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return when {
        !then.before(startOfToday) -> "Today"
        !then.before((startOfToday.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }) -> "Yesterday"
        !then.before((startOfToday.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }) -> "Previous 7 Days"
        else -> "Older"
    }
}

@Composable
fun Sidebar(
    conversations: List<Conversation>,
    activeId: String?,
    email: String,
    plan: String,
    onSelect: (String) -> Unit,
    onNewChat: () -> Unit,
    onTogglePin: (String) -> Unit,
    onArchive: (String) -> Unit,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var archivedOpen by remember { mutableStateOf(false) }

    val visible = conversations.filter { !it.archived }
        .filter { query.isBlank() || it.displayTitle.contains(query, ignoreCase = true) || it.preview.contains(query, ignoreCase = true) }
    val archived = conversations.filter { it.archived }
    val pinned = visible.filter { it.pinned }
    val groups = visible.filter { !it.pinned }.groupBy { groupLabel(it.updatedAt) }
    val groupOrder = listOf("Today", "Yesterday", "Previous 7 Days", "Older")

    Column(
        modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(Ink.I900.copy(alpha = 0.98f)),
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).glass(RoundedCornerShape(Radii.control)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(14.dp), tint = Ink.White)
            }
            Spacer(Modifier.width(10.dp))
            Text("Spettro", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink.White)
            Spacer(Modifier.weight(1f))
            GhostIconButton(Icons.Rounded.Close, "Collapse sidebar", onCollapse)
        }

        // New chat
        Row(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(Radii.control))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onNewChat,
                )
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, null, Modifier.size(15.dp), tint = Ink.White)
            Spacer(Modifier.width(6.dp))
            Text("New Chat", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink.White)
        }
        Spacer(Modifier.height(10.dp))

        // Search
        Row(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.control))
                .background(whiteA(0.04f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, Modifier.size(13.dp), tint = Ink.I500)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                textStyle = TextStyle(color = Ink.White, fontSize = 13.sp),
                cursorBrush = SolidColor(Ink.White),
                singleLine = true,
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("Search chats…", color = Ink.I500, fontSize = 13.sp)
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Rounded.Close,
                    "Clear search",
                    Modifier
                        .size(14.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { query = "" },
                    tint = Ink.I500,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // Thread list
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (visible.isEmpty()) {
                item {
                    Text(
                        if (query.isBlank()) "No chats yet. Start a new one." else "No chats match “$query”",
                        fontSize = 12.sp,
                        color = Ink.I500,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            }
            if (pinned.isNotEmpty()) {
                item { GroupHeader("Pinned", pin = true) }
                items(pinned.size, key = { "p-${pinned[it].id}" }) { i ->
                    ThreadRow(pinned[i], pinned[i].id == activeId, onSelect, onTogglePin, onArchive)
                }
            }
            for (label in groupOrder) {
                val list = groups[label] ?: continue
                item { GroupHeader(label) }
                items(list.size, key = { "g-${list[it].id}" }) { i ->
                    ThreadRow(list[i], list[i].id == activeId, onSelect, onTogglePin, onArchive)
                }
            }
        }

        // Footer: archived + account
        Hairline()
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.control))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { archivedOpen = !archivedOpen }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Inventory2, null, Modifier.size(14.dp), tint = Ink.I500)
                Spacer(Modifier.width(8.dp))
                Text("Archived Chats", fontSize = 13.sp, color = Ink.I500)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.clip(CircleShape).background(whiteA(0.10f)).padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("${archived.size}", style = EyebrowMono, color = Ink.I300)
                }
            }
            AnimatedVisibility(
                archivedOpen && archived.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radii.control))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(6.dp),
                ) {
                    archived.forEach { conv ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                conv.displayTitle,
                                fontSize = 12.sp,
                                color = Ink.I300,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            GhostIconButton(
                                Icons.Rounded.Unarchive, "Restore chat",
                                onClick = { onRestore(conv.id) }, size = 26.dp, iconSize = 12.dp,
                            )
                            GhostIconButton(
                                Icons.Rounded.DeleteOutline, "Delete chat",
                                onClick = { onDelete(conv.id) }, size = 26.dp, iconSize = 12.dp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // Account row
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.control))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenSettings,
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(whiteA(0.25f), whiteA(0.05f)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        email.take(2).uppercase().ifBlank { "SP" },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.White,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        email.ifBlank { "Signed in" },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Ink.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${plan.ifBlank { "free" }.uppercase()} PLAN",
                        style = EyebrowMono,
                        color = Ink.I500,
                    )
                }
                Icon(Icons.Rounded.Settings, null, Modifier.size(14.dp), tint = Ink.I500)
            }
        }
    }
}

@Composable
private fun GroupHeader(label: String, pin: Boolean = false) {
    Row(
        Modifier.padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pin) {
            Icon(Icons.Rounded.PushPin, null, Modifier.size(9.dp), tint = Ink.I500)
            Spacer(Modifier.width(4.dp))
        }
        Text(label.uppercase(), style = EyebrowMono, color = Ink.I500)
    }
}

@Composable
private fun ThreadRow(
    conv: Conversation,
    active: Boolean,
    onSelect: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onArchive: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(Radii.control))
            .background(if (active) whiteA(0.10f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onSelect(conv.id) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(20.dp)
                    .whiteGlow(CircleShape)
                    .clip(CircleShape)
                    .background(Ink.White),
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            Icons.AutoMirrored.Rounded.Chat,
            null,
            Modifier.size(14.dp),
            tint = if (active) Ink.White else Ink.I500,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                conv.displayTitle,
                fontSize = 13.sp,
                color = if (active) Ink.White else Ink.I100,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val previewText = conv.messages.lastOrNull()?.content ?: conv.preview
            if (previewText.isNotBlank()) {
                Text(
                    previewText.replace('\n', ' '),
                    fontSize = 11.sp,
                    color = Ink.I500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        GhostIconButton(
            Icons.Rounded.PushPin,
            if (conv.pinned) "Unpin" else "Pin",
            onClick = { onTogglePin(conv.id) },
            size = 26.dp,
            iconSize = 12.dp,
            tint = if (conv.pinned) Ink.White else Ink.I700,
        )
        GhostIconButton(
            Icons.Rounded.Inventory2,
            "Archive chat",
            onClick = { onArchive(conv.id) },
            size = 26.dp,
            iconSize = 12.dp,
            tint = Ink.I700,
        )
    }
}

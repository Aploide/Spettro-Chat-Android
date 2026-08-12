package to.eyed.spettro.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.Icon
import com.composables.icons.lucide.Archive
import com.composables.icons.lucide.ArchiveRestore
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.Trash2
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
import to.eyed.spettro.chat.ui.components.surfaceHigh
import to.eyed.spettro.chat.ui.components.surfaceLow
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
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
        !then.before((startOfToday.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }) -> "Previous 7 days"
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
    val groupOrder = listOf("Today", "Yesterday", "Previous 7 days", "Older")

    Column(
        modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(Ink.I900),
    ) {
        // Header: wordmark
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Sparkles, null, Modifier.size(18.dp), tint = Ink.White)
            Spacer(Modifier.width(10.dp))
            Text("Spettro", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink.White)
            Spacer(Modifier.weight(1f))
            GhostIconButton(Lucide.X, "Close sidebar", onCollapse, size = 40.dp, iconSize = 18.dp)
        }

        // Search
        Row(
            Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .fillMaxWidth()
                .surfaceLow(CircleShape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Search, null, Modifier.size(16.dp), tint = Ink.I500)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                textStyle = TextStyle(color = Ink.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Ink.White),
                singleLine = true,
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("Search chats", color = Ink.I500, fontSize = 14.sp)
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            if (query.isNotEmpty()) {
                Icon(
                    Lucide.X,
                    "Clear search",
                    Modifier
                        .size(16.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { query = "" },
                    tint = Ink.I500,
                )
            }
        }

        // New chat
        Row(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.row))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onNewChat,
                )
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.SquarePen, null, Modifier.size(19.dp), tint = Ink.I100)
            Spacer(Modifier.width(12.dp))
            Text("New chat", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink.I100)
        }

        // Thread list
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (visible.isEmpty()) {
                item {
                    Text(
                        if (query.isBlank()) "No chats yet. Start a new one." else "No chats match “$query”",
                        fontSize = 13.sp,
                        color = Ink.I500,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp),
                    )
                }
            }
            if (pinned.isNotEmpty()) {
                item { GroupHeader("Pinned") }
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
            // Archived
            if (archived.isNotEmpty()) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(Radii.row))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { archivedOpen = !archivedOpen }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Lucide.Archive, null, Modifier.size(16.dp), tint = Ink.I500)
                        Spacer(Modifier.width(12.dp))
                        Text("Archived", fontSize = 15.sp, color = Ink.I300)
                        Spacer(Modifier.weight(1f))
                        Text("${archived.size}", fontSize = 12.sp, color = Ink.I500)
                    }
                }
                if (archivedOpen) {
                    items(archived.size, key = { "a-${archived[it].id}" }) { i ->
                        val conv = archived[i]
                        Row(
                            Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                conv.displayTitle,
                                fontSize = 14.sp,
                                color = Ink.I500,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            GhostIconButton(
                                Lucide.ArchiveRestore, "Restore chat",
                                onClick = { onRestore(conv.id) }, size = 32.dp, iconSize = 14.dp,
                            )
                            GhostIconButton(
                                Lucide.Trash2, "Delete chat",
                                onClick = { onDelete(conv.id) }, size = 32.dp, iconSize = 14.dp,
                            )
                        }
                    }
                }
            }
        }

        // Account footer
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenSettings,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).surfaceHigh(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    email.take(2).uppercase().ifBlank { "SP" },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink.White,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    email.ifBlank { "Signed in" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ink.I100,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${plan.ifBlank { "free" }.replaceFirstChar { it.uppercase() }} plan",
                    fontSize = 12.sp,
                    color = Ink.I500,
                )
            }
            Icon(Lucide.Settings, null, Modifier.size(18.dp), tint = Ink.I500)
        }
    }
}

@Composable
private fun GroupHeader(label: String) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Ink.I500,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(
    conv: Conversation,
    active: Boolean,
    onSelect: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onArchive: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
                .clip(RoundedCornerShape(Radii.row))
                .background(if (active) Ink.Surface else Color.Transparent)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onSelect(conv.id) },
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (conv.pinned) {
                Icon(Lucide.Pin, null, Modifier.size(13.dp), tint = Ink.I500)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                conv.displayTitle,
                fontSize = 15.sp,
                color = if (active) Ink.White else Ink.I100,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        GlassMenu(visible = menuOpen, onDismiss = { menuOpen = false }, header = null) {
            MenuActionRow(Lucide.Pin, if (conv.pinned) "Unpin" else "Pin") {
                menuOpen = false
                onTogglePin(conv.id)
            }
            MenuActionRow(Lucide.Archive, "Archive") {
                menuOpen = false
                onArchive(conv.id)
            }
        }
    }
}

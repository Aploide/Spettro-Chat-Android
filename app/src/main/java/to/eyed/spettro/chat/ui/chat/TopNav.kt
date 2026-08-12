package to.eyed.spettro.chat.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.composables.icons.lucide.Ghost
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PanelLeft
import to.eyed.spettro.chat.ui.components.GhostIconButton
import to.eyed.spettro.chat.ui.components.surfaceCard
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii

/** Prettify a raw model id ("deepseek-v4-flash" -> "Deepseek V4 Flash"). */
fun modelDisplayName(id: String): String =
    id.split('-', '_', '/').joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

/**
 * Minimal top bar: sidebar toggle on the left, the temporary-chat toggle on
 * the right. Account access lives in the sidebar footer.
 */
@Composable
fun TopNav(
    isTemporary: Boolean,
    onOpenDrawer: () -> Unit,
    onToggleTemporary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            GhostIconButton(
                Lucide.PanelLeft,
                "Open sidebar",
                onOpenDrawer,
                size = 40.dp,
                iconSize = 20.dp,
                tint = Ink.I100,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            if (isTemporary) {
                Text(
                    "Temporary chat",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ink.I300,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            // Temporary chat: nothing said here is remembered.
            val bg by animateColorAsState(
                if (isTemporary) Ink.White else androidx.compose.ui.graphics.Color.Transparent,
                tween(200),
                label = "tempBg",
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleTemporary,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Ghost,
                    contentDescription = if (isTemporary) "End temporary chat" else "Start temporary chat",
                    Modifier.size(20.dp),
                    tint = if (isTemporary) Ink.Pitch else Ink.I100,
                )
            }
        }
    }
}

/** Anchored flat dropdown menu (used by the sidebar). */
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

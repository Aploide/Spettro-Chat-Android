package to.eyed.spettro.chat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleQuestion
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Wrench
import to.eyed.spettro.chat.data.tools.ToolRegistry
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.vm.ToolRunUi

/**
 * One row per tool call: icon, quiet label, pulsing dots while running.
 * A finished row is tappable and opens the full response in a sheet.
 */
@Composable
internal fun ToolActivityList(
    tools: List<ToolRunUi>,
    animations: Boolean,
    onInspect: (ToolRunUi) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tools.forEach { run ->
            val inspectable = !run.running && run.output.isNotBlank()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = inspectable,
                ) { onInspect(run) },
            ) {
                Icon(
                    toolIcon(run),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (run.failed) Ink.I300 else Ink.I500,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    run.label,
                    fontSize = 13.sp,
                    color = if (run.running) Ink.I300 else Ink.I500,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (run.running) {
                    Spacer(Modifier.width(8.dp))
                    PulsingDots(animations)
                }
                if (inspectable) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Lucide.ChevronRight, null, Modifier.size(13.dp), tint = Ink.I500)
                }
            }
        }
    }
}

internal fun toolIcon(run: ToolRunUi) = when {
    run.failed -> Lucide.CircleAlert
    run.name == ToolRegistry.WEB_SEARCH -> Lucide.Globe
    run.name == ToolRegistry.WEB_FETCH -> Lucide.Link
    run.name == ToolRegistry.CURRENT_TIME -> Lucide.Clock
    run.name == ToolRegistry.DEVICE_INFO -> Lucide.Smartphone
    run.name == ToolRegistry.ASK_USER -> Lucide.MessageCircleQuestion
    run.name == ToolRegistry.COMMENT -> Lucide.Info
    else -> Lucide.Wrench
}

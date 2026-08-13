package to.eyed.spettro.chat.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import to.eyed.spettro.chat.ui.components.GhostIconButton
import to.eyed.spettro.chat.ui.components.Hairline
import to.eyed.spettro.chat.ui.components.copyToClipboard
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.MonoBody
import to.eyed.spettro.chat.vm.ToolRunUi

/**
 * Full tool-response transparency: the raw text the tool returned to the
 * model, shown in a bottom sheet so the chat itself stays uncluttered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailSheet(run: ToolRunUi, onDismiss: () -> Unit) {
    val scroll = rememberScrollState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ink.I850,
        contentColor = Ink.I100,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(toolIcon(run), null, Modifier.size(18.dp), tint = Ink.I300)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Tool response",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.White,
                    )
                    Text(
                        run.label,
                        fontSize = 12.sp,
                        color = Ink.I500,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val context = LocalContext.current
                GhostIconButton(
                    Lucide.Copy,
                    "Copy response",
                    onClick = { copyToClipboard(context, run.output, "Tool response") },
                    size = 34.dp,
                    iconSize = 15.dp,
                )
            }
            Spacer(Modifier.height(14.dp))
            Hairline()
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(scroll)
                    .padding(top = 14.dp),
            ) {
                SelectionContainer {
                    Text(
                        run.output.ifBlank { "(empty response)" },
                        style = MonoBody,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = Ink.I100,
                    )
                }
            }
        }
    }
}

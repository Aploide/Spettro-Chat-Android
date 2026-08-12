package to.eyed.spettro.chat.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.data.api.ModelInfo
import to.eyed.spettro.chat.ui.components.Hairline
import to.eyed.spettro.chat.ui.components.SegmentedPill
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.vm.ThinkingLevel

/**
 * Bottom sheet for picking the model, with the thinking level tucked in
 * below it for reasoning-capable models.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    models: List<ModelInfo>,
    selectedModelId: String,
    onSelectModel: (String) -> Unit,
    thinking: ThinkingLevel,
    onSelectThinking: (ThinkingLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = models.firstOrNull { it.id == selectedModelId }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ink.Surface,
        contentColor = Ink.I100,
        shape = RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet),
    ) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Text(
                "Model",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Ink.I500,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (models.isEmpty()) {
                Text(
                    "Your plan has no models enabled yet.",
                    fontSize = 13.sp,
                    color = Ink.I500,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
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
                    onClick = { onSelectModel(m.id) },
                )
            }

            if (selected?.reasoning == true) {
                Spacer(Modifier.height(12.dp))
                Hairline(Modifier.padding(horizontal = 12.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Thinking",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ink.I500,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                SegmentedPill(
                    options = ThinkingLevel.entries.map { it.label },
                    selected = ThinkingLevel.entries.indexOf(thinking),
                    onSelect = { onSelectThinking(ThinkingLevel.entries[it]) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    thinking.description,
                    fontSize = 12.sp,
                    color = Ink.I500,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

package to.eyed.spettro.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import to.eyed.spettro.chat.data.skills.Skill
import to.eyed.spettro.chat.ui.components.Hairline
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii

/** Bottom sheet picking the active skill for the current conversation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillPickerSheet(
    skills: List<Skill>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text("Skills", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink.White)
            }
            Hairline()
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                SkillRow(
                    emoji = "💬",
                    name = "No skill",
                    description = "Plain Spettro, no extra instructions.",
                    selected = selectedId == null,
                    onClick = {
                        onSelect(null)
                        onDismiss()
                    },
                )
                skills.forEach { skill ->
                    SkillRow(
                        emoji = skill.emoji,
                        name = skill.name,
                        description = skill.description.ifBlank { "Custom skill /${skill.slug}" },
                        selected = selectedId == skill.id,
                        onClick = {
                            onSelect(skill.id)
                            onDismiss()
                        },
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SkillRow(
    emoji: String,
    name: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.row))
            .background(if (selected) Ink.SurfaceHigh else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 18.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink.White)
            Spacer(Modifier.height(1.dp))
            Text(description, fontSize = 11.sp, lineHeight = 15.sp, color = Ink.I500, maxLines = 2)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Lucide.Check, null, Modifier.width(16.dp), tint = Ink.White)
        }
    }
}

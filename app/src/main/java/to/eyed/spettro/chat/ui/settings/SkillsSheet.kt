package to.eyed.spettro.chat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.data.skills.Skill
import to.eyed.spettro.chat.ui.components.GlassButton
import to.eyed.spettro.chat.ui.components.Hairline
import to.eyed.spettro.chat.ui.components.PrimaryButton
import to.eyed.spettro.chat.ui.components.surfaceCard
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii

/**
 * Manage skills: bundled ones are read-only (duplicate to customize), user
 * skills are editable. A skill is a name, /slug, description, emoji, and the
 * markdown instructions that join the system prompt when active.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsSheet(
    skills: List<Skill>,
    /** null on success, otherwise the validation error to show in the form. */
    saveError: String?,
    onSave: (Skill) -> Unit,
    onClearSaveError: () -> Unit,
    onDelete: (String) -> Unit,
    newId: () -> String,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<Skill?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ink.SurfaceLow,
        contentColor = Ink.I100,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (editing == null) "Skills" else "Edit skill",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink.White,
                    modifier = Modifier.weight(1f),
                )
                if (editing == null) {
                    GlassButton("New skill", onClick = {
                        onClearSaveError()
                        editing = Skill(id = newId(), name = "", slug = "", description = "", instructions = "")
                    })
                }
            }
            Hairline()

            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                val current = editing
                if (current != null) {
                    SkillForm(
                        initial = current,
                        saveError = saveError,
                        onCancel = {
                            onClearSaveError()
                            editing = null
                        },
                        onSave = { onSave(it) },
                        onSaved = { editing = null },
                    )
                } else {
                    skills.forEachIndexed { i, skill ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        SkillCard(
                            skill = skill,
                            onEdit = {
                                onClearSaveError()
                                editing = skill
                            },
                            onDuplicate = {
                                onClearSaveError()
                                editing = skill.copy(
                                    id = newId(),
                                    name = "${skill.name} (copy)",
                                    slug = "${skill.slug}-copy",
                                    builtin = false,
                                )
                            },
                            onDelete = { onDelete(skill.id) },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .surfaceCard(RoundedCornerShape(Radii.card), fill = Ink.Surface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(skill.emoji, fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(skill.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink.White)
                Text("/${skill.slug}" + if (skill.builtin) " · built-in" else "", fontSize = 11.sp, color = Ink.I500)
            }
        }
        if (skill.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(skill.description, fontSize = 11.sp, lineHeight = 15.sp, color = Ink.I500)
        }
        Spacer(Modifier.height(10.dp))
        Row {
            if (skill.builtin) {
                GlassButton("Duplicate", onClick = onDuplicate)
            } else {
                GlassButton("Edit", onClick = onEdit)
                Spacer(Modifier.width(8.dp))
                var confirming by remember { mutableStateOf(false) }
                GlassButton(
                    if (confirming) "Confirm" else "Delete",
                    onClick = { if (confirming) onDelete() else confirming = true },
                    textColor = Ink.Danger,
                )
            }
        }
    }
}

@Composable
private fun SkillForm(
    initial: Skill,
    saveError: String?,
    onCancel: () -> Unit,
    onSave: (Skill) -> Unit,
    onSaved: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var slug by remember { mutableStateOf(initial.slug) }
    var emoji by remember { mutableStateOf(initial.emoji) }
    var description by remember { mutableStateOf(initial.description) }
    var instructions by remember { mutableStateOf(initial.instructions) }
    var submitted by remember { mutableStateOf(false) }

    // The save round-trips through the repository's validation; close the
    // form only once a submit produced no error.
    if (submitted && saveError == null) {
        submitted = false
        onSaved()
    }

    Column(Modifier.fillMaxWidth()) {
        SkillField("Name", name, { name = it }, placeholder = "Recipe coach")
        SkillField("Slug (the /command)", slug, { slug = it }, placeholder = "recipe-coach")
        SkillField("Emoji", emoji, { emoji = it }, placeholder = "🍳")
        SkillField("Description", description, { description = it }, placeholder = "One line shown in the picker.")
        SkillField(
            "Instructions",
            instructions,
            { instructions = it },
            placeholder = "You are acting as…",
            singleLine = false,
        )
        if (saveError != null) {
            Spacer(Modifier.height(8.dp))
            Text(saveError, fontSize = 12.sp, color = Ink.Danger)
        }
        Spacer(Modifier.height(14.dp))
        Row {
            PrimaryButton(
                text = "Save",
                enabled = name.isNotBlank() && slug.isNotBlank() && instructions.isNotBlank(),
                onClick = {
                    submitted = true
                    onSave(
                        initial.copy(
                            name = name,
                            slug = slug,
                            emoji = emoji.ifBlank { "✨" },
                            description = description,
                            instructions = instructions,
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            GlassButton("Cancel", onClick = onCancel, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

@Composable
private fun SkillField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    singleLine: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Ink.I500)
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Ink.SurfaceHigh, RoundedCornerShape(Radii.control))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = TextStyle(color = Ink.I100, fontSize = 13.sp, lineHeight = 18.sp),
                cursorBrush = SolidColor(Ink.White),
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, fontSize = 13.sp, color = Ink.I500)
                    }
                    inner()
                },
                modifier = if (singleLine) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().heightIn(min = 96.dp),
            )
        }
    }
}

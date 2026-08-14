package to.eyed.spettro.chat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import to.eyed.spettro.chat.data.memory.MemoryFact
import to.eyed.spettro.chat.ui.components.GlassButton
import to.eyed.spettro.chat.ui.components.Hairline
import to.eyed.spettro.chat.ui.components.PrimaryButton
import to.eyed.spettro.chat.ui.components.surfaceCard
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The memory editor: everything the assistant has remembered, in the open.
 * Users add, rewrite, and delete facts here; the model does the same through
 * save-memory / forget-memory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySheet(
    memories: List<MemoryFact>,
    onAdd: (String) -> Unit,
    onUpdate: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Memory",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink.White,
                    modifier = Modifier.weight(1f),
                )
                if (memories.isNotEmpty()) {
                    var confirming by remember { mutableStateOf(false) }
                    GlassButton(
                        if (confirming) "Confirm" else "Forget all",
                        onClick = { if (confirming) onClearAll() else confirming = true },
                        textColor = Ink.Danger,
                    )
                }
            }
            Hairline()

            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(
                    "Spettro remembers these facts in every chat. It saves and removes them " +
                        "itself as you talk; you can edit them here any time.",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Ink.I500,
                )
                Spacer(Modifier.height(12.dp))

                var newFact by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MemoryField(
                        value = newFact,
                        onValueChange = { newFact = it },
                        placeholder = "Add something to remember…",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    GlassButton("Add", onClick = {
                        if (newFact.isNotBlank()) {
                            onAdd(newFact)
                            newFact = ""
                        }
                    })
                }
                Spacer(Modifier.height(14.dp))

                if (memories.isEmpty()) {
                    Text(
                        "Nothing remembered yet. Try telling Spettro something about yourself.",
                        fontSize = 13.sp,
                        color = Ink.I500,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                memories.forEachIndexed { i, fact ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    MemoryCard(
                        fact = fact,
                        onUpdate = { onUpdate(fact.id, it) },
                        onDelete = { onDelete(fact.id) },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MemoryCard(
    fact: MemoryFact,
    onUpdate: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var editing by remember(fact.id) { mutableStateOf(false) }
    var draft by remember(fact.id) { mutableStateOf(fact.text) }

    Column(
        Modifier
            .fillMaxWidth()
            .surfaceCard(RoundedCornerShape(Radii.card), fill = Ink.Surface)
            .padding(14.dp),
    ) {
        if (editing) {
            MemoryField(value = draft, onValueChange = { draft = it }, placeholder = "")
            Spacer(Modifier.height(10.dp))
            Row {
                PrimaryButton(
                    text = "Save",
                    enabled = draft.isNotBlank(),
                    onClick = {
                        onUpdate(draft)
                        editing = false
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                GlassButton(
                    "Cancel",
                    onClick = {
                        draft = fact.text
                        editing = false
                    },
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        } else {
            Text(fact.text, fontSize = 13.sp, lineHeight = 18.sp, color = Ink.I100)
            Spacer(Modifier.height(6.dp))
            val dates = remember(fact) {
                val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                val added = fmt.format(Date(fact.addedAt))
                val used = fmt.format(Date(fact.usedAt))
                if (added == used) "Added $added" else "Added $added · last used $used"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dates, fontSize = 10.sp, color = Ink.I500, modifier = Modifier.weight(1f))
                GlassButton("Edit", onClick = { editing = true })
                Spacer(Modifier.width(8.dp))
                var confirming by remember { mutableStateOf(false) }
                GlassButton(
                    if (confirming) "Confirm" else "Forget",
                    onClick = { if (confirming) onDelete() else confirming = true },
                    textColor = Ink.Danger,
                )
            }
        }
    }
}

@Composable
private fun MemoryField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(Ink.SurfaceHigh, RoundedCornerShape(Radii.control))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.replace("\n", " ")) },
            singleLine = true,
            textStyle = TextStyle(color = Ink.I100, fontSize = 13.sp),
            cursorBrush = SolidColor(Ink.White),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, fontSize = 13.sp, color = Ink.I500)
                }
                inner()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

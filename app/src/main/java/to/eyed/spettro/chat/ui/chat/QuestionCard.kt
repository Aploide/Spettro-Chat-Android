package to.eyed.spettro.chat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleQuestion
import to.eyed.spettro.chat.data.tools.AskAnswer
import to.eyed.spettro.chat.data.tools.AskForm
import to.eyed.spettro.chat.data.tools.AskQuestion
import to.eyed.spettro.chat.ui.components.GlassButton
import to.eyed.spettro.chat.ui.components.PrimaryButton
import to.eyed.spettro.chat.ui.components.surfaceCard
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii

/**
 * The inline ask-user form, shown in the transcript while the model waits.
 * Semantics follow the CLI and spettro-android: nothing is preselected (a
 * recommendation is highlighted, never answered on the user's behalf); on a
 * single-select question free text and options are mutually exclusive; a
 * form of exactly one single-select question submits on tap. Row visuals
 * live in QuestionRows.kt.
 */
@Composable
fun QuestionCard(
    form: AskForm,
    onSubmit: (List<AskAnswer>) -> Unit,
    onDecline: () -> Unit,
) {
    // Keyed by question index; the form lives only while the turn runs.
    val selections = remember(form) { mutableStateMapOf<Int, List<String>>() }
    val customText = remember(form) { mutableStateMapOf<Int, String>() }
    val touched = remember(form) { mutableStateMapOf<Int, Boolean>() }
    val otherOpen = remember(form) { mutableStateMapOf<Int, Boolean>() }
    val previewOpen = remember(form) { mutableStateMapOf<String, Boolean>() }

    fun buildAnswers(): List<AskAnswer> = form.questions.mapIndexed { qi, q ->
        val picked = selections[qi].orEmpty()
        AskAnswer(
            // Declared option order, not tap order, like both existing clients.
            selected = q.options.map { it.label }.filter { it in picked },
            custom = customText[qi].orEmpty().trim(),
            committed = touched[qi] == true,
        )
    }

    val anyAnswered = form.questions.indices.any {
        selections[it].orEmpty().isNotEmpty() || customText[it].orEmpty().isNotBlank()
    }
    val autoSubmits = form.questions.size == 1 &&
        !form.questions[0].multiSelect &&
        otherOpen[0] != true

    Column(
        Modifier
            .fillMaxWidth()
            .surfaceCard(RoundedCornerShape(Radii.card), fill = Ink.Surface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.MessageCircleQuestion, null, Modifier.size(16.dp), tint = Ink.I300)
            Spacer(Modifier.width(8.dp))
            Text(
                if (form.questions.size == 1) "Spettro has a question" else "Spettro has ${form.questions.size} questions",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink.White,
            )
        }
        if (form.context.isNotBlank()) {
            Text(
                form.context,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = Ink.I500,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        form.questions.forEachIndexed { qi, q ->
            Spacer(Modifier.height(14.dp))
            QuestionBlock(
                index = qi,
                question = q,
                showHeader = form.questions.size > 1,
                selected = selections[qi].orEmpty(),
                custom = customText[qi].orEmpty(),
                otherOpen = otherOpen[qi] == true,
                previewOpen = previewOpen,
                onPick = { label ->
                    touched[qi] = true
                    if (q.multiSelect) {
                        selections[qi] = selections[qi].orEmpty().let {
                            if (label in it) it - label else it + label
                        }
                    } else {
                        selections[qi] = listOf(label)
                        customText.remove(qi)
                        otherOpen.remove(qi)
                        if (autoSubmits) onSubmit(buildAnswers())
                    }
                },
                onOpenOther = {
                    touched[qi] = true
                    otherOpen[qi] = true
                },
                onCustomChange = { text ->
                    touched[qi] = true
                    customText[qi] = text
                    // Free text replaces the pick on a single-select question.
                    if (!q.multiSelect && text.isNotBlank()) selections.remove(qi)
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassButton("Decline", onClick = onDecline)
            Spacer(Modifier.width(10.dp))
            if (!autoSubmits) {
                PrimaryButton(
                    "Submit",
                    onClick = { onSubmit(buildAnswers()) },
                    enabled = anyAnswered,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    "Tap an option to answer",
                    fontSize = 12.sp,
                    color = Ink.I500,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun QuestionBlock(
    index: Int,
    question: AskQuestion,
    showHeader: Boolean,
    selected: List<String>,
    custom: String,
    otherOpen: Boolean,
    previewOpen: MutableMap<String, Boolean>,
    onPick: (String) -> Unit,
    onOpenOther: () -> Unit,
    onCustomChange: (String) -> Unit,
) {
    Column {
        if (showHeader) {
            Text(
                question.header.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                color = Ink.I500,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            question.question,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            color = Ink.White,
        )
        if (question.multiSelect) {
            Text(
                "Select all that apply",
                fontSize = 11.sp,
                color = Ink.I500,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            question.options.forEachIndexed { oi, option ->
                val key = "$index:$oi"
                OptionRow(
                    label = option.label,
                    description = option.description,
                    preview = option.preview,
                    recommended = option.recommended,
                    multiSelect = question.multiSelect,
                    selected = option.label in selected,
                    previewExpanded = previewOpen[key] == true,
                    onTogglePreview = { previewOpen[key] = previewOpen[key] != true },
                    onClick = { onPick(option.label) },
                )
            }
            if (question.allowCustom || question.options.isEmpty()) {
                OtherRow(
                    // A question with no options is only the text field.
                    bare = question.options.isEmpty(),
                    open = otherOpen || question.options.isEmpty(),
                    text = custom,
                    onOpen = onOpenOther,
                    onTextChange = onCustomChange,
                )
            }
        }
    }
}

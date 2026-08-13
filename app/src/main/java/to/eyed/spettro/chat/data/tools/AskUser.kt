package to.eyed.spettro.chat.data.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

// The ask-user form, ported from the CLI's internal/agent/ask_user.go:
// same limits, same validation messages, same answer wire format.

const val MAX_ASK_QUESTIONS = 4
const val MAX_ASK_OPTIONS = 8

/** `(not answered)` / `(none of the options)`, exactly as the CLI emits them. */
private const val SKIPPED_MARKER = "(not answered)"
private const val NONE_MARKER = "(none of the options)"

data class AskOption(
    val label: String,
    val description: String = "",
    /** Preformatted content shown beside the option; kept verbatim. */
    val preview: String = "",
    val recommended: Boolean = false,
)

data class AskQuestion(
    val header: String,
    val question: String,
    val options: List<AskOption>,
    val multiSelect: Boolean,
    val allowCustom: Boolean,
)

data class AskForm(
    val questions: List<AskQuestion>,
    val context: String = "",
)

/**
 * One question's answer. [committed] distinguishes a multi-select the user
 * looked at and left empty (→ "none of the options") from one never touched
 * (→ "not answered").
 */
data class AskAnswer(
    val selected: List<String> = emptyList(),
    val custom: String = "",
    val note: String = "",
    val committed: Boolean = false,
)

sealed interface AskParseResult {
    data class Ok(val form: AskForm) : AskParseResult
    /** Model-facing error text, phrased like the CLI's validation messages. */
    data class Invalid(val message: String) : AskParseResult
}

object AskUserForms {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(argumentsJson: String): AskParseResult {
        val root = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            ?: return AskParseResult.Invalid("args: invalid JSON")
        val questionsEl = root["questions"] as? JsonArray
        val flatQuestion = root.string("question")

        val form = if (!questionsEl.isNullOrEmpty()) {
            if (flatQuestion != null || root["options"] != null) {
                return AskParseResult.Invalid(
                    "pass either questions[] or the flat question/options fields, not both",
                )
            }
            AskForm(
                questions = questionsEl.mapNotNull { it as? JsonObject }.map { q ->
                    AskQuestion(
                        header = q.string("header").orEmpty(),
                        question = q.string("question").orEmpty(),
                        options = parseOptions(q["options"] as? JsonArray),
                        multiSelect = q.bool("multi_select"),
                        allowCustom = q.bool("allow_custom"),
                    )
                },
                context = root.string("context").orEmpty(),
            )
        } else {
            // Legacy single-question form: bare option labels, default_option
            // marks the recommendation, no options forces free text.
            if (flatQuestion == null) return AskParseResult.Invalid("at least one question is required")
            val labels = (root["options"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val recommended = root.string("default_option")
            AskForm(
                questions = listOf(
                    AskQuestion(
                        header = flatQuestion,
                        question = flatQuestion,
                        options = labels.map {
                            AskOption(it, recommended = recommended != null && it.equals(recommended, ignoreCase = true))
                        },
                        multiSelect = false,
                        allowCustom = root.bool("allow_free_response") || labels.isEmpty(),
                    ),
                ),
                context = root.string("context").orEmpty(),
            )
        }
        val filled = form.copy(questions = fillHeaders(form.questions))
        validate(filled)?.let { return AskParseResult.Invalid(it) }
        return AskParseResult.Ok(filled)
    }

    private fun parseOptions(arr: JsonArray?): List<AskOption> = arr.orEmpty().mapNotNull { el ->
        when (el) {
            // An option may be an object or a bare string label.
            is JsonPrimitive -> el.content.trim().takeIf { it.isNotEmpty() }?.let { AskOption(it) }
            is JsonObject -> AskOption(
                label = el.string("label").orEmpty(),
                description = el.string("description").orEmpty(),
                preview = (el["preview"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                recommended = el.bool("is_recommended"),
            )
            else -> null
        }
    }

    /** Derives missing headers from the question's first words; dedupes with " 2", " 3"… */
    private fun fillHeaders(questions: List<AskQuestion>): List<AskQuestion> {
        val seen = mutableSetOf<String>()
        return questions.mapIndexed { i, q ->
            var header = q.header.ifBlank {
                q.question.split(Regex("\\s+")).take(4).joinToString(" ")
                    .trimEnd(' ', '?', '!', '.', ',', ';', ':')
                    .take(24)
                    .ifBlank { "Question ${i + 1}" }
            }
            if (header.lowercase() in seen) {
                var n = 2
                while ("${header.lowercase()} $n" in seen) n++
                header = "$header $n"
            }
            seen += header.lowercase()
            q.copy(header = header)
        }
    }

    private fun validate(form: AskForm): String? {
        if (form.questions.isEmpty()) return "at least one question is required"
        if (form.questions.size > MAX_ASK_QUESTIONS) {
            return "a form takes at most $MAX_ASK_QUESTIONS questions, got ${form.questions.size}; " +
                "ask the rest in a follow-up call once these are answered"
        }
        for ((i, q) in form.questions.withIndex()) {
            if (q.question.isBlank()) return "question ${i + 1}: question text is required"
            if (q.options.size > MAX_ASK_OPTIONS) {
                return "question \"${q.header}\" takes at most $MAX_ASK_OPTIONS options, got ${q.options.size}"
            }
            if (q.options.isEmpty() && !q.allowCustom) {
                return "question \"${q.header}\" has no options and allow_custom is false: " +
                    "there is nothing for the user to answer"
            }
            if (q.options.any { it.label.isBlank() }) return "question \"${q.header}\": option label is required"
            val labels = q.options.map { it.label.lowercase() }
            labels.groupBy { it }.values.firstOrNull { it.size > 1 }?.let {
                return "question \"${q.header}\": duplicate option label \"${it.first()}\"; " +
                    "answers come back by label, so each must be distinct"
            }
            if (!q.multiSelect && q.options.count { it.recommended } > 1) {
                return "question \"${q.header}\" recommends ${q.options.count { it.recommended }} options " +
                    "but takes a single answer; mark only the one you would pick"
            }
        }
        return null
    }

    /**
     * The CLI's result format: one `Header: answer` line per question.
     * Returns null when nothing was answered at all (→ error result).
     */
    fun formatAnswers(form: AskForm, answers: List<AskAnswer>): String? {
        var answered = false
        val lines = form.questions.mapIndexed { i, q ->
            val a = answers.getOrNull(i) ?: AskAnswer()
            val parts = a.selected.toMutableList()
            if (a.custom.isNotBlank()) parts += quote(a.custom.trim())
            var text = when {
                parts.isNotEmpty() -> parts.joinToString(", ")
                a.committed -> NONE_MARKER
                else -> SKIPPED_MARKER
            }
            if (parts.isNotEmpty() || a.committed) answered = true
            if (a.note.isNotBlank()) {
                text += " — note: ${quote(a.note.trim())}"
                answered = true
            }
            "${q.header}: $text"
        }
        return if (answered) lines.joinToString("\n") else null
    }

    /** Double-quoted, escaped — the shape Go's strconv.Quote produces. */
    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
}

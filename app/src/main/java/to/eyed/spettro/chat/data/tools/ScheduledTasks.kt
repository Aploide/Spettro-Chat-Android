package to.eyed.spettro.chat.data.tools

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import to.eyed.spettro.chat.data.AppPrefs
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One scheduled agent run, persisted in prefs and mirrored in WorkManager. */
@Serializable
data class ScheduledTask(
    val id: String,
    val title: String,
    val prompt: String,
    /** "none" | "hourly" | "daily" | "weekly" */
    val repeat: String = "none",
    val nextRunAtMillis: Long,
)

/**
 * scheduled-tasks: let the model (or the user, via Settings) schedule agent
 * runs for later — "every morning at 8, check the weather and brief me".
 * WorkManager owns the timing (it survives reboots and Doze); the prefs JSON
 * list is the catalog the tool and the Settings sheet read.
 *
 * Recurring tasks re-enqueue themselves after each run rather than using
 * periodic work, so "daily at 08:00" stays anchored to 08:00 instead of
 * drifting by each run's duration.
 */
internal class ScheduledTaskTools(private val context: Context, private val prefs: AppPrefs) {

    suspend fun run(argumentsJson: String): ToolResult =
        when (val action = ToolArgs.string(argumentsJson, "action")) {
            "create" -> create(argumentsJson)
            "list" -> list()
            "cancel" -> cancel(argumentsJson)
            else -> ToolResult("scheduled-tasks requires action: create, list, or cancel (got: $action)", isError = true)
        }

    private suspend fun create(argumentsJson: String): ToolResult {
        val title = ToolArgs.string(argumentsJson, "title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult("create requires a short title", isError = true)
        val prompt = ToolArgs.string(argumentsJson, "prompt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult("create requires the prompt the task will run", isError = true)
        val repeat = ToolArgs.string(argumentsJson, "repeat") ?: "none"
        if (repeat !in setOf("none", "hourly", "daily", "weekly")) {
            return ToolResult("repeat must be none, hourly, daily, or weekly", isError = true)
        }
        val at = ToolArgs.string(argumentsJson, "at")
            ?: return ToolResult("create requires at (ISO local datetime, e.g. 2026-08-15T08:00)", isError = true)
        val zone = ZoneId.systemDefault()
        var atMillis = runCatching { LocalDateTime.parse(at).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
            ?: return ToolResult("could not parse at: use ISO local datetime, e.g. 2026-08-15T08:00", isError = true)
        if (atMillis <= System.currentTimeMillis()) {
            // A recurring task whose anchor is in the past just starts at the
            // next occurrence; a one-shot in the past is a mistake.
            if (repeat == "none") return ToolResult("that time is in the past", isError = true)
            while (atMillis <= System.currentTimeMillis()) atMillis = ScheduledTasks.advance(atMillis, repeat)
        }

        val existing = ScheduledTasks.load(prefs)
        if (existing.size >= ScheduledTasks.MAX_TASKS) {
            return ToolResult(
                "there are already ${existing.size} scheduled tasks; cancel one first (action: list, then cancel)",
                isError = true,
            )
        }
        val task = ScheduledTask(
            id = newId(),
            title = title.take(64),
            prompt = prompt.take(4_000),
            repeat = repeat,
            nextRunAtMillis = atMillis,
        )
        ScheduledTasks.persist(prefs) { it + task }
        ScheduledTasks.enqueue(context, task)
        val repeatNote = if (repeat == "none") "" else ", repeating $repeat"
        return ToolResult(
            "Scheduled \"${task.title}\" (id ${task.id}) for ${ScheduledTasks.display(atMillis)}$repeatNote. " +
                "The result will arrive as a notification and a new chat.",
        )
    }

    private suspend fun list(): ToolResult {
        val tasks = ScheduledTasks.load(prefs).sortedBy { it.nextRunAtMillis }
        if (tasks.isEmpty()) return ToolResult("No scheduled tasks.")
        return ToolResult(
            tasks.joinToString("\n") {
                "- ${it.id}: \"${it.title}\" — next run ${ScheduledTasks.display(it.nextRunAtMillis)}" +
                    (if (it.repeat == "none") ", one-time" else ", repeats ${it.repeat}")
            },
        )
    }

    private suspend fun cancel(argumentsJson: String): ToolResult {
        val id = ToolArgs.string(argumentsJson, "id")
            ?: return ToolResult("cancel requires the task id (use action: list to see them)", isError = true)
        val task = ScheduledTasks.load(prefs).firstOrNull { it.id == id }
            ?: return ToolResult("no scheduled task with id $id", isError = true)
        ScheduledTasks.cancel(context, prefs, id)
        return ToolResult("Cancelled \"${task.title}\".")
    }

    private fun newId(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/** Persistence and WorkManager wiring shared by the tool, worker, and Settings. */
object ScheduledTasks {
    const val MAX_TASKS = 20
    private const val WORK_PREFIX = "spettro-scheduled-"
    private const val KEY_TASK_ID = "task_id"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(prefs: AppPrefs): List<ScheduledTask> =
        prefs.scheduledTasksJson()?.let {
            runCatching { json.decodeFromString<List<ScheduledTask>>(it) }.getOrNull()
        } ?: emptyList()

    suspend fun persist(prefs: AppPrefs, transform: (List<ScheduledTask>) -> List<ScheduledTask>) {
        prefs.saveScheduledTasksJson(json.encodeToString(transform(load(prefs))))
    }

    /** (Re)enqueues the task's next run; unique work keyed by task id. */
    fun enqueue(context: Context, task: ScheduledTask) {
        val delay = (task.nextRunAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString(KEY_TASK_ID, task.id).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_PREFIX + task.id, ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun cancel(context: Context, prefs: AppPrefs, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PREFIX + id)
        persist(prefs) { tasks -> tasks.filterNot { it.id == id } }
    }

    /** The next occurrence, keeping local wall-clock time across DST shifts. */
    fun advance(fromMillis: Long, repeat: String): Long {
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(fromMillis).atZone(zone)
        return when (repeat) {
            "hourly" -> at.plusHours(1)
            "weekly" -> at.plusWeeks(1)
            else -> at.plusDays(1)
        }.toInstant().toEpochMilli()
    }

    fun display(atMillis: Long): String =
        Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEE MMM d 'at' HH:mm", Locale.getDefault()))

    internal fun taskId(inputData: Data): String? = inputData.getString(KEY_TASK_ID)
}

/**
 * Runs one due scheduled task through the headless agent, then re-enqueues
 * the next occurrence for recurring tasks. WorkManager's ~10-minute execution
 * window comfortably fits a bounded 6-round agent run, so no foreground
 * promotion is needed here.
 */
class ScheduledTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = to.eyed.spettro.chat.data.AppContainer.get(applicationContext)
        val id = ScheduledTasks.taskId(inputData) ?: return Result.success()
        val task = ScheduledTasks.load(container.prefs).firstOrNull { it.id == id }
            ?: return Result.success() // cancelled meanwhile

        container.taskManager.runScheduled(task.id, task.title, task.prompt)

        if (task.repeat == "none") {
            ScheduledTasks.persist(container.prefs) { tasks -> tasks.filterNot { it.id == id } }
        } else {
            var next = ScheduledTasks.advance(task.nextRunAtMillis, task.repeat)
            while (next <= System.currentTimeMillis()) next = ScheduledTasks.advance(next, task.repeat)
            val updated = task.copy(nextRunAtMillis = next)
            ScheduledTasks.persist(container.prefs) { tasks -> tasks.map { if (it.id == id) updated else it } }
            ScheduledTasks.enqueue(applicationContext, updated)
        }
        // The run itself never fails the work: a failed agent run still
        // produced a result chat, and retrying would double-deliver.
        return Result.success()
    }
}

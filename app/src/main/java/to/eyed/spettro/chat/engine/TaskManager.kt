package to.eyed.spettro.chat.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import to.eyed.spettro.chat.data.store.Conversation
import to.eyed.spettro.chat.data.store.ConversationStore
import to.eyed.spettro.chat.data.store.StoredMessage

enum class TaskStatus { Running, Done, Failed }

/** One background agent run, visible in the sidebar's task list. */
data class AgentTask(
    val id: String,
    val title: String,
    val prompt: String,
    /** True when a schedule (not the model or the user) started it. */
    val scheduled: Boolean,
    val status: TaskStatus = TaskStatus.Running,
    /** The running tool's label, for the task row. */
    val statusLine: String = "Starting…",
    /** The chat holding the finished result; set when the task completes. */
    val conversationId: String? = null,
    val startedAt: Long,
)

/**
 * Independent background agent runs, concurrent with each other and with the
 * interactive chat: the model fans work out via spawn-task, and scheduled
 * tasks land here too, so one task list shows everything running.
 *
 * Each task is a [AgentRunner] run whose result is saved as a new
 * conversation and announced with a notification. [ChatRunService] stays
 * foregrounded while anything here is active, so tasks survive the app
 * leaving the screen just like interactive turns do.
 */
class TaskManager(
    private val appContext: Context,
    private val runner: AgentRunner,
    private val store: ConversationStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    val tasks: StateFlow<List<AgentTask>> = _tasks.asStateFlow()

    /** True while any task is running; ORed with the engine to keep the service up. */
    private val _anyRunning = MutableStateFlow(false)
    val anyRunning: StateFlow<Boolean> = _anyRunning.asStateFlow()

    /** Wired to the engine by AppContainer so finished tasks appear in the sidebar. */
    var onConversationsChanged: suspend () -> Unit = {}

    /** Whether any activity is on screen; wired by AppContainer. */
    var appVisibleProvider: () -> Boolean = { false }

    private companion object {
        /** Concurrent background runs; further spawns queue behind these. */
        const val MAX_CONCURRENT = 3

        /** Notification ids offset so tasks never clobber the run/reminder ids. */
        const val NOTIFY_BASE = 2000
    }

    private val semaphore = Semaphore(MAX_CONCURRENT)

    /**
     * Starts a task in the app-scoped background. Called from an interactive
     * turn (the spawn-task tool), so the app is on screen and the foreground
     * service may be started.
     */
    fun spawn(title: String, prompt: String): AgentTask {
        val task = AgentTask(
            id = store.newId(),
            title = title.take(64),
            prompt = prompt,
            scheduled = false,
            startedAt = System.currentTimeMillis(),
        )
        // The caller may be on the tool executor's IO dispatcher; all task
        // state lives on the main dispatcher, so hop before touching it.
        scope.launch {
            register(task)
            runCatching { ChatRunService.start(appContext) }
            semaphore.withPermit { execute(task) }
        }
        return task
    }

    /**
     * Runs a scheduled task to completion on the caller's coroutine (the
     * WorkManager worker), still registered in the task list so it shows up
     * if the app is open. Orchestration hops to the main dispatcher so the
     * task-list state is only ever touched from one thread.
     */
    suspend fun runScheduled(taskId: String, title: String, prompt: String) {
        val task = AgentTask(
            id = taskId,
            title = title.take(64),
            prompt = prompt,
            scheduled = true,
            startedAt = System.currentTimeMillis(),
        )
        kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            register(task)
            execute(task)
        }
    }

    /** Removes a finished task row; running tasks stay until they settle. */
    fun dismiss(id: String) {
        _tasks.value = _tasks.value.filterNot { it.id == id && it.status != TaskStatus.Running }
        refreshRunning()
    }

    private fun register(task: AgentTask) {
        _tasks.value = listOf(task) + _tasks.value.filter { it.id != task.id }
        refreshRunning()
    }

    private fun updateTask(id: String, transform: (AgentTask) -> AgentTask) {
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
        refreshRunning()
    }

    private fun refreshRunning() {
        _anyRunning.value = _tasks.value.any { it.status == TaskStatus.Running }
    }

    private suspend fun execute(task: AgentTask) {
        val result = try {
            runner.run(task.prompt) { label -> updateTask(task.id) { it.copy(statusLine = label) } }
        } catch (e: kotlinx.coroutines.CancellationException) {
            updateTask(task.id) { it.copy(status = TaskStatus.Failed, statusLine = "Stopped") }
            throw e
        }

        // Whatever happened, the outcome is preserved as a chat: the answer,
        // or the partial work plus what went wrong.
        val now = System.currentTimeMillis()
        val body = when {
            !result.failed -> result.text
            result.text.isBlank() -> "**This background task failed.** ${result.error ?: ""}".trim()
            else -> result.text + "\n\n**The task stopped early:** ${result.error ?: "something went wrong"}"
        }
        val conversation = Conversation(
            id = store.newId(),
            title = task.title,
            preview = task.prompt.take(120),
            createdAt = now,
            updatedAt = now,
            messages = listOf(
                StoredMessage(role = "user", content = task.prompt, at = task.startedAt),
                StoredMessage(role = "assistant", content = body, at = now, tools = result.toolRuns),
            ),
        )
        runCatching { store.save(conversation) }
        runCatching { onConversationsChanged() }

        updateTask(task.id) {
            it.copy(
                status = if (result.failed) TaskStatus.Failed else TaskStatus.Done,
                statusLine = if (result.failed) (result.error ?: "Failed") else "Finished",
                conversationId = conversation.id,
            )
        }

        // Scheduled results always notify — delivery is their whole point.
        // Spawned tasks notify only when the app is off screen; on screen,
        // the task list row is the announcement.
        if (task.scheduled || !appVisibleProvider()) {
            AgentNotifications.notifySafely(
                appContext,
                NOTIFY_BASE + (task.id.hashCode() and 0x3FF),
                AgentNotifications.taskDone(appContext, task.title, result.failed, task.scheduled),
            )
        }
    }
}

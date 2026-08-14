package to.eyed.spettro.chat.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import to.eyed.spettro.chat.data.AppContainer
import to.eyed.spettro.chat.vm.StreamState

/**
 * Foreground service alive for the duration of one agent run. Its only job
 * is keeping the process (and its network sockets) unfrozen while the
 * [ChatEngine] loop works with the app in the background, and narrating
 * progress through the ongoing notification.
 *
 * Type `dataSync`: the run is a network data transfer (SSE stream + tool HTTP
 * calls). Started exclusively from user interactions (send/compact/
 * regenerate), so the foreground-start restriction never applies.
 */
class ChatRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var lastNotify = 0L

    companion object {
        /** Minimum gap between updates of the ongoing notification. */
        private const val NOTIFY_INTERVAL_MS = 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ChatRunService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must run within seconds of startForegroundService, before anything
        // that could suspend.
        ServiceCompat.startForeground(
            this,
            AgentNotifications.PROGRESS_ID,
            AgentNotifications.progress(this, "Working…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        observeEngine()
        // A restarted process has no run to resume; don't come back.
        return START_NOT_STICKY
    }

    private fun observeEngine() {
        if (observing) return // redundant start() while already foregrounded
        observing = true
        val engine = AppContainer.get(applicationContext).engine

        val container = AppContainer.get(applicationContext)
        scope.launch {
            engine.stream.collect { state ->
                val label = when (state) {
                    is StreamState.Thinking -> genericToolLabel(container, state.tools) ?: "Thinking…"
                    is StreamState.Streaming -> genericToolLabel(container, state.tools) ?: "Writing the answer…"
                    is StreamState.RateLimited -> "Waiting out a rate limit…"
                    is StreamState.Compacting -> "Compacting the conversation…"
                    else -> null
                }
                val now = System.currentTimeMillis()
                if (label != null && now - lastNotify >= NOTIFY_INTERVAL_MS) {
                    lastNotify = now
                    AgentNotifications.notifySafely(
                        this@ChatRunService,
                        AgentNotifications.PROGRESS_ID,
                        AgentNotifications.progress(this@ChatRunService, label),
                    )
                }
            }
        }

        scope.launch {
            engine.events.collect { event ->
                when (event) {
                    is EngineEvent.RunFinished -> if (!engine.appVisible) {
                        AgentNotifications.notifySafely(
                            this@ChatRunService,
                            AgentNotifications.DONE_ID,
                            AgentNotifications.done(this@ChatRunService, event.chatTitle, event.failed),
                        )
                    }
                    is EngineEvent.NeedsInput -> if (!engine.appVisible) {
                        AgentNotifications.notifySafely(
                            this@ChatRunService,
                            AgentNotifications.INPUT_ID,
                            AgentNotifications.needsInput(this@ChatRunService, event.reason),
                        )
                    }
                }
            }
        }

        val consent = AppContainer.get(applicationContext).consent
        val permissions = AppContainer.get(applicationContext).permissions
        scope.launch {
            consent.pending.collect { request ->
                if (request != null && !engine.appVisible) {
                    AgentNotifications.notifySafely(
                        this@ChatRunService,
                        AgentNotifications.INPUT_ID,
                        AgentNotifications.needsInput(this@ChatRunService, request.title),
                    )
                }
            }
        }
        scope.launch {
            permissions.pending.collect { request ->
                if (request != null && !engine.appVisible) {
                    AgentNotifications.notifySafely(
                        this@ChatRunService,
                        AgentNotifications.INPUT_ID,
                        AgentNotifications.needsInput(this@ChatRunService, "A permission is waiting for you"),
                    )
                }
            }
        }

        // The service outlives the interactive turn while background tasks
        // are still working; it only winds down once everything is idle.
        val taskManager = container.taskManager
        scope.launch {
            kotlinx.coroutines.flow.combine(engine.isRunning, taskManager.anyRunning) { turn, tasks ->
                turn || tasks
            }.collect { active ->
                if (!active) {
                    ServiceCompat.stopForeground(this@ChatRunService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }

        // With no interactive turn, the ongoing notification narrates the
        // task count instead of tool activity.
        scope.launch {
            kotlinx.coroutines.flow.combine(engine.isRunning, taskManager.tasks) { turn, tasks ->
                if (turn) null else tasks.count { it.status == TaskStatus.Running }
            }.collect { running ->
                if (running != null && running > 0) {
                    AgentNotifications.notifySafely(
                        this@ChatRunService,
                        AgentNotifications.PROGRESS_ID,
                        AgentNotifications.progress(
                            this@ChatRunService,
                            if (running == 1) "Running a background task…" else "Running $running background tasks…",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Content-free description of the running tool for the lock screen: the
     * UI's labels can carry model-written text (the comment tool's label IS
     * the model's message), so the notification rebuilds each label from the
     * tool name alone.
     */
    private fun genericToolLabel(
        container: AppContainer,
        tools: List<to.eyed.spettro.chat.vm.ToolRunUi>,
    ): String? {
        val running = tools.lastOrNull { it.running } ?: return null
        return when {
            running.name == to.eyed.spettro.chat.data.tools.ToolRegistry.COMMENT -> "Working…"
            container.mcp.isMcpTool(running.name) -> container.mcp.runningLabel(running.name)
            else -> container.tools.runningLabel(running.name, "")
        }
    }

    /**
     * The user swiped the app away: deliberately keep running — finishing the
     * task is the whole point of this service; the completion notification is
     * the way back into the app.
     */
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    /** Android 15+ enforces a daily budget on dataSync time; wind down cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val engine = AppContainer.get(applicationContext).engine
        engine.stopStreaming() // persists any partial answer
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

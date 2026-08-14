package to.eyed.spettro.chat.data.tools

import android.Manifest
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.spettro.chat.data.AppPrefs
import to.eyed.spettro.chat.data.api.ToolCallData
import to.eyed.spettro.chat.data.api.ToolSpec
import java.net.URL

data class ToolResult(val output: String, val isError: Boolean = false)

/**
 * Marks a tool as touching personal data: the engine runs its calls through
 * the in-app consent card first (mandatory, in addition to the Android
 * runtime permission), then through the permission bridge.
 */
data class SensitiveMeta(
    val consentKey: String,
    val consentTitle: String,
    val consentDetail: String,
    val permissions: List<String>,
    val rationale: String,
)

/**
 * The tools offered to the model on every chat request, mirroring the CLI's
 * native tool calling (internal/agent/llm_runtime_prompt.go) trimmed to what
 * makes sense on a phone. This file owns the specs, labels, and dispatch;
 * implementations live in [WebTools], [DeviceTools], [CalendarTools],
 * [ContactsTools], [ReminderTools], [LocationTools], and AskUser.kt.
 */
class ToolRegistry(
    context: Context,
    prefs: AppPrefs,
    private val memory: to.eyed.spettro.chat.data.memory.MemoryStore,
) {
    private val web = WebTools()
    private val device = DeviceTools(context)
    private val calendar = CalendarTools(context)
    private val contacts = ContactsTools(context)
    private val reminders = ReminderTools(context, prefs)
    private val location = LocationTools(context)
    private val actuators = ActuatorTools(context)
    private val scheduled = ScheduledTaskTools(context, prefs)
    private val notifications = NotificationTools(context)

    /**
     * Whether any activity is on screen right now; wired to the engine by
     * AppContainer. Tools that launch activities or read location must fail
     * softly while the app is backgrounded.
     */
    var appVisibleProvider: () -> Boolean = { true }

    /**
     * Runs a spawn-task call; wired to the TaskManager by AppContainer (the
     * registry is built before the engine-side services exist).
     */
    var taskSpawner: (suspend (title: String, prompt: String) -> ToolResult)? = null

    companion object {
        const val WEB_SEARCH = "web-search"
        const val WEB_FETCH = "web-fetch"
        const val CURRENT_TIME = "current-time"
        const val DEVICE_INFO = "device-info"
        const val ASK_USER = "ask-user"
        const val COMMENT = "comment"
        const val CALENDAR_EVENTS = "calendar-events"
        const val CONTACTS_SEARCH = "contacts-search"
        const val SET_REMINDER = "set-reminder"
        const val GET_LOCATION = "get-location"
        const val SAVE_MEMORY = "save-memory"
        const val FORGET_MEMORY = "forget-memory"
        const val SPAWN_TASK = "spawn-task"
        const val SCHEDULED_TASKS = "scheduled-tasks"
        const val COMPOSE_MESSAGE = "compose-message"
        const val SET_ALARM = "set-alarm"
        const val OPEN_ON_PHONE = "open-on-phone"
        const val MEDIA_CONTROL = "media-control"
        const val READ_NOTIFICATIONS = "read-notifications"

        /**
         * Tools that only make sense with a user present; the headless
         * [to.eyed.spettro.chat.engine.AgentRunner] never offers them
         * (spawn-task also so background tasks cannot fan out further).
         */
        val INTERACTIVE_ONLY = setOf(ASK_USER, SPAWN_TASK)

        /**
         * Tools safe to execute concurrently within one round: no consent
         * card, no UI, no ordering dependency between calls. Web calls are
         * the latency win this exists for.
         */
        val PARALLEL_SAFE = setOf(WEB_SEARCH, WEB_FETCH, CURRENT_TIME, DEVICE_INFO)
    }

    /** Whether calls to this tool may run concurrently with its round's other calls. */
    fun isParallelSafe(name: String): Boolean = name in PARALLEL_SAFE

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = WEB_SEARCH,
            description = "Search the web. Returns result titles, URLs, and snippets. " +
                "Use for current events, facts you are unsure of, or anything after your training data.",
            parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"The search query."},"max_results":{"type":"integer","description":"Maximum results to return (default 10)."}},"required":["query"]}""",
        ),
        ToolSpec(
            name = WEB_FETCH,
            description = "Fetch a URL and return its readable text content. Use after web-search to read a promising result.",
            parametersJson = """{"type":"object","properties":{"url":{"type":"string","description":"The http(s) URL to fetch."},"max_length":{"type":"integer","description":"Maximum characters of text to return (default 20000)."}},"required":["url"]}""",
        ),
        ToolSpec(
            name = CURRENT_TIME,
            description = "Get the current date and time from the device clock. " +
                "Use for any question involving today's date, the time, weekdays, or elapsed time.",
            parametersJson = """{"type":"object","properties":{"timezone":{"type":"string","description":"Optional IANA timezone (e.g. Europe/Rome). Defaults to the device timezone."}},"required":[]}""",
        ),
        ToolSpec(
            name = DEVICE_INFO,
            description = "Read this Android device's status: battery level and charging state, network connectivity, locale, timezone, and device model.",
            parametersJson = """{"type":"object","properties":{},"required":[]}""",
        ),
        ToolSpec(
            name = CALENDAR_EVENTS,
            description = "Read the user's upcoming calendar events, or open their calendar app " +
                "pre-filled to create a new event (the user confirms the save there). " +
                "The app asks the user for approval before any calendar access; a denial is final for this turn. " +
                "Creating an event requires the app to be on screen.",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["read","create"],"description":"read upcoming events, or create one via the calendar editor"},"days":{"type":"integer","description":"read: how many days ahead to look (default 7, max 60)"},"title":{"type":"string","description":"create: event title"},"start":{"type":"string","description":"create: start as ISO local datetime, e.g. 2026-08-14T15:30"},"end":{"type":"string","description":"create: end as ISO local datetime (default start + 1h)"},"location":{"type":"string","description":"create: optional location"},"description":{"type":"string","description":"create: optional notes"}},"required":["action"]}""",
        ),
        ToolSpec(
            name = CONTACTS_SEARCH,
            description = "Search the user's contacts by name; returns matching names with phone numbers and " +
                "email addresses. The app asks the user for approval before any contacts access.",
            parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"Full or partial name to search for."},"max_results":{"type":"integer","description":"Maximum matches to return (default 5, max 10)."}},"required":["name"]}""",
        ),
        ToolSpec(
            name = SET_REMINDER,
            description = "Schedule a reminder delivered as a notification on this phone. Delivery time is " +
                "approximate (the OS may batch it by a few minutes). The app asks the user for approval first.",
            parametersJson = """{"type":"object","properties":{"message":{"type":"string","description":"The reminder text shown in the notification."},"at":{"type":"string","description":"When to remind, as ISO local datetime, e.g. 2026-08-14T15:30."},"in_minutes":{"type":"integer","description":"Alternative to at: minutes from now."}},"required":["message"]}""",
        ),
        ToolSpec(
            name = GET_LOCATION,
            description = "Get the device's approximate (city-level) location. Only works while the app is on " +
                "screen. The app asks the user for approval before any location access.",
            parametersJson = """{"type":"object","properties":{},"required":[]}""",
        ),
        ToolSpec(
            name = SPAWN_TASK,
            description = "Start an independent background task: a separate agent run that works on its own " +
                "(with web access and any always-allowed tools) while this conversation continues. Its result " +
                "arrives as a new chat and a notification. Use it for self-contained work the user doesn't need " +
                "to watch — long research, monitoring something, drafting a document — never for things needing " +
                "their input midway. Returns immediately; do not wait for the task.",
            parametersJson = """{"type":"object","properties":{"title":{"type":"string","description":"Short name for the task, shown in the task list and as the result chat's title."},"prompt":{"type":"string","description":"Complete, self-contained instructions for the task; it cannot ask follow-up questions."}},"required":["title","prompt"]}""",
        ),
        ToolSpec(
            name = SCHEDULED_TASKS,
            description = "Schedule an agent run for later, list scheduled runs, or cancel one. A scheduled run " +
                "executes on its own (web access plus any always-allowed tools, no questions) and delivers its " +
                "result as a notification and a new chat — e.g. \"every morning at 8, check the weather and my " +
                "calendar and brief me\". Timing is approximate (the OS may delay a few minutes). The app asks " +
                "the user for approval first.",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["create","list","cancel"],"description":"create a scheduled run, list existing ones, or cancel by id"},"title":{"type":"string","description":"create: short name, shown in the result notification and chat title"},"prompt":{"type":"string","description":"create: complete self-contained instructions for the run"},"at":{"type":"string","description":"create: first run as ISO local datetime, e.g. 2026-08-15T08:00"},"repeat":{"type":"string","enum":["none","hourly","daily","weekly"],"description":"create: how the run repeats (default none = one-time)"},"id":{"type":"string","description":"cancel: the task id from list"}},"required":["action"]}""",
        ),
        ToolSpec(
            name = COMPOSE_MESSAGE,
            description = "Open the user's SMS app or WhatsApp with a message pre-filled to a number; the user " +
                "reviews and taps send themselves — nothing is sent automatically. Use contacts-search first if " +
                "you only have a name. Only works while the app is on screen. The app asks the user for approval first.",
            parametersJson = """{"type":"object","properties":{"channel":{"type":"string","enum":["sms","whatsapp"],"description":"where to compose the message"},"phone":{"type":"string","description":"recipient phone number with country code, e.g. +39333...; optional for whatsapp (opens its contact picker)"},"message":{"type":"string","description":"the message text to pre-fill"}},"required":["channel","message"]}""",
        ),
        ToolSpec(
            name = SET_ALARM,
            description = "Set an alarm or a countdown timer via the phone's clock app, opened pre-filled so the " +
                "user confirms it there. Use set-reminder instead for date-based reminders with a message. " +
                "Only works while the app is on screen. The app asks the user for approval first.",
            parametersJson = """{"type":"object","properties":{"type":{"type":"string","enum":["alarm","timer"],"description":"a wake-up alarm at a time of day, or a countdown timer"},"hour":{"type":"integer","description":"alarm: hour of day, 0-23"},"minute":{"type":"integer","description":"alarm: minute, 0-59 (default 0)"},"length_minutes":{"type":"integer","description":"timer: countdown length in minutes"},"message":{"type":"string","description":"optional label shown on the alarm or timer"}},"required":["type"]}""",
        ),
        ToolSpec(
            name = OPEN_ON_PHONE,
            description = "Open an installed app by name, or open a link (https://, geo:, or any deep link) in " +
                "whatever handles it. Only works while the app is on screen. The app asks the user for approval first.",
            parametersJson = """{"type":"object","properties":{"app":{"type":"string","description":"name of an installed app, e.g. Spotify"},"url":{"type":"string","description":"a link to open instead of an app, e.g. https://maps.google.com/?q=... or geo:45.4,9.2"}},"required":[]}""",
        ),
        ToolSpec(
            name = MEDIA_CONTROL,
            description = "Control whatever is playing media on this phone: play, pause, skip to the next or " +
                "previous track, or stop. Acts immediately, like pressing a headset button. The app asks the " +
                "user for approval first.",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["play","pause","play_pause","next","previous","stop"],"description":"the media key to send"}},"required":["action"]}""",
        ),
        ToolSpec(
            name = READ_NOTIFICATIONS,
            description = "Read the notifications currently in the phone's status bar — \"what did I miss?\". " +
                "Read-only: nothing is dismissed or answered. Requires the user to have enabled notification " +
                "access for Spettro in the Android settings (the tool's error says how if not). The app asks " +
                "the user for approval before each first use.",
            parametersJson = """{"type":"object","properties":{"max_results":{"type":"integer","description":"Maximum notifications to return (default 25, max 50)."}},"required":[]}""",
        ),
        // Descriptions modeled on the CLI's save-memory (llm_runtime_prompt.go).
        ToolSpec(
            name = SAVE_MEMORY,
            description = "Save one short durable fact or preference about the user to persistent memory; " +
                "it is loaded into context in future chats. Save things worth remembering across " +
                "conversations (name, language, preferences, ongoing projects), never transient details " +
                "of the current task. Keep each fact a single short line.",
            parametersJson = """{"type":"object","properties":{"fact":{"type":"string","description":"The fact, as one short self-contained line."}},"required":["fact"]}""",
        ),
        ToolSpec(
            name = FORGET_MEMORY,
            description = "Remove a fact from persistent memory when the user corrects it, retracts it, or " +
                "asks you to forget something. Pass the fact (or a distinctive part of it); every matching " +
                "memory is removed.",
            parametersJson = """{"type":"object","properties":{"fact":{"type":"string","description":"The remembered fact to remove, or a distinctive fragment of it."}},"required":["fact"]}""",
        ),
        ToolSpec(
            name = COMMENT,
            description = "Emit a progress message visible to the user. " +
                "Use it to report meaningful steps during longer multi-tool runs.",
            parametersJson = """{"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}""",
        ),
        // Description and schema verbatim from the CLI
        // (internal/agent/llm_runtime_prompt.go), so prompting behaves alike.
        ToolSpec(
            name = ASK_USER,
            description = "Ask the user up to 4 related questions as one form and wait for their answers. " +
                "Use it when a decision is genuinely the user's to make and proceeding on a guess would waste work — " +
                "never for something you can determine yourself. Batch questions that belong to the same decision " +
                "into one call rather than interrupting repeatedly. Each question takes a short header (the label " +
                "the UI shows), the question line, and up to 8 options; give every option a label plus a one-line " +
                "description of what choosing it means, mark the one you would pick with is_recommended (the UI " +
                "highlights it), and set preview when there is concrete content — a snippet, a layout, a config — " +
                "worth showing beside the option. Set multi_select when several answers can hold at once: there is " +
                "no exclusivity flag, so phrase those options such that any subset of them reads sensibly. Set " +
                "allow_custom when written input is useful: the user gets a free-text entry and their words come " +
                "back verbatim, quoted. Answers return one line per question, keyed by header; a question the user " +
                "skipped is marked as unanswered, so never read silence as agreement with your recommendation, and " +
                "a multi-select question answered with none of the options is marked as such — that is a decision " +
                "about them, not silence.",
            parametersJson = """{"type":"object","properties":{"questions":{"type":"array","maxItems":4,"description":"the form: up to 4 questions answered in one interaction","items":{"type":"object","properties":{"header":{"type":"string","description":"short label, e.g. \"Focus area\"; must be unique within the form and keys the answer"},"question":{"type":"string","description":"the full question line"},"options":{"type":"array","maxItems":8,"description":"selectable answers; prefer these over an open question","items":{"type":"object","properties":{"label":{"type":"string","description":"the answer as the user reads it"},"description":{"type":"string","description":"one muted line under the label saying what choosing it means"},"preview":{"type":"string","description":"preformatted content (snippet, layout, config) shown beside the option; kept verbatim, so keep lines narrow"},"is_recommended":{"type":"boolean","description":"the answer you would pick; highlighted"}},"required":["label"]}},"multi_select":{"type":"boolean","description":"several answers may be chosen at once; any subset can come back, so phrase the options so every combination of them means something"},"allow_custom":{"type":"boolean","description":"also offer a free-text entry; the typed answer is returned verbatim"}},"required":["question"]}},"context":{"type":"string","description":"one line of background applying to the whole form"},"question":{"type":"string","description":"legacy single-question form; use questions[] instead"},"options":{"type":"array","items":{"type":"string"},"description":"legacy: option labels for the single question"},"default_option":{"type":"string","description":"legacy: the recommended option, matched by label"},"allow_free_response":{"type":"boolean","description":"legacy: allow_custom for the single question"}}}""",
        ),
    )

    /** Label shown while a call runs, e.g. `Searching the web for "x"…`. */
    fun runningLabel(name: String, argumentsJson: String): String = when (name) {
        WEB_SEARCH -> quotedArg(argumentsJson, "query")
            ?.let { "Searching the web for $it…" } ?: "Searching the web…"
        WEB_FETCH -> hostArg(argumentsJson)?.let { "Reading $it…" } ?: "Reading a web page…"
        CURRENT_TIME -> "Checking the time…"
        DEVICE_INFO -> "Reading device status…"
        CALENDAR_EVENTS ->
            if (ToolArgs.string(argumentsJson, "action") == "create") "Preparing a calendar event…"
            else "Reading your calendar…"
        CONTACTS_SEARCH -> quotedArg(argumentsJson, "name")
            ?.let { "Searching contacts for $it…" } ?: "Searching your contacts…"
        SET_REMINDER -> "Setting a reminder…"
        GET_LOCATION -> "Reading your location…"
        SAVE_MEMORY -> "Saving a memory…"
        FORGET_MEMORY -> "Forgetting a memory…"
        SPAWN_TASK -> quotedArg(argumentsJson, "title")
            ?.let { "Starting background task $it…" } ?: "Starting a background task…"
        SCHEDULED_TASKS -> when (ToolArgs.string(argumentsJson, "action")) {
            "list" -> "Checking scheduled tasks…"
            "cancel" -> "Cancelling a scheduled task…"
            else -> "Scheduling a task…"
        }
        COMPOSE_MESSAGE -> if (ToolArgs.string(argumentsJson, "channel") == "whatsapp") {
            "Composing a WhatsApp message…"
        } else {
            "Composing a text message…"
        }
        SET_ALARM -> if (ToolArgs.string(argumentsJson, "type") == "timer") "Setting a timer…" else "Setting an alarm…"
        OPEN_ON_PHONE -> ToolArgs.string(argumentsJson, "app")?.let { "Opening ${it.take(40)}…" } ?: "Opening a link…"
        MEDIA_CONTROL -> "Controlling media playback…"
        READ_NOTIFICATIONS -> "Reading your notifications…"
        ASK_USER -> "Waiting for your answer…"
        COMMENT -> commentMessage(argumentsJson) ?: "…"
        else -> "Running $name…"
    }

    /** Label shown once a call finished, e.g. `Searched the web for "x"`. */
    fun doneLabel(name: String, argumentsJson: String): String = when (name) {
        WEB_SEARCH -> quotedArg(argumentsJson, "query")
            ?.let { "Searched the web for $it" } ?: "Searched the web"
        WEB_FETCH -> hostArg(argumentsJson)?.let { "Read $it" } ?: "Read a web page"
        CURRENT_TIME -> "Checked the time"
        DEVICE_INFO -> "Read device status"
        CALENDAR_EVENTS ->
            if (ToolArgs.string(argumentsJson, "action") == "create") "Opened the calendar editor"
            else "Read your calendar"
        CONTACTS_SEARCH -> quotedArg(argumentsJson, "name")
            ?.let { "Searched contacts for $it" } ?: "Searched your contacts"
        SET_REMINDER -> "Set a reminder"
        GET_LOCATION -> "Read your location"
        SAVE_MEMORY -> quotedArg(argumentsJson, "fact")?.let { "Remembered $it" } ?: "Saved a memory"
        FORGET_MEMORY -> quotedArg(argumentsJson, "fact")?.let { "Forgot $it" } ?: "Forgot a memory"
        SPAWN_TASK -> quotedArg(argumentsJson, "title")
            ?.let { "Started background task $it" } ?: "Started a background task"
        SCHEDULED_TASKS -> when (ToolArgs.string(argumentsJson, "action")) {
            "list" -> "Checked scheduled tasks"
            "cancel" -> "Cancelled a scheduled task"
            else -> "Scheduled a task"
        }
        COMPOSE_MESSAGE -> "Opened the message composer"
        SET_ALARM -> if (ToolArgs.string(argumentsJson, "type") == "timer") "Opened a timer to confirm" else "Opened an alarm to confirm"
        OPEN_ON_PHONE -> ToolArgs.string(argumentsJson, "app")?.let { "Opened ${it.take(40)}" } ?: "Opened a link"
        MEDIA_CONTROL -> "Sent a media command"
        READ_NOTIFICATIONS -> "Read your notifications"
        ASK_USER -> "Asked for your input"
        // A comment's whole point is its text; the label is the message.
        COMMENT -> commentMessage(argumentsJson) ?: "…"
        else -> "Ran $name"
    }

    suspend fun execute(call: ToolCallData): ToolResult = withContext(Dispatchers.IO) {
        try {
            when (call.name) {
                WEB_SEARCH -> web.search(call.arguments)
                WEB_FETCH -> web.fetch(call.arguments)
                CURRENT_TIME -> device.currentTime(call.arguments)
                DEVICE_INFO -> device.deviceInfo()
                CALENDAR_EVENTS -> calendar.run(call.arguments, appVisibleProvider())
                CONTACTS_SEARCH -> contacts.search(call.arguments)
                SET_REMINDER -> reminders.set(call.arguments)
                GET_LOCATION -> location.current(appVisibleProvider())
                SAVE_MEMORY -> saveMemory(call.arguments)
                FORGET_MEMORY -> forgetMemory(call.arguments)
                SPAWN_TASK -> spawnTask(call.arguments)
                SCHEDULED_TASKS -> scheduled.run(call.arguments)
                COMPOSE_MESSAGE -> actuators.composeMessage(call.arguments, appVisibleProvider())
                SET_ALARM -> actuators.setAlarm(call.arguments, appVisibleProvider())
                OPEN_ON_PHONE -> actuators.openOnPhone(call.arguments, appVisibleProvider())
                MEDIA_CONTROL -> actuators.mediaControl(call.arguments)
                READ_NOTIFICATIONS -> notifications.read(call.arguments)
                // The CLI echoes the message back verbatim as the result.
                COMMENT -> ToolResult(ToolArgs.string(call.arguments, "message") ?: "")
                // ask-user blocks on the person; the ViewModel intercepts it
                // before execution ever reaches the registry.
                ASK_USER -> ToolResult("error: ask-user: interactive callback not configured", isError = true)
                else -> ToolResult("Unknown tool: ${call.name}", isError = true)
            }
        } catch (e: Exception) {
            ToolResult("Tool ${call.name} failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    /**
     * Consent + permission requirements for tools touching personal data;
     * null for everything else. Takes the arguments because calendar-events
     * only needs READ_CALENDAR for reads (creation goes through the user's
     * calendar app, which is its own confirmation).
     */
    fun sensitiveMeta(name: String, argumentsJson: String): SensitiveMeta? = when (name) {
        CALENDAR_EVENTS -> {
            val creating = ToolArgs.string(argumentsJson, "action") == "create"
            SensitiveMeta(
                consentKey = "tool:$CALENDAR_EVENTS",
                consentTitle = if (creating) "Allow adding to your calendar?" else "Allow reading your calendar?",
                consentDetail = if (creating) {
                    "The assistant wants to open your calendar app pre-filled with a new event. " +
                        "You review and save it there."
                } else {
                    "The assistant wants to read your upcoming calendar events to answer this request."
                },
                permissions = if (creating) emptyList() else listOf(Manifest.permission.READ_CALENDAR),
                rationale = "Reading your calendar needs the Android calendar permission.",
            )
        }
        CONTACTS_SEARCH -> SensitiveMeta(
            consentKey = "tool:$CONTACTS_SEARCH",
            consentTitle = "Allow searching your contacts?",
            consentDetail = "The assistant wants to look up a name in your contacts and see the matching " +
                "phone numbers and email addresses.",
            permissions = listOf(Manifest.permission.READ_CONTACTS),
            rationale = "Searching your contacts needs the Android contacts permission.",
        )
        SET_REMINDER -> SensitiveMeta(
            consentKey = "tool:$SET_REMINDER",
            consentTitle = "Allow setting reminders?",
            consentDetail = "The assistant wants to schedule a reminder notification on this phone.",
            permissions = emptyList(),
            rationale = "",
        )
        GET_LOCATION -> SensitiveMeta(
            consentKey = "tool:$GET_LOCATION",
            consentTitle = "Allow access to your location?",
            consentDetail = "The assistant wants your approximate (city-level) location to answer this request.",
            permissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            rationale = "Reading your location needs the Android location permission.",
        )
        SCHEDULED_TASKS -> SensitiveMeta(
            consentKey = "tool:$SCHEDULED_TASKS",
            consentTitle = "Allow scheduling background tasks?",
            consentDetail = "The assistant wants to manage scheduled tasks that run on their own and " +
                "deliver results as notifications. You can review them under Settings → Scheduled tasks.",
            permissions = emptyList(),
            rationale = "",
        )
        COMPOSE_MESSAGE -> SensitiveMeta(
            consentKey = "tool:$COMPOSE_MESSAGE",
            consentTitle = "Allow composing messages?",
            consentDetail = "The assistant wants to open your messaging app with a message pre-filled. " +
                "Nothing is sent until you tap send there yourself.",
            permissions = emptyList(),
            rationale = "",
        )
        SET_ALARM -> SensitiveMeta(
            consentKey = "tool:$SET_ALARM",
            consentTitle = "Allow setting alarms and timers?",
            consentDetail = "The assistant wants to open your clock app pre-filled with an alarm or timer. " +
                "You confirm it there.",
            permissions = emptyList(),
            rationale = "",
        )
        OPEN_ON_PHONE -> SensitiveMeta(
            consentKey = "tool:$OPEN_ON_PHONE",
            consentTitle = "Allow opening apps and links?",
            consentDetail = "The assistant wants to open an app or a link on this phone.",
            permissions = emptyList(),
            rationale = "",
        )
        MEDIA_CONTROL -> SensitiveMeta(
            consentKey = "tool:$MEDIA_CONTROL",
            consentTitle = "Allow controlling media playback?",
            consentDetail = "The assistant wants to send play/pause/skip commands to whatever is playing " +
                "on this phone, like pressing a headset button.",
            permissions = emptyList(),
            rationale = "",
        )
        READ_NOTIFICATIONS -> SensitiveMeta(
            consentKey = "tool:$READ_NOTIFICATIONS",
            consentTitle = "Allow reading your notifications?",
            consentDetail = "The assistant wants to read the notifications currently in your status bar. " +
                "Read-only — nothing is dismissed or answered. Also requires Android's notification-access " +
                "grant, which you control in the system settings.",
            permissions = emptyList(),
            rationale = "",
        )
        else -> null
    }

    private suspend fun spawnTask(argumentsJson: String): ToolResult {
        val spawner = taskSpawner
            ?: return ToolResult("spawn-task is not available right now", isError = true)
        val title = ToolArgs.string(argumentsJson, "title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult("spawn-task requires a title", isError = true)
        val prompt = ToolArgs.string(argumentsJson, "prompt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult("spawn-task requires a prompt", isError = true)
        return spawner(title, prompt)
    }

    // Result wording follows the CLI's runSaveMemory, adapted to per-message
    // (not per-session) prompt assembly.
    private suspend fun saveMemory(argumentsJson: String): ToolResult {
        val fact = ToolArgs.string(argumentsJson, "fact")
            ?: return ToolResult("save-memory requires a fact", isError = true)
        return when (val outcome = memory.save(fact)) {
            is to.eyed.spettro.chat.data.memory.MemorySaveOutcome.New ->
                ToolResult("saved to memory; it will be in context from the next message on")
            is to.eyed.spettro.chat.data.memory.MemorySaveOutcome.Duplicate ->
                ToolResult("already in memory (\"${outcome.existing}\") — refreshed its last-used date instead of duplicating it")
            is to.eyed.spettro.chat.data.memory.MemorySaveOutcome.Superseded ->
                ToolResult("saved, replacing the similar older memory \"${outcome.old}\"")
            is to.eyed.spettro.chat.data.memory.MemorySaveOutcome.Invalid ->
                ToolResult("save-memory: ${outcome.reason}", isError = true)
        }
    }

    private suspend fun forgetMemory(argumentsJson: String): ToolResult {
        val fact = ToolArgs.string(argumentsJson, "fact")
            ?: return ToolResult("forget-memory requires the fact to remove", isError = true)
        val removed = memory.forget(fact)
        return if (removed.isEmpty()) {
            ToolResult("no memory matches \"$fact\" — nothing was removed", isError = true)
        } else {
            ToolResult("forgot ${removed.size} ${if (removed.size == 1) "memory" else "memories"}:\n" +
                removed.joinToString("\n") { "- $it" })
        }
    }

    private fun commentMessage(argumentsJson: String): String? =
        ToolArgs.string(argumentsJson, "message")?.take(300)

    private fun quotedArg(argumentsJson: String, key: String): String? =
        ToolArgs.string(argumentsJson, key)?.let { "“${it.take(60)}”" }

    private fun hostArg(argumentsJson: String): String? =
        ToolArgs.string(argumentsJson, "url")?.let { runCatching { URL(it).host }.getOrNull() }
}

package to.eyed.spettro.chat.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import to.eyed.spettro.chat.data.DocumentUtil
import to.eyed.spettro.chat.data.ImageUtil
import to.eyed.spettro.chat.data.SpeechTranscriber
import to.eyed.spettro.chat.data.TtsSpeaker
import to.eyed.spettro.chat.data.api.SpettroApi
import to.eyed.spettro.chat.data.store.StoredFile
import to.eyed.spettro.chat.ui.components.surfaceCard
import to.eyed.spettro.chat.ui.settings.SettingsSheet
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.vm.AppViewModel
import to.eyed.spettro.chat.vm.ChatViewModel
import to.eyed.spettro.chat.vm.StreamState

/**
 * Window width from which the sidebar becomes a permanent pane (Material 3
 * "expanded" class: tablets and unfolded foldables in landscape).
 */
internal val ExpandedNavMinWidth = 840.dp

/** Readable ceiling for the conversation column on wide windows. */
internal val ChatContentMaxWidth = 800.dp

@Composable
fun ChatRoot(
    appVm: AppViewModel,
    chatVm: ChatViewModel,
    onOpenUrl: (String) -> Unit,
) {
    val models by appVm.models.collectAsState()
    val selectedModelId by appVm.selectedModel.collectAsState()
    val thinking by appVm.thinkingLevel.collectAsState()
    val email by appVm.cachedEmail.collectAsState()
    val plan by appVm.cachedPlan.collectAsState()
    val account by appVm.account.collectAsState()

    val conversations by chatVm.conversations.collectAsState()
    val activeId by chatVm.activeId.collectAsState()
    val stream by chatVm.stream.collectAsState()
    val askForm by chatVm.askForm.collectAsState()
    val consentPending by chatVm.consentPending.collectAsState()
    val permissionPending by chatVm.permissionPending.collectAsState()
    val tempChat by chatVm.tempChat.collectAsState()
    val isTemporary by chatVm.isTemporary.collectAsState()

    val selectedModel = models.firstOrNull { it.id == selectedModelId }
    val activeConv = tempChat?.takeIf { it.id == activeId }
        ?: conversations.firstOrNull { it.id == activeId }
    val messages = activeConv?.messages ?: emptyList()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var attachments by remember { mutableStateOf(listOf<PendingImage>()) }
    var fileAttachments by remember { mutableStateOf(listOf<StoredFile>()) }
    var showSettings by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showMcpSheet by remember { mutableStateOf(false) }
    var showSkillsSheet by remember { mutableStateOf(false) }
    var showSkillPicker by remember { mutableStateOf(false) }
    var showMemorySheet by remember { mutableStateOf(false) }
    var showScheduledTasks by remember { mutableStateOf(false) }

    // Skills: the active one comes from the conversation (or, before the
    // first message, from the engine's pending pick).
    val allSkills by chatVm.skills.collectAsState(initial = emptyList())
    val pendingSkillId by chatVm.pendingSkillId.collectAsState()
    val listState = rememberLazyListState()

    val exportChatsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(chatVm::exportChats) }
    // Broad mime list: cloud drives and messengers often relabel .json files.
    val importChatsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(chatVm::importChats) }

    // Export/import outcomes surface as toasts so they outlive the sheet.
    val dataNotice by chatVm.dataNotice.collectAsState()
    LaunchedEffect(dataNotice) {
        dataNotice?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            chatVm.clearDataNotice()
        }
    }

    // The run's progress/completion notifications need POST_NOTIFICATIONS on
    // 33+; asked at first send, and denial never blocks the run itself.
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* the FGS runs either way; denial only hides its notification */ }
    val ensureNotifPermission: () -> Unit = {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Sensitive tools suspend in the engine until the OS permission dialog
    // resolves; the engine may be running under the service, so the dialog is
    // fired from here whenever a request is pending and we're on screen.
    val runtimePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
        chatVm::resolvePermissions,
    )
    LaunchedEffect(permissionPending) {
        permissionPending?.let { runtimePermissions.launch(it.permissions.toTypedArray()) }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(4),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val processed = uris.mapNotNull { uri ->
                ImageUtil.toDataUrl(context, uri)?.let { PendingImage(it, ImageUtil.decodeDataUrl(it)) }
            }
            attachments = (attachments + processed).take(4)
        }
    }

    // Camera capture goes through a FileProvider cache URI; the string
    // survives the activity being recycled behind the camera app.
    var cameraUriString by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val uri = cameraUriString?.toUri()
        cameraUriString = null
        if (saved && uri != null) {
            scope.launch {
                ImageUtil.toDataUrl(context, uri)?.let {
                    attachments = (attachments + PendingImage(it, ImageUtil.decodeDataUrl(it))).take(4)
                }
            }
        }
    }

    // Documents (PDF/text) are reduced to extracted text on attach; anything
    // unusable surfaces its reason as a toast.
    val attachFile: suspend (android.net.Uri) -> Unit = { uri ->
        try {
            val file = DocumentUtil.extract(context, uri)
            fileAttachments = (fileAttachments + file).take(3)
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context,
                e.message ?: "Couldn't attach that file.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch { uris.take(3).forEach { attachFile(it) } }
    }

    // Composer dictation: the transcriber accumulates speech while the
    // composer shows the waveform pill; confirming appends the transcript
    // after whatever is already typed.
    val transcriber = remember { SpeechTranscriber(context.applicationContext) }
    // Read-aloud for assistant messages, on the platform TTS service.
    val ttsSpeaker = remember { TtsSpeaker(context.applicationContext) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            transcriber.destroy()
            ttsSpeaker.release()
        }
    }
    val speakingKey by ttsSpeaker.speakingKey.collectAsState()
    val ttsError by ttsSpeaker.error.collectAsState()
    LaunchedEffect(ttsError) {
        ttsError?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            ttsSpeaker.consumeError()
        }
    }
    // Switching conversations shouldn't keep the old one talking.
    LaunchedEffect(activeId) { ttsSpeaker.stop() }
    val voice by transcriber.state.collectAsState()
    LaunchedEffect(voice.result) {
        voice.result?.let { text ->
            input = if (input.isBlank()) text else input.trimEnd() + " " + text
            transcriber.consumeResult()
        }
    }
    LaunchedEffect(voice.error) {
        voice.error?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            transcriber.consumeError()
        }
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) transcriber.start()
        else android.widget.Toast.makeText(
            context,
            "Microphone permission is needed to dictate.",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
    val startDictation: () -> Unit = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            transcriber.start()
        } else {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    // Content shared in from other apps: prefill a fresh chat with it.
    val sharedPayload by chatVm.sharedPayload.collectAsState()
    LaunchedEffect(sharedPayload) {
        val payload = sharedPayload ?: return@LaunchedEffect
        chatVm.consumeSharedPayload()
        chatVm.newChat()
        attachments = emptyList()
        fileAttachments = emptyList()
        if (payload.text.isNotEmpty()) input = payload.text
        if (payload.imageUris.isNotEmpty()) {
            val processed = payload.imageUris.mapNotNull { uri ->
                ImageUtil.toDataUrl(context, uri)?.let { PendingImage(it, ImageUtil.decodeDataUrl(it)) }
            }
            attachments = processed.take(4)
        }
        payload.fileUris.take(3).forEach { attachFile(it) }
    }

    // The list is reversed (index 0 = bottom), so following the stream just
    // means snapping to 0 - and only when the user is already near the
    // bottom, so scrolling back to read isn't fought.
    LaunchedEffect(messages.size, stream, askForm, consentPending) {
        if (listState.isScrollInProgress) return@LaunchedEffect
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Wide windows (tablets, unfolded foldables in landscape) get the
        // sidebar as a permanent pane; phones keep the modal drawer.
        val expandedNav = maxWidth >= ExpandedNavMinWidth
        var paneVisible by rememberSaveable { mutableStateOf(true) }

        // In the permanent pane, picking a chat keeps the sidebar in place;
        // in the drawer it dismisses it.
        val closeNav: () -> Unit = {
            if (!expandedNav) scope.launch { drawerState.close() }
        }

        val backgroundTasks by chatVm.backgroundTasks.collectAsState()
        val allStreams by chatVm.streams.collectAsState()
        val streamingIds = remember(allStreams) {
            allStreams.filterValues { it !is StreamState.Error }.keys
        }
        val sidebar: @Composable () -> Unit = {
            Sidebar(
                conversations = conversations,
                activeId = activeId,
                streamingIds = streamingIds,
                email = email,
                plan = plan,
                tasks = backgroundTasks,
                onTaskClick = { task ->
                    task.conversationId?.let {
                        chatVm.selectChat(it)
                        closeNav()
                    }
                },
                onTaskDismiss = chatVm::dismissTask,
                onSelect = {
                    chatVm.selectChat(it)
                    closeNav()
                },
                onNewChat = {
                    chatVm.newChat()
                    closeNav()
                },
                onTogglePin = chatVm::togglePin,
                onArchive = chatVm::archive,
                onRestore = chatVm::restore,
                onDelete = chatVm::delete,
                onOpenSettings = {
                    closeNav()
                    showSettings = true
                },
                onCollapse = {
                    if (expandedNav) paneVisible = false
                    else scope.launch { drawerState.close() }
                },
            )
        }

        val chatContent: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize()) {
                TopNav(
                    isTemporary = isTemporary,
                    onOpenDrawer = {
                        if (expandedNav) paneVisible = !paneVisible
                        else scope.launch { drawerState.open() }
                    },
                    onToggleTemporary = chatVm::toggleTemporaryChat,
                )
                val streamingAnimationsOn by appVm.streamingAnimations.collectAsState()
                val hapticsOn by appVm.hapticFeedback.collectAsState()
                val haptics = LocalHapticFeedback.current
                Box(Modifier.weight(1f)) {
                    MessagesList(
                        messages = messages,
                        stream = stream,
                        askForm = askForm,
                        consentRequest = consentPending,
                        listState = listState,
                        animations = streamingAnimationsOn,
                        isTemporary = isTemporary,
                        onRegenerate = { chatVm.regenerate(selectedModel, thinking) },
                        onSubmitAnswers = chatVm::submitAnswers,
                        onDeclineQuestions = chatVm::declineQuestions,
                        onConsentDecision = chatVm::resolveConsent,
                        speakingKey = speakingKey,
                        onToggleSpeak = ttsSpeaker::toggle,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Near the model's context ceiling the composer is replaced
                    // by a hard stop: compact the chat or start a new one.
                    val nearLimit = selectedModel != null && stream is StreamState.Idle &&
                        to.eyed.spettro.chat.vm.ContextEstimator.isNearLimit(messages, selectedModel.contextWindow)
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .widthIn(max = ChatContentMaxWidth),
                    ) {
                        if (nearLimit) {
                            ContextLimitPanel(
                                modelName = modelDisplayName(selectedModel!!.id),
                                onCompact = { chatVm.compact(selectedModel) },
                                onNewChat = { chatVm.newChat() },
                            )
                        } else {
                            val activeSkillId = activeConv?.skillId ?: pendingSkillId
                            val activeSkill = allSkills.firstOrNull { it.id == activeSkillId }
                            Column {
                                // Typing "/xxx" filters the skills; tapping one
                                // applies it to this conversation.
                                val slashQuery = input
                                    .takeIf { it.startsWith("/") && !it.contains(' ') && !it.contains('\n') }
                                    ?.removePrefix("/")
                                if (slashQuery != null) {
                                    val matches = allSkills.filter { it.slug.startsWith(slashQuery.lowercase()) }
                                    if (matches.isNotEmpty()) {
                                        SlashSuggestions(matches) { skill ->
                                            chatVm.setConversationSkill(skill.id)
                                            input = ""
                                        }
                                    }
                                }
                                InputBar(
                                    value = input,
                                    onValueChange = { input = it },
                                    attachments = attachments,
                                    files = fileAttachments,
                                    onCapturePhoto = {
                                        val uri = ImageUtil.newCameraUri(context)
                                        cameraUriString = uri.toString()
                                        cameraLauncher.launch(uri)
                                    },
                                    onPickPhotos = {
                                        photoPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    },
                                    onPickFiles = {
                                        filePicker.launch(
                                            arrayOf(
                                                "application/pdf", "text/*", "application/json",
                                                "application/xml", "application/octet-stream",
                                            ),
                                        )
                                    },
                                    onRemoveImage = { i ->
                                        attachments = attachments.filterIndexed { idx, _ -> idx != i }
                                    },
                                    onRemoveFile = { i ->
                                        fileAttachments = fileAttachments.filterIndexed { idx, _ -> idx != i }
                                    },
                                    canAttachImages = selectedModel?.vision == true,
                                    modelName = selectedModel?.let { modelDisplayName(it.id) },
                                    effortLabel = if (selectedModel?.reasoning == true) thinking.label else null,
                                    skillChip = activeSkill?.let { it.emoji to it.name },
                                    onOpenSkillPicker = { showSkillPicker = true },
                                    onClearSkill = { chatVm.setConversationSkill(null) },
                                    onOpenModelSheet = { showModelSheet = true },
                                    onSend = {
                                        if (hapticsOn) {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        ensureNotifPermission()
                                        // A leading /slug applies that skill and
                                        // is stripped from the message.
                                        var text = input
                                        if (text.startsWith("/")) {
                                            val token = text.removePrefix("/").substringBefore(' ').substringBefore('\n')
                                            allSkills.firstOrNull { it.slug == token.lowercase() }?.let { s ->
                                                chatVm.setConversationSkill(s.id)
                                                text = text.removePrefix("/$token").trim()
                                            }
                                        }
                                        chatVm.send(
                                            text,
                                            attachments.map { it.dataUrl },
                                            fileAttachments,
                                            selectedModel,
                                            thinking,
                                        )
                                        input = ""
                                        attachments = emptyList()
                                        fileAttachments = emptyList()
                                    },
                                    onStop = chatVm::stopStreaming,
                                    isStreaming = stream is StreamState.Thinking ||
                                        stream is StreamState.Streaming ||
                                        stream is StreamState.RateLimited ||
                                        stream is StreamState.Compacting,
                                    isRecording = voice.active,
                                    voiceLevels = voice.levels,
                                    onStartVoice = startDictation,
                                    onCancelVoice = transcriber::cancel,
                                    onConfirmVoice = transcriber::finish,
                                )
                            }
                        }
                    }

                    // Error notice
                    val err = stream as? StreamState.Error
                    if (err != null) {
                        Row(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(16.dp)
                                .widthIn(max = ChatContentMaxWidth)
                                .surfaceCard(RoundedCornerShape(Radii.row), fill = Ink.Surface)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { chatVm.dismissError() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(err.message, fontSize = 12.sp, color = Ink.I100, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(12.dp))
                            Text("DISMISS", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Ink.I500)
                        }
                    }
                }
            }
        }

        if (expandedNav) {
            Row(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = paneVisible,
                    enter = expandHorizontally(),
                    exit = shrinkHorizontally(),
                ) {
                    sidebar()
                }
                Box(Modifier.weight(1f)) { chatContent() }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                scrimColor = Color.Black.copy(alpha = 0.6f),
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = Color.Transparent,
                        drawerShape = RoundedCornerShape(0.dp),
                        windowInsets = WindowInsets(0),
                    ) {
                        sidebar()
                    }
                },
            ) {
                chatContent()
            }
        }
    }

    if (showModelSheet) {
        ModelSheet(
            models = models,
            selectedModelId = selectedModelId,
            onSelectModel = appVm::selectModel,
            thinking = thinking,
            onSelectThinking = appVm::setThinkingLevel,
            chatHasImages = messages.any { it.images.isNotEmpty() },
            onDismiss = { showModelSheet = false },
        )
    }

    if (showSettings) {
        val streamingAnimations by appVm.streamingAnimations.collectAsState()
        val haptics by appVm.hapticFeedback.collectAsState()
        val autoCompact by appVm.autoCompact.collectAsState()
        val grantedKeys by chatVm.alwaysAllowedConsents.collectAsState(initial = emptySet())
        // Re-read on open: the tool may have scheduled tasks since, and the
        // user may have flipped notification access in the OS settings.
        LaunchedEffect(Unit) { chatVm.refreshScheduledTasks() }
        val notificationAccess = remember {
            to.eyed.spettro.chat.data.tools.SpettroNotificationListener.isEnabled(context)
        }
        // Recall stats + generated files come straight from the container:
        // one-shot reads on open.
        val container = remember(context) { to.eyed.spettro.chat.data.AppContainer.get(context) }
        val recallIndexedCount by produceState(initialValue = 0) {
            value = runCatching { container.recall.indexedCount() }.getOrDefault(0)
        }
        var artifactStats by remember {
            mutableStateOf(container.artifacts.count() to container.artifacts.totalBytes())
        }
        SettingsSheet(
            account = account,
            email = email,
            plan = plan,
            streamingAnimations = streamingAnimations,
            hapticFeedback = haptics,
            autoCompact = autoCompact,
            toolGrants = grantedKeys.sorted().map { it to consentLabel(it) },
            onRevokeConsent = chatVm::revokeConsent,
            mcpServerCount = chatVm.mcpServers.collectAsState().value.size,
            onOpenMcpServers = { showMcpSheet = true },
            skillCount = allSkills.size,
            onOpenSkills = { showSkillsSheet = true },
            memoryCount = chatVm.memories.collectAsState(initial = emptyList()).value.size,
            onOpenMemory = { showMemorySheet = true },
            recallIndexedCount = recallIndexedCount,
            artifactCount = artifactStats.first,
            artifactBytes = artifactStats.second,
            onClearArtifacts = {
                container.artifacts.clear()
                artifactStats = 0 to 0L
            },
            scheduledTaskCount = chatVm.scheduledTasks.collectAsState().value.size,
            onOpenScheduledTasks = { showScheduledTasks = true },
            notificationAccessEnabled = notificationAccess,
            onOpenNotificationAccess = {
                runCatching {
                    context.startActivity(
                        to.eyed.spettro.chat.data.tools.SpettroNotificationListener.settingsIntent(),
                    )
                }
            },
            onSetStreamingAnimations = appVm::setStreamingAnimations,
            onSetHapticFeedback = appVm::setHapticFeedback,
            onSetAutoCompact = appVm::setAutoCompact,
            // Pricing and billing live on spettro.app; hand off to the browser.
            onManageSubscription = { onOpenUrl(SpettroApi.PRICING_URL) },
            onExportChats = {
                val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date())
                exportChatsLauncher.launch("spettro-backup-$date.json")
            },
            onImportChats = {
                importChatsLauncher.launch(
                    arrayOf("application/json", "application/octet-stream", "text/plain"),
                )
            },
            onDeleteAllChats = { chatVm.deleteAll() },
            onSignOut = {
                showSettings = false
                appVm.signOut()
            },
            onDismiss = { showSettings = false },
        )
    }

    if (showScheduledTasks) {
        val scheduled by chatVm.scheduledTasks.collectAsState()
        to.eyed.spettro.chat.ui.settings.ScheduledTasksSheet(
            tasks = scheduled,
            onCancel = chatVm::cancelScheduledTask,
            onDismiss = { showScheduledTasks = false },
        )
    }

    if (showMemorySheet) {
        val memories by chatVm.memories.collectAsState(initial = emptyList())
        to.eyed.spettro.chat.ui.settings.MemorySheet(
            memories = memories,
            onAdd = chatVm::addMemory,
            onUpdate = chatVm::updateMemory,
            onDelete = chatVm::deleteMemory,
            onClearAll = chatVm::clearMemories,
            onDismiss = { showMemorySheet = false },
        )
    }

    if (showSkillPicker) {
        SkillPickerSheet(
            skills = allSkills,
            selectedId = activeConv?.skillId ?: pendingSkillId,
            onSelect = chatVm::setConversationSkill,
            onDismiss = { showSkillPicker = false },
        )
    }

    if (showSkillsSheet) {
        val saveError by chatVm.skillSaveError.collectAsState()
        to.eyed.spettro.chat.ui.settings.SkillsSheet(
            skills = allSkills,
            saveError = saveError,
            onSave = chatVm::saveSkill,
            onClearSaveError = chatVm::clearSkillSaveError,
            onDelete = chatVm::deleteSkill,
            newId = chatVm::newSkillId,
            onDismiss = { showSkillsSheet = false },
        )
    }

    if (showMcpSheet) {
        val servers by chatVm.mcpServers.collectAsState()
        val toolsByServer by chatVm.mcpToolsByServer.collectAsState()
        val errors by chatVm.mcpErrors.collectAsState()
        to.eyed.spettro.chat.ui.settings.McpServersSheet(
            servers = servers,
            toolCounts = toolsByServer.mapValues { it.value.size },
            errors = errors,
            onSave = chatVm::saveMcpServer,
            onRemove = chatVm::removeMcpServer,
            onSetEnabled = chatVm::setMcpEnabled,
            onRefresh = chatVm::refreshMcpTools,
            newId = chatVm::newMcpId,
            onDismiss = { showMcpSheet = false },
        )
    }
}

/** The /slug autocomplete panel shown above the composer. */
@Composable
private fun SlashSuggestions(
    matches: List<to.eyed.spettro.chat.data.skills.Skill>,
    onPick: (to.eyed.spettro.chat.data.skills.Skill) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .surfaceCard(RoundedCornerShape(Radii.card), fill = Ink.Surface)
            .padding(vertical = 4.dp),
    ) {
        matches.take(4).forEach { skill ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onPick(skill) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(skill.emoji, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))
                Text("/${skill.slug}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink.White)
                Spacer(Modifier.width(10.dp))
                Text(
                    skill.description.ifBlank { skill.name },
                    fontSize = 11.sp,
                    color = Ink.I500,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/** Display label for a persisted consent grant in Settings. */
private fun consentLabel(key: String): String = when (key) {
    "tool:calendar-events" -> "Calendar"
    "tool:contacts-search" -> "Contacts"
    "tool:set-reminder" -> "Reminders"
    "tool:get-location" -> "Location"
    "tool:scheduled-tasks" -> "Scheduled tasks"
    "tool:compose-message" -> "Composing messages"
    "tool:set-alarm" -> "Alarms & timers"
    "tool:open-on-phone" -> "Opening apps & links"
    "tool:media-control" -> "Media control"
    "tool:read-notifications" -> "Reading notifications"
    else -> if (key.startsWith("mcp:")) "MCP server (${key.removePrefix("mcp:")})" else key
}

/**
 * Hard stop shown instead of the composer when the conversation is close to
 * the selected model's context window.
 */
@Composable
private fun ContextLimitPanel(
    modelName: String,
    onCompact: () -> Unit,
    onNewChat: () -> Unit,
) {
    val canvas = MaterialTheme.colorScheme.background
    Column(Modifier.fillMaxWidth().background(canvas).padding(12.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .surfaceCard(RoundedCornerShape(Radii.card), fill = Ink.Surface)
                .padding(20.dp),
        ) {
            Text(
                "Context limit almost reached",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink.White,
            )
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                "This chat is close to $modelName's context window, so new messages are paused. " +
                    "Compact the conversation into a summary, or start a new chat.",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Ink.I500,
            )
            Spacer(Modifier.padding(top = 10.dp))
            Row {
                to.eyed.spettro.chat.ui.components.PrimaryButton(
                    text = "Compact chat",
                    onClick = onCompact,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                to.eyed.spettro.chat.ui.components.GlassButton(
                    text = "New chat",
                    onClick = onNewChat,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}

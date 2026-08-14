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
import kotlinx.coroutines.launch
import to.eyed.spettro.chat.data.ImageUtil
import to.eyed.spettro.chat.data.api.SpettroApi
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
    var showSettings by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

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

    // The list is reversed (index 0 = bottom), so following the stream just
    // means snapping to 0 - and only when the user is already near the
    // bottom, so scrolling back to read isn't fought.
    LaunchedEffect(messages.size, stream, askForm) {
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

        val sidebar: @Composable () -> Unit = {
            Sidebar(
                conversations = conversations,
                activeId = activeId,
                email = email,
                plan = plan,
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
                        listState = listState,
                        animations = streamingAnimationsOn,
                        isTemporary = isTemporary,
                        onRegenerate = { chatVm.regenerate(selectedModel, thinking) },
                        onSubmitAnswers = chatVm::submitAnswers,
                        onDeclineQuestions = chatVm::declineQuestions,
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
                            InputBar(
                                value = input,
                                onValueChange = { input = it },
                                attachments = attachments,
                                onAddImage = {
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                                onRemoveImage = { i ->
                                    attachments = attachments.filterIndexed { idx, _ -> idx != i }
                                },
                                canAttach = selectedModel?.vision == true,
                                modelName = selectedModel?.let { modelDisplayName(it.id) },
                                effortLabel = if (selectedModel?.reasoning == true) thinking.label else null,
                                onOpenModelSheet = { showModelSheet = true },
                                onSend = {
                                    if (hapticsOn) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    chatVm.send(input, attachments.map { it.dataUrl }, selectedModel, thinking)
                                    input = ""
                                    attachments = emptyList()
                                },
                                onStop = chatVm::stopStreaming,
                                isStreaming = stream is StreamState.Thinking ||
                                    stream is StreamState.Streaming ||
                                    stream is StreamState.RateLimited ||
                                    stream is StreamState.Compacting,
                            )
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
        SettingsSheet(
            account = account,
            email = email,
            plan = plan,
            streamingAnimations = streamingAnimations,
            hapticFeedback = haptics,
            onSetStreamingAnimations = appVm::setStreamingAnimations,
            onSetHapticFeedback = appVm::setHapticFeedback,
            // Pricing and billing live on spettro.app; hand off to the browser.
            onManageSubscription = { onOpenUrl(SpettroApi.PRICING_URL) },
            onDeleteAllChats = { chatVm.deleteAll() },
            onSignOut = {
                showSettings = false
                appVm.signOut()
            },
            onDismiss = { showSettings = false },
        )
    }

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

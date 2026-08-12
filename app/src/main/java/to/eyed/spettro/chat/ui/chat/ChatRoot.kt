package to.eyed.spettro.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import to.eyed.spettro.chat.data.api.SpettroApi
import to.eyed.spettro.chat.ui.components.glassOverlay
import to.eyed.spettro.chat.ui.pricing.PricingScreen
import to.eyed.spettro.chat.ui.settings.SettingsSheet
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.vm.AppViewModel
import to.eyed.spettro.chat.vm.ChatViewModel
import to.eyed.spettro.chat.vm.StreamState

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

    val selectedModel = models.firstOrNull { it.id == selectedModelId }
    val activeConv = conversations.firstOrNull { it.id == activeId }
    val messages = activeConv?.messages ?: emptyList()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showPricing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Follow the stream: keep the newest content visible.
    LaunchedEffect(messages.size, stream) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color.Transparent,
                drawerShape = RoundedCornerShape(0.dp),
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
            ) {
                Sidebar(
                    conversations = conversations,
                    activeId = activeId,
                    email = email,
                    plan = plan,
                    onSelect = {
                        chatVm.selectChat(it)
                        scope.launch { drawerState.close() }
                    },
                    onNewChat = {
                        chatVm.newChat()
                        scope.launch { drawerState.close() }
                    },
                    onTogglePin = chatVm::togglePin,
                    onArchive = chatVm::archive,
                    onRestore = chatVm::restore,
                    onDelete = chatVm::delete,
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        showSettings = true
                    },
                    onCollapse = { scope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            TopNav(
                models = models,
                selectedModelId = selectedModelId,
                onSelectModel = appVm::selectModel,
                thinking = thinking,
                onSelectThinking = appVm::setThinkingLevel,
                showThinking = selectedModel?.reasoning == true,
                email = email,
                plan = plan,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onOpenSettings = { showSettings = true },
                onManageSubscription = { showPricing = true },
                onSignOut = { appVm.signOut() },
            )
            Box(Modifier.weight(1f)) {
                MessagesList(
                    messages = messages,
                    stream = stream,
                    listState = listState,
                    onRegenerate = { chatVm.regenerate(selectedModel, thinking) },
                    onSuggestion = { input = it },
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.align(Alignment.BottomCenter)) {
                    InputBar(
                        value = input,
                        onValueChange = { input = it },
                        onSend = {
                            chatVm.send(input, selectedModel, thinking)
                            input = ""
                        },
                        onStop = chatVm::stopStreaming,
                        isStreaming = stream is StreamState.Thinking ||
                            stream is StreamState.Streaming ||
                            stream is StreamState.RateLimited,
                    )
                }

                // Error notice
                val err = stream as? StreamState.Error
                if (err != null) {
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                            .glassOverlay(RoundedCornerShape(Radii.row))
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

    if (showSettings) {
        val theme by appVm.theme.collectAsState()
        val streamingAnimations by appVm.streamingAnimations.collectAsState()
        val haptics by appVm.hapticFeedback.collectAsState()
        SettingsSheet(
            account = account,
            email = email,
            plan = plan,
            theme = theme,
            streamingAnimations = streamingAnimations,
            hapticFeedback = haptics,
            onSetTheme = appVm::setTheme,
            onSetStreamingAnimations = appVm::setStreamingAnimations,
            onSetHapticFeedback = appVm::setHapticFeedback,
            onManageSubscription = {
                showSettings = false
                showPricing = true
            },
            onDeleteAllChats = { chatVm.deleteAll() },
            onSignOut = {
                showSettings = false
                appVm.signOut()
            },
            onDismiss = { showSettings = false },
        )
    }

    if (showPricing) {
        Box(Modifier.fillMaxSize().background(Ink.Pitch)) {
            PricingScreen(
                currentPlan = plan,
                onUpgrade = { onOpenUrl(SpettroApi.PRICING_URL) },
                onClose = { showPricing = false },
            )
        }
    }
}

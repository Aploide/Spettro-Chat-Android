package to.eyed.spettro.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import to.eyed.spettro.chat.data.AppContainer
import to.eyed.spettro.chat.data.SharedPayload
import to.eyed.spettro.chat.ui.auth.AuthScreen
import to.eyed.spettro.chat.ui.chat.ChatRoot
import to.eyed.spettro.chat.ui.theme.SpettroChatTheme
import to.eyed.spettro.chat.vm.AppViewModel
import to.eyed.spettro.chat.vm.AuthState
import to.eyed.spettro.chat.vm.ChatViewModel

class MainActivity : ComponentActivity() {
    private val appVm: AppViewModel by viewModels { AppViewModel.Factory(AppContainer.get(this)) }
    private val chatVm: ChatViewModel by viewModels { ChatViewModel.Factory(AppContainer.get(this)) }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    /**
     * Content arriving from other apps — the share sheet or the "Ask Spettro"
     * text-selection action — is parked in the share inbox; ChatRoot picks it
     * up and prefills a fresh chat with it.
     */
    private fun handleExternalIntent(intent: Intent?) {
        intent ?: return
        val inbox = AppContainer.get(this).shareInbox
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()
                if (!text.isNullOrEmpty()) inbox.post(SharedPayload(text = text))
            }
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
                val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                val text = when {
                    subject.isEmpty() || body.contains(subject) -> body
                    else -> "$subject\n$body"
                }
                when {
                    stream != null && intent.type.orEmpty().startsWith("image/") ->
                        inbox.post(SharedPayload(text = text, imageUris = listOf(stream)))
                    stream != null -> inbox.post(SharedPayload(text = text, fileUris = listOf(stream)))
                    text.isNotEmpty() -> inbox.post(SharedPayload(text = text))
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streams = IntentCompat
                    .getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.filterNotNull()
                    .orEmpty()
                if (streams.isNotEmpty()) inbox.post(SharedPayload(imageUris = streams))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExternalIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a genuinely new launch: a config change replays the same
        // intent, and the share must not be re-attached on every rotation.
        if (savedInstanceState == null) handleExternalIntent(intent)
        enableEdgeToEdge()
        setContent {
            SpettroChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    val authState by appVm.authState.collectAsState()
                    // safeDrawing already contains the IME inset; adding
                    // imePadding() on top doubles the keyboard padding.
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .fillMaxSize()
                            .safeDrawingPadding(),
                    ) {
                        when (val state = authState) {
                            is AuthState.Loading -> Unit // brand-black splash
                            is AuthState.SignedOut -> AuthScreen(
                                login = state.login,
                                onSignIn = appVm::signInWith,
                                onCancel = appVm::cancelLogin,
                            )
                            is AuthState.SignedIn -> ChatRoot(
                                appVm = appVm,
                                chatVm = chatVm,
                                onOpenUrl = ::openUrl,
                            )
                        }
                    }
                }
            }
        }
    }
}

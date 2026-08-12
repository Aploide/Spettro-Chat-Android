package to.eyed.spettro.chat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import to.eyed.spettro.chat.data.AppContainer
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by appVm.theme.collectAsState()
            SpettroChatTheme(charcoal = theme == "charcoal") {
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
                                onSignIn = { appVm.startLogin(::openUrl) },
                                onCancel = appVm::cancelLogin,
                                onOpenAgain = ::openUrl,
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

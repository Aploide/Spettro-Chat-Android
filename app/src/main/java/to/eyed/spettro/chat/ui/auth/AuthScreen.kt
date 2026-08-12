package to.eyed.spettro.chat.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.ui.components.GlassButton
import to.eyed.spettro.chat.ui.components.LiquidThinking
import to.eyed.spettro.chat.ui.components.PrimaryButton
import to.eyed.spettro.chat.ui.components.surfaceLow
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.vm.LoginFlow

/**
 * Sign-in screen. The backend uses a browser device flow (no passwords):
 * we register a session, open the returned URL, and poll until complete.
 */
@Composable
fun AuthScreen(
    login: LoginFlow,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onOpenAgain: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 400.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .surfaceLow(RoundedCornerShape(Radii.card)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(30.dp), tint = Ink.White)
            }
            Spacer(Modifier.height(24.dp))
            Text("Spettro Chat", style = MaterialTheme.typography.headlineSmall, color = Ink.White)
            Spacer(Modifier.height(8.dp))

            AnimatedContent(
                targetState = login,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "loginState",
            ) { state ->
                when (state) {
                    is LoginFlow.Idle -> IdleContent(onSignIn)
                    is LoginFlow.Starting -> WaitingContent(
                        title = "Connecting",
                        subtitle = "Preparing your sign-in link…",
                        onCancel = onCancel,
                    )
                    is LoginFlow.WaitingBrowser -> WaitingContent(
                        title = "Authenticating",
                        subtitle = "Finish signing in from your browser. This screen updates automatically.",
                        onCancel = onCancel,
                        onOpenAgain = { onOpenAgain(state.browserUrl) },
                    )
                    is LoginFlow.Expired -> ErrorContent(
                        "The login link expired — please try again.",
                        onSignIn,
                    )
                    is LoginFlow.Error -> ErrorContent(state.message, onSignIn)
                }
            }
        }
    }
}

@Composable
private fun IdleContent(onSignIn: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Welcome back. Sign in to continue the conversation.",
            color = Ink.I500,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton(
            text = "Sign in with browser",
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "A secure sign-in page opens in your browser.\nNo passwords are stored in the app.",
            color = Ink.I500,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "By continuing you agree to the Terms and Privacy Policy.",
            color = Ink.I500,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WaitingContent(
    title: String,
    subtitle: String,
    onCancel: () -> Unit,
    onOpenAgain: (() -> Unit)? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        LiquidThinking(size = 44.dp)
        Spacer(Modifier.height(20.dp))
        Text(title, color = Ink.I100, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            color = Ink.I500,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onOpenAgain != null) {
                GlassButton("Open browser again", onClick = onOpenAgain)
            }
            GlassButton("Cancel", onClick = onCancel, textColor = Ink.I300)
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            message,
            color = Ink.I300,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Try again", onClick = onRetry, modifier = Modifier.fillMaxWidth())
    }
}

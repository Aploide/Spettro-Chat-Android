package to.eyed.spettro.chat.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.tooling.preview.Preview
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles
import to.eyed.spettro.chat.R
import to.eyed.spettro.chat.ui.components.GlassButton
import to.eyed.spettro.chat.ui.components.LiquidThinking
import to.eyed.spettro.chat.ui.components.snappySpring
import to.eyed.spettro.chat.ui.components.surfaceLow
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.ui.theme.SpettroChatTheme
import to.eyed.spettro.chat.vm.AuthProvider
import to.eyed.spettro.chat.vm.LoginFlow

/**
 * Sign-in screen. Social login (Google/GitHub) runs in-app through Clerk;
 * after the OAuth redirect the app mints its ep_ API key from spettro.app.
 */
@Composable
fun AuthScreen(
    login: LoginFlow,
    onSignIn: (AuthProvider) -> Unit,
    onCancel: () -> Unit,
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
                Icon(Lucide.Sparkles, null, Modifier.size(30.dp), tint = Ink.White)
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
                    is LoginFlow.Authorizing -> WaitingContent(
                        title = "Signing in with ${state.provider.label}",
                        subtitle = "Finish in the browser window — you'll be brought right back.",
                        onCancel = onCancel,
                    )
                    is LoginFlow.LinkingAccount -> WaitingContent(
                        title = "Setting up your account",
                        subtitle = "Linking your Spettro account…",
                        onCancel = null,
                    )
                    is LoginFlow.Error -> ErrorContent(state.message, onCancel)
                }
            }
        }
    }
}

@Composable
private fun IdleContent(onSignIn: (AuthProvider) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Welcome back. Sign in to continue the conversation.",
            color = Ink.I500,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(32.dp))
        ProviderButton(
            text = "Continue with Google",
            iconRes = R.drawable.ic_google,
            onClick = { onSignIn(AuthProvider.Google) },
        )
        Spacer(Modifier.height(12.dp))
        ProviderButton(
            text = "Continue with GitHub",
            iconRes = R.drawable.ic_github,
            onClick = { onSignIn(AuthProvider.GitHub) },
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Sign-in happens in a secure browser window.\nNo passwords are stored in the app.",
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
private fun ProviderButton(
    text: String,
    iconRes: Int,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, snappySpring(), label = "scale")
    val bg by animateColorAsState(if (pressed) Ink.SurfaceHigh else Ink.SurfaceLow, tween(150), label = "bg")
    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 15.dp, horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            Modifier.size(18.dp),
            tint = Ink.White,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = Ink.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WaitingContent(
    title: String,
    subtitle: String,
    onCancel: (() -> Unit)?,
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
        if (onCancel != null) {
            Spacer(Modifier.height(28.dp))
            GlassButton("Cancel", onClick = onCancel, textColor = Ink.I300)
        }
    }
}

@Composable
private fun ErrorContent(message: String, onBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            message,
            color = Ink.I300,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        GlassButton("Try again", onClick = onBack)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun AuthScreenPreview() {
    SpettroChatTheme {
        AuthScreen(
            login = LoginFlow.Idle,
            onSignIn = {},
            onCancel = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun AuthScreenAuthorizingPreview() {
    SpettroChatTheme {
        AuthScreen(
            login = LoginFlow.Authorizing(AuthProvider.Google),
            onSignIn = {},
            onCancel = {}
        )
    }
}

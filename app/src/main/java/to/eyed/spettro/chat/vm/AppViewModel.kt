package to.eyed.spettro.chat.vm

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clerk.api.Clerk
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.network.serialization.errorMessage
import com.clerk.api.signin.SignIn
import com.clerk.api.signup.SignUp
import com.clerk.api.sso.OAuthProvider
import com.clerk.api.sso.ResultType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.eyed.spettro.chat.SpettroChatApp
import to.eyed.spettro.chat.data.AppContainer
import to.eyed.spettro.chat.data.api.Account
import to.eyed.spettro.chat.data.api.ModelInfo
import to.eyed.spettro.chat.data.api.UnauthorizedException

/** Effort levels shown in the UI, mapped to the wire's reasoning_effort. */
enum class ThinkingLevel(
    val id: String,
    val label: String,
    val description: String,
    val effort: String?,
    val isDefault: Boolean = false,
) {
    Low("low", "Low", "Quick answers to simple questions", "low"),
    Medium("medium", "Medium", "Light, everyday tasks", "medium"),
    High("high", "High", "Balanced for daily work", "high", isDefault = true),
    Extra("extra", "Extra", "Complex, detailed work", "xhigh"),
    Max("max", "Max", "The hardest problems. Takes longer.", "xhigh"),
    ;

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: High
    }
}

/** Social providers offered on the sign-in screen (mirrors spettro.app). */
enum class AuthProvider(val label: String) {
    Google("Google"),
    GitHub("GitHub"),
    ;

    internal val oauth: OAuthProvider
        get() = when (this) {
            Google -> OAuthProvider.GOOGLE
            GitHub -> OAuthProvider.GITHUB
        }
}

sealed interface LoginFlow {
    data object Idle : LoginFlow
    /** The Clerk OAuth flow is running (Custom Tab open, waiting for the redirect). */
    data class Authorizing(val provider: AuthProvider) : LoginFlow
    /** OAuth finished; syncing the account and minting the ep_ API key. */
    data object LinkingAccount : LoginFlow
    data class Error(val message: String) : LoginFlow
}

sealed interface AuthState {
    data object Loading : AuthState
    data class SignedOut(val login: LoginFlow = LoginFlow.Idle) : AuthState
    data object SignedIn : AuthState
}

class AppViewModel(private val container: AppContainer) : ViewModel() {
    private val api = container.api
    private val webApi = container.webApi
    private val prefs = container.prefs

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    /** Cached account values for instant paint before the network refresh. */
    private val _cachedEmail = MutableStateFlow("")
    val cachedEmail: StateFlow<String> = _cachedEmail.asStateFlow()
    private val _cachedPlan = MutableStateFlow("")
    val cachedPlan: StateFlow<String> = _cachedPlan.asStateFlow()

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _selectedModel = MutableStateFlow("")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _thinkingLevel = MutableStateFlow(ThinkingLevel.High)
    val thinkingLevel: StateFlow<ThinkingLevel> = _thinkingLevel.asStateFlow()

    private val _streamingAnimations = MutableStateFlow(true)
    val streamingAnimations: StateFlow<Boolean> = _streamingAnimations.asStateFlow()

    private val _hapticFeedback = MutableStateFlow(true)
    val hapticFeedback: StateFlow<Boolean> = _hapticFeedback.asStateFlow()

    private val _autoCompact = MutableStateFlow(true)
    val autoCompact: StateFlow<Boolean> = _autoCompact.asStateFlow()

    private var loginJob: Job? = null

    init {
        viewModelScope.launch {
            val snapshot = prefs.load()
            _cachedEmail.value = snapshot.email
            _cachedPlan.value = snapshot.plan
            // Paint the cached account and model list immediately; the network
            // refresh below replaces them when (and if) it succeeds.
            _account.value = snapshot.account
            _models.value = snapshot.models
            _selectedModel.value = snapshot.selectedModel
            _thinkingLevel.value = ThinkingLevel.fromId(snapshot.thinkingLevel)
            _streamingAnimations.value = snapshot.streamingAnimations
            _hapticFeedback.value = snapshot.hapticFeedback
            _autoCompact.value = snapshot.autoCompact
            if (snapshot.apiKey != null) {
                _authState.value = AuthState.SignedIn
                refreshAccountAndModels()
            } else {
                _authState.value = AuthState.SignedOut()
            }
        }
        viewModelScope.launch {
            container.unauthorized.collect { signOut(local = true) }
        }
        // A backup import rewrites prefs behind this ViewModel's back; reload
        // the UI-facing settings so the change shows without a restart.
        viewModelScope.launch {
            container.settingsChanged.collect {
                val s = prefs.load()
                if (s.selectedModel.isNotBlank()) _selectedModel.value = s.selectedModel
                _thinkingLevel.value = ThinkingLevel.fromId(s.thinkingLevel)
                _streamingAnimations.value = s.streamingAnimations
                _hapticFeedback.value = s.hapticFeedback
                _autoCompact.value = s.autoCompact
            }
        }
    }

    /**
     * In-app social login via Clerk: run the OAuth flow (Custom Tab), then
     * exchange the Clerk session for an ep_ API key minted by spettro.app.
     */
    fun signInWith(provider: AuthProvider) {
        if (loginJob?.isActive == true) return
        loginJob = viewModelScope.launch {
            if (!SpettroChatApp.isClerkConfigured) {
                _authState.value = AuthState.SignedOut(
                    LoginFlow.Error("This build was compiled without a Clerk publishable key, so sign-in is disabled. See app/build.gradle.kts."),
                )
                return@launch
            }
            _authState.value = AuthState.SignedOut(LoginFlow.Authorizing(provider))

            // A previous run may have completed the Clerk session without ever
            // minting a key (e.g. the exchange failed); reuse it if it's alive.
            if (sessionToken() != null) {
                completeSignIn()
                return@launch
            }

            when (val result = Clerk.auth.signInWithOAuth(provider.oauth)) {
                is ClerkResult.Success -> {
                    val complete = when (result.value.resultType) {
                        ResultType.SIGN_IN -> result.value.signIn?.status == SignIn.Status.COMPLETE
                        ResultType.SIGN_UP -> result.value.signUp?.status == SignUp.Status.COMPLETE
                        ResultType.UNKNOWN -> false
                    }
                    if (complete) {
                        completeSignIn()
                    } else {
                        _authState.value = AuthState.SignedOut(
                            LoginFlow.Error("Your account needs extra verification steps — please sign in once on spettro.app first."),
                        )
                    }
                }
                is ClerkResult.Failure -> {
                    val message = result.errorMessage
                    // The user closing the browser tab is not an error.
                    if (message.contains("cancel", ignoreCase = true)) {
                        _authState.value = AuthState.SignedOut()
                    } else {
                        _authState.value = AuthState.SignedOut(LoginFlow.Error(message))
                    }
                }
            }
        }
    }

    private suspend fun sessionToken(): String? =
        (Clerk.auth.getToken() as? ClerkResult.Success)?.value?.takeIf { it.isNotBlank() }

    /** Clerk session established — sync the user row and mint the ep_ key. */
    private suspend fun completeSignIn() {
        _authState.value = AuthState.SignedOut(LoginFlow.LinkingAccount)
        val token = sessionToken()
        if (token == null) {
            _authState.value = AuthState.SignedOut(LoginFlow.Error("could not read the sign-in session — please try again"))
            return
        }
        // Creates the users row + free subscription on first login. Best-effort:
        // existing users are fine even if this call fails transiently.
        runCatching { webApi.syncUser(token) }
        val grant = try {
            webApi.generateApiKey(token, "Android · ${Build.MANUFACTURER} ${Build.MODEL}")
        } catch (e: Exception) {
            _authState.value = AuthState.SignedOut(LoginFlow.Error(e.message ?: "could not link your account — please try again"))
            return
        }
        // Persist synchronously: the raw key is returned exactly once.
        prefs.saveApiKeyBlocking(grant.key, grant.id.takeIf { it.isNotBlank() })
        _authState.value = AuthState.SignedIn
        refreshAccountAndModels()
    }

    fun cancelLogin() {
        loginJob?.cancel()
        loginJob = null
        _authState.value = AuthState.SignedOut()
    }

    fun refreshAccountAndModels() {
        viewModelScope.launch {
            // Models first; a failure here must not unwind the sign-in.
            try {
                val list = api.listModels()
                _models.value = list
                prefs.saveModels(list)
                // Server order matters: data[0] is the plan default.
                if (_selectedModel.value.isBlank() || list.none { it.id == _selectedModel.value }) {
                    list.firstOrNull()?.let { selectModel(it.id) }
                }
            } catch (e: UnauthorizedException) {
                signOut(local = true)
                return@launch
            } catch (_: Exception) {
            }
            try {
                val acct = api.account()
                _account.value = acct
                _cachedEmail.value = acct.email
                _cachedPlan.value = acct.planOrFree
                prefs.saveAccount(acct)
            } catch (e: UnauthorizedException) {
                signOut(local = true)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Sign out. [local] marks a forced sign-out (401): local state is cleared but
     * the Clerk session is kept so signing back in is a single tap. A user-initiated
     * sign-out also revokes the ep_ key on the server and ends the Clerk session,
     * both best-effort.
     */
    fun signOut(local: Boolean = false) {
        viewModelScope.launch {
            if (!local && SpettroChatApp.isClerkConfigured) {
                runCatching {
                    val keyId = prefs.apiKeyId
                    if (keyId != null) {
                        sessionToken()?.let { webApi.revokeApiKey(it, keyId) }
                    }
                }
                runCatching { Clerk.auth.signOut() }
            }
            prefs.clearApiKeyAndAccount()
            _account.value = null
            _cachedEmail.value = ""
            _cachedPlan.value = ""
            _models.value = emptyList()
            _selectedModel.value = ""
            _authState.value = AuthState.SignedOut(
                if (local) LoginFlow.Error("Your session is no longer valid — please sign in again.") else LoginFlow.Idle,
            )
        }
    }

    fun selectModel(id: String) {
        _selectedModel.value = id
        viewModelScope.launch { prefs.saveSelectedModel(id) }
    }

    fun setThinkingLevel(level: ThinkingLevel) {
        _thinkingLevel.value = level
        viewModelScope.launch { prefs.saveThinkingLevel(level.id) }
    }

    fun setStreamingAnimations(on: Boolean) {
        _streamingAnimations.value = on
        viewModelScope.launch { prefs.saveStreamingAnimations(on) }
    }

    fun setHapticFeedback(on: Boolean) {
        _hapticFeedback.value = on
        viewModelScope.launch { prefs.saveHapticFeedback(on) }
    }

    fun setAutoCompact(on: Boolean) {
        _autoCompact.value = on
        viewModelScope.launch { prefs.saveAutoCompact(on) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
    }
}

package to.eyed.spettro.chat.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.eyed.spettro.chat.data.AppContainer
import to.eyed.spettro.chat.data.api.Account
import to.eyed.spettro.chat.data.api.ModelInfo
import to.eyed.spettro.chat.data.api.SpettroApi
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

sealed interface LoginFlow {
    data object Idle : LoginFlow
    data object Starting : LoginFlow
    data class WaitingBrowser(val browserUrl: String) : LoginFlow
    data class Error(val message: String) : LoginFlow
    data object Expired : LoginFlow
}

sealed interface AuthState {
    data object Loading : AuthState
    data class SignedOut(val login: LoginFlow = LoginFlow.Idle) : AuthState
    data object SignedIn : AuthState
}

class AppViewModel(private val container: AppContainer) : ViewModel() {
    private val api = container.api
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

    private var loginJob: Job? = null

    init {
        viewModelScope.launch {
            val snapshot = prefs.load()
            _cachedEmail.value = snapshot.email
            _cachedPlan.value = snapshot.plan
            _selectedModel.value = snapshot.selectedModel
            _thinkingLevel.value = ThinkingLevel.fromId(snapshot.thinkingLevel)
            _streamingAnimations.value = snapshot.streamingAnimations
            _hapticFeedback.value = snapshot.hapticFeedback
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
    }

    /**
     * Device-flow login: initiate, hand the browser URL to the UI, then poll
     * every 2s (up to 10 minutes) until complete/expired.
     */
    fun startLogin(openUrl: (String) -> Unit) {
        if (loginJob?.isActive == true) return
        loginJob = viewModelScope.launch {
            _authState.value = AuthState.SignedOut(LoginFlow.Starting)
            val session = try {
                api.authInitiate()
            } catch (e: Exception) {
                _authState.value = AuthState.SignedOut(LoginFlow.Error(e.message ?: "login could not be started"))
                return@launch
            }
            _authState.value = AuthState.SignedOut(LoginFlow.WaitingBrowser(session.browserUrl))
            openUrl(session.browserUrl)

            val deadline = System.currentTimeMillis() + SpettroApi.LOGIN_MAX_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(SpettroApi.POLL_INTERVAL_MS)
                val result = try {
                    api.authPoll(session.sessionId)
                } catch (e: Exception) {
                    _authState.value = AuthState.SignedOut(LoginFlow.Error(e.message ?: "login check failed"))
                    return@launch
                }
                when (result) {
                    is SpettroApi.PollResult.Pending -> Unit
                    is SpettroApi.PollResult.Expired -> {
                        _authState.value = AuthState.SignedOut(LoginFlow.Expired)
                        return@launch
                    }
                    is SpettroApi.PollResult.Complete -> {
                        // Persist synchronously: the key is returned exactly once.
                        prefs.saveApiKeyBlocking(result.apiKey)
                        _authState.value = AuthState.SignedIn
                        refreshAccountAndModels()
                        return@launch
                    }
                }
            }
            _authState.value = AuthState.SignedOut(LoginFlow.Expired)
        }
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
                prefs.saveAccount(acct.email, acct.planOrFree, acct.planStatus)
            } catch (e: UnauthorizedException) {
                signOut(local = true)
            } catch (_: Exception) {
            }
        }
    }

    /** Logout is purely client-side; the backend has no revocation endpoint. */
    fun signOut(local: Boolean = false) {
        viewModelScope.launch {
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

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
    }
}

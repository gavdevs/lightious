package com.loosewire.lightious.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.loosewire.lightious.LightiousServices
import com.loosewire.lightious.data.AccountSession
import com.loosewire.lightious.data.AudioLanguagePreference
import com.loosewire.lightious.data.ClientSettings
import com.loosewire.lightious.data.CompanionState
import com.loosewire.lightious.data.InvidiousApi
import com.loosewire.lightious.data.authTokenAllowsHistoryWrite
import com.loosewire.lightious.data.buildAuthorizationUrl
import com.loosewire.lightious.data.normalizeAuthToken
import com.loosewire.lightious.data.normalizeInstanceUrl
import com.loosewire.lightious.data.pairedHistoryAccountKey
import com.loosewire.lightious.data.withoutUnverifiedProfile
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val settings: ClientSettings = ClientSettings(),
    val signedIn: Boolean = false,
    val companion: CompanionState = CompanionState(),
    val loading: Boolean = true,
    val errorMessage: String? = null,
)

class SettingsViewModel(
    private val services: LightiousServices,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private var requestJob: Job? = null

    init {
        load()
    }

    fun load() {
        requestJob?.cancel()
        _uiState.update { state ->
            state.copy(
                companion = state.companion.withoutUnverifiedProfile(),
                loading = true,
                errorMessage = null,
            )
        }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = services.settings.load()
                var companionError: String? = null
                val companion = services.companion.loadActiveState(settings.instanceUrl).getOrElse { error ->
                    companionError = error.userMessage("Could not verify companion settings.")
                    services.companion.load(settings.instanceUrl).withoutUnverifiedProfile()
                }
                _uiState.value = SettingsUiState(
                    settings = settings,
                    signedIn = services.accounts.load(settings.instanceUrl) != null,
                    companion = companion,
                    loading = false,
                    errorMessage = companionError,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(loading = false, errorMessage = error.userMessage("Could not load settings."))
                }
            }
        }
    }

    fun toggleProxyMedia() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.companion.session != null) {
                _uiState.update {
                    it.copy(
                        errorMessage = "Paired playback always stays on the Lightious gateway. Proxy Media only changes unpaired or custom playback.",
                    )
                }
                return@launch
            }
            try {
                services.settings.setProxyMedia(!_uiState.value.settings.proxyMedia)
                requestJob = null
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.userMessage("Could not save this setting.")) }
            }
        }
    }

    fun toggleSelfHost() = updateSetting(
        save = { services.settings.setSelfHostEnabled(!it.selfHostEnabled) },
    )

    fun toggleSearchHistory() = updateSetting(
        save = { services.settings.setSearchHistoryEnabled(!it.saveSearchHistory) },
    )

    fun toggleWatchHistory() = updateSetting(
        save = { services.settings.setWatchHistoryEnabled(!it.saveWatchHistory) },
    )

    fun setAudioLanguage(preference: AudioLanguagePreference) = updateSetting(
        save = { services.settings.setAudioLanguage(preference) },
    )

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun updateSetting(save: suspend (ClientSettings) -> Unit) {
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                save(_uiState.value.settings)
                requestJob = null
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.userMessage("Could not save this setting.")) }
            }
        }
    }
}

class SettingsScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightiousServices,
) : LightScreen<Unit, SettingsViewModel>(sealedActivity) {
    override val viewModelClass = SettingsViewModel::class.java

    override fun createViewModel() = SettingsViewModel(services)

    override fun willShow() {
        viewModel.load()
    }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        LightTheme(colors = colors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                if (state.loading) {
                    LoadingContent("Loading…", "Settings")
                } else {
                    SettingsContent(
                        state = state,
                        onBack = { goBack() },
                        onInstance = {
                            navigateTo(
                                screenFactory = { activity ->
                                    InstanceEditorScreen(
                                        activity,
                                        services,
                                        state.settings.instanceUrl,
                                    )
                                },
                            )
                        },
                        onAccount = {
                            navigateTo(
                                screenFactory = { activity -> AccountScreen(activity, services) },
                            )
                        },
                        onCompanion = {
                            navigateTo(
                                screenFactory = { activity -> CompanionScreen(activity, services) },
                            )
                        },
                        onAudioLanguage = {
                            navigateTo(
                                screenFactory = { activity ->
                                    AudioLanguageScreen(
                                        activity,
                                        state.settings.audioLanguage,
                                    )
                                },
                                resultCallback = viewModel::setAudioLanguage,
                            )
                        },
                        onSelfHost = viewModel::toggleSelfHost,
                        onWatchHistory = viewModel::toggleWatchHistory,
                    )
                }
                state.errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onInstance: () -> Unit,
    onAccount: () -> Unit,
    onCompanion: () -> Unit,
    onAudioLanguage: () -> Unit,
    onSelfHost: () -> Unit,
    onWatchHistory: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Settings"),
        )
        LightScrollView(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            SettingRow(
                "COMPANION",
                when {
                    state.companion.session == null -> "NOT PAIRED"
                    state.companion.profile == null -> "PAIRED · SYNC NEEDED"
                    else -> "PAIRED · ${state.companion.profile.mode.name}"
                },
                onCompanion,
            )
            SettingRow(
                "AUDIO LANGUAGE",
                state.settings.audioLanguage.displayName.uppercase(),
                onAudioLanguage,
            )
            SettingRow(
                "SELF-HOST",
                when {
                    !state.settings.managedServerAvailable -> "REQUIRED"
                    state.settings.selfHostEnabled -> "ON"
                    else -> "OFF"
                },
                onSelfHost,
            )
            if (state.settings.selfHostEnabled) {
                SettingRow("INVIDIOUS SERVER", state.settings.instanceUrl, onInstance)
            }
            LightText(
                text = if (state.settings.selfHostEnabled) {
                    if (state.settings.managedServerAvailable) {
                        "Self-hosting is intended for compatible Lightious servers. Your saved server is kept if you turn it off."
                    } else {
                        "A managed Lightious server is not configured yet, so Self-host is required for this build."
                    }
                } else {
                    "Server details stay hidden. Turn on Self-host only if you run a compatible Lightious server."
                },
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
            SettingRow(
                "SAVE WATCH HISTORY",
                if (state.settings.saveWatchHistory) "ON — local only" else "OFF — existing entries kept",
                onWatchHistory,
            )
            SettingRow(
                "ACCOUNT",
                if (state.signedIn) "SIGNED IN" else "NOT SIGNED IN",
                onAccount,
            )
            LightText(
                text = "Mark Watched controls the channel feed on this phone, even when watch history is off. Optional account history sync is managed in Account.",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        }
    }
}

class AudioLanguageScreen(
    sealedActivity: SealedLightActivity,
    private val selected: AudioLanguagePreference,
) : SimpleLightScreen<AudioLanguagePreference>(sealedActivity) {
    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back",
                    ),
                    center = LightTopBarCenter.Text("Audio Language"),
                )
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    AudioLanguagePreference.entries.forEach { preference ->
                        SettingRow(
                            label = preference.displayName.uppercase(),
                            value = if (preference == selected) "SELECTED" else "",
                            onClick = { goBack(preference) },
                        )
                    }
                    LightText(
                        text = "If the chosen language is unavailable, original audio is used.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

data class AccountUiState(
    val settings: ClientSettings = ClientSettings(),
    val account: AccountSession? = null,
    val companion: CompanionState = CompanionState(),
    val loading: Boolean = true,
    val authorizationUrl: String = "",
    val errorMessage: String? = null,
)

class AccountViewModel(
    private val services: LightiousServices,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()
    private var requestJob: Job? = null

    init {
        load()
    }

    fun load() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val settings = services.settings.load()
            _uiState.value = AccountUiState(
                settings = settings,
                account = services.accounts.load(settings.instanceUrl),
                companion = services.companion.load(settings.instanceUrl),
                loading = false,
                authorizationUrl = buildAuthorizationUrl(
                    settings.instanceUrl,
                    includeHistorySync = settings.syncAccountHistory,
                ),
            )
        }
    }

    fun signIn(tokenInput: String) {
        val token = runCatching { normalizeAuthToken(tokenInput) }.getOrElse { error ->
            _uiState.update { it.copy(errorMessage = error.userMessage("Invalid API token.")) }
            return
        }
        requestJob?.cancel()
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            if (
                state.companion.session == null &&
                state.settings.syncAccountHistory &&
                !authTokenAllowsHistoryWrite(token)
            ) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        errorMessage = "This token does not allow watched-state sync. Use the authorization URL currently shown.",
                    )
                }
                return@launch
            }
            InvidiousApi(
                state.settings.instanceUrl,
                state.settings.proxyMedia,
                state.settings.audioLanguage,
            ).use { api ->
                val feed = api.accountFeed(token)
                val failure = feed.exceptionOrNull()
                if (failure != null) {
                    _uiState.update {
                        it.copy(
                            loading = false,
                            errorMessage = failure.userMessage(
                                "The token was rejected or is missing account feed access.",
                            ),
                        )
                    }
                    return@launch
                }
            }
            try {
                services.accounts.save(state.settings.instanceUrl, token)
                requestJob = null
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(loading = false, errorMessage = error.userMessage("Could not save the account."))
                }
            }
        }
    }

    fun signOut() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val account = _uiState.value.account
            try {
                if (account != null) services.history.clearPendingServerWatches(account.accountKey)
                services.accounts.clear()
                requestJob = null
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.userMessage("Could not sign out.")) }
            }
        }
    }

    fun toggleSync() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val enabling = !_uiState.value.settings.syncAccountHistory
                val account = _uiState.value.account
                val pairedSession = _uiState.value.companion.session
                if (
                    enabling &&
                    pairedSession == null &&
                    account != null &&
                    !authTokenAllowsHistoryWrite(account.token)
                ) {
                    _uiState.update {
                        it.copy(
                            errorMessage = "Sign out, turn on watch sync, then authorize again to grant access.",
                        )
                    }
                    return@launch
                }
                if (!enabling) {
                    account?.let { account ->
                        services.history.clearPendingServerWatches(account.accountKey)
                    }
                    pairedSession?.let { session ->
                        services.history.clearPendingServerWatches(
                            pairedHistoryAccountKey(session.instanceUrl, session.account),
                        )
                    }
                }
                services.settings.setAccountHistorySyncEnabled(enabling)
                requestJob = null
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.userMessage("Could not save sync setting.")) }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

class AccountScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightiousServices,
) : LightScreen<Unit, AccountViewModel>(sealedActivity) {
    override val viewModelClass = AccountViewModel::class.java

    override fun createViewModel() = AccountViewModel(services)

    override fun willShow() {
        viewModel.load()
    }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        LightTheme(colors = colors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                if (state.loading) {
                    LoadingContent("Checking account…", "Account")
                } else {
                    AccountContent(
                        state = state,
                        onBack = { goBack() },
                        onSignIn = {
                            navigateTo(
                                screenFactory = { activity -> TokenInputScreen(activity) },
                                resultCallback = viewModel::signIn,
                            )
                        },
                        onSignOut = {
                            navigateTo(
                                screenFactory = { activity ->
                                    ConfirmScreen(
                                        activity,
                                        title = "Sign Out",
                                        message = "Remove this saved Invidious API token? Local histories and Companion pairing are kept.",
                                        confirmLabel = "SIGN OUT",
                                    )
                                },
                                resultCallback = { confirmed ->
                                    if (confirmed) viewModel.signOut()
                                },
                            )
                        },
                        onToggleSync = viewModel::toggleSync,
                    )
                }
                state.errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }
}

@Composable
private fun AccountContent(
    state: AccountUiState,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onToggleSync: () -> Unit,
) {
    val pairedSession = state.companion.session
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Account"),
        )
        LightScrollView(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            if (pairedSession != null) {
                LightText(
                    text = "STATUS  ·  PAIRED",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                LightText(
                    text = "Watched-state sync can use the paired gateway on this phone. A separate Invidious API token is only needed on unpaired or custom servers.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                )
                SettingRow(
                    "SYNC WATCHED STATE",
                    if (state.settings.syncAccountHistory) {
                        "ON — future watches sync through pairing"
                    } else {
                        "OFF"
                    },
                    onToggleSync,
                )
                if (state.account != null) {
                    LightText(
                        text = "LEGACY TOKEN  ·  SAVED",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                    )
                    ActionRow("REMOVE LEGACY TOKEN") { onSignOut() }
                }
                LightText(
                    text = "Invidious receives watched video IDs only. Playback bytes still stay behind the paired media gateway.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                )
            } else if (state.account == null) {
                LightText(
                    text = "Sign in with a restricted Invidious API token only when this phone is not paired. Lightious never receives your password.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                LightText(
                    text = "On another device, sign in to this server and open:",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                )
                LightText(
                    text = state.authorizationUrl,
                    variant = LightTextVariant.Fine,
                    maxLines = 8,
                    modifier = Modifier.padding(top = 0.4f.gridUnitsAsDp()),
                )
                SettingRow(
                    "SYNC FUTURE WATCHES",
                    if (state.settings.syncAccountHistory) {
                        "ON — requests watched-state access"
                    } else {
                        "OFF — local only"
                    },
                    onToggleSync,
                )
                ActionRow("ENTER GENERATED TOKEN") { onSignIn() }
                LightText(
                    text = "The token is encrypted after saving and can be revoked from your Invidious account.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                )
            } else {
                LightText(
                    text = "STATUS  ·  SIGNED IN",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                SettingRow(
                    "SYNC WATCHED STATE",
                    if (state.settings.syncAccountHistory) "ON — future watches only" else "OFF",
                    onToggleSync,
                )
                ActionRow("SIGN OUT") { onSignOut() }
                LightText(
                    text = "Local watch history stays on this phone. Invidious receives only watched video IDs, not playback position.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                )
            }
        }
    }
}

data class InstanceEditorUiState(
    val initialValue: String,
    val session: Int = 0,
    val loading: Boolean = false,
    val saved: Boolean = false,
    val errorMessage: String? = null,
)

class InstanceEditorViewModel(
    private val services: LightiousServices,
    initialValue: String,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(InstanceEditorUiState(initialValue))
    val uiState: StateFlow<InstanceEditorUiState> = _uiState.asStateFlow()
    private var requestJob: Job? = null

    fun submit(value: CharSequence) {
        val normalized = runCatching { normalizeInstanceUrl(value.toString()) }.getOrElse { error ->
            _uiState.update {
                it.copy(
                    initialValue = value.toString(),
                    session = it.session + 1,
                    errorMessage = error.userMessage("Invalid instance URL."),
                )
            }
            return
        }
        requestJob?.cancel()
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val oldSettings = services.settings.load()
            val oldAccount = if (oldSettings.instanceUrl != normalized) {
                services.accounts.load(oldSettings.instanceUrl)
            } else {
                null
            }
            val oldCompanion = if (oldSettings.instanceUrl != normalized) {
                services.companion.load(oldSettings.instanceUrl)
            } else {
                CompanionState()
            }
            val probe = if (oldSettings.instanceUrl == normalized) {
                services.companion.probeSavedInstance(
                    instanceUrl = normalized,
                    proxyMedia = oldSettings.proxyMedia,
                    audioLanguage = oldSettings.audioLanguage,
                ).getOrElse { error ->
                    _uiState.update {
                        it.copy(
                            initialValue = normalized,
                            session = it.session + 1,
                            loading = false,
                            errorMessage = error.userMessage("Could not verify companion access."),
                        )
                    }
                    return@launch
                }
            } else {
                InvidiousApi(
                    baseUrl = normalized,
                    proxyMedia = oldSettings.proxyMedia,
                    audioLanguage = oldSettings.audioLanguage,
                ).use { it.probe() }
            }
            if (!probe.apiAvailable) {
                _uiState.update {
                    it.copy(
                        initialValue = normalized,
                        session = it.session + 1,
                        loading = false,
                        errorMessage = probe.message,
                    )
                }
                return@launch
            }
            try {
                services.settings.saveInstance(normalized)
                if (oldSettings.instanceUrl != normalized) {
                    withContext(NonCancellable) {
                        try {
                            oldAccount?.let { account ->
                                services.history.clearPendingServerWatches(account.accountKey)
                            }
                        } finally {
                            try {
                                oldCompanion.session?.let { session ->
                                    services.history.clearPendingServerWatches(
                                        pairedHistoryAccountKey(session.instanceUrl, session.account),
                                    )
                                }
                            } finally {
                                try {
                                    services.accounts.clear()
                                } finally {
                                    services.companion.forget(oldSettings.instanceUrl)
                                }
                            }
                        }
                    }
                }
                _uiState.update { it.copy(loading = false, saved = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(loading = false, errorMessage = error.userMessage("Could not save server."))
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

class InstanceEditorScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightiousServices,
    private val initialValue: String,
) : LightScreen<Unit, InstanceEditorViewModel>(sealedActivity) {
    override val viewModelClass = InstanceEditorViewModel::class.java

    override fun createViewModel() = InstanceEditorViewModel(services, initialValue)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        if (state.saved) {
            androidx.compose.runtime.LaunchedEffect(Unit) { goBack() }
        }
        LightTheme(colors = colors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                if (state.loading) {
                    LoadingContent("Checking server…", "Invidious Server")
                } else {
                    key(state.session) {
                        val text = rememberTextFieldState(state.initialValue)
                        val keyboardOptions = rememberKeyboardOptions()
                        LightTextInputEditor(
                            title = "Invidious Server",
                            state = text,
                            keyboardOptionsFlow = keyboardOptions,
                            onSubmit = viewModel::submit,
                            onBack = { goBack() },
                            submitLabel = "CHECK",
                            singleLine = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                state.errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }
}

private class TokenInputScreen(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<String>(sealedActivity) {
    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val text = rememberTextFieldState("")
        val keyboardOptions = rememberKeyboardOptions()
        LightTheme(colors = colors) {
            LightTextInputEditor(
                title = "API Token",
                state = text,
                keyboardOptionsFlow = keyboardOptions,
                onSubmit = { value -> goBack(value.toString()) },
                onBack = { goBack() },
                submitLabel = "SIGN IN",
                singleLine = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

internal class ConfirmScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val message: String,
    private val confirmLabel: String,
) : SimpleLightScreen<Boolean>(sealedActivity) {
    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(center = LightTopBarCenter.Text(title))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = message,
                        variant = LightTextVariant.Copy,
                        align = TextAlign.Center,
                    )
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(text = "CANCEL", onClick = { goBack(false) }),
                        LightBarButton.Text(text = confirmLabel, onClick = { goBack(true) }),
                    ),
                )
            }
        }
    }
}

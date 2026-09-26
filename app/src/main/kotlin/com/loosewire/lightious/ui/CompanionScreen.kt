package com.loosewire.lightious.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.loosewire.lightious.LightiousServices
import com.loosewire.lightious.data.CompanionState
import com.loosewire.lightious.data.ExperienceMode
import com.loosewire.lightious.data.PairingStatus
import com.loosewire.lightious.data.PendingPairing
import com.loosewire.lightious.data.pairedHistoryAccountKey
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CompanionUiState(
    val instanceUrl: String = "",
    val companion: CompanionState = CompanionState(),
    val pending: PendingPairing? = null,
    val pairingStatus: PairingStatus? = null,
    val loading: Boolean = true,
    val errorMessage: String? = null,
)

class CompanionViewModel(
    private val services: LightiousServices,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(CompanionUiState())
    val uiState: StateFlow<CompanionUiState> = _uiState.asStateFlow()
    private var requestJob: Job? = null
    private var pollJob: Job? = null

    init {
        load()
    }

    fun load() {
        requestJob?.cancel()
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = services.settings.load()
                var companion = services.companion.load(settings.instanceUrl)
                var syncError: String? = null
                if (companion.session != null) {
                    services.companion.sync(settings.instanceUrl).fold(
                        onSuccess = { profile -> companion = companion.copy(profile = profile) },
                        onFailure = { error ->
                            companion = try {
                                services.companion.load(settings.instanceUrl).copy(profile = null)
                            } catch (loadError: CancellationException) {
                                throw loadError
                            } catch (_: Exception) {
                                CompanionState()
                            }
                            syncError = error.userMessage("Could not sync the companion.")
                        },
                    )
                }
                _uiState.update {
                    it.copy(
                        instanceUrl = settings.instanceUrl,
                        companion = companion,
                        loading = false,
                        errorMessage = syncError,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(loading = false, errorMessage = error.userMessage("Could not load companion status."))
                }
            }
        }
    }

    fun startPairing() {
        if (_uiState.value.instanceUrl.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Choose an Invidious server first.") }
            return
        }
        requestJob?.cancel()
        pollJob?.cancel()
        _uiState.update { it.copy(loading = true, pending = null, pairingStatus = null, errorMessage = null) }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            services.companion.beginPairing(_uiState.value.instanceUrl).fold(
                onSuccess = { pending ->
                    _uiState.update { it.copy(loading = false, pending = pending) }
                    startPolling(pending)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(loading = false, errorMessage = error.userMessage("Could not start pairing."))
                    }
                },
            )
        }
    }

    fun pollNow() {
        _uiState.value.pending?.let(::startPolling)
    }

    fun cancelPairing() {
        pollJob?.cancel()
        _uiState.update { it.copy(pending = null, pairingStatus = null, errorMessage = null) }
    }

    fun finishPairing() {
        val pending = _uiState.value.pending ?: return
        if (_uiState.value.pairingStatus?.isClaimed != true) return
        requestJob?.cancel()
        pollJob?.cancel()
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            services.companion.activatePairing(pending).fold(
                onSuccess = { companion ->
                    _uiState.update {
                        it.copy(
                            companion = companion,
                            pending = null,
                            pairingStatus = null,
                            loading = false,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(loading = false, errorMessage = error.userMessage("Could not finish pairing."))
                    }
                },
            )
        }
    }

    fun sync() {
        requestJob?.cancel()
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            services.companion.sync(_uiState.value.instanceUrl).fold(
                onSuccess = { profile ->
                    _uiState.update {
                        it.copy(
                            companion = it.companion.copy(profile = profile),
                            loading = false,
                        )
                    }
                },
                onFailure = { error ->
                    val companion = try {
                        services.companion.load(_uiState.value.instanceUrl).copy(profile = null)
                    } catch (loadError: CancellationException) {
                        throw loadError
                    } catch (_: Exception) {
                        CompanionState()
                    }
                    _uiState.update {
                        it.copy(
                            companion = companion,
                            loading = false,
                            errorMessage = error.userMessage("Could not sync the companion."),
                        )
                    }
                },
            )
        }
    }

    fun forget() {
        requestJob?.cancel()
        pollJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value.companion.session?.let { session ->
                    services.history.clearPendingServerWatches(
                        pairedHistoryAccountKey(session.instanceUrl, session.account),
                    )
                }
                services.companion.forget(_uiState.value.instanceUrl)
                _uiState.update {
                    it.copy(
                        companion = CompanionState(),
                        pending = null,
                        pairingStatus = null,
                        loading = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(loading = false, errorMessage = error.userMessage("Could not forget this pairing."))
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun startPolling(pending: PendingPairing) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && _uiState.value.pending?.pairingId == pending.pairingId) {
                val result = services.companion.pollPairing(pending)
                val status = result.getOrElse { error ->
                    _uiState.update { it.copy(errorMessage = error.userMessage("Could not check pairing status.")) }
                    return@launch
                }
                _uiState.update { it.copy(pairingStatus = status) }
                if (status.isClaimed || status.isTerminal) return@launch
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}

class CompanionScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightiousServices,
) : LightScreen<Unit, CompanionViewModel>(sealedActivity) {
    override val viewModelClass = CompanionViewModel::class.java

    override fun createViewModel() = CompanionViewModel(services)

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
                when {
                    state.loading -> LoadingContent("Working…", "Companion")
                    state.pending != null -> PairingContent(
                        state = state,
                        onBack = viewModel::cancelPairing,
                        onPoll = viewModel::pollNow,
                        onFinish = viewModel::finishPairing,
                    )
                    state.companion.session != null -> PairedContent(
                        state = state,
                        onBack = { goBack() },
                        onSync = viewModel::sync,
                        onForget = {
                            navigateTo(
                                screenFactory = { activity ->
                                    ConfirmScreen(
                                        activity,
                                        title = "Forget Companion",
                                        message = "Remove this phone's local companion credential? Revoke it on the website if the phone is lost.",
                                        confirmLabel = "FORGET",
                                    )
                                },
                                resultCallback = { confirmed -> if (confirmed) viewModel.forget() },
                            )
                        },
                    )
                    else -> UnpairedContent(
                        instanceUrl = state.instanceUrl,
                        onBack = { goBack() },
                        onPair = viewModel::startPairing,
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
private fun UnpairedContent(instanceUrl: String, onBack: () -> Unit, onPair: () -> Unit) {
    CompanionFrame(title = "Companion", onBack = onBack) {
        LightText(
            text = "Pair this phone without typing an Invidious token. Your browser login stays on the companion website.",
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
        )
        LightText(
            text = instanceUrl.ifBlank { "No Invidious server selected" },
            variant = LightTextVariant.Fine,
            maxLines = 5,
            modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
        )
        ActionRow("PAIR THIS PHONE", onPair)
    }
}

@Composable
private fun PairingContent(
    state: CompanionUiState,
    onBack: () -> Unit,
    onPoll: () -> Unit,
    onFinish: () -> Unit,
) {
    val pending = checkNotNull(state.pending)
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Cancel pairing",
            ),
            center = LightTopBarCenter.Text("Pair Phone"),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LightText(
                    text = pending.userCode,
                    variant = LightTextVariant.Heading,
                    monospace = true,
                    align = TextAlign.Center,
                )
                LightText(
                    text = "On another device, sign in and open:",
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
                LightText(
                    text = pending.verificationUrl,
                    variant = LightTextVariant.Fine,
                    maxLines = 6,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                LightText(
                    text = when {
                        state.pairingStatus?.isClaimed == true ->
                            "APPROVED · ${state.pairingStatus.account.orEmpty()}"
                        state.pairingStatus?.isTerminal == true -> "PAIRING EXPIRED"
                        else -> "WAITING FOR APPROVAL"
                    },
                    variant = LightTextVariant.Copy,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            }
        }
        LightBottomBar(
            items = if (state.pairingStatus?.isClaimed == true) {
                listOf(LightBarButton.Text(text = "CONFIRM", onClick = onFinish))
            } else {
                listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.REFRESH,
                        onClick = onPoll,
                        contentDescription = "Check approval",
                    ),
                )
            },
        )
    }
}

@Composable
private fun PairedContent(
    state: CompanionUiState,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onForget: () -> Unit,
) {
    val session = checkNotNull(state.companion.session)
    CompanionFrame(title = "Companion", onBack = onBack) {
        LightText(
            text = "PAIRED · ${session.account}",
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
        )
        val profile = state.companion.profile
        SettingRow(
            "EXPERIENCE",
            when (profile?.mode) {
                ExperienceMode.FOCUSED -> "FOCUSED"
                ExperienceMode.LIBRARY -> "LIBRARY"
                null -> "SYNC NEEDED"
            },
            onSync,
        )
        LightText(
            text = "Manage your library and channel feed on the companion website.",
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
        )
        ActionRow("SYNC NOW", onSync)
        ActionRow("FORGET THIS PHONE", onForget)
        LightText(
            text = "Forgetting removes the credential from this phone. Use the companion website to revoke a lost phone.",
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
        )
    }
}

@Composable
private fun CompanionFrame(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text(title),
        )
        LightScrollView(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            content()
        }
    }
}

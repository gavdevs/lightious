package com.loosewire.lightious.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.loosewire.lightious.LightiousServices
import com.loosewire.lightious.data.ClientSettings
import com.loosewire.lightious.data.VideoSummary
import com.loosewire.lightious.data.WatchHistoryEntry
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WatchHistoryUiState(
    val settings: ClientSettings = ClientSettings(),
    val entries: List<WatchHistoryEntry> = emptyList(),
    val loading: Boolean = true,
    val errorMessage: String? = null,
)

class WatchHistoryViewModel(
    private val services: LightiousServices,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(WatchHistoryUiState())
    val uiState: StateFlow<WatchHistoryUiState> = _uiState.asStateFlow()
    private val _navigationTarget = MutableStateFlow<VideoSummary?>(null)
    val navigationTarget: StateFlow<VideoSummary?> = _navigationTarget.asStateFlow()
    private var requestJob: Job? = null

    init {
        load()
    }

    fun load() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = WatchHistoryUiState(
                    settings = services.settings.load(),
                    entries = services.history.watchHistory(),
                    loading = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(loading = false, errorMessage = error.userMessage("Could not load history."))
                }
            }
        }
    }

    fun selectVideo(video: VideoSummary) {
        _navigationTarget.value = video
    }

    fun consumeNavigationTarget() {
        _navigationTarget.value = null
    }

    fun clear() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = services.settings.load()
                val account = services.accounts.load(settings.instanceUrl)
                services.history.clearWatchHistory(account?.accountKey)
                requestJob = null
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.userMessage("Could not clear history.")) }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

class WatchHistoryScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightiousServices,
) : LightScreen<Unit, WatchHistoryViewModel>(sealedActivity) {
    override val viewModelClass = WatchHistoryViewModel::class.java

    override fun createViewModel() = WatchHistoryViewModel(services)

    override fun willShow() {
        viewModel.load()
    }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val navigationTarget by viewModel.navigationTarget.collectAsState()

        LaunchedEffect(navigationTarget?.videoId) {
            navigationTarget?.let { video ->
                navigateTo(
                    screenFactory = { activity ->
                        VideoScreen(activity, state.settings, video, services)
                    },
                )
                viewModel.consumeNavigationTarget()
            }
        }

        LightTheme(colors = colors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                if (state.loading) {
                    LoadingContent("Loading…", "Watch History")
                } else {
                    WatchHistoryContent(
                        state = state,
                        onBack = { goBack() },
                        onVideo = viewModel::selectVideo,
                        onClear = {
                            navigateTo(
                                screenFactory = { activity ->
                                    ConfirmScreen(
                                        activity,
                                        title = "Clear Watch History",
                                        message = "Delete every locally saved watched video? Account history is not changed.",
                                        confirmLabel = "CLEAR",
                                    )
                                },
                                resultCallback = { confirmed ->
                                    if (confirmed) viewModel.clear()
                                },
                            )
                        },
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
private fun WatchHistoryContent(
    state: WatchHistoryUiState,
    onBack: () -> Unit,
    onVideo: (VideoSummary) -> Unit,
    onClear: () -> Unit,
) {
    HistoryScaffold(
        title = "Watch History",
        hasEntries = state.entries.isNotEmpty(),
        onBack = onBack,
        onClear = onClear,
    ) {
        if (!state.settings.saveWatchHistory) {
            HistoryNotice("Saving new watched videos is off in Settings.")
        }
        if (state.entries.isEmpty()) {
            HistoryNotice("No watched videos yet.")
        } else {
            state.entries.forEach { entry ->
                VideoRow(entry.video) { onVideo(entry.video) }
            }
        }
    }
}

@Composable
private fun HistoryScaffold(
    title: String,
    hasEntries: Boolean,
    onBack: () -> Unit,
    onClear: () -> Unit,
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
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            content()
        }
        if (hasEntries) {
            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.DELETE,
                        onClick = onClear,
                        contentDescription = "Clear history",
                    ),
                ),
            )
        }
    }
}

@Composable
private fun HistoryNotice(message: String) {
    LightText(
        text = message,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
    )
}

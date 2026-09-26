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
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.loosewire.lightious.LightiousServices
import com.loosewire.lightious.data.ClientSettings
import com.loosewire.lightious.data.FocusedLibraryFilter
import com.loosewire.lightious.data.FocusedPlaylistEntry
import com.loosewire.lightious.data.FocusedVideoEntry
import com.loosewire.lightious.data.focusedPlaylists
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface FocusedPlaylistMode {
    data object Loading : FocusedPlaylistMode
    data class Loaded(val playlist: FocusedPlaylistEntry) : FocusedPlaylistMode
    data class Failed(val message: String) : FocusedPlaylistMode
}

data class FocusedPlaylistUiState(
    val mode: FocusedPlaylistMode = FocusedPlaylistMode.Loading,
    val filter: FocusedLibraryFilter = FocusedLibraryFilter.ALL,
)

class FocusedPlaylistViewModel(
    private val services: LightiousServices,
    private val settings: ClientSettings,
    private val playlistId: String,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(FocusedPlaylistUiState())
    val uiState: StateFlow<FocusedPlaylistUiState> = _uiState.asStateFlow()
    private var requestJob: Job? = null

    init {
        load()
    }

    fun load() {
        requestJob?.cancel()
        _uiState.update { state -> state.copy(mode = FocusedPlaylistMode.Loading) }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val profile = services.companion.sync(settings.instanceUrl).getOrElse { error ->
                _uiState.update {
                    it.copy(mode = FocusedPlaylistMode.Failed(error.userMessage("Could not sync this playlist.")))
                }
                return@launch
            }
            val playlist = profile.focusedPlaylists().firstOrNull { entry -> entry.id == playlistId }
            _uiState.update {
                it.copy(
                    mode = if (playlist == null) {
                        FocusedPlaylistMode.Failed("This playlist is no longer in your library.")
                    } else {
                        FocusedPlaylistMode.Loaded(playlist)
                    },
                )
            }
        }
    }

    fun selectFilter(filter: FocusedLibraryFilter) {
        _uiState.update { state -> state.copy(filter = filter) }
    }
}

class FocusedPlaylistScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightiousServices,
    private val settings: ClientSettings,
    playlist: FocusedPlaylistEntry,
) : LightScreen<Unit, FocusedPlaylistViewModel>(sealedActivity) {
    private val playlistId = playlist.id

    override val viewModelClass = FocusedPlaylistViewModel::class.java

    override fun createViewModel() = FocusedPlaylistViewModel(services, settings, playlistId)

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
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                            contentDescription = "Back",
                        ),
                        center = LightTopBarCenter.Text("Playlist"),
                        rightButton = LightBarButton.LightIcon(
                            icon = LightIcons.ELLIPSES,
                            onClick = { openOptions(state.filter) },
                            contentDescription = "Playlist options",
                        ),
                    )
                    when (val mode = state.mode) {
                        FocusedPlaylistMode.Loading -> FocusedLibraryLoading("Syncing playlist…")
                        is FocusedPlaylistMode.Failed -> FocusedLibraryFailure(
                            message = mode.message,
                            onRetry = viewModel::load,
                        )
                        is FocusedPlaylistMode.Loaded -> FocusedPlaylistContent(
                            playlist = mode.playlist,
                            selectedFilter = state.filter,
                            onVideo = { video ->
                                navigateTo(
                                    screenFactory = { activity ->
                                        VideoScreen(activity, settings, video.video, services)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    private fun openOptions(selectedFilter: FocusedLibraryFilter) {
        navigateTo(
            screenFactory = { activity ->
                FocusedOptionsScreen(
                    sealedActivity = activity,
                    title = "Playlist Options",
                    selectedFilter = selectedFilter,
                    leadingActions = listOf(FocusedOptionsAction.REFRESH),
                )
            },
            resultCallback = { result ->
                when (result) {
                    is FocusedOptionsResult.SelectFilter -> viewModel.selectFilter(result.filter)
                    is FocusedOptionsResult.RunAction -> when (result.action) {
                        FocusedOptionsAction.REFRESH -> viewModel.load()
                        FocusedOptionsAction.SEARCH,
                        FocusedOptionsAction.SETTINGS,
                        -> Unit
                    }
                }
            },
        )
    }
}

@Composable
private fun FocusedPlaylistContent(
    playlist: FocusedPlaylistEntry,
    selectedFilter: FocusedLibraryFilter,
    onVideo: (FocusedVideoEntry) -> Unit,
) {
    val videos = playlist.videosWithPolicy()
        .filter { video -> selectedFilter.includes(video.playbackPolicy) }
    Column(modifier = Modifier.fillMaxSize()) {
        LightText(
            text = playlist.name,
            variant = LightTextVariant.Subheading,
            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            if (videos.isEmpty()) {
                LightText(
                    text = "No playlist videos match this filter.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            } else {
                videos.forEach { video ->
                    VideoRow(video.rowSummary()) { onVideo(video) }
                }
            }
        }
    }
}

@Composable
internal fun FocusedLibraryLoading(message: String) {
    LightText(
        text = message,
        variant = LightTextVariant.Copy,
        modifier = Modifier.padding(1f.gridUnitsAsDp()),
    )
}

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
import androidx.compose.ui.Modifier
import com.loosewire.lightious.LightiousServices
import com.loosewire.lightious.data.ClientSettings
import com.loosewire.lightious.data.CompanionProfile
import com.loosewire.lightious.data.DownloadedMedia
import com.loosewire.lightious.data.ExperienceMode
import com.loosewire.lightious.data.FocusedLibrarySearchResults
import com.loosewire.lightious.data.searchFocusedLibrary
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface FocusedLibrarySearchMode {
    data class Editor(val initialValue: String, val session: Int) : FocusedLibrarySearchMode
    data class Results(
        val query: String,
        val matches: FocusedLibrarySearchResults,
    ) : FocusedLibrarySearchMode
}

data class FocusedLibrarySearchUiState(
    val mode: FocusedLibrarySearchMode = FocusedLibrarySearchMode.Editor("", 1),
    val errorMessage: String? = null,
)

class FocusedLibrarySearchViewModel(
    private val profile: CompanionProfile?,
    private val downloads: List<DownloadedMedia>,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(FocusedLibrarySearchUiState())
    val uiState: StateFlow<FocusedLibrarySearchUiState> = _uiState.asStateFlow()
    private var editorSession = 1

    fun showEditor(value: String = (_uiState.value.mode as? FocusedLibrarySearchMode.Results)?.query.orEmpty()) {
        editorSession += 1
        _uiState.update {
            it.copy(
                mode = FocusedLibrarySearchMode.Editor(value, editorSession),
                errorMessage = null,
            )
        }
    }

    fun submitSearch(value: CharSequence) {
        val query = value.toString().trim()
        if (query.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Enter something from your library.") }
            return
        }
        _uiState.update {
            it.copy(
                mode = FocusedLibrarySearchMode.Results(
                    query,
                    profile.searchFocusedLibrary(query, downloads),
                ),
                errorMessage = null,
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

class FocusedLibrarySearchScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightiousServices,
    private val settings: ClientSettings,
    private val profile: CompanionProfile?,
    private val downloads: List<DownloadedMedia>,
) : LightScreen<Unit, FocusedLibrarySearchViewModel>(sealedActivity) {
    override val viewModelClass = FocusedLibrarySearchViewModel::class.java

    override fun createViewModel() = FocusedLibrarySearchViewModel(profile, downloads)

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
                when (val mode = state.mode) {
                    is FocusedLibrarySearchMode.Editor -> FocusedLibrarySearchEditor(
                        mode = mode,
                        viewModel = viewModel,
                        onBack = { goBack() },
                    )
                    is FocusedLibrarySearchMode.Results -> FocusedLibrarySearchResultsContent(
                        mode = mode,
                        viewModel = viewModel,
                        onBack = { goBack() },
                        onVideo = { video ->
                            navigateTo(
                                screenFactory = { activity ->
                                    VideoScreen(activity, settings, video.asVideoSummary(), services)
                                },
                            )
                        },
                        onChannel = { channel ->
                            if (profile?.mode == ExperienceMode.LIBRARY) navigateTo(
                                screenFactory = { activity ->
                                    FocusedChannelScreen(activity, services, settings, channel)
                                },
                            )
                        },
                        onPlaylist = { playlist ->
                            navigateTo(
                                screenFactory = { activity ->
                                    FocusedPlaylistScreen(activity, services, settings, playlist)
                                },
                            )
                        },
                        onDownload = { download ->
                            navigateTo(
                                screenFactory = { activity ->
                                    DownloadedMediaScreen(
                                        activity,
                                        services,
                                        download.ownerDeviceId,
                                        download.videoId,
                                    )
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
private fun FocusedLibrarySearchEditor(
    mode: FocusedLibrarySearchMode.Editor,
    viewModel: FocusedLibrarySearchViewModel,
    onBack: () -> Unit,
) {
    key(mode.session) {
        val text = rememberTextFieldState(mode.initialValue)
        val keyboardOptions = rememberKeyboardOptions()
        LightTextInputEditor(
            title = "Search Library",
            state = text,
            keyboardOptionsFlow = keyboardOptions,
            onSubmit = viewModel::submitSearch,
            onBack = onBack,
            submitIcon = LightIcons.SEARCH,
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun FocusedLibrarySearchResultsContent(
    mode: FocusedLibrarySearchMode.Results,
    viewModel: FocusedLibrarySearchViewModel,
    onBack: () -> Unit,
    onVideo: (com.loosewire.lightious.data.CuratedVideo) -> Unit,
    onChannel: (com.loosewire.lightious.data.FocusedChannelEntry) -> Unit,
    onPlaylist: (com.loosewire.lightious.data.FocusedPlaylistEntry) -> Unit,
    onDownload: (DownloadedMedia) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Library Search"),
            rightButton = LightBarButton.LightIcon(
                icon = LightIcons.SEARCH,
                onClick = { viewModel.showEditor(mode.query) },
                contentDescription = "New search",
            ),
        )
        LightScrollView(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            if (mode.matches.isEmpty) {
                LightText(
                    text = "Nothing in your library matches “${mode.query}”.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            } else {
                if (mode.matches.videos.isNotEmpty()) {
                    FocusedSearchSection("VIDEOS & AUDIO")
                    mode.matches.videos.forEach { video ->
                        VideoRow(video.asVideoSummary()) { onVideo(video) }
                    }
                }
                if (mode.matches.channels.isNotEmpty()) {
                    FocusedSearchSection("CHANNELS")
                    mode.matches.channels.forEach { channel ->
                        ChannelRow(channel) { onChannel(channel) }
                    }
                }
                if (mode.matches.playlists.isNotEmpty()) {
                    FocusedSearchSection("PLAYLISTS")
                    mode.matches.playlists.forEach { playlist ->
                        PlaylistRow(playlist) { onPlaylist(playlist) }
                    }
                }
                if (mode.matches.downloads.isNotEmpty()) {
                    FocusedSearchSection("DOWNLOADS")
                    mode.matches.downloads.forEach { download ->
                        DownloadRow(download) { onDownload(download) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusedSearchSection(title: String) {
    LightText(
        text = title,
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
    )
}

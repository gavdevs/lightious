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
import com.loosewire.lightious.enqueueDownload
import com.loosewire.lightious.data.AccountSession
import com.loosewire.lightious.data.ClientSettings
import com.loosewire.lightious.data.CompanionProfile
import com.loosewire.lightious.data.CompanionState
import com.loosewire.lightious.data.CuratedVideo
import com.loosewire.lightious.data.DownloadedMedia
import com.loosewire.lightious.data.downloadJobTag
import com.loosewire.lightious.data.ExperienceMode
import com.loosewire.lightious.data.FocusedChannelEntry
import com.loosewire.lightious.data.FocusedLibraryFilter
import com.loosewire.lightious.data.FocusedPlaylistEntry
import com.loosewire.lightious.data.FocusedVideoEntry
import com.loosewire.lightious.data.HomePage
import com.loosewire.lightious.data.InvidiousApi
import com.loosewire.lightious.data.effectiveExperienceMode
import com.loosewire.lightious.data.channelFeedEntries
import com.loosewire.lightious.data.focusedChannels
import com.loosewire.lightious.data.focusedPlaylists
import com.loosewire.lightious.data.normalizeInstanceUrl
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightJobState
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightActivity
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface HomeMode {
    data class Loading(val message: String) : HomeMode
    data class Failed(val message: String) : HomeMode
    data object Ready : HomeMode
    data class InstanceEditor(
        val initialValue: String,
        val session: Int,
    ) : HomeMode
}

enum class FocusedHomeTab {
    VIDEOS,
    CHANNELS,
    PLAYLISTS,
    DOWNLOADS,
}

data class HomeUiState(
    val settings: ClientSettings = ClientSettings(),
    val account: AccountSession? = null,
    val companion: CompanionState = CompanionState(),
    val downloads: List<DownloadedMedia> = emptyList(),
    val focusedTab: FocusedHomeTab = FocusedHomeTab.CHANNELS,
    val focusedFilter: FocusedLibraryFilter = FocusedLibraryFilter.ALL,
    val channelFeed: HomeChannelFeedState = HomeChannelFeedState.Loading,
    val watchedVideoIds: Set<String> = emptySet(),
    val mode: HomeMode = HomeMode.Loading("Loading…"),
    val errorMessage: String? = null,
)

class HomeViewModel(
    private val services: LightiousServices,
    private val resumeInterruptedDownload: suspend (DownloadedMedia) -> Boolean,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var requestJob: Job? = null
    private var editorSession = 0
    private val channelFeedCache = HomeChannelFeedCache()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            services.downloads.observeAll().collect { downloads ->
                _uiState.update { state -> state.copy(downloads = downloads) }
            }
        }
    }

    fun reload() = load(refreshFeed = true)

    fun onShow() {
        if (requestJob?.isActive == true) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val watchedVideoIds = services.history.watchedVideoIds()
                    _uiState.update { it.copy(watchedVideoIds = watchedVideoIds) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _uiState.update {
                        it.copy(channelFeed = HomeChannelFeedState.Failed(error.userMessage("Could not read watched videos.")))
                    }
                }
            }
        } else {
            load(refreshFeed = false)
        }
    }

    private fun load(refreshFeed: Boolean) {
        requestJob?.cancel()
        _uiState.update {
            it.copy(
                mode = HomeMode.Loading("Loading…"),
                channelFeed = HomeChannelFeedState.Loading,
                errorMessage = null,
            )
        }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val settings = try {
                services.settings.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        mode = HomeMode.Failed(error.userMessage("Could not read saved settings.")),
                    )
                }
                return@launch
            }
            if (settings.instanceUrl.isBlank()) {
                showInstanceEditor("")
                return@launch
            }
            val account = try {
                services.accounts.load(settings.instanceUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            val cachedCompanion = try {
                services.companion.load(settings.instanceUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(mode = HomeMode.Failed(error.userMessage("Could not read the saved pairing.")))
                }
                return@launch
            }
            val companion = loadHomeLibrary(
                state = _uiState,
                settings = settings,
                account = account,
                cachedCompanion = cachedCompanion,
                syncCompanion = { services.companion.sync(settings.instanceUrl) },
                reloadCompanion = { services.companion.load(settings.instanceUrl) },
            )
            loadChannelFeed(settings, companion, refreshFeed)
            companion.session?.deviceId?.let { ownerDeviceId ->
                services.downloads.recoverInterruptedDownloads(ownerDeviceId).forEach { download ->
                    if (!resumeInterruptedDownload(download)) {
                        services.downloads.markFailed(
                            ownerDeviceId,
                            download.videoId,
                            "The background download service is unavailable.",
                            download.kind,
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadChannelFeed(
        settings: ClientSettings,
        companion: CompanionState,
        refresh: Boolean,
    ) {
        val profile = companion.profile ?: return
        if (profile.mode != ExperienceMode.FOCUSED || profile.channels.isEmpty()) return
        val deviceBearer = companion.session?.deviceBearer ?: return
        try {
            val page = channelFeedCache.load(settings.instanceUrl, profile, refresh) {
                InvidiousApi(
                    baseUrl = settings.instanceUrl,
                    proxyMedia = settings.proxyMedia,
                    deviceBearer = deviceBearer,
                    audioLanguage = settings.audioLanguage,
                ).use { api -> api.channelFeed() }
            }
            val watchedVideoIds = services.history.watchedVideoIds()
            _uiState.update {
                it.copy(channelFeed = HomeChannelFeedState.Loaded(page), watchedVideoIds = watchedVideoIds)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _uiState.update {
                it.copy(channelFeed = HomeChannelFeedState.Failed(error.userMessage("Could not load channel releases.")))
            }
        }
    }

    fun submitInstance(value: CharSequence) {
        val normalized = runCatching { normalizeInstanceUrl(value.toString()) }
            .getOrElse { error ->
                showInstanceEditor(
                    value.toString(),
                    error.userMessage("Invalid instance URL."),
                )
                return
            }
        requestJob?.cancel()
        _uiState.update {
            it.copy(mode = HomeMode.Loading("Checking server…"), errorMessage = null)
        }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val currentSettings = _uiState.value.settings
            val probe = if (currentSettings.instanceUrl == normalized) {
                services.companion.probeSavedInstance(
                    instanceUrl = normalized,
                    proxyMedia = currentSettings.proxyMedia,
                    audioLanguage = currentSettings.audioLanguage,
                ).getOrElse { error ->
                    showInstanceEditor(normalized, error.userMessage("Could not verify companion access."))
                    return@launch
                }
            } else {
                InvidiousApi(
                    baseUrl = normalized,
                    proxyMedia = currentSettings.proxyMedia,
                    audioLanguage = currentSettings.audioLanguage,
                ).use { api ->
                    api.probe()
                }
            }
            if (!probe.apiAvailable) {
                showInstanceEditor(normalized, probe.message)
                return@launch
            }
            try {
                services.settings.saveInstance(normalized)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showInstanceEditor(normalized, error.userMessage("Could not save the server."))
                return@launch
            }
            reload()
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun selectFocusedTab(tab: FocusedHomeTab) {
        _uiState.update { state -> state.copy(focusedTab = tab) }
    }

    fun selectFocusedFilter(filter: FocusedLibraryFilter) {
        _uiState.update { state -> state.copy(focusedFilter = filter) }
    }

    private fun showInstanceEditor(initialValue: String, error: String? = null) {
        editorSession += 1
        _uiState.update {
            it.copy(
                mode = HomeMode.InstanceEditor(initialValue, editorSession),
                errorMessage = error,
            )
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {
    private val services by lazy { LightiousServices.from(lightContext) }

    override val viewModelClass = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel(
        services = services,
        resumeInterruptedDownload = { download ->
            when (
                LightWork.getState(
                    lightContext,
                    downloadJobTag(download.ownerDeviceId, download.videoId, download.kind),
                )
            ) {
                LightJobState.Enqueued,
                LightJobState.Running,
                -> true
                else -> enqueueDownload(
                    lightContext,
                    download.ownerDeviceId,
                    download.videoId,
                    download.kind,
                )
            }
        },
    )

    override fun willShow() {
        viewModel.onShow()
    }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val companionProfile = state.companion.profile
        val experienceMode = companionProfile.effectiveExperienceMode()
        val pairedDownloads = state.companion.session?.deviceId?.let { ownerDeviceId ->
            state.downloads.filter { download -> download.ownerDeviceId == ownerDeviceId }
        }.orEmpty()

        LightTheme(colors = colors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (val mode = state.mode) {
                    is HomeMode.Loading -> LoadingContent(mode.message)
                    is HomeMode.Failed -> HomeFailureContent(mode.message, viewModel::reload, ::openSettings)
                    HomeMode.Ready -> FocusedHomeContent(
                        profile = companionProfile,
                        experienceMode = experienceMode,
                        channelFeed = state.channelFeed,
                        watchedVideoIds = state.watchedVideoIds,
                        downloads = pairedDownloads,
                        paired = state.companion.session != null,
                        selectedTab = state.focusedTab,
                        selectedFilter = state.focusedFilter,
                        onVideo = ::openFocusedVideo,
                        onFeedVideo = ::openFeedVideo,
                        onChannel = ::openFocusedChannel,
                        onPlaylist = ::openFocusedPlaylist,
                        onDownload = ::openDownload,
                        onTab = viewModel::selectFocusedTab,
                        onSearch = { openFocusedSearch(companionProfile, pairedDownloads) },
                        onOptions = ::openFocusedOptions,
                        onRefresh = viewModel::reload,
                        onSettings = ::openSettings,
                    )
                    is HomeMode.InstanceEditor -> InitialInstanceEditor(mode, viewModel)
                }

                state.errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }

    private fun openSettings() {
        navigateTo(screenFactory = { activity -> SettingsScreen(activity, services) })
    }

    private fun openFocusedOptions() {
        val state = viewModel.uiState.value
        navigateTo(
            screenFactory = { activity ->
                FocusedOptionsScreen(
                    sealedActivity = activity,
                    title = "Library Options",
                    selectedFilter = state.focusedFilter,
                    trailingActions = listOf(FocusedOptionsAction.REFRESH, FocusedOptionsAction.SETTINGS),
                )
            },
            resultCallback = { result ->
                when (result) {
                    is FocusedOptionsResult.SelectFilter -> viewModel.selectFocusedFilter(result.filter)
                    is FocusedOptionsResult.RunAction -> when (result.action) {
                        FocusedOptionsAction.SEARCH -> {
                            val current = viewModel.uiState.value
                            val downloads = current.companion.session?.deviceId?.let { ownerDeviceId ->
                                current.downloads.filter { download -> download.ownerDeviceId == ownerDeviceId }
                            }.orEmpty()
                            openFocusedSearch(current.companion.profile, downloads)
                        }
                        FocusedOptionsAction.REFRESH -> viewModel.reload()
                        FocusedOptionsAction.SETTINGS -> openSettings()
                    }
                }
            },
        )
    }

    private fun openFocusedVideo(video: CuratedVideo) {
        navigateTo(
            screenFactory = { activity ->
                VideoScreen(activity, viewModel.uiState.value.settings, video.asVideoSummary(), services)
            },
        )
    }

    private fun openFocusedChannel(channel: FocusedChannelEntry) {
        if (viewModel.uiState.value.companion.profile?.mode != ExperienceMode.LIBRARY) return
        navigateTo(
            screenFactory = { activity ->
                FocusedChannelScreen(activity, services, viewModel.uiState.value.settings, channel)
            },
        )
    }

    private fun openFeedVideo(entry: FocusedVideoEntry) {
        navigateTo(
            screenFactory = { activity ->
                VideoScreen(activity, viewModel.uiState.value.settings, entry.video, services)
            },
        )
    }

    private fun openFocusedPlaylist(playlist: FocusedPlaylistEntry) {
        navigateTo(
            screenFactory = { activity ->
                FocusedPlaylistScreen(activity, services, viewModel.uiState.value.settings, playlist)
            },
        )
    }

    private fun openDownload(download: DownloadedMedia) {
        navigateTo(
            screenFactory = { activity ->
                DownloadedMediaScreen(activity, services, download.ownerDeviceId, download.videoId)
            },
        )
    }

    private fun openFocusedSearch(profile: CompanionProfile?, downloads: List<DownloadedMedia>) {
        navigateTo(
            screenFactory = { activity ->
                FocusedLibrarySearchScreen(
                    activity,
                    services,
                    viewModel.uiState.value.settings,
                    profile,
                    downloads,
                )
            },
        )
    }
}

@Composable
private fun FocusedHomeContent(
    profile: CompanionProfile?,
    experienceMode: ExperienceMode,
    channelFeed: HomeChannelFeedState,
    watchedVideoIds: Set<String>,
    downloads: List<DownloadedMedia>,
    paired: Boolean,
    selectedTab: FocusedHomeTab,
    selectedFilter: FocusedLibraryFilter,
    onVideo: (CuratedVideo) -> Unit,
    onFeedVideo: (FocusedVideoEntry) -> Unit,
    onChannel: (FocusedChannelEntry) -> Unit,
    onPlaylist: (FocusedPlaylistEntry) -> Unit,
    onDownload: (DownloadedMedia) -> Unit,
    onTab: (FocusedHomeTab) -> Unit,
    onSearch: () -> Unit,
    onOptions: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    val channels = profile?.focusedChannels().orEmpty().filter { channel -> channel.includes(selectedFilter) }
    val playlists = profile?.focusedPlaylists().orEmpty().filter { playlist -> playlist.includes(selectedFilter) }
    val videos = profile?.items.orEmpty().filter { video -> selectedFilter.includes(video.playbackPolicy) }
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.SEARCH,
                onClick = onSearch,
                contentDescription = "Search library",
            ),
            center = LightTopBarCenter.Text(selectedTab.focusedTitle()),
            rightButton = if (selectedTab == FocusedHomeTab.DOWNLOADS) {
                LightBarButton.LightIcon(
                    icon = LightIcons.SETTINGS,
                    onClick = onSettings,
                    contentDescription = "Settings",
                )
            } else {
                LightBarButton.LightIcon(
                    icon = LightIcons.ELLIPSES,
                    onClick = onOptions,
                    contentDescription = "Library options",
                )
            },
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            if (profile == null && selectedTab != FocusedHomeTab.DOWNLOADS) {
                FocusedEmptyMessage(
                    if (paired) {
                        "Sync is required before this paired phone can open its library."
                    } else {
                        "Pair this phone in Settings to load your Focused library."
                    },
                )
                if (paired) {
                    LightText(
                        text = "RETRY SYNC",
                        variant = LightTextVariant.Button,
                        underline = true,
                        modifier = Modifier
                            .padding(top = 1f.gridUnitsAsDp())
                            .lightClickable(onClick = onRefresh),
                    )
                }
            } else when (selectedTab) {
                FocusedHomeTab.VIDEOS -> if (videos.isEmpty()) {
                    FocusedEmptyMessage("No videos match this filter. Send a video from the companion website.")
                } else {
                    videos.forEach { video -> VideoRow(video.asVideoSummary()) { onVideo(video) } }
                }
                FocusedHomeTab.CHANNELS -> if (experienceMode == ExperienceMode.FOCUSED) {
                    FocusedChannelFeedContent(
                        profile = profile,
                        state = channelFeed,
                        watchedVideoIds = watchedVideoIds,
                        filter = selectedFilter,
                        onVideo = onFeedVideo,
                        onRetry = onRefresh,
                    )
                } else if (channels.isEmpty()) {
                    FocusedEmptyMessage("No channels match this filter. Add a channel from the companion website.")
                } else {
                    channels.forEach { channel -> ChannelRow(channel) { onChannel(channel) } }
                }
                FocusedHomeTab.PLAYLISTS -> if (playlists.isEmpty()) {
                    FocusedEmptyMessage("No playlists match this filter. Build one from videos in the companion website.")
                } else {
                    playlists.forEach { playlist -> PlaylistRow(playlist) { onPlaylist(playlist) } }
                }
                FocusedHomeTab.DOWNLOADS -> if (downloads.isEmpty()) {
                    FocusedEmptyMessage("No downloads yet. Open a video in your library to save it offline.")
                } else {
                    downloads.forEach { download -> DownloadRow(download) { onDownload(download) } }
                }
            }
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.MEDIA,
                    onClick = { onTab(FocusedHomeTab.VIDEOS) },
                    contentDescription = "Videos",
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.CONTACTS,
                    onClick = { onTab(FocusedHomeTab.CHANNELS) },
                    contentDescription = "Channels",
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.LIST,
                    onClick = { onTab(FocusedHomeTab.PLAYLISTS) },
                    contentDescription = "Playlists",
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.DOWNLOAD_ARROW,
                    onClick = { onTab(FocusedHomeTab.DOWNLOADS) },
                    contentDescription = "Downloads",
                ),
            ),
        )
    }
}

@Composable
private fun FocusedChannelFeedContent(
    profile: CompanionProfile?,
    state: HomeChannelFeedState,
    watchedVideoIds: Set<String>,
    filter: FocusedLibraryFilter,
    onVideo: (FocusedVideoEntry) -> Unit,
    onRetry: () -> Unit,
) {
    if (profile == null) return
    if (profile.channels.isEmpty()) {
        FocusedEmptyMessage("Add a channel on the companion website to see its recent releases here.")
        return
    }
    when (state) {
        HomeChannelFeedState.Loading -> FocusedEmptyMessage("Loading channel releases…")
        is HomeChannelFeedState.Failed -> {
            FocusedEmptyMessage(state.message)
            ActionRow("RETRY", onRetry)
        }
        is HomeChannelFeedState.Loaded -> {
            val entries = profile.channelFeedEntries(state.page.videos, watchedVideoIds)
                .filter { entry -> filter.includes(entry.playbackPolicy) }
            if (state.page.failedChannelIds.isNotEmpty()) {
                FocusedEmptyMessage("Some channels could not be refreshed.")
                ActionRow("RETRY", onRetry)
            }
            if (entries.isEmpty() && state.page.failedChannelIds.isEmpty()) {
                FocusedEmptyMessage(
                    when {
                        filter != FocusedLibraryFilter.ALL -> "No recent releases match this filter."
                        profile.hideWatched -> "No recent unwatched videos."
                        else -> "No recent videos."
                    },
                )
            }
            entries.forEach { entry ->
                VideoRow(entry.rowSummary()) { onVideo(entry) }
            }
        }
    }
}

@Composable
private fun FocusedEmptyMessage(message: String) {
    LightText(
        text = message,
        variant = LightTextVariant.Copy,
        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
    )
}

private fun FocusedHomeTab.focusedTitle(): String = when (this) {
    FocusedHomeTab.VIDEOS -> "Videos"
    FocusedHomeTab.CHANNELS -> "Channels"
    FocusedHomeTab.PLAYLISTS -> "Playlists"
    FocusedHomeTab.DOWNLOADS -> "Downloads"
}

@Composable
private fun InitialInstanceEditor(mode: HomeMode.InstanceEditor, viewModel: HomeViewModel) {
    key(mode.session) {
        val text = rememberTextFieldState(mode.initialValue)
        val keyboardOptions = rememberKeyboardOptions()
        LightTextInputEditor(
            title = "Invidious Server",
            state = text,
            keyboardOptionsFlow = keyboardOptions,
            onSubmit = viewModel::submitInstance,
            onBack = {},
            submitLabel = "CHECK",
            showBackButton = false,
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun LoadingContent(message: String, title: String = "Lightious") {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(center = LightTopBarCenter.Text(title))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = message, variant = LightTextVariant.Copy, align = TextAlign.Center)
        }
    }
}

@Composable
private fun HomeFailureContent(message: String, onRetry: () -> Unit, onSettings: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            center = LightTopBarCenter.Text("Lightious"),
            rightButton = LightBarButton.LightIcon(
                icon = LightIcons.SETTINGS,
                onClick = onSettings,
                contentDescription = "Settings",
            ),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightText(
                text = message,
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
            ActionRow("RETRY", onRetry)
        }
    }
}

@Composable
internal fun ActionRow(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Subheading,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.8f.gridUnitsAsDp()),
    )
}

@Composable
internal fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = label, variant = LightTextVariant.Superfine, lighten = true)
        LightText(
            text = value,
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(top = 0.15f.gridUnitsAsDp()),
        )
    }
}

internal fun HomePage.homeLabel(): String = when (this) {
    HomePage.SEARCH -> "SEARCH"
    HomePage.ACCOUNT_FEED -> "ACCOUNT FEED"
    HomePage.WATCH_HISTORY -> "WATCH HISTORY"
    HomePage.SEARCH_HISTORY -> "SEARCH HISTORY"
    HomePage.POPULAR -> "POPULAR"
}

internal fun Throwable.userMessage(fallback: String): String =
    message?.trim()?.takeIf(String::isNotEmpty) ?: fallback

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
import com.loosewire.lightious.data.ExperienceMode
import com.loosewire.lightious.data.FocusedChannelEntry
import com.loosewire.lightious.data.FocusedLibraryFilter
import com.loosewire.lightious.data.FocusedVideoEntry
import com.loosewire.lightious.data.InvidiousApi
import com.loosewire.lightious.data.VideoSummary
import com.loosewire.lightious.data.focusedChannels
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface FocusedChannelMode {
    data object Loading : FocusedChannelMode
    data class Loaded(
        val channel: FocusedChannelEntry,
        val latestUploads: List<VideoSummary>,
        val videos: List<FocusedVideoEntry>,
        val continuation: String? = null,
        val loadingMore: Boolean = false,
    ) : FocusedChannelMode
    data class Failed(val message: String) : FocusedChannelMode
}

data class FocusedChannelUiState(
    val mode: FocusedChannelMode = FocusedChannelMode.Loading,
    val filter: FocusedLibraryFilter = FocusedLibraryFilter.ALL,
    val errorMessage: String? = null,
)

class FocusedChannelViewModel(
    private val services: LightiousServices,
    private val settings: ClientSettings,
    private val initialChannelId: String,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(FocusedChannelUiState())
    val uiState: StateFlow<FocusedChannelUiState> = _uiState.asStateFlow()
    private var requestJob: Job? = null

    init {
        load()
    }

    fun onShow() {
        val loaded = _uiState.value.mode as? FocusedChannelMode.Loaded ?: return
        requestJob?.cancel()
        _uiState.update { it.copy(mode = FocusedChannelMode.Loading, errorMessage = null) }
        requestJob = viewModelScope.launch {
            val companion = withContext(Dispatchers.IO) {
                services.companion.loadActiveState(settings.instanceUrl)
            }.getOrElse { error ->
                _uiState.update {
                    it.copy(mode = FocusedChannelMode.Failed(error.userMessage("Could not verify channel access.")))
                }
                return@launch
            }
            val profile = companion.profile
            if (profile?.mode != ExperienceMode.LIBRARY) {
                _uiState.update {
                    it.copy(mode = FocusedChannelMode.Failed("Channel browsing is available in Library mode."))
                }
                return@launch
            }
            val channel = profile.focusedChannels().firstOrNull { it.channelId == initialChannelId }
            if (channel == null) {
                _uiState.update {
                    it.copy(mode = FocusedChannelMode.Failed("This channel is no longer in your library."))
                }
                return@launch
            }
            val uploads = loaded.latestUploads.takeIf { channel.allowsWholeChannel }.orEmpty()
            _uiState.update {
                it.copy(
                    mode = loaded.copy(
                        channel = channel,
                        latestUploads = uploads,
                        videos = channel.videosWithPolicy(uploads),
                        continuation = loaded.continuation.takeIf { channel.allowsWholeChannel },
                        loadingMore = false,
                    ),
                )
            }
        }
    }

    fun load() {
        requestJob?.cancel()
        _uiState.update { state -> state.copy(mode = FocusedChannelMode.Loading, errorMessage = null) }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val companion = services.companion.loadActiveState(settings.instanceUrl).getOrElse { error ->
                _uiState.update {
                    it.copy(mode = FocusedChannelMode.Failed(error.userMessage("Could not sync this channel.")))
                }
                return@launch
            }
            val profile = companion.profile ?: run {
                _uiState.update {
                    it.copy(mode = FocusedChannelMode.Failed("This phone is not paired with the companion."))
                }
                return@launch
            }
            if (profile.mode != ExperienceMode.LIBRARY) {
                _uiState.update {
                    it.copy(mode = FocusedChannelMode.Failed("Channel browsing is available in Library mode."))
                }
                return@launch
            }
            val channel = profile.focusedChannels().firstOrNull { entry ->
                entry.channelId == initialChannelId
            }
            if (channel == null) {
                _uiState.update {
                    it.copy(mode = FocusedChannelMode.Failed("This channel is no longer in your library."))
                }
                return@launch
            }

            var latestUploads = emptyList<VideoSummary>()
            var continuation: String? = null
            var loadError: String? = null
            if (channel.allowsWholeChannel) {
                val deviceBearer = companion.session?.deviceBearer ?: run {
                    _uiState.update {
                        it.copy(mode = FocusedChannelMode.Failed("This phone is not paired with the companion."))
                    }
                    return@launch
                }
                InvidiousApi(
                    baseUrl = settings.instanceUrl,
                    proxyMedia = settings.proxyMedia,
                    deviceBearer = deviceBearer,
                    audioLanguage = settings.audioLanguage,
                ).use { api ->
                    api.channelVideos(channel.channelId).fold(
                        onSuccess = { page ->
                            latestUploads = page.videos.filter { video -> video.authorId == channel.channelId }
                            continuation = page.continuation
                        },
                        onFailure = { error ->
                            loadError = error.userMessage("Could not load newest channel videos.")
                        },
                    )
                }
            }
            _uiState.update {
                it.copy(
                    mode = FocusedChannelMode.Loaded(
                        channel = channel,
                        latestUploads = latestUploads,
                        videos = channel.videosWithPolicy(latestUploads),
                        continuation = continuation,
                    ),
                    errorMessage = loadError,
                )
            }
        }
    }

    fun selectFilter(filter: FocusedLibraryFilter) {
        _uiState.update { state -> state.copy(filter = filter) }
    }

    fun loadMore() {
        val loaded = _uiState.value.mode as? FocusedChannelMode.Loaded ?: return
        val continuation = loaded.continuation ?: return
        if (loaded.loadingMore) return
        _uiState.update { state ->
            state.copy(mode = loaded.copy(loadingMore = true), errorMessage = null)
        }
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val companion = services.companion.loadActiveState(settings.instanceUrl).getOrElse { error ->
                _uiState.update {
                    it.copy(mode = FocusedChannelMode.Failed(error.userMessage("Could not verify channel access.")))
                }
                return@launch
            }
            val profile = companion.profile ?: run {
                _uiState.update {
                    it.copy(mode = FocusedChannelMode.Failed("This phone is not paired with the companion."))
                }
                return@launch
            }
            if (profile.mode != ExperienceMode.LIBRARY) {
                _uiState.update {
                    it.copy(mode = FocusedChannelMode.Failed("Channel browsing is available in Library mode."))
                }
                return@launch
            }
            val channel = profile.focusedChannels().firstOrNull { entry ->
                entry.channelId == initialChannelId
            }
            if (channel == null) {
                _uiState.update {
                    it.copy(mode = FocusedChannelMode.Failed("This channel is no longer in your library."))
                }
                return@launch
            }
            if (!channel.allowsWholeChannel) {
                _uiState.update {
                    it.copy(
                        mode = FocusedChannelMode.Loaded(
                            channel = channel,
                            latestUploads = emptyList(),
                            videos = channel.videosWithPolicy(emptyList()),
                        ),
                    )
                }
                return@launch
            }

            val deviceBearer = companion.session?.deviceBearer ?: run {
                _uiState.update {
                    it.copy(mode = FocusedChannelMode.Failed("This phone is not paired with the companion."))
                }
                return@launch
            }
            InvidiousApi(
                baseUrl = settings.instanceUrl,
                proxyMedia = settings.proxyMedia,
                deviceBearer = deviceBearer,
                audioLanguage = settings.audioLanguage,
            ).use { api ->
                api.channelVideos(channel.channelId, continuation).fold(
                    onSuccess = { page ->
                        val uploads = (loaded.latestUploads + page.videos)
                            .filter { video -> video.authorId == channel.channelId }
                            .distinctBy(VideoSummary::videoId)
                        _uiState.update {
                            it.copy(
                                mode = FocusedChannelMode.Loaded(
                                    channel = channel,
                                    latestUploads = uploads,
                                    videos = channel.videosWithPolicy(uploads),
                                    continuation = page.continuation,
                                ),
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                mode = loaded.copy(loadingMore = false),
                                errorMessage = error.userMessage("Could not load more channel videos."),
                            )
                        }
                    },
                )
            }
        }
    }

    fun dismissError() {
        _uiState.update { state -> state.copy(errorMessage = null) }
    }
}

class FocusedChannelScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightiousServices,
    private val settings: ClientSettings,
    channel: FocusedChannelEntry,
) : LightScreen<Unit, FocusedChannelViewModel>(sealedActivity) {
    private val channelId = channel.channelId

    override val viewModelClass = FocusedChannelViewModel::class.java

    override fun createViewModel() = FocusedChannelViewModel(services, settings, channelId)

    override fun willShow() {
        viewModel.onShow()
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
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                            contentDescription = "Back",
                        ),
                        center = LightTopBarCenter.Text("Channel"),
                        rightButton = LightBarButton.LightIcon(
                            icon = LightIcons.ELLIPSES,
                            onClick = { openOptions(state) },
                            contentDescription = "Channel options",
                        ),
                    )
                    when (val mode = state.mode) {
                        FocusedChannelMode.Loading -> FocusedLibraryLoading("Syncing channel…")
                        is FocusedChannelMode.Failed -> FocusedLibraryFailure(
                            message = mode.message,
                            onRetry = viewModel::load,
                        )
                        is FocusedChannelMode.Loaded -> FocusedChannelContent(
                            mode = mode,
                            selectedFilter = state.filter,
                            onMore = viewModel::loadMore,
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
                state.errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }

    private fun openOptions(state: FocusedChannelUiState) {
        val loaded = state.mode as? FocusedChannelMode.Loaded
        navigateTo(
            screenFactory = { activity ->
                FocusedOptionsScreen(
                    sealedActivity = activity,
                    title = "Channel Options",
                    selectedFilter = state.filter,
                    leadingActions = buildList {
                        if (loaded != null) add(FocusedOptionsAction.SEARCH)
                        add(FocusedOptionsAction.REFRESH)
                    },
                )
            },
            resultCallback = { result ->
                when (result) {
                    is FocusedOptionsResult.SelectFilter -> viewModel.selectFilter(result.filter)
                    is FocusedOptionsResult.RunAction -> when (result.action) {
                        FocusedOptionsAction.SEARCH -> loaded?.let { mode ->
                            navigateTo(
                                screenFactory = { activity ->
                                    FocusedChannelSearchScreen(
                                        activity,
                                        services,
                                        settings,
                                        mode.channel,
                                        state.filter,
                                    )
                                },
                            )
                        }
                        FocusedOptionsAction.REFRESH -> viewModel.load()
                        FocusedOptionsAction.SETTINGS -> Unit
                    }
                }
            },
        )
    }
}

@Composable
private fun FocusedChannelContent(
    mode: FocusedChannelMode.Loaded,
    selectedFilter: FocusedLibraryFilter,
    onMore: () -> Unit,
    onVideo: (FocusedVideoEntry) -> Unit,
) {
    val videos = mode.videos.filter { video -> selectedFilter.includes(video.playbackPolicy) }
    Column(modifier = Modifier.fillMaxSize()) {
        LightText(
            text = mode.channel.name,
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
                    text = "No channel videos match this filter.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            } else {
                videos.forEach { video ->
                    VideoRow(video.rowSummary()) { onVideo(video) }
                }
            }
            if (mode.continuation != null) {
                ChannelMoreRow(loading = mode.loadingMore, onMore = onMore)
            }
        }
    }
}

@Composable
internal fun ChannelMoreRow(
    loading: Boolean,
    onMore: () -> Unit,
) {
    if (loading) {
        LightText(
            text = "LOADING…",
            variant = LightTextVariant.Subheading,
            lighten = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 0.8f.gridUnitsAsDp()),
        )
    } else {
        ActionRow("SEE MORE", onMore)
    }
}

@Composable
internal fun FocusedLibraryFailure(
    message: String,
    onRetry: () -> Unit,
) {
    LightScrollView(
        modifier = Modifier
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

internal fun FocusedVideoEntry.rowSummary() = video.copy(
    publishedText = buildList {
        add(playbackPolicy.shortLabel())
        video.publishedText
            .takeIf { text ->
                text.isNotBlank() && text != "LISTEN ONLY" && text != "VIDEO ENABLED"
            }
            ?.let(::add)
    }.joinToString("  ·  "),
)

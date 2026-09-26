package com.loosewire.lightious.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface FocusedChannelSearchMode {
    data class Editor(
        val initialValue: String,
        val session: Int,
        val previous: Results? = null,
    ) : FocusedChannelSearchMode

    data class Loading(val query: String, val message: String = "Searching channel…") : FocusedChannelSearchMode

    data class Results(
        val query: String,
        val channel: FocusedChannelEntry,
        val videos: List<FocusedVideoEntry>,
        val nextPage: Int? = null,
        val loadingMore: Boolean = false,
    ) : FocusedChannelSearchMode

    data class Failed(
        val query: String,
        val message: String,
    ) : FocusedChannelSearchMode
}

data class FocusedChannelSearchUiState(
    val mode: FocusedChannelSearchMode = FocusedChannelSearchMode.Editor("", 1),
    val filter: FocusedLibraryFilter = FocusedLibraryFilter.ALL,
    val errorMessage: String? = null,
)

class FocusedChannelSearchViewModel(
    private val services: LightiousServices,
    private val settings: ClientSettings,
    private val channelId: String,
    initialFilter: FocusedLibraryFilter,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(FocusedChannelSearchUiState(filter = initialFilter))
    val uiState: StateFlow<FocusedChannelSearchUiState> = _uiState.asStateFlow()
    private var requestJob: Job? = null
    private var editorSession = 1
    private var pendingResults: FocusedChannelSearchMode.Results? = null

    fun onShow() {
        val previous = _uiState.value.mode as? FocusedChannelSearchMode.Results ?: return
        requestJob?.cancel()
        pendingResults = previous
        _uiState.update {
            it.copy(
                mode = FocusedChannelSearchMode.Loading(previous.query, "Checking channel access…"),
                errorMessage = null,
            )
        }
        requestJob = viewModelScope.launch {
            val companion = withContext(Dispatchers.IO) {
                services.companion.loadActiveState(settings.instanceUrl)
            }.getOrElse { error ->
                failRequest(previous.query, null, error.userMessage("Could not verify channel access."))
                return@launch
            }
            val profile = companion.profile
            if (profile?.mode != ExperienceMode.LIBRARY) {
                failRequest(previous.query, null, "Channel browsing is available in Library mode.")
                return@launch
            }
            val channel = profile.focusedChannels().firstOrNull { it.channelId == channelId }
            if (channel == null || !channel.allowsWholeChannel) {
                failRequest(previous.query, null, "This channel is no longer in your library.")
                return@launch
            }
            pendingResults = null
            _uiState.update {
                it.copy(
                    mode = previous.copy(
                        channel = channel,
                        videos = channel.searchResultsWithPolicy(previous.orEmptySummaries()),
                        loadingMore = false,
                    ),
                )
            }
        }
    }

    fun showEditor() {
        val current = _uiState.value.mode
        val previous = current as? FocusedChannelSearchMode.Results ?: pendingResults
        val query = when (current) {
            is FocusedChannelSearchMode.Editor -> current.initialValue
            is FocusedChannelSearchMode.Failed -> current.query
            is FocusedChannelSearchMode.Loading -> current.query
            is FocusedChannelSearchMode.Results -> current.query
        }
        editorSession += 1
        requestJob?.cancel()
        pendingResults = null
        _uiState.update {
            it.copy(
                mode = FocusedChannelSearchMode.Editor(query, editorSession, previous),
                errorMessage = null,
            )
        }
    }

    fun closeEditor(): Boolean {
        val editor = _uiState.value.mode as? FocusedChannelSearchMode.Editor ?: return false
        val previous = editor.previous ?: return false
        _uiState.update { it.copy(mode = previous, errorMessage = null) }
        onShow()
        return true
    }

    override fun onBackPressed(): Boolean = closeEditor()

    fun submitSearch(value: CharSequence) {
        val query = value.toString().trim()
        if (query.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Enter something from this channel.") }
            return
        }
        requestPage(query = query, page = 1, previous = null)
    }

    fun retry() {
        val failed = _uiState.value.mode as? FocusedChannelSearchMode.Failed ?: return
        requestPage(query = failed.query, page = 1, previous = null)
    }

    fun refresh() {
        when (val current = _uiState.value.mode) {
            is FocusedChannelSearchMode.Results -> requestPage(current.query, page = 1, previous = null)
            is FocusedChannelSearchMode.Failed -> retry()
            is FocusedChannelSearchMode.Loading -> pendingResults?.let { previous ->
                requestPage(previous.query, page = 1, previous = null)
            }
            is FocusedChannelSearchMode.Editor -> Unit
        }
    }

    fun selectFilter(filter: FocusedLibraryFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun loadMore() {
        val current = _uiState.value.mode as? FocusedChannelSearchMode.Results ?: return
        val nextPage = current.nextPage ?: return
        if (current.loadingMore) return
        requestPage(query = current.query, page = nextPage, previous = current)
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun requestPage(
        query: String,
        page: Int,
        previous: FocusedChannelSearchMode.Results?,
    ) {
        requestJob?.cancel()
        pendingResults = null
        _uiState.update { state ->
            state.copy(
                mode = previous?.copy(loadingMore = true) ?: FocusedChannelSearchMode.Loading(query),
                errorMessage = null,
            )
        }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val companion = services.companion.loadActiveState(settings.instanceUrl).getOrElse { error ->
                failRequest(query, previous, error.userMessage("Could not verify channel access."))
                return@launch
            }
            val profile = companion.profile ?: run {
                failRequest(query, null, "This phone is not paired with the companion.")
                return@launch
            }
            if (profile.mode != ExperienceMode.LIBRARY) {
                failRequest(query, null, "Channel browsing is available in Library mode.")
                return@launch
            }
            val channel = profile.focusedChannels().firstOrNull { entry -> entry.channelId == channelId }
            if (channel == null || !channel.allowsWholeChannel) {
                failRequest(query, null, "This channel is no longer in your library.")
                return@launch
            }
            val deviceBearer = companion.session?.deviceBearer ?: run {
                failRequest(query, null, "This phone is not paired with the companion.")
                return@launch
            }

            InvidiousApi(
                baseUrl = settings.instanceUrl,
                proxyMedia = settings.proxyMedia,
                deviceBearer = deviceBearer,
                audioLanguage = settings.audioLanguage,
            ).use { api ->
                api.channelSearch(channel.channelId, query, page).fold(
                    onSuccess = { result ->
                        val summaries = previous.orEmptySummaries() + result.videos
                        _uiState.update {
                            it.copy(
                                mode = FocusedChannelSearchMode.Results(
                                    query = query,
                                    channel = channel,
                                    videos = channel.searchResultsWithPolicy(summaries),
                                    nextPage = result.nextPage,
                                ),
                                errorMessage = null,
                            )
                        }
                    },
                    onFailure = { error ->
                        failRequest(query, previous, error.userMessage("Could not search this channel."))
                    },
                )
            }
        }
    }

    private fun failRequest(
        query: String,
        previous: FocusedChannelSearchMode.Results?,
        message: String,
    ) {
        pendingResults = null
        _uiState.update { state ->
            if (previous == null) {
                state.copy(mode = FocusedChannelSearchMode.Failed(query, message), errorMessage = null)
            } else {
                state.copy(mode = previous.copy(loadingMore = false), errorMessage = message)
            }
        }
    }
}

class FocusedChannelSearchScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightiousServices,
    private val settings: ClientSettings,
    channel: FocusedChannelEntry,
    private val initialFilter: FocusedLibraryFilter,
) : LightScreen<Unit, FocusedChannelSearchViewModel>(sealedActivity) {
    private val channelId = channel.channelId

    override val viewModelClass = FocusedChannelSearchViewModel::class.java

    override fun createViewModel() = FocusedChannelSearchViewModel(
        services,
        settings,
        channelId,
        initialFilter,
    )

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
                when (val mode = state.mode) {
                    is FocusedChannelSearchMode.Editor -> FocusedChannelSearchEditor(
                        mode = mode,
                        onSubmit = viewModel::submitSearch,
                        onBack = {
                            if (!viewModel.closeEditor()) goBack()
                        },
                    )
                    else -> FocusedChannelSearchBody(
                        mode = mode,
                        selectedFilter = state.filter,
                        onBack = { goBack() },
                        onOptions = { openOptions(state.filter) },
                        onRetry = viewModel::retry,
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
                state.errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }

    private fun openOptions(selectedFilter: FocusedLibraryFilter) {
        navigateTo(
            screenFactory = { activity ->
                FocusedOptionsScreen(
                    sealedActivity = activity,
                    title = "Channel Search Options",
                    selectedFilter = selectedFilter,
                    leadingActions = listOf(
                        FocusedOptionsAction.SEARCH,
                        FocusedOptionsAction.REFRESH,
                    ),
                )
            },
            resultCallback = { result ->
                when (result) {
                    is FocusedOptionsResult.SelectFilter -> viewModel.selectFilter(result.filter)
                    is FocusedOptionsResult.RunAction -> when (result.action) {
                        FocusedOptionsAction.SEARCH -> viewModel.showEditor()
                        FocusedOptionsAction.REFRESH -> viewModel.refresh()
                        FocusedOptionsAction.SETTINGS -> Unit
                    }
                }
            },
        )
    }
}

@Composable
private fun FocusedChannelSearchEditor(
    mode: FocusedChannelSearchMode.Editor,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
) {
    key(mode.session) {
        val text = rememberTextFieldState(mode.initialValue)
        val keyboardOptions = rememberKeyboardOptions()
        LightTextInputEditor(
            title = "Search Channel",
            state = text,
            keyboardOptionsFlow = keyboardOptions,
            onSubmit = onSubmit,
            onBack = onBack,
            submitIcon = LightIcons.SEARCH,
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun FocusedChannelSearchBody(
    mode: FocusedChannelSearchMode,
    selectedFilter: FocusedLibraryFilter,
    onBack: () -> Unit,
    onOptions: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onVideo: (FocusedVideoEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Channel Search"),
            rightButton = LightBarButton.LightIcon(
                icon = LightIcons.ELLIPSES,
                onClick = onOptions,
                contentDescription = "Channel search options",
            ),
        )
        when (mode) {
            is FocusedChannelSearchMode.Editor -> Unit
            is FocusedChannelSearchMode.Loading -> FocusedLibraryLoading(mode.message)
            is FocusedChannelSearchMode.Failed -> FocusedLibraryFailure(mode.message, onRetry)
            is FocusedChannelSearchMode.Results -> FocusedChannelSearchResults(
                mode = mode,
                selectedFilter = selectedFilter,
                onMore = onMore,
                onVideo = onVideo,
            )
        }
    }
}

@Composable
private fun FocusedChannelSearchResults(
    mode: FocusedChannelSearchMode.Results,
    selectedFilter: FocusedLibraryFilter,
    onMore: () -> Unit,
    onVideo: (FocusedVideoEntry) -> Unit,
) {
    val videos = mode.videos.filter { video -> selectedFilter.includes(video.playbackPolicy) }
    LightScrollView(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = mode.channel.name,
            variant = LightTextVariant.Subheading,
        )
        LightText(
            text = "RESULTS FOR “${mode.query}”",
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
        )
        if (videos.isEmpty()) {
            LightText(
                text = "No channel videos match this search and filter.",
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        } else {
            videos.forEach { video ->
                VideoRow(video.rowSummary()) { onVideo(video) }
            }
        }
        if (mode.nextPage != null) {
            ChannelMoreRow(loading = mode.loadingMore, onMore = onMore)
        }
    }
}

private fun FocusedChannelSearchMode.Results?.orEmptySummaries(): List<VideoSummary> =
    this?.videos.orEmpty().map(FocusedVideoEntry::video)

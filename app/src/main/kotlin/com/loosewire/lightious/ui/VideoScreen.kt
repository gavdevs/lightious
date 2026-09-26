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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.loosewire.lightious.LightiousServices
import com.loosewire.lightious.cancelDownload
import com.loosewire.lightious.enqueueDownload
import com.loosewire.lightious.data.ClientSettings
import com.loosewire.lightious.data.DownloadKind
import com.loosewire.lightious.data.DownloadState
import com.loosewire.lightious.data.DownloadedMedia
import com.loosewire.lightious.data.InvidiousApi
import com.loosewire.lightious.data.PlaybackPolicy
import com.loosewire.lightious.data.StreamSelection
import com.loosewire.lightious.data.VideoDetails
import com.loosewire.lightious.data.VideoPlaybackSource
import com.loosewire.lightious.data.VideoSummary
import com.loosewire.lightious.data.selectDownloadPlan
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface VideoMode {
    data object Loading : VideoMode
    data class Loaded(
        val details: VideoDetails,
        val playbackPolicy: PlaybackPolicy,
    ) : VideoMode
    data class Failed(val message: String) : VideoMode
}

data class VideoUiState(
    val mode: VideoMode = VideoMode.Loading,
    val errorMessage: String? = null,
    val checkingAction: Boolean = false,
    val download: DownloadedMedia? = null,
    val finished: Boolean = false,
    val watched: Boolean = false,
    val savingWatched: Boolean = false,
)

class VideoViewModel(
    private val settings: ClientSettings,
    private val audio: LightAudio,
    private val initialVideo: VideoSummary,
    private val services: LightiousServices,
    private val scheduleDownload: (String, String, DownloadKind) -> Boolean,
    private val cancelScheduledDownload: (String, String) -> Unit,
) : LightViewModel<Unit>() {
    private val player = audio.newPlayer(
        usage = LightAudioUsage.Speech,
        playback = LightAudioPlayback.Detached,
    )

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()
    private val _audioStarted = MutableStateFlow(false)
    val audioStarted: StateFlow<Boolean> = _audioStarted.asStateFlow()
    private val _audioPlayRequested = MutableStateFlow(false)
    val audioPlayRequested: StateFlow<Boolean> = _audioPlayRequested.asStateFlow()
    val audioPlaying = player.isPlaying
    val audioPositionMs = player.positionMs
    val audioDurationMs = player.durationMs
    val audioError = player.error
    private val playbackActionGate = PlaybackActionGate()
    private var playbackRecorded = false
    private var resumePositionMs = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val watched = services.history.isWatched(initialVideo.videoId)
                _uiState.update { it.copy(watched = it.watched || watched) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A completion lookup must not prevent playback; marking reports write failures.
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ownerDeviceId = services.companion.load(settings.instanceUrl).session?.deviceId
                ?: return@launch
            services.downloads.observeAll().collect { downloads ->
                _uiState.update { state ->
                    state.copy(
                        download = downloads.firstOrNull { download ->
                            download.ownerDeviceId == ownerDeviceId &&
                                download.videoId == initialVideo.videoId
                        },
                    )
                }
            }
        }
        load()
    }

    fun load() {
        _uiState.update { state -> state.copy(mode = VideoMode.Loading, errorMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            when (val resolution = resolvePlayback(FreshPlaybackAction.DISPLAY)) {
                is FreshPlaybackResolution.Allowed -> {
                    _uiState.update { state ->
                        state.copy(mode = VideoMode.Loaded(resolution.details, resolution.policy))
                    }
                }
                is FreshPlaybackResolution.Denied -> {
                    _uiState.update { state ->
                        state.copy(mode = VideoMode.Failed(resolution.message))
                    }
                }
            }
        }
    }

    fun playAudio() = startAudio(resumePositionMs, playWhenReady = true)

    private fun startAudio(positionMs: Long, playWhenReady: Boolean) {
        if (_uiState.value.mode !is VideoMode.Loaded) return
        if (!beginPlaybackAction()) return
        resumePositionMs = positionMs

        viewModelScope.launch {
            try {
                when (val resolution = freshPlayback(FreshPlaybackAction.AUDIO)) {
                    is FreshPlaybackResolution.Denied -> applyDeniedResolution(resolution)
                    is FreshPlaybackResolution.Allowed -> {
                        val details = resolution.details
                        _uiState.update {
                            it.copy(
                                mode = VideoMode.Loaded(details, resolution.policy),
                                errorMessage = null,
                            )
                        }
                        if (!player.awaitReady()) {
                            _uiState.update { it.copy(errorMessage = "Audio player is unavailable.") }
                            return@launch
                        }
                        _audioStarted.value = false
                        _audioPlayRequested.value = false
                        queueAudio(details, resumePositionMs = if (details.summary.liveNow) 0L else positionMs)
                        _audioStarted.value = true
                        _uiState.update { it.copy(finished = false) }
                        _audioPlayRequested.value = playWhenReady
                        if (playWhenReady) player.play()
                        recordPlayback(details.summary)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.userMessage("Could not start audio.")) }
            } finally {
                finishPlaybackAction()
            }
        }
    }

    internal fun watch(onAuthorized: (VideoDetails, VideoPlaybackSource, PlaybackStart) -> Unit) {
        if (_uiState.value.mode !is VideoMode.Loaded) return
        if (!beginPlaybackAction()) return

        viewModelScope.launch {
            try {
                when (val resolution = freshPlayback(FreshPlaybackAction.WATCH)) {
                    is FreshPlaybackResolution.Denied -> applyDeniedResolution(resolution)
                    is FreshPlaybackResolution.Allowed -> {
                        val details = resolution.details
                        val source = checkNotNull(details.watchSource)
                        _uiState.update {
                            it.copy(
                                mode = VideoMode.Loaded(details, resolution.policy),
                                errorMessage = null,
                            )
                        }
                        val start = videoStartFromAudio(
                            audioStarted = _audioStarted.value,
                            audioPositionMs = player.positionMs.value,
                            audioPlaying = _audioPlayRequested.value,
                            savedPositionMs = resumePositionMs,
                        )
                        resumePositionMs = start.positionMs
                        _audioStarted.value = false
                        _audioPlayRequested.value = false
                        player.pause()
                        player.setMediaQueue(emptyList())
                        recordPlayback(details.summary)
                        onAuthorized(details, source, start)
                    }
                }
            } finally {
                finishPlaybackAction()
            }
        }
    }

    fun toggleAudio() {
        if (_audioPlayRequested.value) {
            _audioPlayRequested.value = false
            player.pause()
            return
        }
        if (!_audioStarted.value) return
        resumePositionMs = player.positionMs.value
        startAudio(resumePositionMs, playWhenReady = true)
    }

    fun onVideoResult(result: VideoPlaybackResult) {
        when (result) {
            is VideoPlaybackResult.Stopped -> resumePositionMs = result.positionMs
            is VideoPlaybackResult.Listen -> {
                resumePositionMs = result.positionMs
                startAudio(result.positionMs, result.playWhenReady)
            }
            VideoPlaybackResult.Finished -> playbackFinished()
        }
    }

    fun saveVideoPosition(positionMs: Long) {
        resumePositionMs = positionMs.coerceAtLeast(0L)
    }

    fun playbackFinished() {
        _audioPlayRequested.value = false
        player.pause()
        _uiState.update { it.copy(finished = true) }
    }

    fun markWatched() {
        if (_uiState.value.watched || _uiState.value.savingWatched) return
        val video = (_uiState.value.mode as? VideoMode.Loaded)?.details?.summary ?: return
        _uiState.update { it.copy(savingWatched = true) }
        viewModelScope.launch(Dispatchers.IO) {
            var markedLocally = false
            try {
                services.history.markWatched(video)
                markedLocally = true
                // Completion is durable locally before optional network sync starts.
                val currentSettings = services.settings.load()
                val account = services.accounts.load(currentSettings.instanceUrl)
                services.historySyncer.queueWatched(video, currentSettings, account)
                _uiState.update { it.copy(watched = true, savingWatched = false) }
                services.historySyncer.syncWatched(video, currentSettings, account)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!markedLocally) {
                    _uiState.update { it.copy(errorMessage = error.userMessage("Could not mark watched. Please try again.")) }
                }
            } finally {
                _uiState.update { it.copy(watched = it.watched || markedLocally, savingWatched = false) }
            }
        }
    }

    fun skipAudioBack() = player.skipBack()
    fun skipAudioForward() = player.skipForward()

    fun download() {
        if (_uiState.value.mode !is VideoMode.Loaded || !beginPlaybackAction()) return
        viewModelScope.launch {
            try {
                when (val resolution = freshPlayback(FreshPlaybackAction.DISPLAY)) {
                    is FreshPlaybackResolution.Denied -> applyDeniedResolution(resolution)
                    is FreshPlaybackResolution.Allowed -> withContext(Dispatchers.IO) {
                        val session = services.companion.load(settings.instanceUrl).session
                            ?: error("Pair this phone before downloading.")
                        val kind = selectDownloadPlan(
                            resolution.details,
                            resolution.policy,
                            settings.audioLanguage,
                        ).getOrThrow().kind
                        services.downloads.queue(session.deviceId, resolution.details.summary, kind)
                        if (!scheduleDownload(session.deviceId, initialVideo.videoId, kind)) {
                            services.downloads.markFailed(
                                session.deviceId,
                                initialVideo.videoId,
                                "The background download service is unavailable.",
                            )
                        }
                        _uiState.update { state ->
                            state.copy(
                                mode = VideoMode.Loaded(resolution.details, resolution.policy),
                                errorMessage = null,
                            )
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(errorMessage = error.userMessage("Could not start the download."))
                }
            } finally {
                finishPlaybackAction()
            }
        }
    }

    fun cancelDownload() {
        val download = _uiState.value.download ?: return
        cancelScheduledDownload(download.ownerDeviceId, download.videoId)
        viewModelScope.launch(Dispatchers.IO) {
            services.downloads.cancel(download.ownerDeviceId, download.videoId)
        }
    }
    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    private suspend fun queueAudio(details: VideoDetails, resumePositionMs: Long) {
        val audioUrl = checkNotNull(details.audioUrl)
        prepareAudioAt(
            player = player,
            item = LightAudioItem(
                source = LightAudioSource.UrlSource(audioUrl),
                metadata = LightMediaMetadata(
                    title = details.summary.title,
                    artist = details.summary.author,
                    album = "Lightious",
                    durationMs = details.summary.lengthSeconds
                        .takeIf { it > 0L }
                        ?.times(1_000L),
                ),
            ),
            positionMs = resumePositionMs,
        )
    }

    private fun recordPlayback(video: VideoSummary) {
        if (playbackRecorded) return
        playbackRecorded = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentSettings = services.settings.load()
                val account = services.accounts.load(currentSettings.instanceUrl)
                services.historySyncer.recordPlayback(video, currentSettings, account)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Playback must remain available if local persistence or optional sync fails.
            }
        }
    }

    private suspend fun resolvePlayback(action: FreshPlaybackAction): FreshPlaybackResolution =
        resolveFreshPlayback(
            videoId = initialVideo.videoId,
            action = action,
            fetchDetails = ::fetchDetails,
            authorize = { details ->
                services.companion.authorizePlayback(
                    settings.instanceUrl,
                    details.summary.videoId,
                    details.summary.authorId,
                    details.summary.isShort,
                )
            },
        )

    private suspend fun fetchDetails(videoId: String): Result<VideoDetails> {
        val companion = services.companion.loadActiveState(settings.instanceUrl).getOrElse { error ->
            return Result.failure(error)
        }
        return InvidiousApi(
            baseUrl = settings.instanceUrl,
            proxyMedia = settings.proxyMedia,
            deviceBearer = companion.session?.deviceBearer,
            audioLanguage = settings.audioLanguage,
        ).use { api ->
            api.video(videoId)
        }
    }

    private suspend fun freshPlayback(action: FreshPlaybackAction): FreshPlaybackResolution =
        withContext(Dispatchers.IO) { resolvePlayback(action) }

    private fun beginPlaybackAction(): Boolean {
        if (!playbackActionGate.tryAcquire()) return false
        _uiState.update { it.copy(checkingAction = true, errorMessage = null) }
        return true
    }

    private fun finishPlaybackAction() {
        playbackActionGate.release()
        _uiState.update { it.copy(checkingAction = false) }
    }

    private fun applyDeniedResolution(resolution: FreshPlaybackResolution.Denied) {
        val details = resolution.details
        val policy = resolution.policy
        if (resolution.invalidateAudio) {
            player.pause()
            player.setMediaQueue(emptyList())
            _audioStarted.value = false
            _audioPlayRequested.value = false
        }
        if (details == null && !resolution.invalidateAudio) {
            _uiState.update { it.copy(errorMessage = resolution.message) }
            return
        }
        if (details != null && policy != null) {
            _uiState.update {
                it.copy(
                    mode = VideoMode.Loaded(details, policy),
                    errorMessage = resolution.message,
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    mode = VideoMode.Failed(resolution.message),
                    errorMessage = null,
                )
            }
        }
    }
}

class VideoScreen(
    private val sealedActivity: SealedLightActivity,
    private val settings: ClientSettings,
    private val initialVideo: VideoSummary,
    private val services: LightiousServices,
) : LightScreen<Unit, VideoViewModel>(sealedActivity) {
    override val viewModelClass = VideoViewModel::class.java

    override fun createViewModel() = VideoViewModel(
        settings = settings,
        audio = DefaultLightAudio(sealedActivity),
        initialVideo = initialVideo,
        services = services,
        scheduleDownload = { ownerDeviceId, videoId, kind ->
            enqueueDownload(lightContext, ownerDeviceId, videoId, kind)
        },
        cancelScheduledDownload = { ownerDeviceId, videoId ->
            cancelDownload(lightContext, ownerDeviceId, videoId)
        },
    )

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val audioStarted by viewModel.audioStarted.collectAsState()
        val audioPlaying by viewModel.audioPlaying.collectAsState()
        val audioPlayRequested by viewModel.audioPlayRequested.collectAsState()
        val audioPosition by viewModel.audioPositionMs.collectAsState()
        val audioDuration by viewModel.audioDurationMs.collectAsState()
        val audioError by viewModel.audioError.collectAsState()
        val loadedMode = state.mode as? VideoMode.Loaded
        val presentingAudio = loadedMode?.let { mode ->
            shouldPresentAudioPlayer(mode.playbackPolicy, audioStarted)
        } == true

        LaunchedEffect(audioStarted, audioPlaying, audioPosition, audioDuration, audioError, state.checkingAction) {
            if (audioPlaybackFinished(
                    started = audioStarted,
                    playing = audioPlaying,
                    positionMs = audioPosition,
                    durationMs = audioDuration,
                    failed = audioError != null,
                    changingPlayback = state.checkingAction,
                )
            ) {
                viewModel.playbackFinished()
            }
        }

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
                        center = LightTopBarCenter.Text(if (state.finished) "Finished" else if (presentingAudio) "Listen" else "Video"),
                        rightButton = loadedMode?.takeUnless { state.finished }?.let { mode ->
                            videoDownloadButton(
                                download = state.download,
                                desiredKind = mode.playbackPolicy.downloadKind,
                                checkingAction = state.checkingAction,
                                onDownload = viewModel::download,
                                onCancel = viewModel::cancelDownload,
                            )
                        },
                    )

                    when (val mode = state.mode) {
                        VideoMode.Loading -> VideoLoadingContent(Modifier.weight(1f))
                        is VideoMode.Failed -> VideoFailureContent(
                            message = mode.message,
                            onRetry = viewModel::load,
                            modifier = Modifier.weight(1f),
                        )
                        is VideoMode.Loaded -> {
                            if (state.finished) {
                                PlaybackFinishedContent(
                                    title = mode.details.summary.title,
                                    watched = state.watched,
                                    saving = state.savingWatched,
                                    onMarkWatched = viewModel::markWatched,
                                    onClose = { goBack() },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                if (state.checkingAction && !presentingAudio) {
                                    VideoLoadingContent(Modifier.weight(1f), "Preparing playback…")
                                } else if (presentingAudio) {
                                    AudioPlaybackContent(
                                        title = mode.details.summary.title,
                                        author = mode.details.summary.author,
                                        detailLines = buildList {
                                            if (state.checkingAction) add("Loading playback…")
                                            videoMetadataLine(mode.details.summary)
                                                .takeIf(String::isNotBlank)
                                                ?.let(::add)
                                            add(
                                                if (mode.playbackPolicy == PlaybackPolicy.LISTEN_ONLY) {
                                                    "LISTEN ONLY"
                                                } else {
                                                    "AUDIO"
                                                },
                                            )
                                            state.download?.let { add(downloadStatusLabel(it)) }
                                        },
                                        positionMs = audioPosition,
                                        durationMs = resolvedAudioDurationMs(
                                            playerDurationMs = audioDuration,
                                            metadataDurationSeconds = mode.details.summary.lengthSeconds,
                                        ),
                                        errorMessage = audioError?.let { "Audio failed: ${it.kind}: ${it.diagnostic}" },
                                        modifier = Modifier.weight(1f),
                                    )
                                } else {
                                    VideoDetailsContent(
                                        details = mode.details,
                                        playbackPolicy = mode.playbackPolicy,
                                        download = state.download,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                VideoActions(
                                    details = mode.details,
                                    playbackPolicy = mode.playbackPolicy,
                                    audioStarted = audioStarted,
                                    audioPlaying = audioPlayRequested,
                                    presentingAudio = presentingAudio,
                                    checkingAction = state.checkingAction,
                                    onWatch = {
                                        viewModel.watch { details, source, start ->
                                            navigateTo(
                                                screenFactory = { activity ->
                                                    VideoPlaybackScreen(
                                                        sealedActivity = activity,
                                                        playbackSource = source,
                                                        initialPositionMs = start.positionMs,
                                                        playWhenReady = start.playWhenReady,
                                                        canListen = details.audioUrl != null,
                                                        onPositionSaved = viewModel::saveVideoPosition,
                                                    )
                                                },
                                                resultCallback = viewModel::onVideoResult,
                                            )
                                        }
                                    },
                                    onListen = viewModel::playAudio,
                                    onToggleAudio = viewModel::toggleAudio,
                                    onSkipBack = viewModel::skipAudioBack,
                                    onSkipForward = viewModel::skipAudioForward,
                                )
                            }
                        }
                    }
                }

                state.errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }
}

private fun videoDownloadButton(
    download: DownloadedMedia?,
    desiredKind: DownloadKind,
    checkingAction: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
): LightBarButton = when (videoDownloadAction(download, desiredKind)) {
    VideoDownloadAction.CANCEL -> LightBarButton.LightIcon(
        icon = LightIcons.CLOSE,
        onClick = onCancel,
        contentDescription = "Cancel download",
    )
    VideoDownloadAction.AVAILABLE -> LightBarButton.LightIcon(
        icon = LightIcons.DOWNLOAD_ARROW,
        onClick = null,
        contentDescription = "Available offline",
    )
    VideoDownloadAction.DOWNLOAD,
    VideoDownloadAction.REPLACE,
    VideoDownloadAction.RETRY,
    -> LightBarButton.LightIcon(
        icon = if (videoDownloadAction(download, desiredKind) == VideoDownloadAction.RETRY) {
            LightIcons.REFRESH
        } else {
            LightIcons.DOWNLOAD_ARROW
        },
        onClick = onDownload.takeUnless { checkingAction },
        contentDescription = when (videoDownloadAction(download, desiredKind)) {
            VideoDownloadAction.REPLACE -> "Replace with ${desiredKind.wireValue} download"
            VideoDownloadAction.RETRY -> "Retry download"
            else -> "Download"
        },
    )
}

internal enum class VideoDownloadAction {
    DOWNLOAD,
    REPLACE,
    RETRY,
    CANCEL,
    AVAILABLE,
}

internal fun videoDownloadAction(
    download: DownloadedMedia?,
    desiredKind: DownloadKind,
): VideoDownloadAction = when (download?.state) {
    DownloadState.QUEUED,
    DownloadState.DOWNLOADING,
    -> VideoDownloadAction.CANCEL
    DownloadState.COMPLETE -> if (download.kind == desiredKind) {
        VideoDownloadAction.AVAILABLE
    } else {
        VideoDownloadAction.REPLACE
    }
    DownloadState.FAILED,
    DownloadState.CANCELLED,
    -> VideoDownloadAction.RETRY
    null -> VideoDownloadAction.DOWNLOAD
}

private val PlaybackPolicy.downloadKind: DownloadKind
    get() = when (this) {
        PlaybackPolicy.LISTEN_ONLY -> DownloadKind.AUDIO
        PlaybackPolicy.WATCH_AND_LISTEN -> DownloadKind.VIDEO
    }

@Composable
private fun VideoLoadingContent(modifier: Modifier = Modifier, message: String = "Loading video…") {
    LightText(
        text = message,
        variant = LightTextVariant.Copy,
        modifier = modifier
            .fillMaxWidth()
            .padding(1f.gridUnitsAsDp()),
    )
}

@Composable
private fun VideoFailureContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LightScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = message,
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
        )
        LightText(
            text = "RETRY",
            variant = LightTextVariant.Button,
            underline = true,
            modifier = Modifier
                .padding(top = 1f.gridUnitsAsDp())
                .lightClickable(onClick = onRetry),
        )
    }
}

@Composable
private fun VideoDetailsContent(
    details: VideoDetails,
    playbackPolicy: PlaybackPolicy,
    download: DownloadedMedia?,
    modifier: Modifier = Modifier,
) {
    LightScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = details.summary.title,
            variant = LightTextVariant.Subheading,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
        )
        LightText(
            text = details.summary.author,
            variant = LightTextVariant.Copy,
            lighten = true,
            modifier = Modifier.padding(top = 0.35f.gridUnitsAsDp()),
        )
        LightText(
            text = videoMetadataLine(details.summary),
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
        )
        LightText(
            text = if (playbackPolicy == PlaybackPolicy.LISTEN_ONLY) {
                "LISTEN ONLY"
            } else {
                streamLabel(details.selection)
            },
            variant = LightTextVariant.Superfine,
            lighten = true,
            modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
        )
        download?.let { item ->
            LightText(
                text = downloadStatusLabel(item),
                variant = LightTextVariant.Fine,
                monospace = true,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
            item.errorMessage?.takeIf { item.state != DownloadState.COMPLETE }?.let { message ->
                LightText(
                    text = message,
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                )
            }
        }
        if (details.description.isNotBlank()) {
            LightText(
                text = details.description,
                variant = LightTextVariant.Paragraph,
                maxLines = 20,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
private fun VideoActions(
    details: VideoDetails,
    playbackPolicy: PlaybackPolicy,
    audioStarted: Boolean,
    audioPlaying: Boolean,
    presentingAudio: Boolean,
    checkingAction: Boolean,
    onWatch: () -> Unit,
    onListen: () -> Unit,
    onToggleAudio: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
) {
    val watchSource = if (playbackPolicy == PlaybackPolicy.WATCH_AND_LISTEN) {
        details.watchSource
    } else {
        null
    }
    if (presentingAudio) {
        val watchButton = watchSource?.let {
            LightBarButton.LightIcon(
                icon = LightIcons.MEDIA,
                onClick = onWatch.takeUnless { checkingAction },
                contentDescription = "Watch video",
            )
        }
        LightBottomBar(
            items = playbackTransportItems(
                playing = audioPlaying,
                canSeek = audioStarted,
                playPauseEnabled = details.audioUrl != null && (!checkingAction || audioPlaying),
                onSeekBack = onSkipBack,
                onTogglePlayback = if (audioStarted) onToggleAudio else onListen,
                onSeekForward = onSkipForward,
                trailingItem = watchButton,
            ),
        )
    } else {
        LightBottomBar(
            items = buildList {
                if (watchSource != null) {
                    add(
                        LightBarButton.Text(
                            text = "WATCH",
                            onClick = onWatch.takeUnless { checkingAction },
                        ),
                    )
                }
                add(
                    LightBarButton.Text(
                        text = "LISTEN",
                        onClick = onListen.takeIf { details.audioUrl != null && !checkingAction },
                    ),
                )
            },
        )
    }
}

internal fun streamLabel(selection: StreamSelection): String = when {
    selection.liveHls != null -> "LIVE HLS"
    selection.progressive != null -> listOfNotNull(
        selection.progressive.qualityLabel,
        selection.progressive.container?.uppercase(),
    )
        .joinToString("  ·  ")
        .ifBlank { "PROGRESSIVE VIDEO" }
    selection.adaptiveVideo != null && selection.adaptiveAudio != null -> listOfNotNull(
        selection.adaptiveVideo.qualityLabel,
        selection.adaptiveVideo.container?.uppercase(),
        "ADAPTIVE",
    ).joinToString("  ·  ")
    else -> "NO COMPATIBLE VIDEO STREAM"
}

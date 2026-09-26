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
import com.loosewire.lightious.data.DownloadKind
import com.loosewire.lightious.data.DownloadState
import com.loosewire.lightious.data.DownloadedMedia
import com.loosewire.lightious.data.VideoPlaybackSource
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class DownloadPrimaryAction {
    PLAY_AUDIO,
    WATCH_VIDEO,
    CANCEL,
    RETRY,
    NONE,
}

internal fun downloadedMediaPrimaryAction(download: DownloadedMedia?): DownloadPrimaryAction =
    when {
        download == null || download.isShort -> DownloadPrimaryAction.NONE
        else -> when (download.state) {
            DownloadState.QUEUED, DownloadState.DOWNLOADING -> DownloadPrimaryAction.CANCEL
            DownloadState.FAILED, DownloadState.CANCELLED -> DownloadPrimaryAction.RETRY
            DownloadState.COMPLETE -> if (download.kind == DownloadKind.AUDIO) {
                DownloadPrimaryAction.PLAY_AUDIO
            } else {
                DownloadPrimaryAction.WATCH_VIDEO
            }
        }
    }

internal data class DownloadedMediaUiState(
    val loaded: Boolean = false,
    val download: DownloadedMedia? = null,
    val audioStarted: Boolean = false,
    val changingPlayback: Boolean = false,
    val finished: Boolean = false,
    val watched: Boolean = false,
    val savingWatched: Boolean = false,
    val errorMessage: String? = null,
)

internal fun downloadedMediaPresentsAudio(state: DownloadedMediaUiState): Boolean =
    state.download?.let { download ->
        download.state == DownloadState.COMPLETE && !download.isShort &&
            (download.kind == DownloadKind.AUDIO || state.audioStarted)
    } == true

internal class DownloadedMediaViewModel(
    private val services: LightiousServices,
    private val audio: LightAudio,
    private val ownerDeviceId: String,
    private val videoId: String,
    private val scheduleDownload: (String, String, DownloadKind) -> Boolean,
    private val cancelScheduledDownload: (String, String) -> Unit,
) : LightViewModel<Unit>() {
    private val player = audio.newPlayer(
        usage = LightAudioUsage.Speech,
        playback = LightAudioPlayback.Detached,
    )
    private val _uiState = MutableStateFlow(DownloadedMediaUiState())
    val uiState: StateFlow<DownloadedMediaUiState> = _uiState.asStateFlow()
    val audioPlaying = player.isPlaying
    val audioPositionMs = player.positionMs
    val audioDurationMs = player.durationMs
    val audioError = player.error
    private val _audioPlayRequested = MutableStateFlow(false)
    val audioPlayRequested: StateFlow<Boolean> = _audioPlayRequested.asStateFlow()
    private var playbackRecorded = false
    private var pendingPositionMs = 0L
    private val playbackActionGate = PlaybackActionGate()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val watched = services.history.isWatched(videoId)
                _uiState.update { it.copy(watched = it.watched || watched) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A completion lookup must not prevent playback; marking reports write failures.
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            services.downloads.observeAll().collect { downloads ->
                _uiState.update { state ->
                    state.copy(
                        loaded = true,
                        download = downloads.firstOrNull { download ->
                            download.ownerDeviceId == ownerDeviceId && download.videoId == videoId
                        },
                    )
                }
            }
        }
    }

    fun toggleAudio() {
        if (_uiState.value.changingPlayback) return
        if (_audioPlayRequested.value) {
            _audioPlayRequested.value = false
            player.pause()
            return
        }
        val positionMs = if (_uiState.value.audioStarted) player.positionMs.value else pendingPositionMs
        startAudio(positionMs, playWhenReady = true)
    }

    private fun startAudio(positionMs: Long, playWhenReady: Boolean) {
        val download = _uiState.value.download
            ?.takeIf { !it.isShort && it.state == DownloadState.COMPLETE }
            ?: return
        if (!beginPlaybackAction()) return
        viewModelScope.launch {
            try {
                val file = services.downloads.localFile(download)
                    ?: error("The downloaded file is missing. Download it again to play it.")
                if (!player.awaitReady()) error("Audio player is unavailable.")
                // Retain the handoff position even if preparing the new queue fails.
                pendingPositionMs = positionMs.coerceAtLeast(0L)
                _audioPlayRequested.value = false
                _uiState.update { it.copy(audioStarted = false) }
                prepareAudioAt(
                    player = player,
                    item = LightAudioItem(
                        source = LightAudioSource.FileSource(file),
                        metadata = LightMediaMetadata(
                            title = download.title,
                            artist = download.author,
                            album = "Lightious Downloads",
                            durationMs = download.lengthSeconds
                                .takeIf { it > 0L }
                                ?.times(1_000L),
                        ),
                    ),
                    positionMs = pendingPositionMs,
                )
                _uiState.update { it.copy(audioStarted = true, finished = false) }
                recordPlayback(download)
                _audioPlayRequested.value = playWhenReady
                if (playWhenReady) player.play()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.userMessage("Could not play this download.")) }
            } finally {
                finishPlaybackAction()
            }
        }
    }

    fun watch(onReady: (VideoPlaybackSource.Single, PlaybackStart) -> Unit) {
        val download = _uiState.value.download
            ?.takeIf { downloadedMediaPrimaryAction(it) == DownloadPrimaryAction.WATCH_VIDEO }
            ?: return
        if (!beginPlaybackAction()) return
        try {
            val source = services.downloads.localVideoSource(download)
                ?: error("The downloaded file is missing. Download it again to play it.")
            val start = videoStartFromAudio(
                audioStarted = _uiState.value.audioStarted,
                audioPositionMs = player.positionMs.value,
                audioPlaying = _audioPlayRequested.value,
                savedPositionMs = pendingPositionMs,
            )
            pendingPositionMs = start.positionMs
            _uiState.update { it.copy(audioStarted = false, finished = false) }
            _audioPlayRequested.value = false
            player.pause()
            player.setMediaQueue(emptyList())
            recordPlayback(download)
            onReady(source, start)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = error.userMessage("Could not play this download.")) }
        } finally {
            finishPlaybackAction()
        }
    }

    fun onVideoResult(result: VideoPlaybackResult) {
        when (result) {
            is VideoPlaybackResult.Stopped -> pendingPositionMs = result.positionMs
            is VideoPlaybackResult.Listen -> {
                pendingPositionMs = result.positionMs
                startAudio(result.positionMs, result.playWhenReady)
            }
            VideoPlaybackResult.Finished -> playbackFinished()
        }
    }

    fun saveVideoPosition(positionMs: Long) {
        pendingPositionMs = positionMs.coerceAtLeast(0L)
    }

    fun playbackFinished() {
        _audioPlayRequested.value = false
        player.pause()
        _uiState.update { it.copy(finished = true) }
    }

    fun markWatched() {
        val state = _uiState.value
        if (state.watched || state.savingWatched) return
        val video = state.download?.asVideoSummary() ?: return
        _uiState.update { it.copy(savingWatched = true) }
        viewModelScope.launch(Dispatchers.IO) {
            var markedLocally = false
            try {
                services.history.markWatched(video)
                markedLocally = true
                // Offline completion is durable before attempting optional network sync.
                val settings = services.settings.load()
                val account = services.accounts.load(settings.instanceUrl)
                services.historySyncer.queueWatched(video, settings, account)
                _uiState.update { it.copy(watched = true, savingWatched = false) }
                services.historySyncer.syncWatched(video, settings, account)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!markedLocally) {
                    _uiState.update {
                        it.copy(errorMessage = error.userMessage("Could not mark watched. Please try again."))
                    }
                }
            } finally {
                _uiState.update { it.copy(watched = it.watched || markedLocally, savingWatched = false) }
            }
        }
    }

    private fun beginPlaybackAction(): Boolean {
        if (!playbackActionGate.tryAcquire()) return false
        _uiState.update { it.copy(changingPlayback = true, errorMessage = null) }
        return true
    }

    private fun finishPlaybackAction() {
        playbackActionGate.release()
        _uiState.update { it.copy(changingPlayback = false) }
    }

    fun retry() {
        val download = _uiState.value.download ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                services.downloads.queue(ownerDeviceId, download.asVideoSummary(), download.kind)
                if (!scheduleDownload(ownerDeviceId, videoId, download.kind)) {
                    services.downloads.markFailed(
                        ownerDeviceId,
                        videoId,
                        "The background download service is unavailable.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.userMessage("Could not retry the download.")) }
            }
        }
    }

    fun cancel() {
        cancelScheduledDownload(ownerDeviceId, videoId)
        viewModelScope.launch(Dispatchers.IO) {
            services.downloads.cancel(ownerDeviceId, videoId)
        }
    }

    fun delete() {
        cancelScheduledDownload(ownerDeviceId, videoId)
        _audioPlayRequested.value = false
        player.pause()
        player.setMediaQueue(emptyList())
        viewModelScope.launch(Dispatchers.IO) {
            services.downloads.delete(ownerDeviceId, videoId)
        }
    }

    fun skipBack() = player.skipBack()
    fun skipForward() = player.skipForward()
    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun recordPlayback(download: DownloadedMedia) {
        if (playbackRecorded) return
        playbackRecorded = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = services.settings.load()
                val account = services.accounts.load(settings.instanceUrl)
                services.historySyncer.recordPlayback(download.asVideoSummary(), settings, account)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Offline playback must not depend on local history or optional sync.
            }
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}

internal class DownloadedMediaScreen(
    private val sealedActivity: SealedLightActivity,
    private val services: LightiousServices,
    private val ownerDeviceId: String,
    private val videoId: String,
) : LightScreen<Unit, DownloadedMediaViewModel>(sealedActivity) {
    override val viewModelClass = DownloadedMediaViewModel::class.java

    override fun createViewModel() = DownloadedMediaViewModel(
        services = services,
        audio = DefaultLightAudio(sealedActivity),
        ownerDeviceId = ownerDeviceId,
        videoId = videoId,
        scheduleDownload = { owner, video, kind ->
            enqueueDownload(lightContext, owner, video, kind)
        },
        cancelScheduledDownload = { owner, video -> cancelDownload(lightContext, owner, video) },
    )

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val audioPlaying by viewModel.audioPlaying.collectAsState()
        val audioPlayRequested by viewModel.audioPlayRequested.collectAsState()
        val audioPositionMs by viewModel.audioPositionMs.collectAsState()
        val audioDurationMs by viewModel.audioDurationMs.collectAsState()
        val audioError by viewModel.audioError.collectAsState()
        val presentingAudio = downloadedMediaPresentsAudio(state)
        val onWatch: () -> Unit = {
            viewModel.watch { source, start ->
                navigateTo(
                    screenFactory = { activity ->
                        VideoPlaybackScreen(
                            sealedActivity = activity,
                            playbackSource = source,
                            initialPositionMs = start.positionMs,
                            playWhenReady = start.playWhenReady,
                            canListen = true,
                            onPositionSaved = viewModel::saveVideoPosition,
                        )
                    },
                    resultCallback = viewModel::onVideoResult,
                )
            }
        }

        LaunchedEffect(
            state.audioStarted,
            audioPlaying,
            audioPositionMs,
            audioDurationMs,
            audioError,
            state.changingPlayback,
        ) {
            if (audioPlaybackFinished(
                    started = state.audioStarted,
                    playing = audioPlaying,
                    positionMs = audioPositionMs,
                    durationMs = audioDurationMs,
                    failed = audioError != null,
                    changingPlayback = state.changingPlayback,
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
                        center = LightTopBarCenter.Text(
                            when {
                                state.finished -> "Finished"
                                presentingAudio -> "Listen"
                                else -> "Offline"
                            },
                        ),
                        rightButton = if (state.finished) {
                            null
                        } else if (presentingAudio && state.download?.kind == DownloadKind.VIDEO) {
                            LightBarButton.Text(
                                text = "WATCH",
                                onClick = onWatch.takeUnless { state.changingPlayback },
                            )
                        } else state.download?.let {
                            LightBarButton.LightIcon(
                                icon = LightIcons.DELETE,
                                onClick = {
                                    navigateTo(
                                        screenFactory = { activity ->
                                            ConfirmScreen(
                                                activity,
                                                title = "Delete Download",
                                                message = "Remove this saved copy from your phone?",
                                                confirmLabel = "DELETE",
                                            )
                                        },
                                        resultCallback = { confirmed ->
                                            if (confirmed) {
                                                viewModel.delete()
                                                goBack()
                                            }
                                        },
                                    )
                                },
                                contentDescription = "Delete download",
                            )
                        },
                    )
                    if (state.finished) {
                        PlaybackFinishedContent(
                            title = state.download?.title.orEmpty(),
                            watched = state.watched,
                            saving = state.savingWatched,
                            onMarkWatched = viewModel::markWatched,
                            onClose = { goBack() },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        DownloadedMediaContent(
                            state = state,
                            audioPositionMs = audioPositionMs,
                            audioDurationMs = audioDurationMs,
                            audioError = audioError?.let { "Audio failed: ${it.kind}: ${it.diagnostic}" },
                            modifier = Modifier.weight(1f),
                        )
                        DownloadedMediaActions(
                            state = state,
                            audioPlaying = audioPlayRequested,
                            onAudio = viewModel::toggleAudio,
                            onWatch = onWatch,
                            onRetry = viewModel::retry,
                            onCancel = viewModel::cancel,
                            onSkipBack = viewModel::skipBack,
                            onSkipForward = viewModel::skipForward,
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

@Composable
private fun DownloadedMediaContent(
    state: DownloadedMediaUiState,
    audioPositionMs: Long,
    audioDurationMs: Long,
    audioError: String?,
    modifier: Modifier = Modifier,
) {
    val download = state.download
    if (download != null && downloadedMediaPresentsAudio(state)) {
        AudioPlaybackContent(
            title = download.title,
            author = download.author,
            detailLines = listOf(downloadStatusLabel(download)),
            positionMs = audioPositionMs,
            durationMs = resolvedAudioDurationMs(
                playerDurationMs = audioDurationMs,
                metadataDurationSeconds = download.lengthSeconds,
            ),
            errorMessage = audioError,
            modifier = modifier,
        )
        return
    }

    LightScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        when {
            !state.loaded -> LightText(
                text = "Loading download…",
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
            download == null -> LightText(
                text = "This download is no longer on the phone.",
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
            else -> {
                LightText(
                    text = download.title,
                    variant = LightTextVariant.Subheading,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                LightText(
                    text = download.author,
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.35f.gridUnitsAsDp()),
                )
                LightText(
                    text = downloadStatusLabel(download),
                    variant = LightTextVariant.Fine,
                    monospace = true,
                    modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                )
                download.errorMessage?.let { message ->
                    LightText(
                        text = message,
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadedMediaActions(
    state: DownloadedMediaUiState,
    audioPlaying: Boolean,
    onAudio: () -> Unit,
    onWatch: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
) {
    val primaryAction = if (downloadedMediaPresentsAudio(state)) {
        DownloadPrimaryAction.PLAY_AUDIO
    } else {
        downloadedMediaPrimaryAction(state.download)
    }
    when (primaryAction) {
        DownloadPrimaryAction.PLAY_AUDIO -> LightBottomBar(
            items = playbackTransportItems(
                playing = audioPlaying,
                canSeek = state.audioStarted && !state.changingPlayback,
                playPauseEnabled = !state.changingPlayback,
                onSeekBack = onSkipBack,
                onTogglePlayback = onAudio,
                onSeekForward = onSkipForward,
            ),
        )
        DownloadPrimaryAction.WATCH_VIDEO -> LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "WATCH", onClick = onWatch.takeUnless { state.changingPlayback }),
                LightBarButton.Text(text = "LISTEN", onClick = onAudio.takeUnless { state.changingPlayback }),
            ),
        )
        DownloadPrimaryAction.CANCEL -> LightBottomBar(
            items = listOf(LightBarButton.Text(text = "CANCEL", onClick = onCancel)),
        )
        DownloadPrimaryAction.RETRY -> LightBottomBar(
            items = listOf(LightBarButton.Text(text = "RETRY", onClick = onRetry)),
        )
        DownloadPrimaryAction.NONE -> Unit
    }
}

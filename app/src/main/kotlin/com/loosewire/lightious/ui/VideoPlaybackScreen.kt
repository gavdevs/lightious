package com.loosewire.lightious.ui

import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.loosewire.lightious.data.MediaStream
import com.loosewire.lightious.data.VideoPlaybackSource
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightScrollBarPosition
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Sideload-only video surface. The surrounding navigation and controls stay in
 * the Light SDK vocabulary; Media3 owns decoding and network playback.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoPlaybackScreen(
    sealedActivity: SealedLightActivity,
    private val playbackSource: VideoPlaybackSource,
    private val initialPositionMs: Long = 0L,
    private val playWhenReady: Boolean = true,
    private val canListen: Boolean = false,
    private val onPositionSaved: (Long) -> Unit = {},
) : SimpleLightScreen<VideoPlaybackResult>(sealedActivity) {
    private var player: ExoPlayer? = null
    private var videoTexture: TextureView? = null
    private val playbackExit = VideoPlaybackExit(
        stopPlayback = ::releasePlayer,
        returnResult = { result -> super.goBack(result) },
    )

    override fun goBack(result: VideoPlaybackResult?) {
        playbackExit.stop(player?.currentPosition ?: initialPositionMs)
    }

    private fun switchToListen() {
        if (!canListen) return
        playbackExit.listen(
            positionMs = player?.currentPosition ?: initialPositionMs,
            playWhenReady = player?.playWhenReady ?: playWhenReady,
        )
    }

    override fun willHide() {
        pauseAndSavePosition()
    }

    override fun onAppPause() {
        pauseAndSavePosition()
    }

    private fun pauseAndSavePosition() {
        videoTexture?.keepScreenOn = false
        player?.let {
            // System Back invokes this SDK lifecycle hook without a typed result.
            onPositionSaved(it.currentPosition.coerceAtLeast(0L))
            it.pause()
        }
    }

    override fun onScreenDestroy() {
        releasePlayer()
    }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        var playing by remember { mutableStateOf(playWhenReady) }
        var ready by remember { mutableStateOf(false) }
        var positionMs by remember { mutableLongStateOf(initialPositionMs.coerceAtLeast(0L)) }
        var durationMs by remember { mutableLongStateOf(C.TIME_UNSET) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var fullscreen by remember { mutableStateOf(false) }
        var displayAspectRatio by remember(playbackSource) {
            mutableStateOf(playbackSource.initialVideoAspectRatio())
        }
        val videoScrollState = rememberScrollState()
        val fullscreenPainter = remember(colors.content, fullscreen) {
            FullscreenCornersPainter(
                color = colors.content,
                collapse = fullscreen,
            )
        }

        val listener = remember {
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    videoTexture?.keepScreenOn = shouldKeepVideoScreenOn(isPlaying)
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    playing = playWhenReady
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    ready = playbackState == Player.STATE_READY
                    player?.let { durationMs = it.duration }
                    if (playbackState == Player.STATE_ENDED) playbackExit.finish()
                }

                override fun onPlayerError(error: PlaybackException) {
                    videoTexture?.keepScreenOn = false
                    errorMessage = "Playback failed: ${PlaybackException.getErrorCodeName(error.errorCode)}"
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    displayAspectRatio = videoAspectRatio(
                        width = videoSize.width,
                        height = videoSize.height,
                        pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
                    )
                }
            }
        }

        DisposableEffect(playbackSource) {
            onDispose {
                releasePlayer()
            }
        }

        LaunchedEffect(ready) {
            while (ready && isActive) {
                player?.let {
                    positionMs = it.currentPosition.coerceAtLeast(0L)
                    durationMs = it.duration
                }
                delay(500)
            }
        }

        LaunchedEffect(fullscreen) {
            videoScrollState.scrollTo(0)
        }

        LightTheme(colors = colors) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (fullscreen) Color.Black else LightThemeTokens.colors.background),
            ) {
                val videoLayout = playbackVideoLayout(
                    containerWidth = maxWidth.value,
                    containerHeight = maxHeight.value,
                    videoAspectRatio = displayAspectRatio,
                    fullscreen = fullscreen,
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    if (!fullscreen) {
                        LightTopBar(
                            leftButton = LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                onClick = { goBack() },
                                contentDescription = "Back",
                            ),
                            center = LightTopBarCenter.Text("Watch"),
                            rightButton = if (canListen) {
                                LightBarButton.Text(text = "LISTEN", onClick = ::switchToListen)
                            } else {
                                null
                            },
                        )
                    }

                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        scrollBarPosition = LightScrollBarPosition.Inside,
                        scrollState = videoScrollState,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(videoLayout.viewportSize.height.dp)
                                .background(Color.Black),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            AndroidView(
                                factory = { context ->
                                    TextureView(context).also { texture ->
                                        videoTexture = texture
                                        texture.keepScreenOn = false
                                        val newPlayer = ExoPlayer.Builder(context).build()
                                        player = newPlayer
                                        newPlayer.apply {
                                            setHandleAudioBecomingNoisy(true)
                                            setAudioAttributes(
                                                AudioAttributes.Builder()
                                                    .setUsage(C.USAGE_MEDIA)
                                                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                                                    .build(),
                                                true,
                                            )
                                            setWakeMode(C.WAKE_MODE_NETWORK)
                                            setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                                            setVideoTextureView(texture)
                                            addListener(listener)
                                            when (val source = playbackSource) {
                                                is VideoPlaybackSource.Single ->
                                                    setMediaItem(source.stream.toMediaItem())
                                                is VideoPlaybackSource.Separate ->
                                                    setMediaSource(source.toMergedMediaSource())
                                            }
                                            seekTo(initialPositionMs.coerceAtLeast(0L))
                                            playWhenReady = this@VideoPlaybackScreen.playWhenReady
                                            prepare()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .offset(
                                        x = videoLayout.videoOffset.x.dp,
                                        y = videoLayout.videoOffset.y.dp,
                                    )
                                    .width(videoLayout.videoSize.width.dp)
                                    .height(videoLayout.videoSize.height.dp)
                                    .background(Color.Black),
                            )

                            if (fullscreen) {
                                Image(
                                    painter = fullscreenPainter,
                                    contentDescription = "Exit fullscreen",
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(1f.gridUnitsAsDp())
                                        .background(LightThemeTokens.colors.background)
                                        .lightClickable(
                                            onClickLabel = "Exit fullscreen",
                                            onClick = { fullscreen = false },
                                        )
                                        .padding(1f.gridUnitsAsDp())
                                        .size(2f.gridUnitsAsDp()),
                                )
                            }
                        }
                    }

                    if (!fullscreen) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 1f.gridUnitsAsDp()),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 0.75f.gridUnitsAsDp()),
                            ) {
                                LightProgressBar(
                                    colors = colors,
                                    progress = playbackProgress(positionMs, durationMs),
                                )
                            }
                            LightText(
                                text = playbackTimeLabel(positionMs, durationMs),
                                variant = LightTextVariant.Superfine,
                                monospace = true,
                                lighten = true,
                                modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                            )
                            errorMessage?.let { message ->
                                LightText(
                                    text = message,
                                    variant = LightTextVariant.Detail,
                                    maxLines = 2,
                                    modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                                )
                            }
                        }
                    }

                    if (!fullscreen) {
                        LightBottomBar(
                            items = playbackBottomBarItems(
                                playing = playing,
                                ready = ready,
                                fullscreen = false,
                                fullscreenPainter = fullscreenPainter,
                                onSeekBack = { seekBy(-15_000L) },
                                onTogglePlayback = ::togglePlayback,
                                onSeekForward = { seekBy(15_000L) },
                                onToggleFullscreen = { fullscreen = true },
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun togglePlayback() {
        player?.let { if (it.playWhenReady) it.pause() else it.play() }
    }

    private fun seekBy(deltaMs: Long) {
        player?.let {
            val upperBound = it.duration.takeIf { duration -> duration > 0L } ?: Long.MAX_VALUE
            it.seekTo((it.currentPosition + deltaMs).coerceIn(0L, upperBound))
        }
    }

    private fun releasePlayer() {
        videoTexture?.keepScreenOn = false
        videoTexture?.let { texture -> player?.clearVideoTextureView(texture) }
        videoTexture = null
        player?.release()
        player = null
    }
}

internal fun shouldKeepVideoScreenOn(isPlaying: Boolean): Boolean = isPlaying

internal data class PlaybackVideoLayout(
    val viewportSize: Size,
    val videoSize: Size,
    val videoOffset: Offset,
)

internal fun playbackVideoLayout(
    containerWidth: Float,
    containerHeight: Float,
    videoAspectRatio: Float,
    fullscreen: Boolean,
): PlaybackVideoLayout {
    val safeAspectRatio = videoAspectRatio
        .takeIf { it.isFinite() && it > 0f }
        ?: DEFAULT_VIDEO_ASPECT_RATIO
    val safeWidth = containerWidth.coerceAtLeast(0f)
    val viewportSize = Size(
        width = safeWidth,
        height = (if (fullscreen) {
            containerHeight
        } else {
            safeWidth / safeAspectRatio
        }).coerceAtLeast(0f),
    )
    val videoSize = fitVideoSize(
        containerWidth = viewportSize.width,
        containerHeight = viewportSize.height,
        videoAspectRatio = safeAspectRatio,
    )
    return PlaybackVideoLayout(
        viewportSize = viewportSize,
        videoSize = videoSize,
        videoOffset = Offset(
            x = ((viewportSize.width - videoSize.width) / 2f).coerceAtLeast(0f),
            y = ((viewportSize.height - videoSize.height) / 2f).coerceAtLeast(0f),
        ),
    )
}

internal fun fitVideoSize(
    containerWidth: Float,
    containerHeight: Float,
    videoAspectRatio: Float,
): Size {
    if (containerWidth <= 0f || containerHeight <= 0f) return Size.Zero
    val aspectRatio = videoAspectRatio.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_VIDEO_ASPECT_RATIO
    val heightAtFullWidth = containerWidth / aspectRatio
    return if (heightAtFullWidth <= containerHeight) {
        Size(containerWidth, heightAtFullWidth)
    } else {
        Size(containerHeight * aspectRatio, containerHeight)
    }
}

internal fun playbackBottomBarItems(
    playing: Boolean,
    ready: Boolean,
    fullscreen: Boolean,
    fullscreenPainter: Painter,
    onSeekBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekForward: () -> Unit,
    onToggleFullscreen: () -> Unit,
): List<LightBarButton> = playbackTransportItems(
    playing = playing,
    canSeek = ready,
    playPauseEnabled = true,
    onSeekBack = onSeekBack,
    onTogglePlayback = onTogglePlayback,
    onSeekForward = onSeekForward,
    trailingItem = LightBarButton.Icon(
        painter = fullscreenPainter,
        onClick = onToggleFullscreen,
        contentDescription = if (fullscreen) "Exit fullscreen" else "Enter fullscreen",
    ),
)

/** Four-corner expand/collapse mark for the SDK's custom icon button slot. */
private class FullscreenCornersPainter(
    private val color: Color,
    private val collapse: Boolean,
) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
        val strokeWidth = (size.minDimension * 0.075f).coerceAtLeast(1f)
        val outer = size.minDimension * 0.13f
        val inner = size.minDimension * 0.39f
        val far = size.minDimension - outer
        val nearFar = size.minDimension - inner

        if (collapse) {
            drawLine(color, Offset(outer, inner), Offset(inner, inner), strokeWidth)
            drawLine(color, Offset(inner, outer), Offset(inner, inner), strokeWidth)
            drawLine(color, Offset(far, inner), Offset(nearFar, inner), strokeWidth)
            drawLine(color, Offset(nearFar, outer), Offset(nearFar, inner), strokeWidth)
            drawLine(color, Offset(outer, nearFar), Offset(inner, nearFar), strokeWidth)
            drawLine(color, Offset(inner, far), Offset(inner, nearFar), strokeWidth)
            drawLine(color, Offset(far, nearFar), Offset(nearFar, nearFar), strokeWidth)
            drawLine(color, Offset(nearFar, far), Offset(nearFar, nearFar), strokeWidth)
        } else {
            drawLine(color, Offset(outer, outer), Offset(inner, outer), strokeWidth)
            drawLine(color, Offset(outer, outer), Offset(outer, inner), strokeWidth)
            drawLine(color, Offset(far, outer), Offset(nearFar, outer), strokeWidth)
            drawLine(color, Offset(far, outer), Offset(far, inner), strokeWidth)
            drawLine(color, Offset(outer, far), Offset(inner, far), strokeWidth)
            drawLine(color, Offset(outer, far), Offset(outer, nearFar), strokeWidth)
            drawLine(color, Offset(far, far), Offset(nearFar, far), strokeWidth)
            drawLine(color, Offset(far, far), Offset(far, nearFar), strokeWidth)
        }
    }
}

private const val DEFAULT_VIDEO_ASPECT_RATIO = 16f / 9f

internal fun videoAspectRatio(
    width: Int?,
    height: Int?,
    pixelWidthHeightRatio: Float = 1f,
): Float {
    if (width == null || width <= 0 || height == null || height <= 0) {
        return DEFAULT_VIDEO_ASPECT_RATIO
    }
    if (!pixelWidthHeightRatio.isFinite() || pixelWidthHeightRatio <= 0f) {
        return DEFAULT_VIDEO_ASPECT_RATIO
    }
    return (width.toFloat() * pixelWidthHeightRatio / height.toFloat())
        .takeIf { ratio -> ratio.isFinite() && ratio > 0f }
        ?: DEFAULT_VIDEO_ASPECT_RATIO
}

private fun VideoPlaybackSource.initialVideoAspectRatio(): Float = when (this) {
    is VideoPlaybackSource.Single -> videoAspectRatio(stream.width, stream.height)
    is VideoPlaybackSource.Separate -> videoAspectRatio(video.width, video.height)
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun VideoPlaybackSource.Separate.toMergedMediaSource(): MediaSource {
    val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent("Lightious/0.4.0")
    val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
    return MergingMediaSource(
        true,
        true,
        mediaSourceFactory.createMediaSource(video.toMediaItem()),
        mediaSourceFactory.createMediaSource(audio.toMediaItem()),
    )
}

private fun MediaStream.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri(url)
    .apply { mimeType?.let(::setMimeType) }
    .build()

internal fun playbackProgress(positionMs: Long, durationMs: Long): Float {
    if (durationMs == C.TIME_UNSET || durationMs <= 0L) return 0f
    val boundedPositionMs = positionMs.coerceIn(0L, durationMs)
    return (boundedPositionMs.toDouble() / durationMs.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
}

internal fun playbackTimeLabel(positionMs: Long, durationMs: Long): String {
    val hasKnownDuration = durationMs != C.TIME_UNSET && durationMs > 0L
    val boundedPositionMs = if (hasKnownDuration) {
        positionMs.coerceIn(0L, durationMs)
    } else {
        positionMs.coerceAtLeast(0L)
    }
    return if (hasKnownDuration) {
        "${formatPlaybackTime(boundedPositionMs)}  /  ${formatPlaybackTime(durationMs)}"
    } else {
        "${formatPlaybackTime(boundedPositionMs)}  /  LIVE"
    }
}

internal fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

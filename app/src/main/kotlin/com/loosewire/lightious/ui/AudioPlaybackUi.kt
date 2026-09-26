package com.loosewire.lightious.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.loosewire.lightious.data.PlaybackPolicy
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Audio-only counterpart to the video surface: media identity takes the main
 * content area while the same progress/time treatment stays next to transport
 * controls at the bottom of the screen.
 */
@Composable
internal fun AudioPlaybackContent(
    title: String,
    author: String,
    detailLines: List<String>,
    positionMs: Long,
    durationMs: Long,
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightText(
                text = title,
                variant = LightTextVariant.Subheading,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
            LightText(
                text = author,
                variant = LightTextVariant.Copy,
                lighten = true,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 0.35f.gridUnitsAsDp()),
            )
            detailLines.filter(String::isNotBlank).forEach { line ->
                LightText(
                    text = line,
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
            }
            errorMessage?.let { message ->
                LightText(
                    text = message,
                    variant = LightTextVariant.Detail,
                    maxLines = 3,
                    modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                )
            }
        }

        AudioPlaybackTimeline(
            positionMs = positionMs,
            durationMs = durationMs,
        )
    }
}

@Composable
private fun AudioPlaybackTimeline(
    positionMs: Long,
    durationMs: Long,
) {
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
                colors = LightThemeTokens.colors,
                progress = playbackProgress(positionMs, durationMs),
            )
        }
        LightText(
            text = audioPlaybackTimeLabel(positionMs, durationMs),
            variant = LightTextVariant.Superfine,
            monospace = true,
            lighten = true,
            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
        )
    }
}

internal fun audioPlaybackTimeLabel(positionMs: Long, durationMs: Long): String =
    if (durationMs > 0L) {
        playbackTimeLabel(positionMs, durationMs)
    } else {
        "${formatPlaybackTime(positionMs)}  /  --:--"
    }

internal fun resolvedAudioDurationMs(
    playerDurationMs: Long,
    metadataDurationSeconds: Long,
): Long {
    if (playerDurationMs > 0L) return playerDurationMs
    if (metadataDurationSeconds <= 0L || metadataDurationSeconds > Long.MAX_VALUE / 1_000L) return 0L
    return metadataDurationSeconds * 1_000L
}

internal fun shouldPresentAudioPlayer(
    playbackPolicy: PlaybackPolicy,
    audioStarted: Boolean,
): Boolean = audioStarted || playbackPolicy == PlaybackPolicy.LISTEN_ONLY

internal fun playbackTransportItems(
    playing: Boolean,
    canSeek: Boolean,
    playPauseEnabled: Boolean,
    onSeekBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekForward: () -> Unit,
    trailingItem: LightBarButton? = null,
): List<LightBarButton> = buildList {
    add(
        LightBarButton.LightIcon(
            icon = LightIcons.SKIP_BACKWARD_FIFTEEN,
            onClick = onSeekBack.takeIf { canSeek },
            contentDescription = "Back 15 seconds",
        ),
    )
    add(
        LightBarButton.LightIcon(
            icon = if (playing) LightIcons.PAUSE else LightIcons.PLAY,
            onClick = onTogglePlayback.takeIf { playPauseEnabled },
            contentDescription = if (playing) "Pause" else "Play",
        ),
    )
    add(
        LightBarButton.LightIcon(
            icon = LightIcons.SKIP_FORWARD_FIFTEEN,
            onClick = onSeekForward.takeIf { canSeek },
            contentDescription = "Forward 15 seconds",
        ),
    )
    trailingItem?.let(::add)
}

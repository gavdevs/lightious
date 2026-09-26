package com.loosewire.lightious.ui

import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

internal data class PlaybackStart(val positionMs: Long, val playWhenReady: Boolean)

/** Paused audio stays paused when changing presentation; a fresh selection starts playing. */
internal fun videoStartFromAudio(
    audioStarted: Boolean,
    audioPositionMs: Long,
    audioPlaying: Boolean,
    savedPositionMs: Long,
): PlaybackStart = PlaybackStart(
    positionMs = (if (audioStarted) audioPositionMs else savedPositionMs).coerceAtLeast(0L),
    playWhenReady = !audioStarted || audioPlaying,
)

internal fun audioPlaybackFinished(
    started: Boolean,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    failed: Boolean,
    changingPlayback: Boolean,
): Boolean = started && !playing && !failed && !changingPlayback &&
    durationMs > 0L && positionMs >= durationMs

/** The SDK clamps seeks to zero until the newly prepared media's duration is known. */
internal suspend fun prepareAudioAt(
    player: LightAudioPlayer,
    item: LightAudioItem,
    positionMs: Long,
) {
    player.pause()
    // Clear the previous duration before waiting for the new stream's timeline.
    player.setMediaQueue(emptyList())
    player.setMediaQueue(listOf(item))
    restoreAudioPosition(
        positionMs = positionMs,
        durations = player.durationMs,
        failures = player.error.map { it != null },
        seek = player::seekTo,
    )
}

internal suspend fun restoreAudioPosition(
    positionMs: Long,
    durations: Flow<Long>,
    failures: Flow<Boolean>,
    seek: (Long) -> Unit,
) {
    if (positionMs <= 0L) return
    val timeline = withTimeoutOrNull(15_000L) {
        combine(durations, failures) { duration, failed -> duration to failed }
            .first { (duration, failed) -> duration > 0L || failed }
    } ?: error("Could not restore your place. Please try again.")
    check(!timeline.second) { "Could not load audio at your place. Please try again." }
    seek(positionMs.coerceAtMost(timeline.first))
}

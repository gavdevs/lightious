package com.loosewire.lightious.ui

sealed interface VideoPlaybackResult {
    data class Stopped(val positionMs: Long, val playWhenReady: Boolean) : VideoPlaybackResult

    data class Listen(val positionMs: Long, val playWhenReady: Boolean) : VideoPlaybackResult

    data object Finished : VideoPlaybackResult
}

/** Owns the one-time handoff so decoding stops before another player can start. */
internal class VideoPlaybackExit(
    private val stopPlayback: () -> Unit,
    private val returnResult: (VideoPlaybackResult) -> Unit,
) {
    private var returned = false

    fun stop(positionMs: Long) = deliver(
        VideoPlaybackResult.Stopped(positionMs.coerceAtLeast(0L), playWhenReady = false),
    )

    fun listen(positionMs: Long, playWhenReady: Boolean) = deliver(
        VideoPlaybackResult.Listen(positionMs.coerceAtLeast(0L), playWhenReady),
    )

    fun finish() = deliver(VideoPlaybackResult.Finished)

    private fun deliver(result: VideoPlaybackResult) {
        if (returned) return
        returned = true
        stopPlayback()
        returnResult(result)
    }
}

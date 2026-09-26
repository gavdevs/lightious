package com.loosewire.lightious.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.media3.common.C
import com.thelightphone.sdk.ui.LightBarButton
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoPlaybackScreenTest {
    @Test
    fun listenHandoffStopsVideoBeforeReturningItsPositionAndPlayingIntent() {
        val events = mutableListOf<Any>()
        val exit = VideoPlaybackExit(
            stopPlayback = { events += "video stopped" },
            returnResult = { events += it },
        )

        // playWhenReady stays true while buffering even though isPlaying is false.
        exit.listen(positionMs = 73_000L, playWhenReady = true)

        assertEquals(
            listOf("video stopped", VideoPlaybackResult.Listen(73_000L, playWhenReady = true)),
            events,
        )
    }

    @Test
    fun listenHandoffPreservesPausedPlayback() {
        val results = mutableListOf<VideoPlaybackResult>()
        val exit = VideoPlaybackExit(stopPlayback = {}, returnResult = { results += it })

        exit.listen(positionMs = 73_000L, playWhenReady = false)

        assertEquals(listOf<VideoPlaybackResult>(VideoPlaybackResult.Listen(73_000L, playWhenReady = false)), results)
    }

    @Test
    fun backKeepsThePositionWithoutStartingAudio() {
        val results = mutableListOf<VideoPlaybackResult>()
        val exit = VideoPlaybackExit(stopPlayback = {}, returnResult = { results += it })

        exit.stop(positionMs = 73_000L)

        assertEquals(listOf<VideoPlaybackResult>(VideoPlaybackResult.Stopped(73_000L, playWhenReady = false)), results)
    }

    @Test
    fun completionAndLateCallbacksCanOnlyReturnOneResult() {
        val events = mutableListOf<Any>()
        lateinit var exit: VideoPlaybackExit
        exit = VideoPlaybackExit(
            stopPlayback = {
                events += "video stopped"
                exit.finish()
            },
            returnResult = { events += it },
        )

        exit.finish()
        exit.stop(positionMs = 90_000L)
        exit.listen(positionMs = 90_000L, playWhenReady = true)

        assertEquals(listOf("video stopped", VideoPlaybackResult.Finished), events)
    }

    @Test
    fun formatsShortAndLongDurations() {
        assertEquals("00:00", formatPlaybackTime(0L))
        assertEquals("01:05", formatPlaybackTime(65_999L))
        assertEquals("1:01:01", formatPlaybackTime(3_661_000L))
    }

    @Test
    fun labelsUnknownDurationAsLive() {
        assertEquals("00:05  /  LIVE", playbackTimeLabel(5_000L, C.TIME_UNSET))
        assertEquals("00:00  /  LIVE", playbackTimeLabel(-1L, 0L))
    }

    @Test
    fun clampsKnownPlaybackLabelsToTheDuration() {
        assertEquals("00:00  /  01:00", playbackTimeLabel(-1L, 60_000L))
        assertEquals("01:00  /  01:00", playbackTimeLabel(90_000L, 60_000L))
    }

    @Test
    fun calculatesAndClampsPlaybackProgressSafely() {
        assertClose(0f, playbackProgress(-1L, 60_000L))
        assertClose(0.5f, playbackProgress(30_000L, 60_000L))
        assertClose(1f, playbackProgress(90_000L, 60_000L))
        assertClose(0f, playbackProgress(30_000L, C.TIME_UNSET))
        assertClose(0f, playbackProgress(30_000L, 0L))
    }

    @Test
    fun preservesLandscapePortraitAndAnamorphicVideoAspectRatios() {
        assertClose(16f / 9f, videoAspectRatio(1920, 1080))
        assertClose(9f / 16f, videoAspectRatio(1080, 1920))
        assertClose(3f / 2f, videoAspectRatio(720, 720, pixelWidthHeightRatio = 1.5f))
    }

    @Test
    fun fallsBackToWidescreenForMissingOrInvalidVideoDimensions() {
        assertClose(16f / 9f, videoAspectRatio(null, 1080))
        assertClose(16f / 9f, videoAspectRatio(1920, 0))
        assertClose(16f / 9f, videoAspectRatio(1920, 1080, pixelWidthHeightRatio = Float.NaN))
    }

    @Test
    fun fourPlaybackControlsStayWithinTheSdkIconOnlyBottomBarContract() {
        val items = playbackBottomBarItems(
            playing = false,
            ready = true,
            fullscreen = false,
            fullscreenPainter = EmptyPainter,
            onSeekBack = {},
            onTogglePlayback = {},
            onSeekForward = {},
            onToggleFullscreen = {},
        )

        assertEquals(4, items.size)
        assertTrue(items.none { it is LightBarButton.Text })
        assertEquals("Enter fullscreen", items.last().contentDescription)
    }

    @Test
    fun keepsScreenAwakeOnlyDuringActivePlayback() {
        assertTrue(shouldKeepVideoScreenOn(isPlaying = true))
        assertTrue(!shouldKeepVideoScreenOn(isPlaying = false))
    }

    @Test
    fun fitsCommonVideoShapesInsideLp3LikeNormalPlaybackBounds() {
        val portrait = fitVideoSize(360f, 227f, 9f / 16f)
        val square = fitVideoSize(360f, 227f, 1f)
        val landscape = fitVideoSize(360f, 227f, 16f / 9f)

        assertTrue(portrait.width <= 360f && portrait.height <= 227f)
        assertTrue(square.width <= 360f && square.height <= 227f)
        assertTrue(landscape.width <= 360f && landscape.height <= 227f)
        assertClose(9f / 16f, portrait.width / portrait.height)
        assertClose(1f, square.width / square.height)
        assertClose(16f / 9f, landscape.width / landscape.height)
    }

    @Test
    fun fullscreenUsesTheWholeViewportAsItsAspectSafeFitBoundary() {
        val fullscreen = playbackVideoLayout(
            containerWidth = 360f,
            containerHeight = 413f,
            videoAspectRatio = 9f / 16f,
            fullscreen = true,
        )

        assertTrue(fullscreen.videoSize.width <= 360f && fullscreen.videoSize.height <= 413f)
        assertClose(9f / 16f, fullscreen.videoSize.width / fullscreen.videoSize.height)
        assertTrue(fullscreen.videoOffset.x > 0f)
        assertClose(0f, fullscreen.videoOffset.y)
    }

    @Test
    fun normalPlaybackKeepsEveryVideoShapeFullWidth() {
        val portrait = playbackVideoLayout(360f, 413f, 9f / 16f, fullscreen = false)
        val square = playbackVideoLayout(360f, 413f, 1f, fullscreen = false)
        val landscape = playbackVideoLayout(360f, 413f, 16f / 9f, fullscreen = false)

        assertClose(360f, portrait.videoSize.width)
        assertClose(360f, square.videoSize.width)
        assertClose(360f, landscape.videoSize.width)
        assertClose(640f, portrait.viewportSize.height)
        assertClose(360f, square.viewportSize.height)
        assertClose(202.5f, landscape.viewportSize.height)
    }

    @Test
    fun fullscreenSquareCentersWithinTheFullViewport() {
        val normal = playbackVideoLayout(
            containerWidth = 360f,
            containerHeight = 413f,
            videoAspectRatio = 1f,
            fullscreen = false,
        )
        val fullscreen = playbackVideoLayout(
            containerWidth = 360f,
            containerHeight = 413f,
            videoAspectRatio = 1f,
            fullscreen = true,
        )

        assertClose(360f, fullscreen.viewportSize.width)
        assertClose(413f, fullscreen.viewportSize.height)
        assertClose(360f, normal.videoSize.width)
        assertClose(360f, normal.videoSize.height)
        assertClose(360f, fullscreen.videoSize.width)
        assertClose(360f, fullscreen.videoSize.height)
        assertClose(0f, fullscreen.videoOffset.x)
        assertClose(26.5f, fullscreen.videoOffset.y)
        assertClose(
            fullscreen.viewportSize.width,
            fullscreen.videoOffset.x * 2f + fullscreen.videoSize.width,
        )
        assertClose(
            fullscreen.viewportSize.height,
            fullscreen.videoOffset.y * 2f + fullscreen.videoSize.height,
        )
    }

    @Test
    fun fullscreenLandscapeAndPortraitGeometryRemainUpright() {
        val portrait = playbackVideoLayout(360f, 413f, 9f / 16f, fullscreen = true)
        val landscape = playbackVideoLayout(360f, 413f, 16f / 9f, fullscreen = true)

        assertTrue(portrait.videoSize.height > portrait.videoSize.width)
        assertTrue(landscape.videoSize.width > landscape.videoSize.height)
        assertClose(9f / 16f, portrait.videoSize.width / portrait.videoSize.height)
        assertClose(16f / 9f, landscape.videoSize.width / landscape.videoSize.height)
        assertClose(0f, portrait.videoOffset.y)
        assertClose(0f, landscape.videoOffset.x)
    }

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < 0.0001f, "Expected $expected but was $actual")
    }

    private object EmptyPainter : Painter() {
        override val intrinsicSize: Size = Size.Unspecified

        override fun DrawScope.onDraw() = Unit
    }
}

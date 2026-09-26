package com.loosewire.lightious.ui

import com.loosewire.lightious.data.PlaybackPolicy
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioPlaybackUiTest {
    @Test
    fun `audio timeline distinguishes unresolved duration from live video`() {
        assertEquals("00:05  /  --:--", audioPlaybackTimeLabel(5_000L, 0L))
        assertEquals("00:00  /  --:--", audioPlaybackTimeLabel(-1L, 0L))
        assertEquals("00:05  /  01:00", audioPlaybackTimeLabel(5_000L, 60_000L))
    }

    @Test
    fun `audio timeline uses metadata until the player reports its duration`() {
        assertEquals(60_000L, resolvedAudioDurationMs(playerDurationMs = 0L, metadataDurationSeconds = 60L))
        assertEquals(59_500L, resolvedAudioDurationMs(playerDurationMs = 59_500L, metadataDurationSeconds = 60L))
        assertEquals(0L, resolvedAudioDurationMs(playerDurationMs = 0L, metadataDurationSeconds = -1L))
        assertEquals(0L, resolvedAudioDurationMs(playerDurationMs = 0L, metadataDurationSeconds = Long.MAX_VALUE))
    }

    @Test
    fun `listen only opens as a player while optional audio waits for selection`() {
        assertTrue(shouldPresentAudioPlayer(PlaybackPolicy.LISTEN_ONLY, audioStarted = false))
        assertTrue(shouldPresentAudioPlayer(PlaybackPolicy.WATCH_AND_LISTEN, audioStarted = true))
        assertTrue(!shouldPresentAudioPlayer(PlaybackPolicy.WATCH_AND_LISTEN, audioStarted = false))
    }

    @Test
    fun `transport deck keeps seek disabled until media has started`() {
        val items = playbackTransportItems(
            playing = false,
            canSeek = false,
            playPauseEnabled = true,
            onSeekBack = {},
            onTogglePlayback = {},
            onSeekForward = {},
        )

        assertEquals(3, items.size)
        assertNull(items[0].onClick)
        assertNotNull(items[1].onClick)
        assertNull(items[2].onClick)
        assertEquals("Back 15 seconds", items[0].contentDescription)
        assertEquals("Play", items[1].contentDescription)
        assertEquals("Forward 15 seconds", items[2].contentDescription)
    }

    @Test
    fun `transport deck supports a fourth media action`() {
        val watch = LightBarButton.LightIcon(
            icon = LightIcons.MEDIA,
            onClick = {},
            contentDescription = "Watch video",
        )
        val items = playbackTransportItems(
            playing = true,
            canSeek = true,
            playPauseEnabled = false,
            onSeekBack = {},
            onTogglePlayback = {},
            onSeekForward = {},
            trailingItem = watch,
        )

        assertEquals(4, items.size)
        assertNotNull(items[0].onClick)
        assertNull(items[1].onClick)
        assertNotNull(items[2].onClick)
        assertEquals("Pause", items[1].contentDescription)
        assertEquals("Watch video", items[3].contentDescription)
    }
}

package com.loosewire.lightious.ui

import com.loosewire.lightious.data.FocusedChannelEntry
import com.loosewire.lightious.data.PlaybackPolicy
import com.loosewire.lightious.data.VideoSummary
import com.loosewire.lightious.data.FocusedLibraryFilter
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoRowsTest {
    @Test
    fun usesFocusedAudioVideoFilterLabels() {
        assertEquals("ALL", FocusedLibraryFilter.ALL.filterLabel())
        assertEquals("AUDIO", FocusedLibraryFilter.LISTEN.filterLabel())
        assertEquals("VIDEO", FocusedLibraryFilter.WATCH.filterLabel())
    }

    @Test
    fun formatsDurationAndCountsForSmallScreen() {
        assertEquals("4:05", formatSeconds(245L))
        assertEquals("1:01:01", formatSeconds(3_661L))
        assertEquals("999", formatCompactCount(999L))
        assertEquals("1.2K", formatCompactCount(1_250L))
        assertEquals("2M", formatCompactCount(2_000_000L))
    }

    @Test
    fun buildsLiveMetadataWithoutDuration() {
        val video = VideoSummary(
            videoId = "abcdefghijk",
            title = "Live",
            author = "Creator",
            lengthSeconds = 0L,
            viewCount = 1_200L,
            publishedText = "now",
            liveNow = true,
        )

        assertEquals("LIVE  ·  1.2K views  ·  now", videoMetadataLine(video))
    }

    @Test
    fun buildsChannelGroupMetadataWithPluralCountsAndPolicy() {
        val listenOnly = channel(channelPolicy = PlaybackPolicy.LISTEN_ONLY)
        val watchable = channel(channelPolicy = PlaybackPolicy.WATCH_AND_LISTEN)

        assertEquals("1 VIDEO OF 3 UNWATCHED  ·  LISTEN ONLY", channelFeedGroupMetadataLine(listenOnly, 1, 3))
        assertEquals("2 VIDEOS OF 5 UNWATCHED  ·  WATCH", channelFeedGroupMetadataLine(watchable, 2, 5))
    }

    private fun channel(channelPolicy: PlaybackPolicy) = FocusedChannelEntry(
        channelId = "UCXuqSBlHAE6Xw-yeJA0Tunw",
        name = "Channel",
        thumbnailUrl = null,
        channelPolicy = channelPolicy,
        curatedVideos = emptyList(),
    )
}

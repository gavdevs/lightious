package com.loosewire.lightious.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelFeedTest {
    @Test
    fun `completion shrinks the newest window without replacing it with older uploads`() {
        val videos = (1..6).map { index -> video(index) }
        val profile = profile()

        assertEquals(listOf(id(6), id(5), id(4)), profile.channelFeedEntries(videos, emptySet()).ids())
        assertEquals(listOf(id(5), id(4)), profile.channelFeedEntries(videos, setOf(id(6))).ids())
        assertTrue(profile.channelFeedEntries(videos, setOf(id(6), id(5), id(4))).isEmpty())
    }

    @Test
    fun `each channel gets its own window and merged releases are sorted and deduplicated`() {
        val videos = listOf(
            video(1), video(4), video(6), video(8),
            video(2, CHANNEL_B), video(3, CHANNEL_B), video(5, CHANNEL_B), video(7, CHANNEL_B),
            video(8).copy(title = "Duplicate"),
        )

        val entries = profile().channelFeedEntries(videos, emptySet())

        assertEquals(listOf(8, 7, 6, 5, 4, 3).map(::id), entries.ids())
        assertEquals("Video 8", entries.first().video.title)
    }

    @Test
    fun `companion limit and hide watched settings apply without changing the window`() {
        val videos = (1..6).map { index -> video(index) }
        val profile = profile().copy(channelFeedLimit = 5, hideWatched = false)

        assertEquals(listOf(6, 5, 4, 3, 2).map(::id), profile.channelFeedEntries(videos, setOf(id(6))).ids())
        assertEquals(listOf(id(6)), profile.copy(channelFeedLimit = 1).channelFeedEntries(videos, emptySet()).ids())
        assertEquals(5, profile.copy(channelFeedLimit = 100).channelFeedEntries(videos, emptySet()).size)
        assertEquals(1, profile.copy(channelFeedLimit = 0).channelFeedEntries(videos, emptySet()).size)
    }

    @Test
    fun `Shorts live upcoming blocked and unapproved channel videos never enter a window`() {
        val videos = listOf(
            video(1),
            video(2).copy(isShort = true),
            video(3).copy(liveNow = true),
            video(4).copy(isUpcoming = true),
            video(5),
            video(6, "UCaaaaaaaaaaaaaaaaaaaaaa"),
            video(7).copy(authorId = null),
        )
        val profile = profile().copy(
            blockedVideoIds = setOf(id(5)),
            // An exact saved video never subscribes to its author's feed.
            items = listOf(curated(6)),
        )

        assertEquals(listOf(id(1)), profile.channelFeedEntries(videos, emptySet()).ids())
    }

    @Test
    fun `saved video and playlist policies override the channel policy`() {
        val saved = curated(3)
        val playlistOnly = curated(2)
        val profile = profile().copy(
            items = listOf(saved),
            playlists = listOf(
                CuratedPlaylist(
                    "playlist", "Playlist",
                    listOf(saved.copy(playbackPolicy = PlaybackPolicy.WATCH_AND_LISTEN), playlistOnly),
                ),
            ),
        )

        val entries = profile.channelFeedEntries((1..3).map { video(it) }, emptySet())

        assertEquals(
            listOf(PlaybackPolicy.LISTEN_ONLY, PlaybackPolicy.LISTEN_ONLY, PlaybackPolicy.WATCH_AND_LISTEN),
            entries.map(FocusedVideoEntry::playbackPolicy),
        )
    }

    @Test
    fun `playback filter cannot pull older matching uploads into the selected window`() {
        val profile = profile().copy(items = (3..5).map { curated(it) })

        val watchable = profile.channelFeedEntries((1..5).map { video(it) }, emptySet())
            .filter { entry -> FocusedLibraryFilter.WATCH.includes(entry.playbackPolicy) }

        assertTrue(watchable.isEmpty())
    }

    private fun profile() = CompanionProfile(
        deviceId = "device",
        account = "account",
        revision = 1,
        mode = ExperienceMode.FOCUSED,
        items = emptyList(),
        channels = listOf(CHANNEL_A, CHANNEL_B).map { channel ->
            CuratedChannel(channel, channel, "Channel", playbackPolicy = PlaybackPolicy.WATCH_AND_LISTEN)
        },
    )

    private fun video(index: Int, channelId: String = CHANNEL_A) = VideoSummary(
        videoId = id(index),
        title = "Video $index",
        author = "Channel",
        lengthSeconds = 60,
        viewCount = 0,
        publishedText = "",
        liveNow = false,
        authorId = channelId,
        published = index.toLong(),
    )

    private fun curated(index: Int) = CuratedVideo(
        id = "item-$index",
        videoId = id(index),
        title = "Video $index",
        author = "Channel",
        authorId = CHANNEL_A,
        lengthSeconds = 60,
        playbackPolicy = PlaybackPolicy.LISTEN_ONLY,
    )

    private fun id(index: Int): String = "video${index.toString().padStart(6, '0')}"

    private fun List<FocusedVideoEntry>.ids() = map { entry -> entry.video.videoId }

    private companion object {
        const val CHANNEL_A = "UCXuqSBlHAE6Xw-yeJA0Tunw"
        const val CHANNEL_B = "UCBJycsmduvYEL83R_U4JriQ"
    }
}

package com.loosewire.lightious.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FocusedLibraryTest {
    @Test
    fun `channels remain explicit and never infer access from a selected video's author`() {
        val profile = profile()

        val channels = profile.focusedChannels()

        assertEquals(listOf(CHANNEL_A), channels.map(FocusedChannelEntry::channelId))
        val explicit = channels.single()
        assertTrue(explicit.allowsWholeChannel)
        assertTrue(explicit.includes(FocusedLibraryFilter.LISTEN))
        assertTrue(explicit.includes(FocusedLibraryFilter.WATCH))
        assertEquals(listOf(VIDEO_A, VIDEO_C), explicit.curatedVideos.map(CuratedVideo::videoId))
    }

    @Test
    fun `channel uploads inherit channel policy while exact video policy wins`() {
        val channel = profile().focusedChannels().first()

        val entries = channel.videosWithPolicy(
            listOf(
                summary(VIDEO_A, CHANNEL_A),
                summary(VIDEO_C, CHANNEL_A),
                summary(VIDEO_C, CHANNEL_A),
            ),
        )

        assertEquals(listOf(VIDEO_A, VIDEO_C), entries.map { entry -> entry.video.videoId })
        assertEquals(PlaybackPolicy.LISTEN_ONLY, entries.first().playbackPolicy)
        assertEquals(PlaybackPolicy.LISTEN_ONLY, entries.last().playbackPolicy)
    }

    @Test
    fun `channel search keeps only matching channel results and applies exact policy overrides`() {
        val channel = profile().focusedChannels().first()

        val entries = channel.searchResultsWithPolicy(
            listOf(
                summary(VIDEO_A, CHANNEL_A),
                summary(VIDEO_D, CHANNEL_A),
                summary(VIDEO_B, CHANNEL_B),
                summary(VIDEO_C, CHANNEL_A, isShort = true),
            ),
        )

        assertEquals(listOf(VIDEO_A, VIDEO_D), entries.map { entry -> entry.video.videoId })
        assertEquals(PlaybackPolicy.LISTEN_ONLY, entries.first().playbackPolicy)
        assertEquals(PlaybackPolicy.WATCH_AND_LISTEN, entries.last().playbackPolicy)
    }

    @Test
    fun `playlist-only videos stay out of main videos and remain available in their playlist`() {
        val profile = profile()

        val playlists = profile.focusedPlaylists()

        assertEquals(listOf(VIDEO_A, VIDEO_C), playlists.first().items.map(CuratedVideo::videoId))
        assertEquals(listOf(VIDEO_A, VIDEO_B), profile.items.map(CuratedVideo::videoId))
        assertFalse(profile.items.any { item -> item.videoId == VIDEO_C })
        assertTrue(playlists.first().includes(FocusedLibraryFilter.ALL))
        assertTrue(playlists.first().includes(FocusedLibraryFilter.LISTEN))
        assertTrue(playlists.first().includes(FocusedLibraryFilter.WATCH))
        assertTrue(FocusedPlaylistEntry("empty", "Empty", emptyList()).includes(FocusedLibraryFilter.ALL))
    }

    @Test
    fun `focused authorization includes playlist-only videos and exact policies beat whole channels`() {
        val profile = profile()

        assertEquals(PlaybackPolicy.LISTEN_ONLY, profile.playbackPolicyFor(VIDEO_A, CHANNEL_A))
        assertEquals(PlaybackPolicy.LISTEN_ONLY, profile.playbackPolicyFor(VIDEO_C, CHANNEL_A))
        assertEquals(PlaybackPolicy.LISTEN_ONLY, profile.playbackPolicyFor(VIDEO_C, null))
        assertEquals(PlaybackPolicy.WATCH_AND_LISTEN, profile.playbackPolicyFor(VIDEO_D, CHANNEL_A))
        assertNull(profile.playbackPolicyFor(VIDEO_D, CHANNEL_B))
        assertNull(profile.playbackPolicyFor(VIDEO_D, null))
    }

    @Test
    fun `library search finds videos channels and playlists without changing main visibility`() {
        val profile = profile().copy(mode = ExperienceMode.LIBRARY)

        val contentMatch = profile.searchFocusedLibrary("sleep audio")

        assertEquals(listOf(VIDEO_C), contentMatch.videos.map(CuratedVideo::videoId))
        assertTrue(contentMatch.channels.isEmpty())
        assertEquals(listOf("playlist"), contentMatch.playlists.map(FocusedPlaylistEntry::id))
        assertFalse(profile.items.any { item -> item.videoId == VIDEO_C })

        val allTypes = profile.searchFocusedLibrary("explicit channel")
        assertEquals(listOf(VIDEO_A), allTypes.videos.map(CuratedVideo::videoId))
        assertEquals(listOf(CHANNEL_A), allTypes.channels.map(FocusedChannelEntry::channelId))
        assertEquals(listOf("playlist"), allTypes.playlists.map(FocusedPlaylistEntry::id))

        val standaloneVideo = profile.searchFocusedLibrary("inferred")
        assertEquals(listOf(VIDEO_B), standaloneVideo.videos.map(CuratedVideo::videoId))
        assertTrue(standaloneVideo.channels.isEmpty())
        assertTrue(profile.searchFocusedLibrary("   ").isEmpty)
    }

    @Test
    fun `focused search retains saved content without opening channel archives`() {
        val results = profile().searchFocusedLibrary("explicit channel")

        assertEquals(listOf(VIDEO_A), results.videos.map(CuratedVideo::videoId))
        assertTrue(results.channels.isEmpty())
        assertEquals(listOf("playlist"), results.playlists.map(FocusedPlaylistEntry::id))
    }

    @Test
    fun `offline search finds downloaded titles without a remote profile`() {
        val download = DownloadedMedia(
            ownerDeviceId = "0123456789abcdef0123456789abcdef",
            videoId = VIDEO_C,
            title = "Sleep story",
            author = "Quiet narrator",
            authorId = CHANNEL_A,
            lengthSeconds = 60,
            kind = DownloadKind.AUDIO,
            state = DownloadState.COMPLETE,
            fileName = "saved.m4a",
            downloadedBytes = 100,
            totalBytes = 100,
            updatedAt = 1,
        )

        val results = (null as CompanionProfile?).searchFocusedLibrary("sleep audio", listOf(download))

        assertEquals(listOf(VIDEO_C), results.downloads.map(DownloadedMedia::videoId))
        assertTrue(results.videos.isEmpty())
        assertTrue(results.channels.isEmpty())
        assertTrue(results.playlists.isEmpty())
    }

    @Test
    fun `explicit Shorts never enter focused videos channels playlists or offline search`() {
        val blocked = curated(
            VIDEO_D,
            "Explicit channel",
            CHANNEL_A,
            PlaybackPolicy.WATCH_AND_LISTEN,
            title = "Blocked Short",
        )
        val contaminated = profile().copy(
            items = profile().items + blocked,
            playlists = profile().playlists.map { playlist -> playlist.copy(items = playlist.items + blocked) },
            blockedVideoIds = setOf(VIDEO_D),
        )
        val channel = contaminated.focusedChannels().single()
        val uploads = channel.videosWithPolicy(
            listOf(summary(VIDEO_D, CHANNEL_A), summary(VIDEO_B, CHANNEL_A)),
        )
        val shortDownload = DownloadedMedia(
            ownerDeviceId = "0123456789abcdef0123456789abcdef",
            videoId = VIDEO_D,
            title = "Blocked Short",
            author = "Explicit channel",
            authorId = CHANNEL_A,
            lengthSeconds = 30,
            kind = DownloadKind.VIDEO,
            state = DownloadState.COMPLETE,
            fileName = "saved.mp4",
            updatedAt = 1,
            isShort = true,
        )

        assertFalse(contaminated.allCuratedVideos().any { video -> video.videoId == VIDEO_D })
        assertFalse(channel.curatedVideos.any { video -> video.videoId == VIDEO_D })
        assertFalse(contaminated.focusedPlaylists().single().items.any { video -> video.videoId == VIDEO_D })
        assertFalse(uploads.any { entry -> entry.video.videoId == VIDEO_D })
        assertTrue(uploads.any { entry -> entry.video.videoId == VIDEO_B })
        assertNull(contaminated.playbackPolicyFor(VIDEO_D, CHANNEL_A))
        assertTrue(contaminated.searchFocusedLibrary("blocked short", listOf(shortDownload)).isEmpty)
    }

    private fun profile() = CompanionProfile(
        deviceId = "device",
        account = "account",
        revision = 2,
        mode = ExperienceMode.FOCUSED,
        items = listOf(
            curated(VIDEO_A, "Explicit channel", CHANNEL_A, PlaybackPolicy.LISTEN_ONLY),
            curated(VIDEO_B, "Inferred channel", CHANNEL_B, PlaybackPolicy.WATCH_AND_LISTEN),
        ),
        channels = listOf(
            CuratedChannel(
                id = "channel-entry",
                channelId = CHANNEL_A,
                name = "Explicit channel",
                playbackPolicy = PlaybackPolicy.WATCH_AND_LISTEN,
            ),
        ),
        playlists = listOf(
            CuratedPlaylist(
                id = "playlist",
                name = "Bedtime",
                items = listOf(
                    curated(VIDEO_A, "Explicit channel", CHANNEL_A, PlaybackPolicy.WATCH_AND_LISTEN),
                    curated(
                        VIDEO_C,
                        "Quiet narrator",
                        CHANNEL_A,
                        PlaybackPolicy.LISTEN_ONLY,
                        title = "Sleep story",
                    ),
                ),
            ),
        ),
    )

    private fun curated(
        videoId: String,
        author: String,
        authorId: String,
        policy: PlaybackPolicy,
        title: String = "Video $videoId",
        isShort: Boolean = false,
    ) = CuratedVideo(
        id = "item-$videoId",
        videoId = videoId,
        title = title,
        author = author,
        lengthSeconds = 60,
        playbackPolicy = policy,
        authorId = authorId,
        isShort = isShort,
    )

    private fun summary(videoId: String, authorId: String, isShort: Boolean = false) = VideoSummary(
        videoId = videoId,
        title = "Video $videoId",
        author = "Explicit channel",
        lengthSeconds = 60,
        viewCount = 0,
        publishedText = "",
        liveNow = false,
        authorId = authorId,
        isShort = isShort,
    )

    private companion object {
        const val VIDEO_A = "dQw4w9WgXcQ"
        const val VIDEO_B = "aqz-KE-bpKQ"
        const val VIDEO_C = "m8KnrXli-bA"
        const val VIDEO_D = "9bZkp7q19f0"
        const val CHANNEL_A = "UCXuqSBlHAE6Xw-yeJA0Tunw"
        const val CHANNEL_B = "UCBJycsmduvYEL83R_U4JriQ"
    }
}

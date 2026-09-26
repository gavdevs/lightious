package com.loosewire.lightious.data

import java.util.Locale

enum class FocusedLibraryFilter {
    ALL,
    LISTEN,
    WATCH,
    ;

    fun includes(policy: PlaybackPolicy): Boolean = when (this) {
        ALL -> true
        LISTEN -> policy == PlaybackPolicy.LISTEN_ONLY
        WATCH -> policy == PlaybackPolicy.WATCH_AND_LISTEN
    }
}

data class FocusedVideoEntry(
    val video: VideoSummary,
    val playbackPolicy: PlaybackPolicy,
)

data class FocusedChannelEntry(
    val channelId: String,
    val name: String,
    val thumbnailUrl: String?,
    val channelPolicy: PlaybackPolicy?,
    val curatedVideos: List<CuratedVideo>,
    val blockedVideoIds: Set<String> = emptySet(),
) {
    val allowsWholeChannel: Boolean
        get() = channelPolicy != null

    fun includes(filter: FocusedLibraryFilter): Boolean =
        channelPolicy?.let(filter::includes) == true ||
            curatedVideos.any { video -> filter.includes(video.playbackPolicy) }

    fun videosWithPolicy(latestUploads: List<VideoSummary>): List<FocusedVideoEntry> {
        val safeCuratedVideos = curatedVideos.filter { video ->
            !video.isShort && video.videoId !in blockedVideoIds
        }
        val curatedById = safeCuratedVideos.associateBy(CuratedVideo::videoId)
        val entries = buildList {
            if (channelPolicy != null) {
                latestUploads.filter { video ->
                    !video.isShort && video.videoId !in blockedVideoIds
                }.forEach { video ->
                    add(
                        FocusedVideoEntry(
                            video = video,
                            playbackPolicy = curatedById[video.videoId]?.playbackPolicy ?: channelPolicy,
                        ),
                    )
                }
            }
            safeCuratedVideos.forEach { video ->
                if (none { entry -> entry.video.videoId == video.videoId }) {
                    add(FocusedVideoEntry(video.asVideoSummary(), video.playbackPolicy))
                }
            }
        }
        return entries.distinctBy { entry -> entry.video.videoId }
    }

    fun searchResultsWithPolicy(results: List<VideoSummary>): List<FocusedVideoEntry> {
        val curatedById = curatedVideos
            .filter { video -> !video.isShort && video.videoId !in blockedVideoIds }
            .associateBy(CuratedVideo::videoId)
        return results.mapNotNull { video ->
            if (video.isShort || video.videoId in blockedVideoIds || video.authorId != channelId) {
                return@mapNotNull null
            }
            val policy = curatedById[video.videoId]?.playbackPolicy ?: channelPolicy
                ?: return@mapNotNull null
            FocusedVideoEntry(video, policy)
        }.distinctBy { entry -> entry.video.videoId }
    }
}

data class FocusedPlaylistEntry(
    val id: String,
    val name: String,
    val items: List<CuratedVideo>,
) {
    fun includes(filter: FocusedLibraryFilter): Boolean =
        filter == FocusedLibraryFilter.ALL || items.any { item -> filter.includes(item.playbackPolicy) }

    fun videosWithPolicy(): List<FocusedVideoEntry> = items.map { item ->
        FocusedVideoEntry(item.asVideoSummary(), item.playbackPolicy)
    }
}

data class FocusedLibrarySearchResults(
    val videos: List<CuratedVideo>,
    val channels: List<FocusedChannelEntry>,
    val playlists: List<FocusedPlaylistEntry>,
    val downloads: List<DownloadedMedia> = emptyList(),
) {
    val isEmpty: Boolean
        get() = videos.isEmpty() && channels.isEmpty() && playlists.isEmpty() && downloads.isEmpty()
}

fun CompanionProfile.focusedChannels(): List<FocusedChannelEntry> {
    data class MutableChannel(
        var name: String,
        var thumbnailUrl: String?,
        var policy: PlaybackPolicy?,
        val videos: MutableList<CuratedVideo> = mutableListOf(),
    )

    val channelsById = linkedMapOf<String, MutableChannel>()
    channels.forEach { channel ->
        channelsById[channel.channelId] = MutableChannel(
            name = channel.name,
            thumbnailUrl = channel.thumbnailUrl,
            policy = channel.playbackPolicy,
        )
    }
    allCuratedVideos().forEach { video ->
        val channelId = video.authorId?.takeIf(::validYouTubeChannelId) ?: return@forEach
        val channel = channelsById[channelId] ?: return@forEach
        if (channel.videos.none { existing -> existing.videoId == video.videoId }) {
            channel.videos += video
        }
    }

    return channelsById.map { (channelId, channel) ->
        FocusedChannelEntry(
            channelId = channelId,
            name = channel.name,
            thumbnailUrl = channel.thumbnailUrl,
            channelPolicy = channel.policy,
            curatedVideos = channel.videos.toList(),
            blockedVideoIds = knownShortVideoIds(),
        )
    }
}

fun CompanionProfile.focusedPlaylists(): List<FocusedPlaylistEntry> {
    val blockedVideoIds = knownShortVideoIds()
    return playlists.map { playlist ->
        FocusedPlaylistEntry(
            id = playlist.id,
            name = playlist.name,
            items = playlist.items
                .filter { item -> !item.isShort && item.videoId !in blockedVideoIds }
                .distinctBy(CuratedVideo::videoId),
        )
    }
}

fun CompanionProfile?.searchFocusedLibrary(
    query: String,
    downloads: List<DownloadedMedia> = emptyList(),
): FocusedLibrarySearchResults {
    val terms = query
        .trim()
        .lowercase(Locale.ROOT)
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)
    if (terms.isEmpty()) {
        return FocusedLibrarySearchResults(emptyList(), emptyList(), emptyList(), emptyList())
    }

    fun matches(vararg values: String?): Boolean {
        val searchable = values
            .filterNotNull()
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        return terms.all(searchable::contains)
    }

    fun CuratedVideo.matchesSearch(): Boolean = matches(
        title,
        author,
        videoId,
        when (playbackPolicy) {
            PlaybackPolicy.LISTEN_ONLY -> "audio listen listen-only"
            PlaybackPolicy.WATCH_AND_LISTEN -> "video watch"
        },
    )

    val focusedPlaylists = this?.focusedPlaylists().orEmpty()
    return FocusedLibrarySearchResults(
        videos = this?.allCuratedVideos().orEmpty().filter(CuratedVideo::matchesSearch),
        channels = this?.takeIf { profile -> profile.mode == ExperienceMode.LIBRARY }
            ?.focusedChannels().orEmpty().filter { channel ->
            matches(channel.name, channel.channelId)
        },
        playlists = focusedPlaylists.filter { playlist ->
            matches(playlist.name, playlist.id) || playlist.items.any(CuratedVideo::matchesSearch)
        },
        downloads = downloads.filter { download -> !download.isShort && download.matchesDownloadSearch(terms) },
    )
}

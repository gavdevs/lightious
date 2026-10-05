package com.loosewire.lightious.data

const val DEFAULT_CHANNEL_FEED_LIMIT: Int = 3
internal val CHANNEL_FEED_LIMIT_RANGE: IntRange = 1..5

data class ChannelFeedPage(
    val videos: List<VideoSummary>,
    val failedChannelIds: List<String> = emptyList(),
)

/** A recent release window, never a queue that refills from the channel archive. */
fun CompanionProfile.channelFeedEntries(
    videos: List<VideoSummary>,
    watchedVideoIds: Set<String>,
): List<FocusedVideoEntry> {
    val channelPolicies = channels.associate { channel -> channel.channelId to channel.playbackPolicy }
    val blockedIds = knownShortVideoIds()
    val exactPolicies = allCuratedVideos().associate { video -> video.videoId to video.playbackPolicy }
    val newestFirst = compareByDescending<VideoSummary> { video -> video.published }
        .thenBy { video -> video.videoId }

    return videos.asSequence()
        .filter { video ->
            video.authorId in channelPolicies &&
                !video.isShort && !video.liveNow && !video.isUpcoming &&
                video.videoId !in blockedIds
        }
        .sortedWith(newestFirst)
        .distinctBy(VideoSummary::videoId)
        .groupBy(VideoSummary::authorId)
        .values
        .flatMap { channelVideos -> channelVideos.take(channelFeedLimit.coerceIn(CHANNEL_FEED_LIMIT_RANGE)) }
        // Completion only removes entries after the recent window has been selected.
        .filter { video -> !hideWatched || video.videoId !in watchedVideoIds }
        .sortedWith(newestFirst)
        .map { video ->
            FocusedVideoEntry(
                video = video,
                playbackPolicy = exactPolicies[video.videoId] ?: channelPolicies.getValue(video.authorId!!),
            )
        }
}

/** One channel's already-selected release window, for grouped display. */
data class ChannelFeedGroup(
    val channel: FocusedChannelEntry,
    val videos: List<FocusedVideoEntry>,
)

/**
 * Groups the merged release window back per channel. Watched filtering is
 * applied here, after the window was selected, so a grouped row can honestly
 * report how many of its videos remain available on this phone.
 */
fun CompanionProfile.channelFeedGroups(
    videos: List<VideoSummary>,
    watchedVideoIds: Set<String>,
): List<ChannelFeedGroup> {
    val byChannelId = focusedChannels().associateBy(FocusedChannelEntry::channelId)
    return channelFeedEntries(videos, watchedVideoIds)
        .groupBy { entry -> entry.video.authorId }
        .mapNotNull { (channelId, entries) ->
            byChannelId[channelId]?.let { channel ->
                ChannelFeedGroup(channel = channel, videos = entries.sortedByDescending { entry -> entry.video.published })
            }
        }
        .sortedByDescending { group -> group.videos.maxOfOrNull { entry -> entry.video.published } ?: 0L }
}

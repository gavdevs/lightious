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

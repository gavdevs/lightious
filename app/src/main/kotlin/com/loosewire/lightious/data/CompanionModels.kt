package com.loosewire.lightious.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = ExperienceModeSerializer::class)
enum class ExperienceMode(val wireValue: String) {
    FOCUSED("focused"),
    LIBRARY("library"),
    ;

    companion object {
        fun fromWire(value: String): ExperienceMode? = when (value) {
            // Old servers and cached profiles must never restore unrestricted browsing.
            "explore" -> LIBRARY
            else -> entries.firstOrNull { it.wireValue == value }
        }
    }
}

internal object ExperienceModeSerializer : KSerializer<ExperienceMode> {
    override val descriptor = PrimitiveSerialDescriptor("ExperienceMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ExperienceMode) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): ExperienceMode {
        val value = decoder.decodeString()
        return ExperienceMode.fromWire(value.lowercase())
            ?: throw SerializationException("Unknown experience mode: $value")
    }
}

@Serializable
enum class PlaybackPolicy(val wireValue: String) {
    LISTEN_ONLY("listen_only"),
    WATCH_AND_LISTEN("watch_and_listen"),
    ;

    companion object {
        fun fromWire(value: String): PlaybackPolicy? = entries.firstOrNull { it.wireValue == value }
    }
}

@Serializable
data class CuratedVideo(
    val id: String,
    val videoId: String,
    val title: String,
    val author: String,
    val lengthSeconds: Long,
    val thumbnailUrl: String? = null,
    val playbackPolicy: PlaybackPolicy,
    val authorId: String? = null,
    val isShort: Boolean = false,
) {
    fun asVideoSummary(): VideoSummary = VideoSummary(
        videoId = videoId,
        title = title,
        author = author,
        lengthSeconds = lengthSeconds,
        viewCount = 0L,
        publishedText = if (playbackPolicy == PlaybackPolicy.LISTEN_ONLY) "LISTEN ONLY" else "VIDEO ENABLED",
        liveNow = false,
        thumbnailUrl = thumbnailUrl,
        authorId = authorId,
        isShort = isShort,
    )
}

@Serializable
data class CuratedChannel(
    val id: String,
    val channelId: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val playbackPolicy: PlaybackPolicy,
)

@Serializable
data class CuratedPlaylist(
    val id: String,
    val name: String,
    val items: List<CuratedVideo> = emptyList(),
)

@Serializable
data class CompanionProfile(
    val deviceId: String,
    val account: String,
    val revision: Long,
    val mode: ExperienceMode,
    val items: List<CuratedVideo>,
    val channels: List<CuratedChannel> = emptyList(),
    val playlists: List<CuratedPlaylist> = emptyList(),
    val blockedVideoIds: Set<String> = emptySet(),
    val channelFeedLimit: Int = DEFAULT_CHANNEL_FEED_LIMIT,
    val hideWatched: Boolean = true,
)

internal fun CompanionProfile?.effectiveExperienceMode(): ExperienceMode =
    this?.mode ?: ExperienceMode.FOCUSED

internal fun CompanionState.withoutUnverifiedProfile(): CompanionState = copy(profile = null)

internal fun CompanionProfile.allCuratedVideos(): List<CuratedVideo> {
    val blockedIds = knownShortVideoIds()
    return buildList {
        addAll(items)
        playlists.forEach { playlist -> addAll(playlist.items) }
    }.filter { video -> !video.isShort && video.videoId !in blockedIds }
        .distinctBy(CuratedVideo::videoId)
}

internal fun CompanionProfile.knownShortVideoIds(): Set<String> = buildSet {
    blockedVideoIds
        .filter { videoId -> extractYouTubeVideoId(videoId) == videoId }
        .forEach(::add)
    items.filter(CuratedVideo::isShort).forEach { video -> add(video.videoId) }
    playlists.forEach { playlist ->
        playlist.items.filter(CuratedVideo::isShort).forEach { video -> add(video.videoId) }
    }
}

internal fun CompanionProfile.withoutShorts(): CompanionProfile {
    val blockedIds = knownShortVideoIds()
    return copy(
        items = items.filter { video -> !video.isShort && video.videoId !in blockedIds },
        playlists = playlists.map { playlist ->
            playlist.copy(
                items = playlist.items.filter { video -> !video.isShort && video.videoId !in blockedIds },
            )
        },
        blockedVideoIds = blockedIds,
    )
}

internal fun CompanionProfile.playbackPolicyFor(
    videoId: String,
    authorId: String?,
): PlaybackPolicy? {
    if (videoId in knownShortVideoIds()) return null
    return allCuratedVideos()
        .firstOrNull { item -> item.videoId == videoId }
        ?.playbackPolicy
        ?: authorId
            ?.takeIf(::validYouTubeChannelId)
            ?.let { channelId ->
                channels.firstOrNull { channel -> channel.channelId == channelId }?.playbackPolicy
            }
}

data class CompanionSession(
    val instanceUrl: String,
    val deviceId: String,
    val account: String,
    val deviceBearer: String,
)

data class CompanionState(
    val session: CompanionSession? = null,
    val profile: CompanionProfile? = null,
)

data class PendingPairing(
    val instanceUrl: String,
    val pairingId: String,
    val userCode: String,
    val pollSecret: String,
    val deviceBearer: String,
    val verificationUrl: String,
    val expiresAt: String,
)

data class PairingStatus(
    val state: String,
    val deviceLabel: String,
    val account: String? = null,
    val expiresAt: String,
) {
    val isClaimed: Boolean
        get() = state == "claimed"

    val isTerminal: Boolean
        get() = state == "expired" || state == "consumed" || state == "rejected"
}

data class PlaybackAccess(
    val allowed: Boolean,
    val policy: PlaybackPolicy? = null,
    val message: String? = null,
)

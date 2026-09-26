package com.loosewire.lightious.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

const val DEFAULT_INVIDIOUS_INSTANCE_URL: String = ""

enum class HomePage {
    SEARCH,
    ACCOUNT_FEED,
    WATCH_HISTORY,
    SEARCH_HISTORY,
    POPULAR,
}

val DEFAULT_HOME_PAGES: List<HomePage> = listOf(
    HomePage.SEARCH,
    HomePage.ACCOUNT_FEED,
    HomePage.WATCH_HISTORY,
    HomePage.SEARCH_HISTORY,
)

data class ClientSettings(
    val instanceUrl: String = DEFAULT_INVIDIOUS_INSTANCE_URL,
    val selfHostEnabled: Boolean = DEFAULT_INVIDIOUS_INSTANCE_URL.isBlank(),
    val managedServerAvailable: Boolean = DEFAULT_INVIDIOUS_INSTANCE_URL.isNotBlank(),
    val proxyMedia: Boolean = true,
    val homePages: List<HomePage> = DEFAULT_HOME_PAGES,
    val saveSearchHistory: Boolean = true,
    val saveWatchHistory: Boolean = true,
    val syncAccountHistory: Boolean = false,
    val audioLanguage: AudioLanguagePreference = AudioLanguagePreference.ORIGINAL,
)

enum class AudioLanguagePreference(
    val languageCode: String?,
    val displayName: String,
) {
    ORIGINAL(null, "Original audio"),
    ENGLISH("en", "English"),
    SPANISH("es", "Spanish"),
    FRENCH("fr", "French"),
    GERMAN("de", "German"),
    ITALIAN("it", "Italian"),
    PORTUGUESE("pt", "Portuguese"),
    JAPANESE("ja", "Japanese"),
    KOREAN("ko", "Korean"),
    CHINESE("zh", "Chinese"),
    HINDI("hi", "Hindi"),
}

@Serializable
data class VideoSummary(
    val videoId: String,
    val title: String,
    val author: String,
    val lengthSeconds: Long,
    val viewCount: Long,
    val publishedText: String,
    val liveNow: Boolean,
    val thumbnailUrl: String? = null,
    val authorId: String? = null,
    val isShort: Boolean = false,
    val published: Long = 0L,
    val isUpcoming: Boolean = false,
)

data class ChannelVideosPage(
    val videos: List<VideoSummary>,
    val continuation: String? = null,
)

data class ChannelSearchPage(
    val videos: List<VideoSummary>,
    val nextPage: Int? = null,
)

data class SearchHistoryEntry(
    val query: String,
    val lastSearchedAt: Long,
    val useCount: Int,
)

data class WatchHistoryEntry(
    val video: VideoSummary,
    val lastWatchedAt: Long,
)

data class AccountSession(
    val instanceUrl: String,
    val token: String,
    val accountKey: String,
    val username: String? = null,
)

enum class MediaStreamKind {
    PROGRESSIVE,
    ADAPTIVE_AUDIO,
    ADAPTIVE_VIDEO,
    LIVE_HLS,
    UNKNOWN,
}

enum class AudioContentType {
    ORIGINAL,
    DUBBED,
    DUBBED_AUTO,
    UNKNOWN,
}

data class MediaStream(
    val url: String,
    val kind: MediaStreamKind,
    val itag: Int? = null,
    val mimeType: String? = null,
    val container: String? = null,
    val codecs: String? = null,
    val qualityLabel: String? = null,
    val bitrate: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val contentLength: Long? = null,
    val hasAudio: Boolean = false,
    val hasVideo: Boolean = false,
    val audioTrackId: String? = null,
    val audioTrackName: String? = null,
    val audioTrackIsDefault: Boolean? = null,
    val audioLanguage: String? = null,
    val audioContent: AudioContentType = AudioContentType.UNKNOWN,
    val audioDynamicRangeCompressed: Boolean = false,
)

sealed interface VideoPlaybackSource {
    data class Single(val stream: MediaStream) : VideoPlaybackSource

    data class Separate(
        val video: MediaStream,
        val audio: MediaStream,
    ) : VideoPlaybackSource
}

data class StreamSelection(
    val liveHls: MediaStream? = null,
    val progressive: MediaStream? = null,
    val adaptiveAudio: MediaStream? = null,
    val adaptiveVideo: MediaStream? = null,
    val preferAdaptivePair: Boolean = false,
) {
    val watchSource: VideoPlaybackSource?
        get() = liveHls?.let(VideoPlaybackSource::Single)
            ?: (if (preferAdaptivePair) adaptivePair() else null)
            ?: progressive?.let(VideoPlaybackSource::Single)
            ?: adaptivePair()

    val watchProbeUrl: String?
        get() = when (val source = watchSource) {
            is VideoPlaybackSource.Single -> source.stream.url
            is VideoPlaybackSource.Separate -> source.video.url
            null -> null
        }

    val audioUrl: String?
        get() = liveHls?.url ?: adaptiveAudio?.url ?: progressive?.url

    private fun adaptivePair(): VideoPlaybackSource.Separate? = adaptiveVideo?.let { video ->
        adaptiveAudio?.let { audio -> VideoPlaybackSource.Separate(video, audio) }
    }
}

data class VideoDetails(
    val summary: VideoSummary,
    val description: String,
    val formatStreams: List<MediaStream>,
    val adaptiveFormats: List<MediaStream>,
    val hlsUrl: String?,
    val dashUrl: String?,
    val selection: StreamSelection,
) {
    val watchSource: VideoPlaybackSource?
        get() = selection.watchSource

    val audioUrl: String?
        get() = selection.audioUrl
}

data class InstanceProbe(
    val instanceUrl: String,
    val reachable: Boolean,
    val apiAvailable: Boolean,
    val playbackAvailable: Boolean,
    val rangeSupported: Boolean,
    val apiStatusCode: Int? = null,
    val playbackStatusCode: Int? = null,
    val videoId: String? = null,
    val message: String,
) {
    val successful: Boolean
        get() = apiAvailable && playbackAvailable
}

class InvidiousApiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

@Serializable
internal data class InvidiousThumbnailDto(
    val quality: String? = null,
    val url: String? = null,
    val width: JsonElement? = null,
    val height: JsonElement? = null,
)

@Serializable
internal data class InvidiousVideoItemDto(
    val type: String? = null,
    val videoId: String? = null,
    val title: String? = null,
    val author: String? = null,
    val authorId: String? = null,
    val lengthSeconds: JsonElement? = null,
    val viewCount: JsonElement? = null,
    val publishedText: String? = null,
    val liveNow: JsonElement? = null,
    val videoThumbnails: List<InvidiousThumbnailDto>? = null,
    val isShort: JsonElement? = null,
    val published: JsonElement? = null,
    val isUpcoming: JsonElement? = null,
    val premiereTimestamp: JsonElement? = null,
)

@Serializable
internal data class InvidiousVideoDto(
    val type: String? = null,
    val videoId: String? = null,
    val title: String? = null,
    val author: String? = null,
    val authorId: String? = null,
    val lengthSeconds: JsonElement? = null,
    val viewCount: JsonElement? = null,
    val publishedText: String? = null,
    val liveNow: JsonElement? = null,
    val description: String? = null,
    val videoThumbnails: List<InvidiousThumbnailDto>? = null,
    val formatStreams: List<InvidiousFormatDto>? = null,
    val adaptiveFormats: List<InvidiousFormatDto>? = null,
    val hlsUrl: String? = null,
    val dashUrl: String? = null,
    val isShort: JsonElement? = null,
    val published: JsonElement? = null,
    val isUpcoming: JsonElement? = null,
    val premiereTimestamp: JsonElement? = null,
)

@Serializable
internal data class InvidiousFeedDto(
    val notifications: List<InvidiousVideoItemDto>? = null,
    val videos: List<InvidiousVideoItemDto>? = null,
)

@Serializable
internal data class InvidiousStatsDto(
    val software: InvidiousSoftwareDto? = null,
)

@Serializable
internal data class InvidiousSoftwareDto(
    val name: String? = null,
)

@Serializable
internal data class InvidiousChannelVideosDto(
    val videos: List<InvidiousVideoItemDto> = emptyList(),
    val continuation: String? = null,
)

@Serializable
internal data class InvidiousChannelFeedDto(
    val videos: List<InvidiousVideoItemDto>,
    val failedChannelIds: List<String> = emptyList(),
)

@Serializable
internal data class InvidiousChannelSearchDto(
    val videos: List<InvidiousVideoItemDto> = emptyList(),
    val nextPage: Int? = null,
)

@Serializable
internal data class InvidiousFormatDto(
    val url: String? = null,
    val itag: JsonElement? = null,
    val type: String? = null,
    val container: String? = null,
    val encoding: String? = null,
    val quality: String? = null,
    val qualityLabel: String? = null,
    val resolution: String? = null,
    val bitrate: JsonElement? = null,
    val width: JsonElement? = null,
    val height: JsonElement? = null,
    val fps: JsonElement? = null,
    val audioQuality: String? = null,
    val audioSampleRate: JsonElement? = null,
    val audioChannels: JsonElement? = null,
    val audioTrack: InvidiousAudioTrackDto? = null,
    @SerialName("clen") val contentLength: JsonElement? = null,
)

@Serializable
internal data class InvidiousAudioTrackDto(
    val id: String? = null,
    val displayName: String? = null,
    val audioIsDefault: JsonElement? = null,
)

internal fun JsonElement?.asLongOrNull(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.content.toLongOrNull()
}

internal fun JsonElement?.asIntOrNull(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.content.toIntOrNull()
}

internal fun JsonElement?.asBooleanOrNull(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.booleanOrNull ?: when (primitive.content.trim().lowercase()) {
        "1", "yes" -> true
        "0", "no" -> false
        else -> null
    }
}

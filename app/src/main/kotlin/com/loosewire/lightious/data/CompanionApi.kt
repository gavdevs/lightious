package com.loosewire.lightious.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CompanionApi internal constructor(
    instanceUrl: String,
    private val transport: CompanionHttpTransport,
) : AutoCloseable {
    val baseUrl: String = normalizeInstanceUrl(instanceUrl)

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    constructor(instanceUrl: String) : this(instanceUrl, KtorCompanionHttpTransport())

    suspend fun createPairing(
        deviceLabel: String,
        deviceBearerDigest: String,
    ): Result<PendingPairing> = companionRunCatching {
        val response = transport.post(
            url = "$baseUrl/api/lightious/v1/pairings",
            body = json.encodeToString(CreatePairingRequest(deviceLabel, deviceBearerDigest)),
        )
        response.requireSuccess("Pairing request")
        val dto = decode<CreatePairingResponse>(response.body, "pairing response")
        PendingPairing(
            instanceUrl = baseUrl,
            pairingId = dto.pairingId,
            userCode = dto.userCode,
            pollSecret = dto.pollSecret,
            deviceBearer = "",
            verificationUrl = resolveCompanionUrl(baseUrl, dto.verificationUrl),
            expiresAt = dto.expiresAt,
        )
    }

    suspend fun pairingStatus(pending: PendingPairing): Result<PairingStatus> = companionRunCatching {
        val response = transport.get(
            url = "$baseUrl/api/lightious/v1/pairings/${pending.pairingId}",
            headers = bearerHeaders(pending.pollSecret),
        )
        response.requireSuccess("Pairing status")
        val dto = decode<PairingStatusResponse>(response.body, "pairing status")
        PairingStatus(dto.state, dto.deviceLabel, dto.account, dto.expiresAt)
    }

    suspend fun activatePairing(pending: PendingPairing): Result<ActivatePairingResponse> =
        companionRunCatching {
            val response = transport.post(
                url = "$baseUrl/api/lightious/v1/pairings/${pending.pairingId}/activate",
                headers = bearerHeaders(pending.pollSecret),
                body = "{}",
            )
            response.requireSuccess("Pairing activation")
            decode(response.body, "pairing activation")
        }

    suspend fun sync(deviceBearer: String): Result<CompanionProfile> = companionRunCatching {
        require(validDeviceBearer(deviceBearer)) { "Stored device credential is invalid." }
        val response = transport.get(
            url = "$baseUrl/api/lightious/v1/sync",
            headers = bearerHeaders(deviceBearer),
        )
        response.requireSuccess("Companion sync")
        val dto = decode<SyncResponse>(response.body, "companion sync")
        val mode = ExperienceMode.fromWire(dto.mode)
            ?: throw InvidiousApiException("The companion returned an unknown experience mode.")
        val items = dto.items.map(::mapSyncItem).distinctBy(CuratedVideo::videoId)
        CompanionProfile(
            deviceId = dto.deviceId,
            account = dto.account,
            revision = dto.revision,
            mode = mode,
            items = items,
            channels = dto.channels.map { channel ->
                require(validYouTubeChannelId(channel.channelId)) {
                    "The companion returned an invalid channel ID."
                }
                val policy = PlaybackPolicy.fromWire(channel.playbackPolicy)
                    ?: throw InvidiousApiException("The companion returned an unknown playback policy.")
                CuratedChannel(
                    id = channel.id,
                    channelId = channel.channelId,
                    name = channel.name.ifBlank { "Unknown channel" },
                    thumbnailUrl = channel.thumbnailUrl?.let { resolveCompanionUrl(baseUrl, it) },
                    playbackPolicy = policy,
                )
            }.distinctBy(CuratedChannel::channelId),
            playlists = dto.playlists.map { playlist ->
                CuratedPlaylist(
                    id = playlist.id,
                    name = playlist.name.ifBlank { "Untitled playlist" },
                    items = playlist.items
                        .map(::mapSyncItem)
                        .distinctBy(CuratedVideo::videoId),
                )
            }.distinctBy(CuratedPlaylist::id),
            blockedVideoIds = dto.blockedVideoIds.mapTo(linkedSetOf()) { videoId ->
                require(extractYouTubeVideoId(videoId) == videoId) {
                    "The companion returned an invalid blocked video ID."
                }
                videoId
            },
            channelFeedLimit = dto.channelFeedLimit.coerceIn(CHANNEL_FEED_LIMIT_RANGE),
            hideWatched = dto.hideWatched,
        )
    }

    override fun close() {
        transport.close()
    }

    private inline fun <reified T> decode(body: String, description: String): T = try {
        json.decodeFromString(body)
    } catch (error: SerializationException) {
        throw InvidiousApiException("The server returned an invalid $description.", cause = error)
    }

    private fun mapSyncItem(item: SyncItemResponse): CuratedVideo {
        val policy = PlaybackPolicy.fromWire(item.playbackPolicy)
            ?: throw InvidiousApiException("The companion returned an unknown playback policy.")
        require(extractYouTubeVideoId(item.videoId) == item.videoId) {
            "The companion returned an invalid video ID."
        }
        val authorId = item.authorId?.takeIf(String::isNotBlank)?.also { value ->
            require(validYouTubeChannelId(value)) { "The companion returned an invalid channel ID." }
        }
        return CuratedVideo(
            id = item.id,
            videoId = item.videoId,
            title = item.title.ifBlank { "Untitled video" },
            author = item.author.ifBlank { "Unknown channel" },
            lengthSeconds = item.lengthSeconds.coerceAtLeast(0L),
            thumbnailUrl = item.thumbnailUrl?.let { resolveCompanionUrl(baseUrl, it) },
            playbackPolicy = policy,
            authorId = authorId,
            isShort = item.isShort,
        )
    }
}

@Serializable
private data class CreatePairingRequest(
    val deviceLabel: String,
    val deviceBearerDigest: String,
)

@Serializable
private data class CreatePairingResponse(
    val pairingId: String,
    val userCode: String,
    val pollSecret: String,
    val verificationUrl: String,
    val expiresAt: String,
)

@Serializable
private data class PairingStatusResponse(
    val state: String,
    val deviceLabel: String,
    val account: String? = null,
    val expiresAt: String,
)

@Serializable
data class ActivatePairingResponse(
    val deviceId: String,
    val account: String,
)

@Serializable
private data class SyncResponse(
    val deviceId: String,
    val account: String,
    val revision: Long,
    val mode: String,
    val items: List<SyncItemResponse> = emptyList(),
    val channels: List<SyncChannelResponse> = emptyList(),
    val playlists: List<SyncPlaylistResponse> = emptyList(),
    val blockedVideoIds: List<String> = emptyList(),
    val channelFeedLimit: Int = DEFAULT_CHANNEL_FEED_LIMIT,
    val hideWatched: Boolean = true,
)

@Serializable
private data class SyncItemResponse(
    val id: String,
    val videoId: String,
    val title: String,
    val author: String,
    val authorId: String? = null,
    val lengthSeconds: Long = 0L,
    val thumbnailUrl: String? = null,
    val playbackPolicy: String,
    val isShort: Boolean = false,
)

@Serializable
private data class SyncChannelResponse(
    val id: String,
    val channelId: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val playbackPolicy: String,
)

@Serializable
private data class SyncPlaylistResponse(
    val id: String,
    val name: String,
    val items: List<SyncItemResponse> = emptyList(),
)

internal interface CompanionHttpTransport {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): InvidiousHttpResponse

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String,
    ): InvidiousHttpResponse

    fun close()
}

private class KtorCompanionHttpTransport : CompanionHttpTransport {
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000L
            requestTimeoutMillis = 20_000L
            socketTimeoutMillis = 20_000L
        }
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): InvidiousHttpResponse {
        val response = client.get(url) {
            accept(ContentType.Application.Json)
            headers.forEach { (name, value) -> header(name, value) }
        }
        return InvidiousHttpResponse(response.status.value, response.bodyAsText())
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): InvidiousHttpResponse {
        val response = client.post(url) {
            accept(ContentType.Application.Json)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            headers.forEach { (name, value) -> header(name, value) }
            setBody(body)
        }
        return InvidiousHttpResponse(response.status.value, response.bodyAsText())
    }

    override fun close() {
        client.close()
    }
}

fun generateDeviceBearer(random: SecureRandom = SecureRandom()): String =
    DEVICE_BEARER_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

fun deviceBearerDigest(deviceBearer: String): String {
    require(validDeviceBearer(deviceBearer)) { "Invalid device credential." }
    return MessageDigest.getInstance("SHA-256")
        .digest(deviceBearer.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

fun validDeviceBearer(value: String): Boolean = validRandomBearer(value, DEVICE_BEARER_PREFIX)

internal fun validPollSecret(value: String): Boolean = validRandomBearer(value, POLL_SECRET_PREFIX)

private fun validRandomBearer(value: String, prefix: String): Boolean {
    val payload = value.removePrefix(prefix)
    return value.startsWith(prefix) &&
        payload.length == ENCODED_SECRET_LENGTH &&
        payload.all { character ->
            character in 'A'..'Z' || character in 'a'..'z' ||
                character in '0'..'9' || character == '-' || character == '_'
        } &&
        payload.last() in BASE64URL_FINAL_CHARS
}

private fun bearerHeaders(value: String): Map<String, String> = mapOf("Authorization" to "Bearer $value")

private fun resolveCompanionUrl(instanceUrl: String, value: String): String =
    runCatching { URI("${normalizeInstanceUrl(instanceUrl)}/").resolve(value).toString() }
        .getOrDefault(value)

private suspend fun <T> companionRunCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}

private const val DEVICE_BEARER_PREFIX = "lpt_device_"
private const val POLL_SECRET_PREFIX = "lpt_poll_"
private const val ENCODED_SECRET_LENGTH = 43
private const val BASE64URL_FINAL_CHARS = "AEIMQUYcgkosw048"

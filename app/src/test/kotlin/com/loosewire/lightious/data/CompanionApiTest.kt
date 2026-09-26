package com.loosewire.lightious.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionApiTest {
    @Test
    fun `device bearer generation and digest match the server contract`() {
        val bearer = "lpt_device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        assertTrue(validDeviceBearer(bearer))
        assertEquals(
            "ab4bc1907dd011a72fcf10f36082e3e33c20285615049f5f778fec1edd0b2f73",
            deviceBearerDigest(bearer),
        )
        assertTrue(validDeviceBearer(generateDeviceBearer()))
    }

    @Test
    fun `bearer validators enforce canonical 32 byte base64url payloads`() {
        "AEIMQUYcgkosw048".forEach { finalCharacter ->
            val payload = "A".repeat(42) + finalCharacter
            assertTrue(validDeviceBearer("lpt_device_$payload"))
            assertTrue(validPollSecret("lpt_poll_$payload"))
        }
        listOf('B', 'Z', '-', '_').forEach { finalCharacter ->
            val payload = "A".repeat(42) + finalCharacter
            assertFalse(validDeviceBearer("lpt_device_$payload"))
            assertFalse(validPollSecret("lpt_poll_$payload"))
        }
        assertFalse(validDeviceBearer("lpt_device_short"))
        assertFalse(validPollSecret("lpt_device_${"A".repeat(43)}"))
    }

    @Test
    fun `pairing uses polling credential and sync maps playlist items independently`() = runTest {
        val transport = FakeCompanionTransport()
        val api = CompanionApi("https://invidious.example", transport)

        val pending = api.createPairing("Light Phone III", "a".repeat(64)).getOrThrow()
            .copy(deviceBearer = DEVICE_BEARER)
        val status = api.pairingStatus(pending).getOrThrow()
        val activated = api.activatePairing(pending).getOrThrow()
        val profile = api.sync(DEVICE_BEARER).getOrThrow()

        assertEquals("ABCD-EFGH-JKMN", pending.userCode)
        assertEquals("https://invidious.example/lightious/pair", pending.verificationUrl)
        assertTrue(status.isClaimed)
        assertEquals(DEVICE_ID, activated.deviceId)
        assertEquals(ExperienceMode.FOCUSED, profile.mode)
        assertEquals(PlaybackPolicy.LISTEN_ONLY, profile.items.single().playbackPolicy)
        assertEquals(CHANNEL_ID, profile.items.single().authorId)
        assertEquals("https://invidious.example/vi/dQw4w9WgXcQ/mqdefault.jpg", profile.items.single().thumbnailUrl)
        assertEquals(CHANNEL_ID, profile.channels.single().channelId)
        assertEquals(PlaybackPolicy.WATCH_AND_LISTEN, profile.channels.single().playbackPolicy)
        assertEquals(
            listOf("dQw4w9WgXcQ", PLAYLIST_ONLY_VIDEO_ID),
            profile.playlists.single().items.map(CuratedVideo::videoId),
        )
        assertEquals("Different playlist metadata", profile.playlists.single().items.first().title)
        assertEquals(PlaybackPolicy.WATCH_AND_LISTEN, profile.playlists.single().items.first().playbackPolicy)
        assertEquals(
            "https://invidious.example/vi/$PLAYLIST_ONLY_VIDEO_ID/mqdefault.jpg",
            profile.playlists.single().items.last().thumbnailUrl,
        )
        assertFalse(profile.items.any { item -> item.videoId == PLAYLIST_ONLY_VIDEO_ID })
        assertTrue(transport.posts.first().body.contains("\"deviceBearerDigest\":\"${"a".repeat(64)}\""))
        assertEquals("Bearer lpt_poll_${"A".repeat(43)}", transport.gets.first().headers["Authorization"])
        assertEquals("Bearer $DEVICE_BEARER", transport.gets.last().headers["Authorization"])
        api.close()
    }

    @Test
    fun `sync rejects a non-canonical selected channel`() = runTest {
        val api = CompanionApi("https://invidious.example", InvalidChannelTransport)

        val result = api.sync(DEVICE_BEARER)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("invalid channel ID") == true)
        api.close()
    }

    @Test
    fun `sync preserves explicit Short markers for repository cleanup`() = runTest {
        val api = CompanionApi("https://invidious.example", FlaggedShortSyncTransport)

        val profile = api.sync(DEVICE_BEARER).getOrThrow()

        assertEquals(setOf(SHORT_VIDEO_ID, BLOCKED_VIDEO_ID), profile.knownShortVideoIds())
        assertEquals(setOf(BLOCKED_VIDEO_ID), profile.blockedVideoIds)
        assertTrue(profile.items.single().isShort)
        assertTrue(profile.playlists.single().items.single().isShort)
        api.close()
    }

    @Test
    fun `sync defaults feed settings for older servers and maps explore to library`() = runTest {
        CompanionApi("https://invidious.example", SyncSettingsTransport("\"mode\":\"explore\"")).use { api ->
            val profile = api.sync(DEVICE_BEARER).getOrThrow()

            assertEquals(ExperienceMode.LIBRARY, profile.mode)
            assertEquals(3, profile.channelFeedLimit)
            assertTrue(profile.hideWatched)
        }
    }

    @Test
    fun `sync reads bounded feed preferences and rejects unknown modes`() = runTest {
        listOf(0 to 1, 5 to 5, 100 to 5).forEach { (received, expected) ->
            CompanionApi(
                "https://invidious.example",
                SyncSettingsTransport("\"mode\":\"focused\",\"channelFeedLimit\":$received,\"hideWatched\":false"),
            ).use { api ->
                val profile = api.sync(DEVICE_BEARER).getOrThrow()

                assertEquals(expected, profile.channelFeedLimit)
                assertFalse(profile.hideWatched)
            }
        }
        CompanionApi("https://invidious.example", SyncSettingsTransport("\"mode\":\"unknown\"")).use { api ->
            assertTrue(api.sync(DEVICE_BEARER).isFailure)
        }
    }

    private class SyncSettingsTransport(private val fields: String) : CompanionHttpTransport {
        override suspend fun get(url: String, headers: Map<String, String>) = InvidiousHttpResponse(
            200,
            """{"deviceId":"$DEVICE_ID","account":"gav","revision":1,$fields}""",
        )

        override suspend fun post(url: String, headers: Map<String, String>, body: String): InvidiousHttpResponse =
            error("Unexpected POST: $url")

        override fun close() = Unit
    }

    private data class Request(val url: String, val headers: Map<String, String>, val body: String = "")

    private class FakeCompanionTransport : CompanionHttpTransport {
        val gets = mutableListOf<Request>()
        val posts = mutableListOf<Request>()

        override suspend fun get(url: String, headers: Map<String, String>): InvidiousHttpResponse {
            gets += Request(url, headers)
            return when {
                url.endsWith("/pairings/$PAIRING_ID") -> InvidiousHttpResponse(
                    200,
                    """{"state":"claimed","deviceLabel":"Light Phone III","account":"ga…@example.com","expiresAt":"2026-08-31T20:00:00Z"}""",
                )
                url.endsWith("/sync") -> InvidiousHttpResponse(
                    200,
                    """
                    {
                      "deviceId":"$DEVICE_ID",
                      "account":"ga…@example.com",
                      "revision":4,
                      "mode":"focused",
                      "items":[{
                        "id":"item-1",
                        "videoId":"dQw4w9WgXcQ",
                        "title":"A video",
                        "author":"A channel",
                        "authorId":"$CHANNEL_ID",
                        "lengthSeconds":213,
                        "thumbnailUrl":"/vi/dQw4w9WgXcQ/mqdefault.jpg",
                        "playbackPolicy":"listen_only"
                      }],
                      "channels":[{
                        "id":"channel-1",
                        "channelId":"$CHANNEL_ID",
                        "name":"A channel",
                        "thumbnailUrl":"/ggpht/channel.jpg",
                        "playbackPolicy":"watch_and_listen"
                      }],
                      "playlists":[{
                        "id":"playlist-1",
                        "name":"Bedtime",
                        "items":[{
                          "id":"item-1",
                          "videoId":"dQw4w9WgXcQ",
                          "title":"Different playlist metadata",
                          "author":"A channel",
                          "authorId":"$CHANNEL_ID",
                          "lengthSeconds":213,
                          "playbackPolicy":"watch_and_listen"
                        },{
                          "id":"playlist-item-2",
                          "videoId":"$PLAYLIST_ONLY_VIDEO_ID",
                          "title":"Playlist-only video",
                          "author":"Another channel",
                          "lengthSeconds":90,
                          "thumbnailUrl":"/vi/$PLAYLIST_ONLY_VIDEO_ID/mqdefault.jpg",
                          "playbackPolicy":"listen_only"
                        }]
                      }]
                    }
                    """.trimIndent(),
                )
                else -> error("Unexpected GET: $url")
            }
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): InvidiousHttpResponse {
            posts += Request(url, headers, body)
            return when {
                url.endsWith("/pairings") -> InvidiousHttpResponse(
                    201,
                    """
                    {
                      "pairingId":"$PAIRING_ID",
                      "userCode":"ABCD-EFGH-JKMN",
                      "pollSecret":"lpt_poll_${"A".repeat(43)}",
                      "verificationUrl":"/lightious/pair",
                      "expiresAt":"2026-08-31T20:00:00Z"
                    }
                    """.trimIndent(),
                )
                url.endsWith("/activate") -> InvidiousHttpResponse(
                    200,
                    """{"deviceId":"$DEVICE_ID","account":"ga…@example.com"}""",
                )
                else -> error("Unexpected POST: $url")
            }
        }

        override fun close() = Unit
    }

    private data object InvalidChannelTransport : CompanionHttpTransport {
        override suspend fun get(url: String, headers: Map<String, String>) = InvidiousHttpResponse(
            200,
            """
            {
              "deviceId":"$DEVICE_ID",
              "account":"gav",
              "revision":1,
              "mode":"focused",
              "channels":[{
                "id":"channel-1",
                "channelId":"not-a-channel",
                "name":"Broken",
                "playbackPolicy":"listen_only"
              }]
            }
            """.trimIndent(),
        )

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): InvidiousHttpResponse = error("Unexpected POST: $url")

        override fun close() = Unit
    }

    private data object FlaggedShortSyncTransport : CompanionHttpTransport {
        override suspend fun get(url: String, headers: Map<String, String>) = InvidiousHttpResponse(
            200,
            """
            {
              "deviceId":"$DEVICE_ID",
              "account":"gav",
              "revision":2,
              "mode":"focused",
              "blockedVideoIds":["$BLOCKED_VIDEO_ID"],
              "items":[{
                "id":"short-item",
                "videoId":"$SHORT_VIDEO_ID",
                "title":"A Short",
                "author":"Channel",
                "playbackPolicy":"watch_and_listen",
                "isShort":true
              }],
              "playlists":[{
                "id":"short-playlist",
                "name":"Bad cache",
                "items":[{
                  "id":"short-playlist-item",
                  "videoId":"$SHORT_VIDEO_ID",
                  "title":"A Short",
                  "author":"Channel",
                  "playbackPolicy":"listen_only",
                  "isShort":true
                }]
              }]
            }
            """.trimIndent(),
        )

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): InvidiousHttpResponse = error("Unexpected POST: $url")

        override fun close() = Unit
    }

    private companion object {
        const val PAIRING_ID = "11111111111111111111111111111111"
        const val DEVICE_ID = "22222222222222222222222222222222"
        const val DEVICE_BEARER = "lpt_device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val CHANNEL_ID = "UCXuqSBlHAE6Xw-yeJA0Tunw"
        const val PLAYLIST_ONLY_VIDEO_ID = "aqz-KE-bpKQ"
        const val SHORT_VIDEO_ID = "m8KnrXli-bA"
        const val BLOCKED_VIDEO_ID = "9bZkp7q19f0"
    }
}

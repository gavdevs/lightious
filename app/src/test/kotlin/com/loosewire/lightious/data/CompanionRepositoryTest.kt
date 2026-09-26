package com.loosewire.lightious.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionRepositoryTest {
    @Test
    fun `begin pairing accepts current and legacy rolling upgrade codes`() = runTest {
        listOf("ABCD-EFGH-JKMN", "ABCD-EFGH").forEach { userCode ->
            val store = CompanionStore(
                createDataStore(File.createTempFile("lightious-pairing", ".preferences_pb")),
                FakeCipher(),
            )
            val repository = CompanionRepository(store) { url ->
                CompanionApi(url, PairingTransport(userCode))
            }

            val pending = repository.beginPairing("https://invidious.example").getOrThrow()

            assertEquals(userCode, pending.userCode)
            assertTrue(validDeviceBearer(pending.deviceBearer))
        }
    }

    @Test
    fun `activation returns empty state after authoritative sync rejection`() = runTest {
        listOf(401, 404).forEach { statusCode ->
            val store = CompanionStore(
                createDataStore(File.createTempFile("lightious-activation-rejected", ".preferences_pb")),
                FakeCipher(),
            )
            val repository = CompanionRepository(store) { url ->
                CompanionApi(url, ActivationSyncTransport(statusCode))
            }

            val state = repository.activatePairing(pendingPairing()).getOrThrow()

            assertEquals(CompanionState(), state)
            assertEquals(CompanionState(), repository.load("https://invidious.example"))
        }
    }

    @Test
    fun `activation retains paired state after transient sync failure`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-activation-transient", ".preferences_pb")),
            FakeCipher(),
        )
        val repository = CompanionRepository(store) { url ->
            CompanionApi(url, ActivationSyncTransport(503))
        }

        val state = repository.activatePairing(pendingPairing()).getOrThrow()

        assertEquals(DEVICE_ID, state.session?.deviceId)
        assertEquals("gav", state.session?.account)
        assertEquals(null, state.profile)
        assertEquals(state, repository.load("https://invidious.example"))
    }

    @Test
    fun `unpaired playback is denied before any server request`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-unpaired-policy", ".preferences_pb")),
            FakeCipher(),
        )
        val repository = CompanionRepository(store) { error("Unpaired playback must not contact the server.") }

        val access = repository.authorizePlayback("https://invidious.example", "dQw4w9WgXcQ", null)

        assertFalse(access.allowed)
        assertEquals(null, access.policy)
        assertEquals("Pair this phone with Lightious before playing videos.", access.message)
    }

    @Test
    fun `paired library profile requires curated playback authorization`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-library-policy", ".preferences_pb")),
            FakeCipher(),
        )
        store.saveSession(
            CompanionSession("https://invidious.example", DEVICE_ID, "gav", DEVICE_BEARER),
        )
        val transport = ProfileSyncTransport(ExperienceMode.LIBRARY)
        val repository = CompanionRepository(store) { url -> CompanionApi(url, transport) }

        val allowed = repository.authorizePlayback("https://invidious.example", "dQw4w9WgXcQ", null)
        val access = repository.authorizePlayback("https://invidious.example", "aqz-KE-bpKQ", null)

        assertTrue(allowed.allowed)
        assertEquals(PlaybackPolicy.LISTEN_ONLY, allowed.policy)
        assertFalse(access.allowed)
        assertEquals(null, access.policy)
        assertEquals(2, transport.syncCalls)
    }

    @Test
    fun `explicitly flagged Shorts are denied without syncing`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-short-policy", ".preferences_pb")),
            FakeCipher(),
        )
        val repository = CompanionRepository(store) {
            error("A known Short must be denied before contacting the server.")
        }

        val access = repository.authorizePlayback(
            "https://invidious.example",
            SHORT_VIDEO_ID,
            CHANNEL_ID,
            isShort = true,
        )

        assertFalse(access.allowed)
        assertEquals(SHORTS_BLOCKED_MESSAGE, access.message)
    }

    @Test
    fun `sync exposes flagged Shorts only to cleanup and never caches them`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-short-sync", ".preferences_pb")),
            FakeCipher(),
        )
        val session = CompanionSession("https://invidious.example", DEVICE_ID, "gav", DEVICE_BEARER)
        store.saveSession(session)
        var cleanupIds = emptySet<String>()
        val repository = CompanionRepository(
            store = store,
            onProfileSynced = { profile -> cleanupIds = profile.knownShortVideoIds() },
            apiFactory = { url -> CompanionApi(url, FlaggedShortSyncTransport) },
        )

        val profile = repository.sync(session.instanceUrl).getOrThrow()
        val access = repository.authorizePlayback(
            session.instanceUrl,
            SHORT_VIDEO_ID,
            CHANNEL_ID,
        )

        assertTrue(profile.items.isEmpty())
        assertTrue(profile.playlists.single().items.isEmpty())
        assertEquals(setOf(SHORT_VIDEO_ID), cleanupIds)
        assertTrue(repository.load(session.instanceUrl).profile?.allCuratedVideos().orEmpty().isEmpty())
        assertFalse(access.allowed)
        assertEquals(SHORTS_BLOCKED_MESSAGE, access.message)
    }

    @Test
    fun `focused mode allows top-level and playlist-only videos while denying unknown videos`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-policy", ".preferences_pb")),
            FakeCipher(),
        )
        store.saveSession(
            CompanionSession(
                "https://invidious.example",
                DEVICE_ID,
                "gav",
                DEVICE_BEARER,
            ),
        )
        val transport = ProfileSyncTransport()
        val repository = CompanionRepository(store) { url -> CompanionApi(url, transport) }

        val allowed = repository.authorizePlayback("https://invidious.example", "dQw4w9WgXcQ", null)
        val playlistOnly = repository.authorizePlayback(
            "https://invidious.example",
            PLAYLIST_ONLY_VIDEO_ID,
            null,
        )
        val denied = repository.authorizePlayback("https://invidious.example", "9bZkp7q19f0", null)

        assertTrue(allowed.allowed)
        assertEquals(PlaybackPolicy.LISTEN_ONLY, allowed.policy)
        assertTrue(playlistOnly.allowed)
        assertEquals(PlaybackPolicy.WATCH_AND_LISTEN, playlistOnly.policy)
        assertFalse(denied.allowed)
        assertEquals("This video is not in your library.", denied.message)
        assertEquals(3, transport.syncCalls)
    }

    @Test
    fun `focused mode permits selected channel but exact video policy wins`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-channel-policy", ".preferences_pb")),
            FakeCipher(),
        )
        store.saveSession(
            CompanionSession("https://invidious.example", DEVICE_ID, "gav", DEVICE_BEARER),
        )
        val repository = CompanionRepository(store) { url -> CompanionApi(url, ProfileSyncTransport()) }

        val exact = repository.authorizePlayback(
            "https://invidious.example",
            "dQw4w9WgXcQ",
            CHANNEL_ID,
        )
        val channel = repository.authorizePlayback(
            "https://invidious.example",
            "aqz-KE-bpKQ",
            CHANNEL_ID,
        )
        val wrongChannel = repository.authorizePlayback(
            "https://invidious.example",
            "aqz-KE-bpKQ",
            "UCBJycsmduvYEL83R_U4JriQ",
        )
        val missingChannel = repository.authorizePlayback(
            "https://invidious.example",
            "aqz-KE-bpKQ",
            null,
        )

        assertEquals(PlaybackPolicy.LISTEN_ONLY, exact.policy)
        assertEquals(PlaybackPolicy.WATCH_AND_LISTEN, channel.policy)
        assertFalse(wrongChannel.allowed)
        assertFalse(missingChannel.allowed)
    }

    @Test
    fun `paired playback fails closed when current policy cannot be synced`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-revoked", ".preferences_pb")),
            FakeCipher(),
        )
        store.saveSession(
            CompanionSession(
                "https://invidious.example",
                DEVICE_ID,
                "gav",
                DEVICE_BEARER,
            ),
        )
        store.saveProfile(
            "https://invidious.example",
            CompanionProfile(
                deviceId = DEVICE_ID,
                account = "gav",
                revision = 1,
                mode = ExperienceMode.FOCUSED,
                items = emptyList(),
            ),
        )
        val repository = CompanionRepository(store) { url -> CompanionApi(url, UnauthorizedSyncTransport) }

        val access = repository.authorizePlayback("https://invidious.example", "dQw4w9WgXcQ", null)

        assertFalse(access.allowed)
        assertTrue(access.message?.contains("HTTP 401") == true)
        assertEquals(CompanionState(), repository.load("https://invidious.example"))
    }

    @Test
    fun `missing device sync clears stale companion state`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-missing-device", ".preferences_pb")),
            FakeCipher(),
        )
        val session = CompanionSession(
            "https://invidious.example",
            DEVICE_ID,
            "gav",
            DEVICE_BEARER,
        )
        val profile = CompanionProfile(
            deviceId = DEVICE_ID,
            account = "gav",
            revision = 1,
            mode = ExperienceMode.FOCUSED,
            items = emptyList(),
        )
        store.saveSession(session)
        store.saveProfile(session.instanceUrl, profile)
        var removedOwner: String? = null
        val repository = CompanionRepository(
            store = store,
            onPairingRemoved = { owner -> removedOwner = owner },
            apiFactory = { url -> CompanionApi(url, FailedSyncTransport(404)) },
        )

        val result = repository.sync(session.instanceUrl)

        assertTrue(result.isFailure)
        assertEquals(CompanionState(), repository.load(session.instanceUrl))
        assertEquals(DEVICE_ID, removedOwner)
    }

    @Test
    fun `forget removes media owned by the paired device`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-forget-downloads", ".preferences_pb")),
            FakeCipher(),
        )
        val session = CompanionSession(
            "https://invidious.example",
            DEVICE_ID,
            "gav",
            DEVICE_BEARER,
        )
        store.saveSession(session)
        var removedOwner: String? = null
        val repository = CompanionRepository(
            store = store,
            onPairingRemoved = { owner -> removedOwner = owner },
        )

        repository.forget(session.instanceUrl)

        assertEquals(CompanionState(), repository.load(session.instanceUrl))
        assertEquals(DEVICE_ID, removedOwner)
    }

    @Test
    fun `transient sync failure preserves cached companion state`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-transient", ".preferences_pb")),
            FakeCipher(),
        )
        val session = CompanionSession(
            "https://invidious.example",
            DEVICE_ID,
            "gav",
            DEVICE_BEARER,
        )
        val profile = CompanionProfile(
            deviceId = DEVICE_ID,
            account = "gav",
            revision = 1,
            mode = ExperienceMode.FOCUSED,
            items = emptyList(),
        )
        store.saveSession(session)
        store.saveProfile(session.instanceUrl, profile)
        val repository = CompanionRepository(store) { url ->
            CompanionApi(url, FailedSyncTransport(503))
        }

        val result = repository.sync(session.instanceUrl)

        assertTrue(result.isFailure)
        assertEquals(CompanionState(session, profile), repository.load(session.instanceUrl))
    }

    @Test
    fun `network sync failure preserves cached companion state`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-network", ".preferences_pb")),
            FakeCipher(),
        )
        val session = CompanionSession(
            "https://invidious.example",
            DEVICE_ID,
            "gav",
            DEVICE_BEARER,
        )
        val profile = CompanionProfile(
            deviceId = DEVICE_ID,
            account = "gav",
            revision = 1,
            mode = ExperienceMode.FOCUSED,
            items = emptyList(),
        )
        store.saveSession(session)
        store.saveProfile(session.instanceUrl, profile)
        val repository = CompanionRepository(store) { url ->
            CompanionApi(url, NetworkFailureTransport)
        }

        val result = repository.sync(session.instanceUrl)

        assertTrue(result.isFailure)
        assertEquals(CompanionState(session, profile), repository.load(session.instanceUrl))
    }

    @Test
    fun `late unauthorized response does not clear a newly paired session`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-repaired-race", ".preferences_pb")),
            FakeCipher(),
        )
        val oldSession = CompanionSession(
            "https://invidious.example",
            DEVICE_ID,
            "old account",
            DEVICE_BEARER,
        )
        val newSession = CompanionSession(
            "https://invidious.example",
            "33333333333333333333333333333333",
            "new account",
            "lpt_device_${"E".repeat(43)}",
        )
        store.saveSession(oldSession)
        val repository = CompanionRepository(store) { url ->
            CompanionApi(url, RepairedDuringSyncTransport(store, newSession))
        }

        val result = repository.sync(oldSession.instanceUrl)

        assertTrue(result.isFailure)
        assertEquals(CompanionState(session = newSession), repository.load(oldSession.instanceUrl))
    }

    private fun TestScope.createDataStore(file: File): DataStore<Preferences> {
        file.delete()
        return PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
    }

    private class FakeCipher : TokenCipher {
        override fun encrypt(plaintext: String): ByteArray =
            "encrypted:${plaintext.reversed()}".toByteArray(StandardCharsets.UTF_8)

        override fun decrypt(blob: ByteArray): String =
            String(blob, StandardCharsets.UTF_8).removePrefix("encrypted:").reversed()
    }

    private class ProfileSyncTransport(
        private val mode: ExperienceMode = ExperienceMode.FOCUSED,
    ) : CompanionHttpTransport {
        var syncCalls = 0

        override suspend fun get(url: String, headers: Map<String, String>): InvidiousHttpResponse {
            check(url.endsWith("/sync"))
            syncCalls += 1
            return InvidiousHttpResponse(
                200,
                """
                {
                  "deviceId":"$DEVICE_ID",
                  "account":"gav",
                  "revision":7,
                  "mode":"${mode.wireValue}",
                  "items":[{
                    "id":"item-1",
                    "videoId":"dQw4w9WgXcQ",
                    "title":"A video",
                    "author":"A channel",
                    "authorId":"$CHANNEL_ID",
                    "lengthSeconds":213,
                    "playbackPolicy":"listen_only"
                  }],
                  "channels":[{
                    "id":"channel-1",
                    "channelId":"$CHANNEL_ID",
                    "name":"A channel",
                    "playbackPolicy":"watch_and_listen"
                  }],
                  "playlists":[{
                    "id":"playlist-1",
                    "name":"Bedtime",
                    "items":[{
                      "id":"playlist-item-1",
                      "videoId":"$PLAYLIST_ONLY_VIDEO_ID",
                      "title":"Playlist-only video",
                      "author":"A different channel",
                      "lengthSeconds":90,
                      "playbackPolicy":"watch_and_listen"
                    }]
                  }]
                }
                """.trimIndent(),
            )
        }

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
              "revision":8,
              "mode":"explore",
              "blockedVideoIds":["$SHORT_VIDEO_ID"],
              "items":[],
              "playlists":[{
                "id":"short-playlist",
                "name":"Bad cache",
                "items":[]
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

    private class PairingTransport(
        private val userCode: String,
    ) : CompanionHttpTransport {
        override suspend fun get(url: String, headers: Map<String, String>): InvidiousHttpResponse =
            error("Unexpected GET: $url")

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ) = InvidiousHttpResponse(
            201,
            """
            {
              "pairingId":"11111111111111111111111111111111",
              "userCode":"$userCode",
              "pollSecret":"lpt_poll_${"A".repeat(43)}",
              "verificationUrl":"/lightious/pair",
              "expiresAt":"2026-09-01T20:00:00Z"
            }
            """.trimIndent(),
        )

        override fun close() = Unit
    }

    private class ActivationSyncTransport(
        private val syncStatusCode: Int,
    ) : CompanionHttpTransport {
        override suspend fun get(url: String, headers: Map<String, String>): InvidiousHttpResponse {
            check(url.endsWith("/sync"))
            return InvidiousHttpResponse(syncStatusCode, "Sync failed.")
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): InvidiousHttpResponse {
            check(url.endsWith("/pairings/11111111111111111111111111111111/activate"))
            return InvidiousHttpResponse(
                200,
                """{"deviceId":"$DEVICE_ID","account":"gav"}""",
            )
        }

        override fun close() = Unit
    }

    private data object UnauthorizedSyncTransport : CompanionHttpTransport {
        override suspend fun get(url: String, headers: Map<String, String>) =
            InvidiousHttpResponse(401, "Invalid device credential.")

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): InvidiousHttpResponse = error("Unexpected POST: $url")

        override fun close() = Unit
    }

    private class FailedSyncTransport(
        private val statusCode: Int,
    ) : CompanionHttpTransport {
        override suspend fun get(url: String, headers: Map<String, String>) =
            InvidiousHttpResponse(statusCode, "Sync failed.")

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): InvidiousHttpResponse = error("Unexpected POST: $url")

        override fun close() = Unit
    }

    private data object NetworkFailureTransport : CompanionHttpTransport {
        override suspend fun get(url: String, headers: Map<String, String>): InvidiousHttpResponse =
            throw IllegalStateException("offline")

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): InvidiousHttpResponse = error("Unexpected POST: $url")

        override fun close() = Unit
    }

    private class RepairedDuringSyncTransport(
        private val store: CompanionStore,
        private val newSession: CompanionSession,
    ) : CompanionHttpTransport {
        override suspend fun get(url: String, headers: Map<String, String>): InvidiousHttpResponse {
            store.saveSession(newSession)
            return InvidiousHttpResponse(401, "Old device credential was revoked.")
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): InvidiousHttpResponse = error("Unexpected POST: $url")

        override fun close() = Unit
    }

    private fun pendingPairing() = PendingPairing(
        instanceUrl = "https://invidious.example",
        pairingId = "11111111111111111111111111111111",
        userCode = "ABCD-EFGH-JKMN",
        pollSecret = "lpt_poll_${"A".repeat(43)}",
        deviceBearer = DEVICE_BEARER,
        verificationUrl = "https://invidious.example/lightious/pair",
        expiresAt = "2026-09-01T20:00:00Z",
    )

    private companion object {
        const val DEVICE_ID = "22222222222222222222222222222222"
        const val DEVICE_BEARER = "lpt_device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val CHANNEL_ID = "UCXuqSBlHAE6Xw-yeJA0Tunw"
        const val PLAYLIST_ONLY_VIDEO_ID = "m8KnrXli-bA"
        const val SHORT_VIDEO_ID = "9bZkp7q19f0"
    }
}

package com.loosewire.lightious.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanionStoreTest {
    @Test
    fun `older cached profile defaults channels playlists and author id`() {
        val profile = Json { ignoreUnknownKeys = true }.decodeFromString<CompanionProfile>(
            """
            {
              "deviceId":"device-1",
              "account":"gav",
              "revision":1,
              "mode":"FOCUSED",
              "items":[{
                "id":"item-1",
                "videoId":"dQw4w9WgXcQ",
                "title":"A video",
                "author":"A channel",
                "lengthSeconds":213,
                "playbackPolicy":"LISTEN_ONLY"
              }]
            }
            """.trimIndent(),
        )

        assertEquals(emptyList(), profile.channels)
        assertEquals(emptyList(), profile.playlists)
        assertNull(profile.items.single().authorId)
        assertFalse(profile.items.single().isShort)
        assertEquals(3, profile.channelFeedLimit)
        assertTrue(profile.hideWatched)
    }

    @Test
    fun `legacy cached explore profile loads as library`() {
        val profile = Json.decodeFromString<CompanionProfile>(
            """{"deviceId":"device-1","account":"gav","revision":1,"mode":"EXPLORE","items":[]}""",
        )

        assertEquals(ExperienceMode.LIBRARY, profile.mode)
        assertEquals(3, profile.channelFeedLimit)
        assertTrue(profile.hideWatched)
    }

    @Test
    fun `cached profiles never return explicitly flagged Shorts`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-short-cache", ".preferences_pb")),
            FakeCipher(),
        )
        val session = CompanionSession("https://example.com", "device-1", "gav", DEVICE_BEARER)
        val short = CuratedVideo(
            id = "short-item",
            videoId = "dQw4w9WgXcQ",
            title = "A Short",
            author = "A channel",
            lengthSeconds = 30,
            playbackPolicy = PlaybackPolicy.WATCH_AND_LISTEN,
            isShort = true,
        )
        store.saveSession(session)
        store.saveProfile(
            session.instanceUrl,
            CompanionProfile(
                deviceId = session.deviceId,
                account = session.account,
                revision = 1,
                mode = ExperienceMode.FOCUSED,
                items = listOf(short),
                playlists = listOf(CuratedPlaylist("playlist", "Playlist", listOf(short))),
            ),
        )

        val cached = store.load(session.instanceUrl).profile

        assertTrue(cached?.items.orEmpty().isEmpty())
        assertTrue(cached?.playlists?.single()?.items.orEmpty().isEmpty())
    }

    @Test
    fun `device credential is encrypted and cached profile is instance scoped`() = runTest {
        val dataStore = createDataStore(File.createTempFile("lightious-companion", ".preferences_pb"))
        val cipher = FakeCipher()
        val store = CompanionStore(dataStore, cipher)
        val session = CompanionSession(
            instanceUrl = "Example.COM:443/",
            deviceId = "device-1",
            account = "ga…@example.com",
            deviceBearer = DEVICE_BEARER,
        )
        val profile = CompanionProfile(
            deviceId = "device-1",
            account = "ga…@example.com",
            revision = 3,
            mode = ExperienceMode.FOCUSED,
            channelFeedLimit = 5,
            hideWatched = false,
            items = listOf(
                CuratedVideo(
                    id = "item-1",
                    videoId = "dQw4w9WgXcQ",
                    title = "A video",
                    author = "A channel",
                    lengthSeconds = 213,
                    playbackPolicy = PlaybackPolicy.LISTEN_ONLY,
                ),
            ),
            channels = listOf(
                CuratedChannel(
                    id = "channel-1",
                    channelId = "UCXuqSBlHAE6Xw-yeJA0Tunw",
                    name = "A channel",
                    playbackPolicy = PlaybackPolicy.WATCH_AND_LISTEN,
                ),
            ),
            playlists = listOf(
                CuratedPlaylist(
                    id = "playlist-1",
                    name = "Bedtime",
                    items = emptyList(),
                ),
            ),
        )

        store.saveSession(session)
        store.saveProfile("https://example.com", profile)

        assertEquals(CompanionState(session.copy(instanceUrl = "https://example.com"), profile), store.load("example.com"))
        assertEquals(CompanionState(), store.load("https://other.example"))
        assertFalse(
            dataStore.data.first().asMap().values
                .map(Any::toString)
                .any { value -> value.contains(DEVICE_BEARER) },
        )

        store.clear()
        assertNull(store.load("https://example.com").session)
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

    private companion object {
        const val DEVICE_BEARER = "lpt_device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}

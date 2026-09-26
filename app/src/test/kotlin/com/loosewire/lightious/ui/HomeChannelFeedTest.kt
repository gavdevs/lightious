package com.loosewire.lightious.ui

import com.loosewire.lightious.data.ChannelFeedPage
import com.loosewire.lightious.data.CompanionProfile
import com.loosewire.lightious.data.ExperienceMode
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class HomeChannelFeedTest {
    @Test
    fun `returning from playback reuses the same finite release window`() = runTest {
        val cache = HomeChannelFeedCache()
        var requests = 0
        val fetch: suspend () -> Result<ChannelFeedPage> = {
            requests += 1
            Result.success(page)
        }

        assertEquals(page, cache.load(instance, profile, false, fetch))
        assertEquals(page, cache.load(instance, profile, false, fetch))
        assertEquals(1, requests)

        cache.load(instance, profile, true, fetch)
        assertEquals(2, requests)
    }

    @Test
    fun `changed policy pairing or server invalidates the cached window`() = runTest {
        val cache = HomeChannelFeedCache()
        var requests = 0
        val fetch: suspend () -> Result<ChannelFeedPage> = {
            requests += 1
            Result.success(page)
        }

        cache.load(instance, profile, false, fetch)
        cache.load(instance, profile.copy(revision = 2), false, fetch)
        cache.load(instance, profile.copy(deviceId = "another-device", revision = 2), false, fetch)
        cache.load("https://another.example", profile.copy(deviceId = "another-device", revision = 2), false, fetch)

        assertEquals(4, requests)
    }

    @Test
    fun `failed refresh never silently restores old releases on the next return`() = runTest {
        val cache = HomeChannelFeedCache()
        cache.load(instance, profile, false) { Result.success(page) }

        assertFailsWith<IOException> {
            cache.load(instance, profile, true) { Result.failure(IOException("Server unavailable")) }
        }

        var retried = false
        cache.load(instance, profile, false) {
            retried = true
            Result.success(page)
        }
        assertEquals(true, retried)
    }

    @Test
    fun `canceled response cannot replace a later profile window`() = runTest {
        val cache = HomeChannelFeedCache()
        val response = CompletableDeferred<Result<ChannelFeedPage>>()
        val request = launch { cache.load(instance, profile, false) { response.await() } }
        runCurrent()
        request.cancel()
        request.join()

        val refreshed = ChannelFeedPage(emptyList(), listOf("failed-channel"))
        cache.load(instance, profile.copy(revision = 2), false) { Result.success(refreshed) }
        response.complete(Result.success(page))

        assertEquals(
            refreshed,
            cache.load(instance, profile.copy(revision = 2), false) { error("Should use the current window") },
        )
    }

    private companion object {
        const val instance = "https://invidious.example"
        val profile = CompanionProfile("device", "account", 1, ExperienceMode.FOCUSED, emptyList())
        val page = ChannelFeedPage(emptyList(), emptyList())
    }
}

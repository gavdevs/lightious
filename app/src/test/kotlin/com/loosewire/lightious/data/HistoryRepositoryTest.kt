package com.loosewire.lightious.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryRepositoryTest {
    private companion object {
        const val TEST_DEVICE_BEARER = "lpt_device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }

    @Test
    fun `search history deduplicates normalized queries and orders newest first`() = runTest {
        val dao = FakeHistoryDao()
        var clock = 100L
        val repository = HistoryRepository(dao, dao, now = { clock })

        repository.recordSearch("  Kotlin   Coroutines  ")
        clock = 200L
        repository.recordSearch("another query")
        clock = 300L
        repository.recordSearch("KOTLIN COROUTINES")

        val history = repository.searchHistory()

        assertEquals(listOf("KOTLIN COROUTINES", "another query"), history.map { it.query })
        assertEquals(2, history.first().useCount)
        assertEquals(300L, history.first().lastSearchedAt)
    }

    @Test
    fun `search history excludes direct YouTube video URLs`() = runTest {
        val dao = FakeHistoryDao()
        val repository = HistoryRepository(dao, dao, now = { 100L })

        repository.recordSearch("https://youtu.be/dQw4w9WgXcQ?t=12")
        repository.recordSearch("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        repository.recordSearch("https://www.youtube.com/shorts/dQw4w9WgXcQ")

        assertTrue(repository.searchHistory().isEmpty())
    }

    @Test
    fun `watch history upserts metadata and moves a rewatch to newest`() = runTest {
        val dao = FakeHistoryDao()
        var clock = 100L
        val repository = HistoryRepository(dao, dao, now = { clock })

        repository.recordWatch(video(videoId = "first-video", title = "Original title"))
        clock = 200L
        repository.recordWatch(video(videoId = "second-video", title = "Second video"))
        clock = 300L
        repository.recordWatch(video(videoId = "first-video", title = "Updated title"))

        val history = repository.watchHistory()

        assertEquals(listOf("first-video", "second-video"), history.map { it.video.videoId })
        assertEquals("Updated title", history.first().video.title)
        assertEquals(300L, history.first().lastWatchedAt)
    }

    @Test
    fun `starting playback never marks a video watched or queues completion sync`() = runTest {
        val dao = FakeHistoryDao()
        val repository = HistoryRepository(dao, dao, now = { 100L })
        val account = AccountSession("https://invidious.example", "token", "account")

        HistorySyncer(repository).recordPlayback(
            video = video("started-video", "Started"),
            settings = ClientSettings(
                instanceUrl = account.instanceUrl,
                saveWatchHistory = true,
                syncAccountHistory = true,
            ),
            account = account,
        )

        assertEquals(listOf("started-video"), repository.watchHistory().map { it.video.videoId })
        assertFalse(repository.isWatched("started-video"))
        assertTrue(repository.watchedVideoIds().isEmpty())
        assertTrue(repository.pendingServerWatches(account.accountKey).isEmpty())
    }

    @Test
    fun `explicit watched state survives repository recreation and recent history clearing`() = runTest {
        val dao = FakeHistoryDao()
        val repository = HistoryRepository(dao, dao, now = { 100L })
        val completedVideo = video("completed-video", "Finished")
        repository.recordWatch(completedVideo)
        repository.markWatched(completedVideo)
        repository.markWatched(completedVideo)

        repository.clearWatchHistory()
        val reopenedRepository = HistoryRepository(dao, dao)

        assertTrue(reopenedRepository.watchHistory().isEmpty())
        assertTrue(reopenedRepository.isWatched(completedVideo.videoId))
        assertEquals(setOf(completedVideo.videoId), reopenedRepository.watchedVideoIds())
    }

    @Test
    fun `recent history pruning does not forget explicit completion`() = runTest {
        val dao = FakeHistoryDao()
        var clock = 0L
        val repository = HistoryRepository(dao, dao, now = { clock++ })
        val completedVideo = video("completed-video", "Finished")
        repository.recordWatch(completedVideo)
        repository.markWatched(completedVideo)

        repeat(500) { index -> repository.recordWatch(video("other-$index", "Other")) }

        assertFalse(repository.watchHistory().any { it.video.videoId == completedVideo.videoId })
        assertTrue(repository.isWatched(completedVideo.videoId))
    }

    @Test
    fun `explicit watched state does not require enabled history recording or server sync`() = runTest {
        val dao = FakeHistoryDao()
        val repository = HistoryRepository(dao, dao, now = { 100L })
        val completedVideo = video("completed-video", "Finished")
        val settings = ClientSettings(saveWatchHistory = false, syncAccountHistory = false)
        val syncer = HistorySyncer(repository)
        syncer.recordPlayback(completedVideo, settings, account = null)

        repository.markWatched(completedVideo)
        syncer.syncWatched(completedVideo, settings, account = null)

        assertTrue(repository.watchHistory().isEmpty())
        assertTrue(repository.isWatched(completedVideo.videoId))
        assertTrue(repository.pendingServerWatches("account").isEmpty())
    }

    @Test
    fun `sync cannot enqueue an unmarked video even when account history is enabled`() = runTest {
        val dao = FakeHistoryDao()
        val repository = HistoryRepository(dao, dao, now = { 100L })
        val account = AccountSession("https://invidious.example", "token", "account")

        HistorySyncer(repository).syncWatched(
            video("unmarked-video", "Unfinished"),
            ClientSettings(instanceUrl = account.instanceUrl, syncAccountHistory = true),
            account,
        )

        assertTrue(repository.pendingServerWatches(account.accountKey).isEmpty())
    }

    @Test
    fun `completion can be durably queued without starting network sync`() = runTest {
        val dao = FakeHistoryDao()
        val repository = HistoryRepository(dao, dao, now = { 100L })
        val account = AccountSession("https://invidious.example", "token", "account")
        val settings = ClientSettings(instanceUrl = account.instanceUrl, syncAccountHistory = true)
        val completedVideo = video("dQw4w9WgXcQ", "Finished")
        val syncer = HistorySyncer(repository)

        assertFalse(syncer.queueWatched(completedVideo, settings, account))
        repository.markWatched(completedVideo)
        assertTrue(syncer.queueWatched(completedVideo, settings, account))
        assertTrue(syncer.queueWatched(completedVideo, settings, account))

        // Ending the screen before syncWatched runs must leave a deduplicated
        // durable record for a later playback to retry.
        val reopenedRepository = HistoryRepository(dao, dao)
        assertTrue(reopenedRepository.isWatched(completedVideo.videoId))
        assertEquals(listOf(completedVideo.videoId), reopenedRepository.pendingServerWatches(account.accountKey))
    }

    @Test
    fun `explicit Shorts are never recorded or queued for history sync`() = runTest {
        val dao = FakeHistoryDao()
        val repository = HistoryRepository(dao, dao, now = { 100L })
        val syncer = HistorySyncer(repository)
        val short = video("short-video", "A Short").copy(isShort = true)

        repository.recordWatch(short)
        repository.markWatched(short)
        syncer.recordPlayback(
            video = short,
            settings = ClientSettings(saveWatchHistory = true, syncAccountHistory = true),
            account = AccountSession("https://invidious.example", "token", "account"),
        )

        assertTrue(repository.watchHistory().isEmpty())
        assertFalse(repository.isWatched(short.videoId))
        assertTrue(repository.pendingServerWatches("account").isEmpty())
    }

    @Test
    fun `sync purges known Shorts from cached watch history and pending outbox`() = runTest {
        val dao = FakeHistoryDao()
        val repository = HistoryRepository(dao, dao, now = { 100L })
        val video = video("dQw4w9WgXcQ", "Previously cached")
        repository.recordWatch(video)
        repository.markWatched(video)
        repository.enqueueServerWatch("account", video.videoId)

        repository.reconcile(
            CompanionProfile(
                deviceId = "0123456789abcdef0123456789abcdef",
                account = "account",
                revision = 2,
                mode = ExperienceMode.LIBRARY,
                items = emptyList(),
                blockedVideoIds = setOf(video.videoId),
            ),
        )

        assertTrue(repository.watchHistory().isEmpty())
        assertFalse(repository.isWatched(video.videoId))
        assertTrue(repository.pendingServerWatches("account").isEmpty())
    }

    @Test
    fun `history syncer respects local and account history toggles`() = runTest {
        val dao = FakeHistoryDao()
        val repository = HistoryRepository(dao, dao, now = { 100L })
        val syncer = HistorySyncer(repository)
        val account = AccountSession(
            instanceUrl = "https://invidious.example",
            token = "token",
            accountKey = "account",
        )

        syncer.recordPlayback(
            video = video(videoId = "disabled-video", title = "Disabled"),
            settings = ClientSettings(
                saveWatchHistory = false,
                syncAccountHistory = false,
            ),
            account = account,
        )

        assertTrue(repository.watchHistory().isEmpty())
        assertTrue(repository.pendingServerWatches(account.accountKey).isEmpty())

        syncer.recordPlayback(
            video = video(videoId = "local-video", title = "Local only"),
            settings = ClientSettings(
                saveWatchHistory = true,
                syncAccountHistory = false,
            ),
            account = account,
        )

        assertEquals(listOf("local-video"), repository.watchHistory().map { it.video.videoId })
        assertTrue(repository.pendingServerWatches(account.accountKey).isEmpty())
    }

    @Test
    fun `outbox is account scoped deduplicated and completed in queue order`() = runTest {
        val dao = FakeHistoryDao()
        var clock = 100L
        val repository = HistoryRepository(dao, dao, now = { clock })

        repository.enqueueServerWatch("account-a", "video-one")
        clock = 200L
        repository.enqueueServerWatch("account-b", "other-account-video")
        clock = 300L
        repository.enqueueServerWatch("account-a", "video-two")
        clock = 400L
        repository.enqueueServerWatch("account-a", "video-one")

        assertEquals(
            listOf("video-two", "video-one"),
            repository.pendingServerWatches("account-a"),
        )
        assertEquals(
            listOf("other-account-video"),
            repository.pendingServerWatches("account-b"),
        )

        repository.completeServerWatch("account-a", "video-two")
        assertEquals(listOf("video-one"), repository.pendingServerWatches("account-a"))

        repository.clearPendingServerWatches("account-a")
        assertTrue(repository.pendingServerWatches("account-a").isEmpty())
        assertEquals(
            listOf("other-account-video"),
            repository.pendingServerWatches("account-b"),
        )
    }

    @Test
    fun `outbox returns at most one sync batch`() = runTest {
        val dao = FakeHistoryDao()
        var clock = 0L
        val repository = HistoryRepository(dao, dao, now = { clock++ })

        repeat(30) { index ->
            repository.enqueueServerWatch("account", "video-$index")
        }

        assertEquals(
            (0 until 25).map { "video-$it" },
            repository.pendingServerWatches("account"),
        )
    }

    @Test
    fun `paired sync target uses paired credential and account key`() {
        val account = AccountSession(
            instanceUrl = "https://invidious.example",
            token = "legacy-token",
            accountKey = "legacy-account",
        )
        val cachedCompanion = CompanionState(
            session = CompanionSession(
                instanceUrl = "https://invidious.example",
                deviceId = "0123456789abcdef0123456789abcdef",
                account = "@paired",
                deviceBearer = TEST_DEVICE_BEARER,
            ),
        )
        val activeCompanion = Result.success(cachedCompanion)

        val target = selectHistorySyncTarget(
            instanceUrl = "https://invidious.example",
            account = account,
            cachedCompanion = cachedCompanion,
            activeCompanion = activeCompanion,
        )

        assertEquals(
            pairedHistoryAccountKey("https://invidious.example", "@paired"),
            target?.accountKey,
        )
        assertEquals("https://invidious.example", target?.instanceUrl)
        assertEquals(
            TEST_DEVICE_BEARER,
            target?.deviceBearer,
        )
        assertEquals("", target?.token)
    }

    @Test
    fun `offline paired completion is queued for the paired account without a network check`() {
        val instanceUrl = "https://invidious.example"
        val account = AccountSession(instanceUrl, "legacy-token", "legacy-account")
        val cachedCompanion = CompanionState(
            session = CompanionSession(
                instanceUrl = instanceUrl,
                deviceId = "0123456789abcdef0123456789abcdef",
                account = "@paired",
                deviceBearer = TEST_DEVICE_BEARER,
            ),
        )

        assertEquals(
            pairedHistoryAccountKey(instanceUrl, "@paired"),
            selectPendingHistoryAccountKey(instanceUrl, account, cachedCompanion),
        )
        assertNull(selectPendingHistoryAccountKey("https://other.example", account, cachedCompanion))
    }

    @Test
    fun `pending completion never targets a legacy account from another instance`() {
        val account = AccountSession("https://invidious.example", "token", "account")

        assertEquals(
            account.accountKey,
            selectPendingHistoryAccountKey(account.instanceUrl, account, CompanionState()),
        )
        assertNull(selectPendingHistoryAccountKey("https://other.example", account, CompanionState()))
    }

    @Test
    fun `paired sync target does not downgrade to legacy token after paired failure`() {
        val account = AccountSession(
            instanceUrl = "https://invidious.example",
            token = "legacy-token",
            accountKey = "legacy-account",
        )
        val cachedCompanion = CompanionState(
            session = CompanionSession(
                instanceUrl = "https://invidious.example",
                deviceId = "0123456789abcdef0123456789abcdef",
                account = "@paired",
                deviceBearer = TEST_DEVICE_BEARER,
            ),
        )

        val target = selectHistorySyncTarget(
            instanceUrl = "https://invidious.example",
            account = account,
            cachedCompanion = cachedCompanion,
            activeCompanion = Result.failure(IllegalStateException("revoked")),
        )

        assertNull(target)
    }

    @Test
    fun `legacy sync target is used when the phone is not paired`() {
        val account = AccountSession(
            instanceUrl = "https://invidious.example",
            token = "legacy-token",
            accountKey = "legacy-account",
        )

        val target = selectHistorySyncTarget(
            instanceUrl = "https://invidious.example",
            account = account,
            cachedCompanion = CompanionState(),
        )

        assertEquals("legacy-account", target?.accountKey)
        assertEquals("https://invidious.example", target?.instanceUrl)
        assertEquals("legacy-token", target?.token)
        assertNull(target?.deviceBearer)
    }

    private fun video(videoId: String, title: String): VideoSummary = VideoSummary(
        videoId = videoId,
        title = title,
        author = "Author",
        lengthSeconds = 120L,
        viewCount = 1_000L,
        publishedText = "today",
        liveNow = false,
        thumbnailUrl = "https://example.test/$videoId.jpg",
    )

    private class FakeHistoryDao : HistoryDao, WatchedVideoDao {
        private val searches = mutableMapOf<String, SearchHistoryEntity>()
        private val watches = mutableMapOf<String, WatchHistoryEntity>()
        private val watched = mutableMapOf<String, WatchedVideoEntity>()
        private val outbox = mutableMapOf<Pair<String, String>, ServerHistoryOutboxEntity>()

        override suspend fun markWatched(entity: WatchedVideoEntity) {
            watched[entity.videoId] = entity
        }

        override suspend fun isWatched(videoId: String): Boolean = videoId in watched

        override suspend fun watchedVideoIds(): List<String> = watched.keys.toList()

        override suspend fun deleteWatched(videoId: String) {
            watched.remove(videoId)
        }

        override suspend fun listSearchHistory(limit: Int): List<SearchHistoryEntity> = searches.values
            .sortedByDescending(SearchHistoryEntity::lastSearchedAt)
            .take(limit)

        override suspend fun findSearch(normalizedQuery: String): SearchHistoryEntity? =
            searches[normalizedQuery]

        override suspend fun upsertSearch(entity: SearchHistoryEntity) {
            searches[entity.normalizedQuery] = entity
        }

        override suspend fun pruneSearchHistory(limit: Int) {
            val retained = listSearchHistory(limit).mapTo(mutableSetOf(), SearchHistoryEntity::normalizedQuery)
            searches.keys.retainAll(retained)
        }

        override suspend fun clearSearchHistory() {
            searches.clear()
        }

        override suspend fun listWatchHistory(limit: Int): List<WatchHistoryEntity> = watches.values
            .sortedByDescending(WatchHistoryEntity::lastWatchedAt)
            .take(limit)

        override suspend fun upsertWatch(entity: WatchHistoryEntity) {
            watches[entity.videoId] = entity
        }

        override suspend fun pruneWatchHistory(limit: Int) {
            val retained = listWatchHistory(limit).mapTo(mutableSetOf(), WatchHistoryEntity::videoId)
            watches.keys.retainAll(retained)
        }

        override suspend fun clearWatchHistory() {
            watches.clear()
        }

        override suspend fun deleteWatch(videoId: String) {
            watches.remove(videoId)
        }

        override suspend fun enqueueServerWatch(entity: ServerHistoryOutboxEntity) {
            outbox[entity.accountKey to entity.videoId] = entity
        }

        override suspend fun listPendingServerWatches(
            accountKey: String,
            limit: Int,
        ): List<ServerHistoryOutboxEntity> = outbox.values
            .asSequence()
            .filter { it.accountKey == accountKey }
            .sortedBy(ServerHistoryOutboxEntity::queuedAt)
            .take(limit)
            .toList()

        override suspend fun deletePendingServerWatch(accountKey: String, videoId: String) {
            outbox.remove(accountKey to videoId)
        }

        override suspend fun clearPendingServerWatches(accountKey: String) {
            outbox.entries.removeAll { it.value.accountKey == accountKey }
        }

        override suspend fun deletePendingServerWatchForVideo(videoId: String) {
            outbox.entries.removeAll { it.value.videoId == videoId }
        }
    }
}

package com.loosewire.lightious.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRepositoryTest {
    @Test
    fun `manifest becomes playable only after atomic promotion`() = runTest {
        val directory = Files.createTempDirectory("lightious-downloads-test").toFile()
        val dao = FakeDownloadsDao()
        val repository = DownloadRepository(dao, directory, now = { 42L })
        val plan = audioPlan()

        repository.queue(OWNER, summary(), DownloadKind.AUDIO)
        val target = repository.prepareTarget(OWNER, VIDEO_ID, plan)
        repository.markRunning(OWNER, summary(), plan, target)
        target.partialFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        repository.markProgress(OWNER, VIDEO_ID, 4L, 4L)
        promoteDownload(target)
        repository.markComplete(OWNER, VIDEO_ID, target)

        val stored = repository.get(OWNER, VIDEO_ID)
        assertEquals(DownloadState.COMPLETE, stored?.state)
        assertEquals(4L, stored?.downloadedBytes)
        assertEquals(target.finalFile, stored?.let(repository::localFile))
        assertFalse(target.partialFile.exists())
        assertTrue(target.finalFile.isFile)
    }

    @Test
    fun `unsafe persisted paths are ignored and never deleted`() = runTest {
        val directory = Files.createTempDirectory("lightious-downloads-traversal").toFile()
        val outside = File(directory, "outside.mp4").apply { writeText("keep") }
        val dao = FakeDownloadsDao()
        dao.upsert(entity(fileName = "../outside.mp4", state = DownloadState.COMPLETE))
        val repository = DownloadRepository(dao, File(directory, "files"))

        val stored = repository.get(OWNER, VIDEO_ID)
        assertNull(stored?.let(repository::localFile))
        repository.delete(OWNER, VIDEO_ID)

        assertTrue(outside.isFile)
        assertEquals("keep", outside.readText())
    }

    @Test
    fun `cancel removes partials and keeps a retryable manifest`() = runTest {
        val directory = Files.createTempDirectory("lightious-downloads-cancel").toFile()
        val repository = DownloadRepository(FakeDownloadsDao(), directory)
        val plan = audioPlan()
        repository.queue(OWNER, summary(), DownloadKind.AUDIO)
        val target = repository.prepareTarget(OWNER, VIDEO_ID, plan)
        target.partialFile.writeText("partial")
        repository.markRunning(OWNER, summary(), plan, target)

        repository.cancel(OWNER, VIDEO_ID)

        assertFalse(target.partialFile.exists())
        assertEquals(DownloadState.CANCELLED, repository.get(OWNER, VIDEO_ID)?.state)
    }

    @Test
    fun `transient failure preserves a compatible partial for resume`() = runTest {
        val directory = Files.createTempDirectory("lightious-downloads-resume").toFile()
        val repository = DownloadRepository(FakeDownloadsDao(), directory)
        val plan = audioPlan()
        repository.queue(OWNER, summary(), DownloadKind.AUDIO)
        val target = repository.prepareTarget(OWNER, VIDEO_ID, plan)
        repository.markRunning(OWNER, summary(), plan, target)
        target.partialFile.writeText("partial")

        repository.markRetrying(OWNER, VIDEO_ID, "temporary", DownloadKind.AUDIO)

        assertTrue(target.partialFile.isFile)
        assertEquals(target.partialFile.length(), repository.get(OWNER, VIDEO_ID)?.downloadedBytes)
        assertEquals(DownloadState.QUEUED, repository.get(OWNER, VIDEO_ID)?.state)
    }

    @Test
    fun `startup recovery requeues stale interrupted work and preserves its partial`() = runTest {
        val directory = Files.createTempDirectory("lightious-downloads-recovery").toFile()
        val dao = FakeDownloadsDao()
        val repository = DownloadRepository(dao, directory, now = { 600_001L })
        val plan = audioPlan()
        repository.queue(OWNER, summary(), DownloadKind.AUDIO)
        val target = repository.prepareTarget(OWNER, VIDEO_ID, plan)
        repository.markRunning(OWNER, summary(), plan, target)
        target.partialFile.writeText("partial")
        dao.upsert(
            checkNotNull(dao.find(OWNER, VIDEO_ID)).copy(updatedAt = 1L),
        )

        val recovered = repository.recoverInterruptedDownloads(OWNER)

        assertEquals(listOf(VIDEO_ID), recovered.map(DownloadedMedia::videoId))
        assertEquals(DownloadState.QUEUED, repository.get(OWNER, VIDEO_ID)?.state)
        assertEquals(target.partialFile.length(), repository.get(OWNER, VIDEO_ID)?.downloadedBytes)
        assertTrue(target.partialFile.isFile)
    }

    @Test
    fun `queue replaces completed audio when current policy asks for video`() = runTest {
        val directory = Files.createTempDirectory("lightious-downloads-upgrade").toFile()
        val repository = DownloadRepository(FakeDownloadsDao(), directory)
        repository.queue(OWNER, summary(), DownloadKind.AUDIO)
        val audioTarget = repository.prepareTarget(OWNER, VIDEO_ID, audioPlan())
        repository.markRunning(OWNER, summary(), audioPlan(), audioTarget)
        audioTarget.partialFile.writeText("audio")
        promoteDownload(audioTarget)
        repository.markComplete(OWNER, VIDEO_ID, audioTarget)

        val queued = repository.queue(OWNER, summary(), DownloadKind.VIDEO)

        assertEquals(DownloadKind.VIDEO, queued.kind)
        assertEquals(DownloadState.QUEUED, queued.state)
        assertFalse(audioTarget.finalFile.exists())
    }

    @Test
    fun `queue rejects an explicitly flagged Short before writing a manifest`() = runTest {
        val directory = Files.createTempDirectory("lightious-downloads-short-reject").toFile()
        val repository = DownloadRepository(FakeDownloadsDao(), directory)

        val error = assertFailsWith<IllegalArgumentException> {
            repository.queue(OWNER, summary(isShort = true), DownloadKind.AUDIO)
        }

        assertEquals(SHORTS_BLOCKED_MESSAGE, error.message)
        assertTrue(repository.listAll().isEmpty())
    }

    @Test
    fun `changed stream identity discards an incompatible retry partial`() = runTest {
        val directory = Files.createTempDirectory("lightious-downloads-stream-change").toFile()
        val repository = DownloadRepository(FakeDownloadsDao(), directory)
        repository.queue(OWNER, summary(), DownloadKind.AUDIO)
        val firstPlan = audioPlan(itag = 140)
        val target = repository.prepareTarget(OWNER, VIDEO_ID, firstPlan)
        repository.markRunning(OWNER, summary(), firstPlan, target)
        target.partialFile.writeText("old representation")

        val changedPlan = audioPlan(itag = 251)
        repository.markRunning(OWNER, summary(), changedPlan, target)

        assertFalse(target.partialFile.exists())
        assertEquals(0L, repository.get(OWNER, VIDEO_ID)?.downloadedBytes)
        assertEquals(
            downloadStreamFingerprint(changedPlan.stream),
            repository.get(OWNER, VIDEO_ID)?.streamFingerprint,
        )
    }

    @Test
    fun `complete resumable partial is promoted without requesting it again`() {
        val directory = Files.createTempDirectory("lightious-downloads-complete-part").toFile()
        val repository = DownloadRepository(FakeDownloadsDao(), directory)
        val plan = audioPlan().copy(stream = audioPlan().stream.copy(contentLength = 4L))
        val target = repository.prepareTarget(OWNER, VIDEO_ID, plan)
        target.partialFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        val transfer = completedPartialTransfer(plan.stream, target)
        promoteDownload(target)

        assertEquals(DownloadTransfer(4L, 4L), transfer)
        assertFalse(target.partialFile.exists())
        assertTrue(target.finalFile.isFile)
        assertEquals(DownloadTransfer(4L, 4L), completedFinalTransfer(plan.stream, target))
    }

    @Test
    fun `successful sync removes revoked and downgraded video files`() = runTest {
        val directory = Files.createTempDirectory("lightious-downloads-reconcile").toFile()
        val repository = DownloadRepository(FakeDownloadsDao(), directory)
        repository.queue(OWNER, summary(), DownloadKind.VIDEO)
        val plan = videoPlan()
        val target = repository.prepareTarget(OWNER, VIDEO_ID, plan)
        repository.markRunning(OWNER, summary(), plan, target)
        target.partialFile.writeText("video")
        promoteDownload(target)
        repository.markComplete(OWNER, VIDEO_ID, target)

        repository.reconcile(
            CompanionProfile(
                deviceId = OWNER,
                account = "account",
                revision = 2,
                mode = ExperienceMode.FOCUSED,
                items = listOf(
                    CuratedVideo(
                        id = "item",
                        videoId = VIDEO_ID,
                        title = "Title",
                        author = "Author",
                        lengthSeconds = 60,
                        playbackPolicy = PlaybackPolicy.LISTEN_ONLY,
                    ),
                ),
            ),
        )

        assertNull(repository.get(OWNER, VIDEO_ID))
        assertFalse(target.finalFile.exists())
    }

    @Test
    fun `Library mode removes downloads without a curated grant`() = runTest {
        val directory = Files.createTempDirectory("lightious-library-reconcile").toFile()
        val repository = DownloadRepository(FakeDownloadsDao(), directory)
        repository.queue(OWNER, summary(), DownloadKind.VIDEO)

        repository.reconcile(
            CompanionProfile(
                deviceId = OWNER,
                account = "account",
                revision = 4,
                mode = ExperienceMode.LIBRARY,
                items = emptyList(),
            ),
        )

        assertNull(repository.get(OWNER, VIDEO_ID))
    }

    @Test
    fun `sync removes a known Short download in Library mode`() = runTest {
        val directory = Files.createTempDirectory("lightious-downloads-short-reconcile").toFile()
        val repository = DownloadRepository(FakeDownloadsDao(), directory)
        repository.queue(OWNER, summary(), DownloadKind.VIDEO)
        val target = repository.prepareTarget(OWNER, VIDEO_ID, videoPlan())
        repository.markRunning(OWNER, summary(), videoPlan(), target)
        target.partialFile.writeText("video")
        promoteDownload(target)
        repository.markComplete(OWNER, VIDEO_ID, target)

        repository.reconcile(
            CompanionProfile(
                deviceId = OWNER,
                account = "account",
                revision = 3,
                mode = ExperienceMode.LIBRARY,
                items = emptyList(),
                blockedVideoIds = setOf(VIDEO_ID),
            ),
        )

        assertNull(repository.get(OWNER, VIDEO_ID))
        assertFalse(target.finalFile.exists())
    }

    private fun entity(
        fileName: String?,
        state: DownloadState,
    ) = DownloadEntity(
        ownerDeviceId = OWNER,
        videoId = VIDEO_ID,
        title = "Title",
        author = "Author",
        authorId = null,
        lengthSeconds = 60,
        kind = DownloadKind.VIDEO.wireValue,
        state = state.wireValue,
        fileName = fileName,
        mimeType = "video/mp4",
        width = 640,
        height = 360,
        streamFingerprint = null,
        downloadedBytes = 4,
        totalBytes = 4,
        errorMessage = null,
        updatedAt = 1,
    )

    private fun summary(isShort: Boolean = false) = VideoSummary(
        VIDEO_ID,
        "Title",
        "Author",
        60,
        0,
        "",
        false,
        isShort = isShort,
    )

    private fun audioPlan(itag: Int = 140) = DownloadPlan(
        DownloadKind.AUDIO,
        MediaStream(
            url = "https://media.example/audio",
            kind = MediaStreamKind.ADAPTIVE_AUDIO,
            mimeType = "audio/mp4",
            container = "m4a",
            itag = itag,
            hasAudio = true,
        ),
    )

    private fun videoPlan() = DownloadPlan(
        DownloadKind.VIDEO,
        MediaStream(
            url = "https://media.example/video",
            kind = MediaStreamKind.PROGRESSIVE,
            mimeType = "video/mp4",
            container = "mp4",
            width = 640,
            height = 360,
            hasAudio = true,
            hasVideo = true,
        ),
    )

    private class FakeDownloadsDao : DownloadsDao {
        private val values = linkedMapOf<Pair<String, String>, DownloadEntity>()
        private val updates = MutableStateFlow<List<DownloadEntity>>(emptyList())

        override fun observeAll(): Flow<List<DownloadEntity>> = updates
        override suspend fun listAll(): List<DownloadEntity> = updates.value
        override suspend fun find(ownerDeviceId: String, videoId: String): DownloadEntity? =
            values[ownerDeviceId to videoId]

        override suspend fun upsert(entity: DownloadEntity) {
            values[entity.ownerDeviceId to entity.videoId] = entity
            emit()
        }

        override suspend fun delete(ownerDeviceId: String, videoId: String) {
            values.remove(ownerDeviceId to videoId)
            emit()
        }

        override suspend fun deleteForOwner(ownerDeviceId: String) {
            values.keys.removeAll { (owner, _) -> owner == ownerDeviceId }
            emit()
        }

        private fun emit() {
            updates.value = values.values.sortedByDescending(DownloadEntity::updatedAt)
        }
    }

    private companion object {
        const val OWNER = "0123456789abcdef0123456789abcdef"
        const val VIDEO_ID = "dQw4w9WgXcQ"
    }
}

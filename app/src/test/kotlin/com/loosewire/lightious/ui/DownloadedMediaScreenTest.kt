package com.loosewire.lightious.ui

import com.loosewire.lightious.data.DownloadKind
import com.loosewire.lightious.data.DownloadState
import com.loosewire.lightious.data.DownloadedMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadedMediaScreenTest {
    @Test
    fun `video download uses listening controls after an audio handoff`() {
        val state = DownloadedMediaUiState(
            loaded = true,
            download = download(DownloadState.COMPLETE, DownloadKind.VIDEO),
        )

        assertFalse(downloadedMediaPresentsAudio(state))
        assertTrue(downloadedMediaPresentsAudio(state.copy(audioStarted = true)))
        assertEquals(DownloadPrimaryAction.WATCH_VIDEO, downloadedMediaPrimaryAction(state.download))
    }

    @Test
    fun `audio downloads retain listening controls before playback starts`() {
        val state = DownloadedMediaUiState(
            loaded = true,
            download = download(DownloadState.COMPLETE, DownloadKind.AUDIO),
        )

        assertTrue(downloadedMediaPresentsAudio(state))
        assertEquals(DownloadPrimaryAction.PLAY_AUDIO, downloadedMediaPrimaryAction(state.download))
    }

    @Test
    fun `stale audio state cannot make an unavailable download playable`() {
        for (state in listOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.FAILED)) {
            assertFalse(
                downloadedMediaPresentsAudio(
                    DownloadedMediaUiState(download = download(state), audioStarted = true),
                ),
            )
        }
        assertFalse(downloadedMediaPresentsAudio(DownloadedMediaUiState(audioStarted = true)))
        assertFalse(
            downloadedMediaPresentsAudio(
                DownloadedMediaUiState(
                    download = download(DownloadState.COMPLETE, isShort = true),
                    audioStarted = true,
                ),
            ),
        )
    }

    @Test
    fun `download action follows persisted state and media kind`() {
        assertEquals(DownloadPrimaryAction.NONE, downloadedMediaPrimaryAction(null))
        assertEquals(DownloadPrimaryAction.CANCEL, downloadedMediaPrimaryAction(download(DownloadState.QUEUED)))
        assertEquals(DownloadPrimaryAction.CANCEL, downloadedMediaPrimaryAction(download(DownloadState.DOWNLOADING)))
        assertEquals(DownloadPrimaryAction.RETRY, downloadedMediaPrimaryAction(download(DownloadState.FAILED)))
        assertEquals(
            DownloadPrimaryAction.PLAY_AUDIO,
            downloadedMediaPrimaryAction(download(DownloadState.COMPLETE, DownloadKind.AUDIO)),
        )
        assertEquals(
            DownloadPrimaryAction.WATCH_VIDEO,
            downloadedMediaPrimaryAction(download(DownloadState.COMPLETE, DownloadKind.VIDEO)),
        )
        assertEquals(
            DownloadPrimaryAction.NONE,
            downloadedMediaPrimaryAction(download(DownloadState.COMPLETE, isShort = true)),
        )
    }

    @Test
    fun `download labels expose deterministic progress`() {
        assertEquals(
            "VIDEO  ·  25%",
            downloadStatusLabel(
                download(
                    state = DownloadState.DOWNLOADING,
                    downloadedBytes = 250,
                    totalBytes = 1_000,
                ),
            ),
        )
        assertEquals("2 MB", formatDownloadBytes(2_500_000))
    }

    @Test
    fun `completed audio can be replaced after policy upgrades to video`() {
        val completedAudio = download(DownloadState.COMPLETE, DownloadKind.AUDIO)

        assertEquals(
            VideoDownloadAction.AVAILABLE,
            videoDownloadAction(completedAudio, DownloadKind.AUDIO),
        )
        assertEquals(
            VideoDownloadAction.REPLACE,
            videoDownloadAction(completedAudio, DownloadKind.VIDEO),
        )
    }

    private fun download(
        state: DownloadState,
        kind: DownloadKind = DownloadKind.VIDEO,
        downloadedBytes: Long = 10,
        totalBytes: Long? = 10,
        isShort: Boolean = false,
    ) = DownloadedMedia(
        ownerDeviceId = "0123456789abcdef0123456789abcdef",
        videoId = "dQw4w9WgXcQ",
        title = "Title",
        author = "Author",
        authorId = null,
        lengthSeconds = 60,
        kind = kind,
        state = state,
        fileName = if (state == DownloadState.COMPLETE) "file.mp4" else null,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        updatedAt = 1,
        isShort = isShort,
    )
}

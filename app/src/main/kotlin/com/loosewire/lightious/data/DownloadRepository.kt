package com.loosewire.lightious.data

import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal data class DownloadTarget(
    val finalFile: File,
    val partialFile: File,
)

class DownloadRepository internal constructor(
    private val dao: DownloadsDao,
    filesDir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val root = File(filesDir, DOWNLOAD_DIRECTORY).canonicalFile.also { directory ->
        check(directory.mkdirs() || directory.isDirectory) { "Could not create the download directory." }
    }

    fun observeAll(): Flow<List<DownloadedMedia>> = dao.observeAll().map { entities ->
        entities.mapNotNull(DownloadEntity::toModelOrNull)
    }

    suspend fun listAll(): List<DownloadedMedia> = dao.listAll().mapNotNull(DownloadEntity::toModelOrNull)

    suspend fun get(ownerDeviceId: String, videoId: String): DownloadedMedia? {
        validateIdentity(ownerDeviceId, videoId)
        return dao.find(ownerDeviceId, videoId)?.toModelOrNull()
    }

    suspend fun queue(
        ownerDeviceId: String,
        summary: VideoSummary,
        kind: DownloadKind,
    ): DownloadedMedia {
        require(!summary.isShort) { SHORTS_BLOCKED_MESSAGE }
        validateIdentity(ownerDeviceId, summary.videoId)
        val existing = dao.find(ownerDeviceId, summary.videoId)
        val existingDownload = existing?.toModelOrNull()
        if (
            existing?.state == DownloadState.COMPLETE.wireValue &&
            existingDownload != null &&
            existingDownload.kind == kind &&
            localFile(existingDownload) != null
        ) {
            return existingDownload
        }
        if (existing != null && existing.kind != kind.wireValue) {
            deleteFiles(existing)
            cleanupPartials(ownerDeviceId, summary.videoId)
        }
        val queued = DownloadEntity(
            ownerDeviceId = ownerDeviceId,
            videoId = summary.videoId,
            title = summary.title.ifBlank { "Untitled video" },
            author = summary.author.ifBlank { "Unknown channel" },
            authorId = summary.authorId?.takeIf(::validYouTubeChannelId),
            lengthSeconds = summary.lengthSeconds.coerceAtLeast(0L),
            kind = kind.wireValue,
            state = DownloadState.QUEUED.wireValue,
            fileName = existing?.fileName.takeIf { existing?.kind == kind.wireValue },
            mimeType = existing?.mimeType.takeIf { existing?.kind == kind.wireValue },
            width = existing?.width.takeIf { existing?.kind == kind.wireValue },
            height = existing?.height.takeIf { existing?.kind == kind.wireValue },
            streamFingerprint = existing?.streamFingerprint.takeIf { existing?.kind == kind.wireValue },
            downloadedBytes = partialBytes(ownerDeviceId, summary.videoId),
            totalBytes = existing?.totalBytes.takeIf { existing?.kind == kind.wireValue },
            errorMessage = null,
            updatedAt = now(),
        )
        dao.upsert(queued)
        return checkNotNull(queued.toModelOrNull())
    }

    internal suspend fun markRunning(
        ownerDeviceId: String,
        summary: VideoSummary,
        plan: DownloadPlan,
        target: DownloadTarget,
    ): Boolean {
        require(!summary.isShort) { SHORTS_BLOCKED_MESSAGE }
        validateIdentity(ownerDeviceId, summary.videoId)
        val current = dao.find(ownerDeviceId, summary.videoId) ?: return false
        if (
            current.state == DownloadState.CANCELLED.wireValue ||
            current.kind != plan.kind.wireValue
        ) {
            return false
        }
        val streamFingerprint = downloadStreamFingerprint(plan.stream)
        if (current.streamFingerprint != streamFingerprint) {
            target.partialFile.delete()
            target.finalFile.delete()
        }
        dao.upsert(
            current.copy(
                title = summary.title.ifBlank { current.title },
                author = summary.author.ifBlank { current.author },
                authorId = summary.authorId?.takeIf(::validYouTubeChannelId),
                lengthSeconds = summary.lengthSeconds.coerceAtLeast(0L),
                kind = plan.kind.wireValue,
                state = DownloadState.DOWNLOADING.wireValue,
                fileName = target.finalFile.name,
                mimeType = plan.stream.mimeType,
                width = plan.stream.width,
                height = plan.stream.height,
                streamFingerprint = streamFingerprint,
                downloadedBytes = target.partialFile.length().coerceAtLeast(0L),
                totalBytes = plan.stream.contentLength,
                errorMessage = null,
                updatedAt = now(),
            ),
        )
        return true
    }

    suspend fun markProgress(
        ownerDeviceId: String,
        videoId: String,
        downloadedBytes: Long,
        totalBytes: Long?,
        expectedKind: DownloadKind? = null,
    ) {
        validateIdentity(ownerDeviceId, videoId)
        val current = dao.find(ownerDeviceId, videoId) ?: return
        if (
            current.state != DownloadState.DOWNLOADING.wireValue ||
            expectedKind != null && current.kind != expectedKind.wireValue
        ) {
            return
        }
        dao.upsert(
            current.copy(
                downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                totalBytes = totalBytes?.coerceAtLeast(0L),
                updatedAt = now(),
            ),
        )
    }

    internal suspend fun markComplete(
        ownerDeviceId: String,
        videoId: String,
        target: DownloadTarget,
        expectedKind: DownloadKind? = null,
    ) {
        validateIdentity(ownerDeviceId, videoId)
        val current = dao.find(ownerDeviceId, videoId) ?: run {
            target.finalFile.delete()
            target.partialFile.delete()
            return
        }
        if (
            current.state == DownloadState.CANCELLED.wireValue ||
            expectedKind != null && current.kind != expectedKind.wireValue
        ) {
            target.finalFile.delete()
            target.partialFile.delete()
            return
        }
        check(target.finalFile.isFile) { "Downloaded media file is missing." }
        dao.upsert(
            current.copy(
                state = DownloadState.COMPLETE.wireValue,
                fileName = target.finalFile.name,
                downloadedBytes = target.finalFile.length(),
                totalBytes = target.finalFile.length(),
                errorMessage = null,
                updatedAt = now(),
            ),
        )
    }

    suspend fun markFailed(
        ownerDeviceId: String,
        videoId: String,
        message: String,
        expectedKind: DownloadKind? = null,
    ) {
        validateIdentity(ownerDeviceId, videoId)
        val current = dao.find(ownerDeviceId, videoId) ?: return
        if (
            current.state == DownloadState.CANCELLED.wireValue ||
            expectedKind != null && current.kind != expectedKind.wireValue
        ) {
            return
        }
        dao.upsert(
            current.copy(
                state = DownloadState.FAILED.wireValue,
                downloadedBytes = partialBytes(ownerDeviceId, videoId),
                errorMessage = message.trim().ifBlank { "Download failed." }.take(300),
                updatedAt = now(),
            ),
        )
    }

    suspend fun markRetrying(
        ownerDeviceId: String,
        videoId: String,
        message: String,
        expectedKind: DownloadKind,
    ) {
        validateIdentity(ownerDeviceId, videoId)
        val current = dao.find(ownerDeviceId, videoId) ?: return
        if (
            current.state == DownloadState.CANCELLED.wireValue ||
            current.kind != expectedKind.wireValue
        ) {
            return
        }
        dao.upsert(
            current.copy(
                state = DownloadState.QUEUED.wireValue,
                downloadedBytes = partialBytes(ownerDeviceId, videoId),
                errorMessage = message.trim().ifBlank { "Download interrupted; retrying." }.take(300),
                updatedAt = now(),
            ),
        )
    }

    suspend fun recoverInterruptedDownloads(ownerDeviceId: String): List<DownloadedMedia> {
        require(validOwnerDeviceId(ownerDeviceId)) { "Invalid download owner." }
        val staleBefore = now() - STALE_DOWNLOAD_AFTER_MILLIS
        val recovered = mutableListOf<DownloadedMedia>()
        dao.listAll()
            .filter { entity ->
                entity.ownerDeviceId == ownerDeviceId &&
                    entity.state == DownloadState.DOWNLOADING.wireValue &&
                    entity.updatedAt <= staleBefore
            }
            .forEach { entity ->
                val updated = entity.copy(
                    state = DownloadState.QUEUED.wireValue,
                    downloadedBytes = partialBytes(entity.ownerDeviceId, entity.videoId),
                    errorMessage = "Resuming an interrupted download.",
                    updatedAt = now(),
                )
                dao.upsert(updated)
                updated.toModelOrNull()?.let(recovered::add)
            }
        return recovered
    }

    suspend fun cancel(ownerDeviceId: String, videoId: String) {
        validateIdentity(ownerDeviceId, videoId)
        val current = dao.find(ownerDeviceId, videoId) ?: return
        deleteFiles(current)
        cleanupPartials(ownerDeviceId, videoId)
        dao.upsert(
            current.copy(
                state = DownloadState.CANCELLED.wireValue,
                fileName = null,
                downloadedBytes = 0L,
                totalBytes = null,
                errorMessage = "Download cancelled.",
                updatedAt = now(),
            ),
        )
    }

    suspend fun delete(ownerDeviceId: String, videoId: String) {
        validateIdentity(ownerDeviceId, videoId)
        dao.find(ownerDeviceId, videoId)?.let(::deleteFiles)
        cleanupPartials(ownerDeviceId, videoId)
        dao.delete(ownerDeviceId, videoId)
    }

    suspend fun deleteForOwner(ownerDeviceId: String) {
        require(validOwnerDeviceId(ownerDeviceId)) { "Invalid download owner." }
        dao.listAll()
            .filter { entity -> entity.ownerDeviceId == ownerDeviceId }
            .forEach { entity ->
                deleteFiles(entity)
                cleanupPartials(ownerDeviceId, entity.videoId)
            }
        dao.deleteForOwner(ownerDeviceId)
    }

    suspend fun reconcile(profile: CompanionProfile) {
        val blockedShorts = profile.knownShortVideoIds()
        dao.listAll()
            .mapNotNull(DownloadEntity::toModelOrNull)
            .filter { download -> download.ownerDeviceId == profile.deviceId }
            .forEach { download ->
                val policy = if (download.videoId in blockedShorts) {
                    null
                } else {
                    profile.playbackPolicyFor(download.videoId, download.authorId)
                }
                if (
                    policy == null ||
                    download.kind == DownloadKind.VIDEO && policy != PlaybackPolicy.WATCH_AND_LISTEN
                ) {
                    delete(download.ownerDeviceId, download.videoId)
                }
            }
    }

    internal fun prepareTarget(
        ownerDeviceId: String,
        videoId: String,
        plan: DownloadPlan,
    ): DownloadTarget {
        validateIdentity(ownerDeviceId, videoId)
        val extension = mediaExtension(plan.kind, plan.stream.mimeType, plan.stream.container)
        val finalFile = resolveStoredFile(
            "$ownerDeviceId-$videoId-${plan.kind.wireValue}.$extension",
        ) ?: error("Could not create a safe download path.")
        val partialFile = resolveStoredFile("${finalFile.name}.part")
            ?: error("Could not create a safe partial-download path.")
        root.listFiles().orEmpty()
            .filter { file ->
                file.name.startsWith("$ownerDeviceId-$videoId-") &&
                    file.name.endsWith(".part") &&
                    file != partialFile
            }
            .forEach(File::delete)
        return DownloadTarget(finalFile, partialFile)
    }

    fun localFile(download: DownloadedMedia): File? {
        if (!download.isPlayable) return null
        validateIdentity(download.ownerDeviceId, download.videoId)
        return download.fileName
            ?.let(::resolveStoredFile)
            ?.takeIf(File::isFile)
    }

    fun localVideoSource(download: DownloadedMedia): VideoPlaybackSource.Single? {
        if (download.kind != DownloadKind.VIDEO) return null
        val file = localFile(download) ?: return null
        return VideoPlaybackSource.Single(
            MediaStream(
                url = file.toURI().toString(),
                kind = MediaStreamKind.PROGRESSIVE,
                mimeType = download.mimeType,
                width = download.width,
                height = download.height,
                contentLength = file.length(),
                hasAudio = true,
                hasVideo = true,
            ),
        )
    }

    internal fun resolveStoredFile(fileName: String): File? {
        if (!SAFE_FILE_NAME.matches(fileName) || File(fileName).name != fileName) return null
        val resolved = File(root, fileName).canonicalFile
        return resolved.takeIf { file -> file.parentFile == root }
    }

    internal fun cleanupPartials(
        ownerDeviceId: String,
        videoId: String,
        kind: DownloadKind? = null,
    ) {
        validateIdentity(ownerDeviceId, videoId)
        val prefix = "$ownerDeviceId-$videoId-${kind?.wireValue.orEmpty()}"
        root.listFiles().orEmpty()
            .filter { file -> file.name.startsWith(prefix) && file.name.endsWith(".part") }
            .forEach(File::delete)
    }

    private fun partialBytes(ownerDeviceId: String, videoId: String): Long {
        validateIdentity(ownerDeviceId, videoId)
        val prefix = "$ownerDeviceId-$videoId-"
        return root.listFiles().orEmpty()
            .filter { file -> file.name.startsWith(prefix) && file.name.endsWith(".part") }
            .maxOfOrNull(File::length)
            ?: 0L
    }

    private fun deleteFiles(entity: DownloadEntity) {
        entity.fileName?.let(::resolveStoredFile)?.delete()
    }

    private fun validateIdentity(ownerDeviceId: String, videoId: String) {
        require(validOwnerDeviceId(ownerDeviceId)) { "Invalid download owner." }
        require(extractYouTubeVideoId(videoId) == videoId) { "Invalid download video ID." }
    }

    private companion object {
        const val DOWNLOAD_DIRECTORY = "lightious-downloads"
        const val STALE_DOWNLOAD_AFTER_MILLIS = 9L * 60L * 1_000L
        val SAFE_FILE_NAME = Regex("^[0-9a-f]{32}-[A-Za-z0-9_-]{11}-(audio|video)\\.(m4a|mp4|webm)(\\.part)?$")
    }
}

private fun DownloadEntity.toModelOrNull(): DownloadedMedia? {
    if (!validOwnerDeviceId(ownerDeviceId) || extractYouTubeVideoId(videoId) != videoId) return null
    val parsedKind = DownloadKind.fromWire(kind) ?: return null
    val parsedState = DownloadState.fromWire(state) ?: return null
    return DownloadedMedia(
        ownerDeviceId = ownerDeviceId,
        videoId = videoId,
        title = title,
        author = author,
        authorId = authorId?.takeIf(::validYouTubeChannelId),
        lengthSeconds = lengthSeconds.coerceAtLeast(0L),
        kind = parsedKind,
        state = parsedState,
        fileName = fileName,
        mimeType = mimeType,
        width = width?.takeIf { it > 0 },
        height = height?.takeIf { it > 0 },
        streamFingerprint = streamFingerprint,
        downloadedBytes = downloadedBytes.coerceAtLeast(0L),
        totalBytes = totalBytes?.coerceAtLeast(0L),
        errorMessage = errorMessage,
        updatedAt = updatedAt,
    )
}

private fun validOwnerDeviceId(value: String): Boolean = value.matches(Regex("^[0-9a-f]{32}$"))

private fun mediaExtension(kind: DownloadKind, mimeType: String?, container: String?): String {
    val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    val normalizedContainer = container?.trim()?.lowercase(Locale.ROOT)
    return when (kind) {
        DownloadKind.AUDIO -> when {
            normalizedMime == "audio/mp4" || normalizedContainer in setOf("m4a", "mp4") -> "m4a"
            normalizedMime == "audio/webm" || normalizedContainer == "webm" -> "webm"
            else -> throw IllegalStateException("The selected audio format cannot be stored offline.")
        }
        DownloadKind.VIDEO -> when {
            normalizedMime == "video/mp4" || normalizedContainer == "mp4" -> "mp4"
            normalizedMime == "video/webm" || normalizedContainer == "webm" -> "webm"
            else -> throw IllegalStateException("The selected video format cannot be stored offline.")
        }
    }
}

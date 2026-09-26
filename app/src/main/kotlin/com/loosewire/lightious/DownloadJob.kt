package com.loosewire.lightious

import com.loosewire.lightious.data.DownloadKind
import com.loosewire.lightious.data.DownloadState
import com.loosewire.lightious.data.DownloadTimeSlice
import com.loosewire.lightious.data.InvidiousApi
import com.loosewire.lightious.data.MediaDownloadClient
import com.loosewire.lightious.data.downloadJobTag
import com.loosewire.lightious.data.extractYouTubeVideoId
import com.loosewire.lightious.data.isRetryableDownloadFailure
import com.loosewire.lightious.data.playbackPolicyFor
import com.loosewire.lightious.data.selectDownloadPlan
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CancellationException

internal const val DOWNLOAD_JOB_KEY = "lightious-download"
internal const val DOWNLOAD_OWNER_INPUT = "ownerDeviceId"
internal const val DOWNLOAD_VIDEO_INPUT = "videoId"
internal const val DOWNLOAD_KIND_INPUT = "kind"

@LightJob("lightious-download")
val lightiousDownloadJob: LightJobHandler = { context, input ->
    val ownerDeviceId = input[DOWNLOAD_OWNER_INPUT].orEmpty()
    val videoId = input[DOWNLOAD_VIDEO_INPUT].orEmpty()
    val requestedKind = input[DOWNLOAD_KIND_INPUT]?.let(DownloadKind::fromWire)
    val validInput = requestedKind != null && runCatching {
        downloadJobTag(ownerDeviceId, videoId, requestedKind)
    }.isSuccess
    if (!validInput || extractYouTubeVideoId(videoId) != videoId) {
        LightJobResult.Error(mapOf("message" to "Invalid download request."))
    } else {
        val services = LightiousServices.from(context)
        try {
            val timeSlice = DownloadTimeSlice()
            val queued = services.downloads.get(ownerDeviceId, videoId)
                ?: error("This download was removed.")
            if (queued.kind != requestedKind || queued.state == DownloadState.CANCELLED) {
                LightJobResult.Success(mapOf("videoId" to videoId))
            } else {
                val settings = services.settings.load()
                val active = services.companion.loadActiveState(settings.instanceUrl).getOrThrow()
                val session = active.session ?: error("This phone is not paired with the companion.")
                require(session.deviceId == ownerDeviceId) { "This download belongs to another pairing." }
                val profile = active.profile ?: error("The current companion policy could not be loaded.")
                val details = InvidiousApi(
                    baseUrl = settings.instanceUrl,
                    proxyMedia = settings.proxyMedia,
                    deviceBearer = session.deviceBearer,
                    audioLanguage = settings.audioLanguage,
                ).use { api -> api.video(videoId).getOrThrow() }
                val policy = profile.playbackPolicyFor(details.summary.videoId, details.summary.authorId)
                    ?: error("This video is no longer in your library.")
                val plan = selectDownloadPlan(details, policy, settings.audioLanguage).getOrThrow()
                require(plan.kind == requestedKind) {
                    "Download permissions changed. Retry from the refreshed video page."
                }
                val currentRequest = services.downloads.get(ownerDeviceId, videoId)
                    ?: throw DownloadStoppedException()
                if (currentRequest.kind != requestedKind) throw DownloadStoppedException()
                val target = services.downloads.prepareTarget(ownerDeviceId, videoId, plan)
                if (!services.downloads.markRunning(ownerDeviceId, details.summary, plan, target)) {
                    throw DownloadStoppedException()
                }
                timeSlice.throwIfExpired()
                MediaDownloadClient().use { downloader ->
                    downloader.download(
                        stream = plan.stream,
                        target = target,
                        yieldIfNeeded = timeSlice::throwIfExpired,
                    ) { downloadedBytes, totalBytes ->
                        val current = services.downloads.get(ownerDeviceId, videoId)
                        if (
                            current == null ||
                            current.kind != plan.kind ||
                            current.state == DownloadState.CANCELLED
                        ) {
                            throw DownloadStoppedException()
                        }
                        services.downloads.markProgress(
                            ownerDeviceId,
                            videoId,
                            downloadedBytes,
                            totalBytes,
                            plan.kind,
                        )
                    }
                }
                val current = services.downloads.get(ownerDeviceId, videoId)
                if (
                    current == null ||
                    current.kind != requestedKind ||
                    current.state == DownloadState.CANCELLED
                ) {
                    target.finalFile.delete()
                    target.partialFile.delete()
                } else {
                    services.downloads.markComplete(ownerDeviceId, videoId, target, requestedKind)
                }
                LightJobResult.Success(mapOf("videoId" to videoId))
            }
        } catch (error: CancellationException) {
            // Keep a valid partial for Range resume. Explicit cancel/delete and
            // policy reconciliation clean their own files before stopping work.
            throw error
        } catch (_: DownloadStoppedException) {
            LightJobResult.Success(mapOf("videoId" to videoId))
        } catch (error: Exception) {
            val retryable = error.isRetryableDownloadFailure()
            if (retryable) {
                services.downloads.markRetrying(
                    ownerDeviceId,
                    videoId,
                    "Download interrupted. Lightious will retry automatically.",
                    requestedKind,
                )
                LightJobResult.Retry
            } else {
                services.downloads.markFailed(
                    ownerDeviceId,
                    videoId,
                    error.message ?: "Download failed.",
                    requestedKind,
                )
                LightJobResult.Error(
                    mapOf(
                        "videoId" to videoId,
                        "message" to (error.message ?: "Download failed.").take(300),
                    ),
                )
            }
        }
    }
}

private class DownloadStoppedException : Exception()

internal fun enqueueDownload(
    context: SealedLightContext,
    ownerDeviceId: String,
    videoId: String,
    kind: DownloadKind,
): Boolean {
    DownloadKind.entries
        .filterNot { existingKind -> existingKind == kind }
        .forEach { existingKind ->
            LightWork.cancel(context, downloadJobTag(ownerDeviceId, videoId, existingKind))
        }
    return LightWork.enqueue(
        lightContext = context,
        jobKey = DOWNLOAD_JOB_KEY,
        inputData = mapOf(
            DOWNLOAD_OWNER_INPUT to ownerDeviceId,
            DOWNLOAD_VIDEO_INPUT to videoId,
            DOWNLOAD_KIND_INPUT to kind.wireValue,
        ),
        tag = downloadJobTag(ownerDeviceId, videoId, kind),
    )
}

internal fun cancelDownload(
    context: SealedLightContext,
    ownerDeviceId: String,
    videoId: String,
) {
    DownloadKind.entries.forEach { kind ->
        LightWork.cancel(context, downloadJobTag(ownerDeviceId, videoId, kind))
    }
}

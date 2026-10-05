package com.loosewire.lightious.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.loosewire.lightious.data.FocusedChannelEntry
import com.loosewire.lightious.data.FocusedPlaylistEntry
import com.loosewire.lightious.data.DownloadKind
import com.loosewire.lightious.data.DownloadState
import com.loosewire.lightious.data.DownloadedMedia
import com.loosewire.lightious.data.PlaybackPolicy
import com.loosewire.lightious.data.VideoSummary
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun VideoRow(
    video: VideoSummary,
    onClick: () -> Unit,
) {
    if (video.isShort) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.6f.gridUnitsAsDp()),
    ) {
        LightText(
            text = video.title,
            variant = LightTextVariant.Copy,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = video.author,
            variant = LightTextVariant.Detail,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 0.1f.gridUnitsAsDp()),
        )
        LightText(
            text = videoMetadataLine(video),
            variant = LightTextVariant.Fine,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 0.1f.gridUnitsAsDp()),
        )
    }
}

@Composable
internal fun ChannelRow(
    channel: FocusedChannelEntry,
    onClick: () -> Unit,
) {
    LibraryRow(
        title = channel.name,
        detail = channelMetadataLine(channel),
        onClick = onClick,
    )
}

@Composable
internal fun ChannelFeedGroupRow(
    channel: FocusedChannelEntry,
    unwatchedCount: Int,
    limit: Int,
    onClick: () -> Unit,
) {
    LibraryRow(
        title = channel.name,
        detail = channelFeedGroupMetadataLine(channel, unwatchedCount, limit),
        onClick = onClick,
    )
}

@Composable
internal fun PlaylistRow(
    playlist: FocusedPlaylistEntry,
    onClick: () -> Unit,
) {
    LibraryRow(
        title = playlist.name,
        detail = playlistMetadataLine(playlist),
        onClick = onClick,
    )
}

@Composable
internal fun DownloadRow(
    download: DownloadedMedia,
    onClick: () -> Unit,
) {
    if (download.isShort) return
    LibraryRow(
        title = download.title,
        detail = "${download.author}  ·  ${downloadStatusLabel(download)}",
        onClick = onClick,
    )
}

internal fun downloadStatusLabel(download: DownloadedMedia): String {
    val mediaKind = if (download.kind == DownloadKind.AUDIO) "AUDIO" else "VIDEO"
    return when (download.state) {
        DownloadState.QUEUED -> "$mediaKind  ·  QUEUED"
        DownloadState.DOWNLOADING -> {
            val total = download.totalBytes?.takeIf { it > 0L }
            if (total != null) {
                val percent = ((download.downloadedBytes.coerceAtMost(total) * 100L) / total)
                "$mediaKind  ·  $percent%"
            } else {
                "$mediaKind  ·  ${formatDownloadBytes(download.downloadedBytes)}"
            }
        }
        DownloadState.COMPLETE -> "$mediaKind  ·  ${formatDownloadBytes(download.downloadedBytes)}  ·  OFFLINE"
        DownloadState.FAILED -> "$mediaKind  ·  FAILED"
        DownloadState.CANCELLED -> "$mediaKind  ·  CANCELLED"
    }
}

internal fun formatDownloadBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe >= 1_000_000_000L -> "${safe / 1_000_000_000L} GB"
        safe >= 1_000_000L -> "${safe / 1_000_000L} MB"
        safe >= 1_000L -> "${safe / 1_000L} KB"
        else -> "$safe B"
    }
}

@Composable
private fun LibraryRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.7f.gridUnitsAsDp()),
    ) {
        LightText(
            text = title,
            variant = LightTextVariant.Copy,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = detail,
            variant = LightTextVariant.Fine,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 0.15f.gridUnitsAsDp()),
        )
    }
}

internal fun channelMetadataLine(channel: FocusedChannelEntry): String =
    channel.channelPolicy?.let { policy -> "ALL VIDEOS  ·  ${policy.shortLabel()}" }
        ?: "${channel.curatedVideos.size} SELECTED ${if (channel.curatedVideos.size == 1) "VIDEO" else "VIDEOS"}"

internal fun playlistMetadataLine(playlist: FocusedPlaylistEntry): String =
    "${playlist.items.size} ${if (playlist.items.size == 1) "VIDEO" else "VIDEOS"}"

internal fun channelFeedGroupMetadataLine(
    channel: FocusedChannelEntry,
    unwatchedCount: Int,
    limit: Int,
): String {
    val policy = channel.channelPolicy?.let(PlaybackPolicy::shortLabel)
    val count = "$unwatchedCount ${if (unwatchedCount == 1) "VIDEO" else "VIDEOS"} OF $limit UNWATCHED"
    return if (policy != null) "$count  ·  $policy" else count
}

internal fun PlaybackPolicy.shortLabel(): String = when (this) {
    PlaybackPolicy.LISTEN_ONLY -> "LISTEN ONLY"
    PlaybackPolicy.WATCH_AND_LISTEN -> "WATCH"
}

internal fun videoMetadataLine(video: VideoSummary): String = buildList {
    if (video.liveNow) {
        add("LIVE")
    } else if (video.lengthSeconds > 0L) {
        add(formatSeconds(video.lengthSeconds))
    }
    if (video.viewCount > 0L) add("${formatCompactCount(video.viewCount)} views")
    video.publishedText.takeIf(String::isNotBlank)?.let(::add)
}.joinToString("  ·  ")

internal fun formatSeconds(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val hours = safe / 3_600L
    val minutes = (safe % 3_600L) / 60L
    val seconds = safe % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

internal fun formatCompactCount(value: Long): String = when {
    value >= 1_000_000_000L -> formatCompactUnit(value, 1_000_000_000L, "B")
    value >= 1_000_000L -> formatCompactUnit(value, 1_000_000L, "M")
    value >= 1_000L -> formatCompactUnit(value, 1_000L, "K")
    else -> NumberFormat.getIntegerInstance(Locale.US).format(value)
}

private fun formatCompactUnit(value: Long, divisor: Long, suffix: String): String {
    val whole = value / divisor
    val tenths = (value % divisor) / (divisor / 10L)
    return if (whole >= 100L || tenths == 0L) {
        "$whole$suffix"
    } else {
        "$whole.$tenths$suffix"
    }
}

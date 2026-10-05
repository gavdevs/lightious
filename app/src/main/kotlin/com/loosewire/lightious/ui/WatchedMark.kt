package com.loosewire.lightious.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * The single explicit watched-state control shared by the video details page,
 * the audio/listen-only player, and offline media details.
 *
 * Shows the action only while marking is possible; reflects the locally
 * durable result ("Marked watched.") or the in-flight save ("Saving…").
 */
@Composable
internal fun WatchedMark(
    watched: Boolean,
    savingWatched: Boolean,
    onMarkWatched: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (watched || savingWatched) {
        LightText(
            text = if (watched) "Marked watched." else "Saving…",
            variant = LightTextVariant.Detail,
            modifier = modifier,
        )
    } else {
        LightText(
            text = "MARK WATCHED",
            variant = LightTextVariant.Button,
            underline = true,
            modifier = modifier.lightClickable(onClick = onMarkWatched),
        )
    }
}

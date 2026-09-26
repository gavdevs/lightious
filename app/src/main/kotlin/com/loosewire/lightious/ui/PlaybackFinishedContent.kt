package com.loosewire.lightious.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** A stopping point, with no next item, automatic completion, or remaining count. */
@Composable
internal fun PlaybackFinishedContent(
    title: String,
    watched: Boolean,
    saving: Boolean,
    onMarkWatched: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LightScrollView(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightText(
                text = "Playback finished",
                variant = LightTextVariant.Subheading,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
            LightText(
                text = title,
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
            if (watched || saving) {
                LightText(
                    text = if (watched) "Marked watched." else "Saving…",
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            }
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.Text(
                    text = if (watched) "WATCHED" else "MARK WATCHED",
                    onClick = onMarkWatched.takeUnless { watched || saving },
                ),
                LightBarButton.Text(text = "CLOSE", onClick = onClose.takeUnless { saving }),
            ),
        )
    }
}

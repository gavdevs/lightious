package com.loosewire.lightious.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.loosewire.lightious.LightiousServices
import com.loosewire.lightious.data.ChannelFeedGroup
import com.loosewire.lightious.data.ClientSettings
import com.loosewire.lightious.data.FocusedVideoEntry
import com.loosewire.lightious.data.FocusedLibraryFilter
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FocusedChannelReleasesUiState(
    val group: ChannelFeedGroup,
    val filter: FocusedLibraryFilter = FocusedLibraryFilter.ALL,
)

class FocusedChannelReleasesViewModel(
    initialGroup: ChannelFeedGroup,
    initialFilter: FocusedLibraryFilter,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(
        FocusedChannelReleasesUiState(group = initialGroup, filter = initialFilter),
    )
    val uiState: StateFlow<FocusedChannelReleasesUiState> = _uiState.asStateFlow()

    fun selectFilter(filter: FocusedLibraryFilter) {
        _uiState.update { state -> state.copy(filter = filter) }
    }
}

class FocusedChannelReleasesScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightiousServices,
    private val settings: ClientSettings,
    group: ChannelFeedGroup,
    filter: FocusedLibraryFilter,
) : LightScreen<Unit, FocusedChannelReleasesViewModel>(sealedActivity) {
    private val initialGroup = group
    private val initialFilter = filter

    override val viewModelClass = FocusedChannelReleasesViewModel::class.java

    override fun createViewModel() = FocusedChannelReleasesViewModel(initialGroup, initialFilter)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LightTheme(colors = colors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                            contentDescription = "Back",
                        ),
                        center = LightTopBarCenter.Text("Channel"),
                        rightButton = LightBarButton.LightIcon(
                            icon = LightIcons.ELLIPSES,
                            onClick = { openOptions(state.filter) },
                            contentDescription = "Channel options",
                        ),
                    )
                    FocusedChannelReleasesContent(
                        group = state.group,
                        selectedFilter = state.filter,
                        onVideo = { video ->
                            navigateTo(
                                screenFactory = { activity ->
                                    VideoScreen(activity, settings, video.video, services)
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    private fun openOptions(selectedFilter: FocusedLibraryFilter) {
        navigateTo(
            screenFactory = { activity ->
                FocusedOptionsScreen(
                    sealedActivity = activity,
                    title = "Channel Options",
                    selectedFilter = selectedFilter,
                )
            },
            resultCallback = { result ->
                when (result) {
                    is FocusedOptionsResult.SelectFilter -> viewModel.selectFilter(result.filter)
                    is FocusedOptionsResult.RunAction,
                    is FocusedOptionsResult.SelectChannelDisplay,
                    -> Unit
                }
            },
        )
    }
}

@Composable
private fun FocusedChannelReleasesContent(
    group: ChannelFeedGroup,
    selectedFilter: FocusedLibraryFilter,
    onVideo: (FocusedVideoEntry) -> Unit,
) {
    val videos = group.videos
        .filter { video -> selectedFilter.includes(video.playbackPolicy) }
    Column(modifier = Modifier.fillMaxSize()) {
        LightText(
            text = group.channel.name,
            variant = LightTextVariant.Subheading,
            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            if (videos.isEmpty()) {
                LightText(
                    text = "No channel releases match this filter.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            } else {
                videos.forEach { video ->
                    VideoRow(video.rowSummary()) { onVideo(video) }
                }
            }
        }
    }
}

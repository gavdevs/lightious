package com.loosewire.lightious.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.loosewire.lightious.data.FocusedLibraryFilter
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
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
import com.thelightphone.sdk.ui.lightClickable

internal enum class FocusedOptionsAction {
    SEARCH,
    REFRESH,
    SETTINGS,
}

internal sealed interface FocusedOptionsResult {
    data class SelectFilter(val filter: FocusedLibraryFilter) : FocusedOptionsResult

    data class RunAction(val action: FocusedOptionsAction) : FocusedOptionsResult
}

internal data class FocusedOptionEntry(
    val label: String,
    val result: FocusedOptionsResult,
    val selected: Boolean = false,
)

internal data class FocusedOptionsModel(
    val leadingActions: List<FocusedOptionEntry>,
    val filters: List<FocusedOptionEntry>,
    val trailingActions: List<FocusedOptionEntry>,
)

internal val focusedFilterDisplayOrder = listOf(
    FocusedLibraryFilter.ALL,
    FocusedLibraryFilter.WATCH,
    FocusedLibraryFilter.LISTEN,
)

internal fun focusedOptionsModel(
    selectedFilter: FocusedLibraryFilter?,
    leadingActions: List<FocusedOptionsAction> = emptyList(),
    trailingActions: List<FocusedOptionsAction> = emptyList(),
) = FocusedOptionsModel(
    leadingActions = leadingActions.map { action -> action.asEntry() },
    filters = selectedFilter?.let { selected ->
        focusedFilterDisplayOrder.map { filter ->
            FocusedOptionEntry(
                label = filter.filterLabel(),
                result = FocusedOptionsResult.SelectFilter(filter),
                selected = filter == selected,
            )
        }
    }.orEmpty(),
    trailingActions = trailingActions.map { action -> action.asEntry() },
)

internal class FocusedOptionsScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val selectedFilter: FocusedLibraryFilter?,
    private val leadingActions: List<FocusedOptionsAction> = emptyList(),
    private val trailingActions: List<FocusedOptionsAction> = emptyList(),
) : SimpleLightScreen<FocusedOptionsResult>(sealedActivity) {
    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val model = focusedOptionsModel(selectedFilter, leadingActions, trailingActions)

        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back",
                    ),
                    center = LightTopBarCenter.Text(title),
                )
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    model.leadingActions.forEach { entry -> OptionRow(entry) }
                    if (model.filters.isNotEmpty()) {
                        LightText(
                            text = "SHOW",
                            variant = LightTextVariant.Fine,
                            lighten = true,
                            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                        )
                        model.filters.forEach { entry -> OptionRow(entry) }
                    }
                    model.trailingActions.forEach { entry -> OptionRow(entry) }
                }
            }
        }
    }

    @Composable
    private fun OptionRow(entry: FocusedOptionEntry) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { goBack(entry.result) }
                .padding(vertical = 0.75f.gridUnitsAsDp()),
        ) {
            LightText(text = entry.label, variant = LightTextVariant.Subheading)
            if (entry.selected) {
                LightText(
                    text = "SELECTED",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.15f.gridUnitsAsDp()),
                )
            }
        }
    }
}

internal fun FocusedLibraryFilter.filterLabel(): String = when (this) {
    FocusedLibraryFilter.ALL -> "ALL"
    FocusedLibraryFilter.LISTEN -> "AUDIO"
    FocusedLibraryFilter.WATCH -> "VIDEO"
}

private fun FocusedOptionsAction.asEntry() = FocusedOptionEntry(
    label = when (this) {
        FocusedOptionsAction.SEARCH -> "SEARCH"
        FocusedOptionsAction.REFRESH -> "REFRESH"
        FocusedOptionsAction.SETTINGS -> "SETTINGS"
    },
    result = FocusedOptionsResult.RunAction(this),
)

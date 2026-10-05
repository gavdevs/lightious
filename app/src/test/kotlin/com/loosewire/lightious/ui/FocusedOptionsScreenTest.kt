package com.loosewire.lightious.ui

import com.loosewire.lightious.data.FocusedChannelDisplay
import com.loosewire.lightious.data.FocusedLibraryFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusedOptionsScreenTest {
    @Test
    fun `filter choices are shown as all video then audio`() {
        val model = focusedOptionsModel(selectedFilter = FocusedLibraryFilter.WATCH)

        assertEquals(
            listOf(FocusedLibraryFilter.ALL, FocusedLibraryFilter.WATCH, FocusedLibraryFilter.LISTEN),
            model.filters.map { entry ->
                (entry.result as FocusedOptionsResult.SelectFilter).filter
            },
        )
        assertEquals(listOf("ALL", "VIDEO", "AUDIO"), model.filters.map(FocusedOptionEntry::label))
        assertTrue(model.filters.single { entry -> entry.label == "VIDEO" }.selected)
        assertFalse(model.filters.single { entry -> entry.label == "ALL" }.selected)
    }

    @Test
    fun `actions stay on their requested side of the filter choices`() {
        val model = focusedOptionsModel(
            selectedFilter = FocusedLibraryFilter.ALL,
            leadingActions = listOf(FocusedOptionsAction.SEARCH, FocusedOptionsAction.REFRESH),
            trailingActions = listOf(FocusedOptionsAction.SETTINGS),
        )

        assertEquals(listOf("SEARCH", "REFRESH"), model.leadingActions.map(FocusedOptionEntry::label))
        assertEquals(listOf("SETTINGS"), model.trailingActions.map(FocusedOptionEntry::label))
        assertEquals(
            FocusedOptionsResult.RunAction(FocusedOptionsAction.SEARCH),
            model.leadingActions.first().result,
        )
    }

    @Test
    fun `display choices are shown only when a selected display is provided`() {
        val model = focusedOptionsModel(
            selectedFilter = FocusedLibraryFilter.ALL,
            selectedChannelDisplay = FocusedChannelDisplay.BY_CHANNEL,
        )

        assertEquals(
            listOf(FocusedChannelDisplay.ALL_VIDEOS, FocusedChannelDisplay.BY_CHANNEL),
            model.channelDisplay.map { entry ->
                (entry.result as FocusedOptionsResult.SelectChannelDisplay).display
            },
        )
        assertEquals(listOf("ALL VIDEOS", "BY CHANNEL"), model.channelDisplay.map(FocusedOptionEntry::label))
        assertTrue(model.channelDisplay.single { entry -> entry.label == "BY CHANNEL" }.selected)
        assertFalse(model.channelDisplay.single { entry -> entry.label == "ALL VIDEOS" }.selected)
    }

    @Test
    fun `display section can be omitted for menus without a display choice`() {
        val model = focusedOptionsModel(
            selectedFilter = FocusedLibraryFilter.ALL,
            selectedChannelDisplay = null,
        )

        assertTrue(model.channelDisplay.isEmpty())
    }
}

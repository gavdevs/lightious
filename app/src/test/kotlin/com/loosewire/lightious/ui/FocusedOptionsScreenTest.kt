package com.loosewire.lightious.ui

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
    fun `filter section can be omitted for action-only menus`() {
        val model = focusedOptionsModel(
            selectedFilter = null,
            leadingActions = listOf(FocusedOptionsAction.SETTINGS),
        )

        assertTrue(model.filters.isEmpty())
    }
}

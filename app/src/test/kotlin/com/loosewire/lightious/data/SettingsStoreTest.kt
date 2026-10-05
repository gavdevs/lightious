package com.loosewire.lightious.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingsStoreTest {
    @Test
    fun `defaults to unconfigured with media proxying enabled`() = runTest {
        val store = createStore(File.createTempFile("lightious-settings", ".preferences_pb"))

        assertEquals(ClientSettings(instanceUrl = "", proxyMedia = true), store.load())
        assertEquals(true, store.load().selfHostEnabled)
        assertEquals(false, store.load().managedServerAvailable)
    }

    @Test
    fun `default home pages are focused and exclude popular`() = runTest {
        val store = createStore(File.createTempFile("lightious-settings", ".preferences_pb"))

        assertEquals(
            listOf(
                HomePage.SEARCH,
                HomePage.ACCOUNT_FEED,
                HomePage.WATCH_HISTORY,
                HomePage.SEARCH_HISTORY,
            ),
            store.load().homePages,
        )
        assertEquals(false, HomePage.POPULAR in store.load().homePages)
        assertEquals(false, store.load().syncAccountHistory)
    }

    @Test
    fun `normalizes and persists both settings`() = runTest {
        val store = createStore(File.createTempFile("lightious-settings", ".preferences_pb"))

        store.saveInstance("Example.COM:443/")
        store.setProxyMedia(false)

        assertEquals(
            ClientSettings(
                instanceUrl = "https://example.com",
                selfHostEnabled = true,
                proxyMedia = false,
            ),
            store.load(),
        )
    }

    @Test
    fun `migrates an existing explicit instance to self-host enabled`() = runTest {
        val dataStore = createDataStore(File.createTempFile("lightious-settings", ".preferences_pb"))
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("invidious_instance_url")] = "https://example.com"
        }

        val settings = SettingsStore(dataStore).load()

        assertEquals("https://example.com", settings.instanceUrl)
        assertEquals(true, settings.selfHostEnabled)
    }

    @Test
    fun `requires self-host and preserves its URL without a managed server`() = runTest {
        val store = createStore(File.createTempFile("lightious-settings", ".preferences_pb"))
        store.saveInstance("https://example.com")

        assertFailsWith<IllegalArgumentException> {
            store.setSelfHostEnabled(false)
        }

        assertEquals("https://example.com", store.load().instanceUrl)
        assertEquals(true, store.load().selfHostEnabled)
    }

    @Test
    fun `uses the built-in server when self-host is disabled and restores the custom server`() = runTest {
        val dataStore = createDataStore(File.createTempFile("lightious-settings", ".preferences_pb"))
        val store = SettingsStore(dataStore, defaultInstanceUrl = "https://lightious.example")

        assertEquals("https://lightious.example", store.load().instanceUrl)
        assertEquals(false, store.load().selfHostEnabled)
        assertEquals(true, store.load().managedServerAvailable)

        store.saveInstance("https://custom.example")

        store.setSelfHostEnabled(false)
        assertEquals("https://lightious.example", store.load().instanceUrl)
        assertEquals(false, store.load().selfHostEnabled)

        store.setSelfHostEnabled(true)
        assertEquals("https://custom.example", store.load().instanceUrl)
        assertEquals(true, store.load().selfHostEnabled)
    }

    @Test
    fun `home pages preserve order while removing duplicates`() = runTest {
        val store = createStore(File.createTempFile("lightious-settings", ".preferences_pb"))

        store.setHomePages(
            listOf(
                HomePage.WATCH_HISTORY,
                HomePage.SEARCH,
                HomePage.WATCH_HISTORY,
                HomePage.ACCOUNT_FEED,
                HomePage.SEARCH,
            ),
        )

        assertEquals(
            listOf(
                HomePage.WATCH_HISTORY,
                HomePage.SEARCH,
                HomePage.ACCOUNT_FEED,
            ),
            store.load().homePages,
        )
    }

    @Test
    fun `persists independent history toggles`() = runTest {
        val store = createStore(File.createTempFile("lightious-settings", ".preferences_pb"))

        store.setSearchHistoryEnabled(false)
        store.setWatchHistoryEnabled(false)
        store.setAccountHistorySyncEnabled(true)

        val settings = store.load()
        assertEquals(false, settings.saveSearchHistory)
        assertEquals(false, settings.saveWatchHistory)
        assertEquals(true, settings.syncAccountHistory)
    }

    @Test
    fun `persists one app wide audio language preference`() = runTest {
        val store = createStore(File.createTempFile("lightious-settings", ".preferences_pb"))

        store.setAudioLanguage(AudioLanguagePreference.ENGLISH)

        assertEquals(AudioLanguagePreference.ENGLISH, store.load().audioLanguage)
    }

    @Test
    fun `persists the focused channel display preference`() = runTest {
        val store = createStore(File.createTempFile("lightious-settings", ".preferences_pb"))

        assertEquals(FocusedChannelDisplay.ALL_VIDEOS, store.load().focusedChannelDisplay)

        store.setFocusedChannelDisplay(FocusedChannelDisplay.BY_CHANNEL)

        assertEquals(FocusedChannelDisplay.BY_CHANNEL, store.load().focusedChannelDisplay)
    }

    @Test
    fun `does not persist an invalid instance`() = runTest {
        val store = createStore(File.createTempFile("lightious-settings", ".preferences_pb"))

        assertFailsWith<IllegalArgumentException> {
            store.saveInstance("https://example.com/not-an-origin")
        }
        assertEquals("", store.load().instanceUrl)
    }

    private fun kotlinx.coroutines.test.TestScope.createStore(file: File): SettingsStore {
        return SettingsStore(createDataStore(file))
    }

    private fun kotlinx.coroutines.test.TestScope.createDataStore(file: File): DataStore<Preferences> {
        file.delete()
        return PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
    }
}

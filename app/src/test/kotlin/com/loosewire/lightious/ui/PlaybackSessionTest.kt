package com.loosewire.lightious.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackSessionTest {
    @Test
    fun `switching from paused audio preserves its place and does not start video`() {
        assertEquals(PlaybackStart(45_000L, false), videoStartFromAudio(true, 45_000L, false, 0L))
        assertEquals(PlaybackStart(45_000L, true), videoStartFromAudio(true, 45_000L, true, 0L))
        assertEquals(PlaybackStart(80_000L, true), videoStartFromAudio(false, 0L, false, 80_000L))
    }

    @Test
    fun `audio ending excludes pause buffering errors and handoffs`() {
        assertTrue(audioPlaybackFinished(true, false, 100L, 100L, false, false))
        assertFalse(audioPlaybackFinished(true, false, 50L, 100L, false, false))
        assertFalse(audioPlaybackFinished(true, false, 0L, 0L, false, false))
        assertFalse(audioPlaybackFinished(true, false, 100L, 100L, true, false))
        assertFalse(audioPlaybackFinished(true, false, 100L, 100L, false, true))
        assertFalse(audioPlaybackFinished(false, false, 100L, 100L, false, false))
    }

    @Test
    fun `handoff waits for prepared timeline before seeking to saved position`() = runTest {
        val durations = MutableStateFlow(0L)
        val failures = MutableStateFlow(false)
        val seeks = mutableListOf<Long>()
        val operation = async { restoreAudioPosition(45_000L, durations, failures, seeks::add) }
        runCurrent()
        assertTrue(seeks.isEmpty())
        durations.value = 120_000L
        operation.await()
        assertEquals(listOf(45_000L), seeks)
    }

    @Test
    fun `failed handoff does not silently restart at zero`() = runTest {
        val seeks = mutableListOf<Long>()
        assertFailsWith<IllegalStateException> {
            restoreAudioPosition(45_000L, MutableStateFlow(0L), MutableStateFlow(true), seeks::add)
        }
        assertTrue(seeks.isEmpty())
    }

    @Test
    fun `handoff times out without silently losing saved position`() = runTest {
        val seeks = mutableListOf<Long>()
        assertFailsWith<IllegalStateException> {
            restoreAudioPosition(45_000L, MutableStateFlow(0L), MutableStateFlow(false), seeks::add)
        }
        assertTrue(seeks.isEmpty())
    }
}

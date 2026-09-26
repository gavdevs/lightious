package com.loosewire.lightious.ui

import com.loosewire.lightious.data.ClientSettings
import com.loosewire.lightious.data.CompanionProfile
import com.loosewire.lightious.data.CompanionSession
import com.loosewire.lightious.data.CompanionState
import com.loosewire.lightious.data.ExperienceMode
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class HomeLoadingTest {
    @Test
    fun `paired home stays on connecting until fresh library arrives`() = runTest {
        val state = MutableStateFlow(HomeUiState(companion = paired))
        val response = CompletableDeferred<Result<CompanionProfile>>()
        val job = launch { load(state, response) }

        runCurrent()

        assertEquals(HomeMode.Loading("Connecting…"), state.value.mode)
        assertNull(state.value.errorMessage)
        assertNull(state.value.companion.profile)
        assertEquals(session, state.value.companion.session)

        // State written by other observers during a network request survives it.
        state.update { it.copy(focusedTab = FocusedHomeTab.DOWNLOADS) }
        val freshProfile = profile.copy(revision = 2)
        response.complete(Result.success(freshProfile))
        job.join()

        assertEquals(HomeMode.Ready, state.value.mode)
        assertEquals(freshProfile, state.value.companion.profile)
        assertEquals(FocusedHomeTab.DOWNLOADS, state.value.focusedTab)
        assertNull(state.value.errorMessage)
    }

    @Test
    fun `connection failure appears only after response and retry clears it while pending`() = runTest {
        val state = MutableStateFlow(HomeUiState(companion = paired))
        val failure = CompletableDeferred<Result<CompanionProfile>>()
        val failedJob = launch { load(state, failure) }
        runCurrent()

        assertIs<HomeMode.Loading>(state.value.mode)
        assertNull(state.value.errorMessage)

        failure.complete(Result.failure(IOException("Server unavailable")))
        failedJob.join()

        assertEquals(HomeMode.Ready, state.value.mode)
        assertEquals("Server unavailable", state.value.errorMessage)
        assertNull(state.value.companion.profile)
        assertEquals(session, state.value.companion.session)

        val retry = CompletableDeferred<Result<CompanionProfile>>()
        val retryJob = launch { load(state, retry) }
        runCurrent()

        assertEquals(HomeMode.Loading("Connecting…"), state.value.mode)
        assertNull(state.value.errorMessage)
        assertNull(state.value.companion.profile)

        retry.complete(Result.success(profile))
        retryJob.join()

        assertEquals(HomeMode.Ready, state.value.mode)
        assertEquals(profile, state.value.companion.profile)
        assertNull(state.value.errorMessage)
    }

    @Test
    fun `canceling a superseded connection never displays a connection failure`() = runTest {
        val state = MutableStateFlow(HomeUiState(companion = paired))
        val response = CompletableDeferred<Result<CompanionProfile>>()
        val job = launch { load(state, response) }
        runCurrent()

        job.cancel()
        job.join()

        assertIs<HomeMode.Loading>(state.value.mode)
        assertNull(state.value.errorMessage)
        assertNull(state.value.companion.profile)
    }

    @Test
    fun `authoritative pairing rejection does not restore cached library or credentials`() = runTest {
        val state = MutableStateFlow(HomeUiState(companion = paired))

        loadHomeLibrary(
            state = state,
            settings = settings,
            account = null,
            cachedCompanion = paired,
            syncCompanion = { Result.failure(IOException("This phone is no longer paired")) },
            reloadCompanion = { CompanionState() },
        )

        assertEquals(HomeMode.Ready, state.value.mode)
        assertEquals(CompanionState(), state.value.companion)
        assertEquals("This phone is no longer paired", state.value.errorMessage)
    }

    @Test
    fun `unpaired home becomes ready without attempting a connection`() = runTest {
        val state = MutableStateFlow(HomeUiState())

        loadHomeLibrary(
            state = state,
            settings = settings,
            account = null,
            cachedCompanion = CompanionState(),
            syncCompanion = { error("An unpaired phone should not sync") },
            reloadCompanion = { error("An unpaired phone should not reload pairing") },
        )

        assertEquals(HomeMode.Ready, state.value.mode)
        assertNull(state.value.errorMessage)
        assertEquals(CompanionState(), state.value.companion)
    }

    private suspend fun load(
        state: MutableStateFlow<HomeUiState>,
        response: CompletableDeferred<Result<CompanionProfile>>,
    ) = loadHomeLibrary(
        state = state,
        settings = settings,
        account = null,
        cachedCompanion = paired,
        syncCompanion = { response.await() },
        reloadCompanion = { paired },
    )

    private companion object {
        val settings = ClientSettings(instanceUrl = "https://invidious.example")
        val session = CompanionSession(settings.instanceUrl, "device", "account", "bearer")
        val profile = CompanionProfile("device", "account", 1, ExperienceMode.FOCUSED, emptyList())
        val paired = CompanionState(session, profile)
    }
}

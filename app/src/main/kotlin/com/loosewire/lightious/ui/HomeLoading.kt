package com.loosewire.lightious.ui

import com.loosewire.lightious.data.AccountSession
import com.loosewire.lightious.data.ClientSettings
import com.loosewire.lightious.data.CompanionProfile
import com.loosewire.lightious.data.CompanionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal suspend fun loadHomeLibrary(
    state: MutableStateFlow<HomeUiState>,
    settings: ClientSettings,
    account: AccountSession?,
    cachedCompanion: CompanionState,
    syncCompanion: suspend () -> Result<CompanionProfile>,
    reloadCompanion: suspend () -> CompanionState,
): CompanionState {
    currentCoroutineContext().ensureActive()
    state.update { current ->
        current.copy(
            settings = settings,
            account = account,
            companion = cachedCompanion.copy(profile = null),
            mode = HomeMode.Loading(if (cachedCompanion.session != null) "Connecting…" else "Loading…"),
            errorMessage = null,
        )
    }
    var companion = cachedCompanion
    var syncError: String? = null
    if (cachedCompanion.session != null) {
        try {
            companion = cachedCompanion.copy(profile = syncCompanion().getOrThrow())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Re-read pairing because an authoritative rejection can remove it.
            // A failed request must never restore an unverified cached profile.
            companion = try {
                reloadCompanion().copy(profile = null)
            } catch (loadError: CancellationException) {
                throw loadError
            } catch (_: Exception) {
                CompanionState()
            }
            syncError = error.userMessage("Could not connect to Lightious.")
        }
    }
    currentCoroutineContext().ensureActive()
    state.update { current ->
        current.copy(
            companion = companion,
            mode = HomeMode.Ready,
            errorMessage = syncError,
        )
    }
    return companion
}

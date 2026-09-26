package com.loosewire.lightious.data

import kotlinx.coroutines.CancellationException

class CompanionRepository internal constructor(
    private val store: CompanionStore,
    private val onProfileSynced: suspend (CompanionProfile) -> Unit = {},
    private val onPairingRemoved: suspend (String) -> Unit = {},
    private val apiFactory: (String) -> CompanionApi = { instanceUrl -> CompanionApi(instanceUrl) },
) {
    suspend fun load(instanceUrl: String): CompanionState =
        if (instanceUrl.isBlank()) CompanionState() else store.load(instanceUrl)

    suspend fun loadActiveState(instanceUrl: String): Result<CompanionState> = repositoryRunCatching {
        if (instanceUrl.isBlank()) return@repositoryRunCatching CompanionState()
        val normalized = normalizeInstanceUrl(instanceUrl)
        val cached = store.load(normalized)
        if (cached.session == null) return@repositoryRunCatching cached
        sync(normalized).getOrThrow()
        store.load(normalized)
    }

    suspend fun beginPairing(
        instanceUrl: String,
        deviceLabel: String = "Light Phone III",
    ): Result<PendingPairing> = repositoryRunCatching {
        val normalized = normalizeInstanceUrl(instanceUrl)
        val deviceBearer = generateDeviceBearer()
        val pending = apiFactory(normalized).use { api ->
            api.createPairing(deviceLabel, deviceBearerDigest(deviceBearer)).getOrThrow()
        }
        require(pending.pairingId.matches(Regex("^[0-9a-f]{32}$"))) {
            "The server returned an invalid pairing ID."
        }
        require(pending.userCode.matches(Regex("^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}(?:-[0-9A-HJKMNP-TV-Z]{4})?$"))) {
            "The server returned an invalid pairing code."
        }
        require(validPollSecret(pending.pollSecret)) {
            "The server returned an invalid polling credential."
        }
        pending.copy(deviceBearer = deviceBearer)
    }

    suspend fun pollPairing(pending: PendingPairing): Result<PairingStatus> =
        apiFactory(pending.instanceUrl).use { api -> api.pairingStatus(pending) }

    suspend fun activatePairing(pending: PendingPairing): Result<CompanionState> = repositoryRunCatching {
        require(validDeviceBearer(pending.deviceBearer)) { "Pairing must be restarted on this phone." }
        val activated = apiFactory(pending.instanceUrl).use { api ->
            api.activatePairing(pending).getOrThrow()
        }
        require(activated.deviceId.matches(Regex("^[0-9a-f]{32}$"))) {
            "The server returned an invalid device ID."
        }
        val session = CompanionSession(
            instanceUrl = pending.instanceUrl,
            deviceId = activated.deviceId,
            account = activated.account,
            deviceBearer = pending.deviceBearer,
        )
        store.saveSession(session)
        sync(pending.instanceUrl)
        // Sync can authoritatively revoke and clear this just-created session,
        // while a transient failure deliberately keeps it for retry. Always
        // return what is actually stored rather than the pre-sync local value.
        store.load(pending.instanceUrl)
    }

    suspend fun sync(instanceUrl: String): Result<CompanionProfile> = repositoryRunCatching {
        val normalized = normalizeInstanceUrl(instanceUrl)
        val session = store.load(normalized).session
            ?: throw IllegalStateException("This phone is not paired with the companion.")
        val receivedProfile = try {
            apiFactory(normalized).use { api -> api.sync(session.deviceBearer).getOrThrow() }
        } catch (error: InvidiousApiException) {
            if (error.statusCode == 401 || error.statusCode == 404) {
                store.clearIfMatches(normalized, session.deviceId)
                onPairingRemoved(session.deviceId)
            }
            throw error
        }
        require(receivedProfile.deviceId == session.deviceId) { "The companion returned a different device." }
        val profile = receivedProfile.withoutShorts()
        store.saveProfile(normalized, profile)
        onProfileSynced(receivedProfile)
        profile
    }

    suspend fun authorizePlayback(
        instanceUrl: String,
        videoId: String,
        authorId: String?,
        isShort: Boolean = false,
    ): PlaybackAccess {
        if (isShort) {
            return PlaybackAccess(allowed = false, message = SHORTS_BLOCKED_MESSAGE)
        }
        val cached = load(instanceUrl)
        if (cached.session == null) {
            return PlaybackAccess(
                allowed = false,
                message = "Pair this phone with Lightious before playing videos.",
            )
        }

        val profile = sync(instanceUrl).getOrElse { error ->
            return PlaybackAccess(
                allowed = false,
                message = error.message ?: "Could not verify the companion policy.",
            )
        }
        if (videoId in profile.knownShortVideoIds()) {
            return PlaybackAccess(allowed = false, message = SHORTS_BLOCKED_MESSAGE)
        }
        val policy = profile.playbackPolicyFor(videoId, authorId)
            ?: return PlaybackAccess(
                allowed = false,
                message = "This video is not in your library.",
            )
        return PlaybackAccess(allowed = true, policy = policy)
    }

    suspend fun probeSavedInstance(
        instanceUrl: String,
        proxyMedia: Boolean,
        audioLanguage: AudioLanguagePreference,
    ): Result<InstanceProbe> = repositoryRunCatching {
        val active = loadActiveState(instanceUrl).getOrThrow()
        val session = active.session ?: return@repositoryRunCatching InvidiousApi(
            baseUrl = instanceUrl,
            proxyMedia = proxyMedia,
            audioLanguage = audioLanguage,
        ).use { api ->
            api.probe()
        }
        InvidiousApi(
            baseUrl = session.instanceUrl,
            proxyMedia = proxyMedia,
            deviceBearer = session.deviceBearer,
            audioLanguage = audioLanguage,
        ).use { api ->
            api.probeAuthorized(active.profile?.items?.firstOrNull()?.videoId)
        }
    }

    suspend fun forget(instanceUrl: String) {
        val ownerDeviceId = load(instanceUrl).session?.deviceId
        store.clear()
        ownerDeviceId?.let { onPairingRemoved(it) }
    }
}

private suspend fun <T> repositoryRunCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}

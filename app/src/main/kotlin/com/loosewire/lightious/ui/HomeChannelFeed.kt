package com.loosewire.lightious.ui

import com.loosewire.lightious.data.ChannelFeedPage
import com.loosewire.lightious.data.CompanionProfile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface HomeChannelFeedState {
    data object Loading : HomeChannelFeedState
    data class Loaded(val page: ChannelFeedPage) : HomeChannelFeedState
    data class Failed(val message: String) : HomeChannelFeedState
}

/** Keeps one finite release window while the user opens videos and returns home. */
internal class HomeChannelFeedCache {
    private data class Key(val instanceUrl: String, val deviceId: String, val revision: Long)

    private var key: Key? = null
    private var page: ChannelFeedPage? = null

    suspend fun load(
        instanceUrl: String,
        profile: CompanionProfile,
        refresh: Boolean,
        fetch: suspend () -> Result<ChannelFeedPage>,
    ): ChannelFeedPage {
        val requestedKey = Key(instanceUrl, profile.deviceId, profile.revision)
        if (!refresh && key == requestedKey) page?.let { return it }
        key = null
        page = null
        val result = fetch().getOrThrow()
        currentCoroutineContext().ensureActive()
        key = requestedKey
        page = result
        return result
    }
}

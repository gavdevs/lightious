package com.loosewire.lightious

import com.loosewire.lightious.data.AccountStore
import com.loosewire.lightious.data.CompanionRepository
import com.loosewire.lightious.data.CompanionStore
import com.loosewire.lightious.data.DownloadRepository
import com.loosewire.lightious.data.DownloadsDatabase
import com.loosewire.lightious.data.HistoryDatabase
import com.loosewire.lightious.data.HistoryRepository
import com.loosewire.lightious.data.HistorySyncer
import com.loosewire.lightious.data.SettingsStore
import com.loosewire.lightious.data.WatchedDatabase
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase

class LightiousServices private constructor(
    val settings: SettingsStore,
    val accounts: AccountStore,
    val companion: CompanionRepository,
    val downloads: DownloadRepository,
    val history: HistoryRepository,
    val historySyncer: HistorySyncer,
) {
    companion object {
        @Volatile
        private var instance: LightiousServices? = null

        fun from(context: SealedLightContext): LightiousServices =
            instance ?: synchronized(this) {
                instance ?: run {
                    val history = HistoryRepository(
                        context.buildDatabase(HistoryDatabase::class.java, HistoryDatabase.NAME)
                            .historyDao(),
                        context.buildDatabase(WatchedDatabase::class.java, WatchedDatabase.NAME)
                            .watchedVideoDao(),
                    )
                    val downloads = DownloadRepository(
                        context.buildDatabase(DownloadsDatabase::class.java, DownloadsDatabase.NAME)
                            .downloadsDao(),
                        context.filesDir,
                    )
                    val companion = CompanionRepository(
                        store = CompanionStore(context.dataStore),
                        onProfileSynced = { profile ->
                            downloads.reconcile(profile)
                            history.reconcile(profile)
                        },
                        onPairingRemoved = downloads::deleteForOwner,
                    )
                    LightiousServices(
                        settings = SettingsStore(context.dataStore),
                        accounts = AccountStore(context.dataStore),
                        companion = companion,
                        downloads = downloads,
                        history = history,
                        historySyncer = HistorySyncer(
                            history = history,
                            companion = companion,
                        ),
                    ).also { instance = it }
                }
            }
    }
}

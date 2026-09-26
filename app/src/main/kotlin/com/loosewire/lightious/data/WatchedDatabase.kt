package com.loosewire.lightious.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/** Explicit completion is independent of the older, start-based playback history. */
@Entity(tableName = "watched_videos")
internal data class WatchedVideoEntity(
    @PrimaryKey val videoId: String,
    val markedWatchedAt: Long,
)

@Dao
internal interface WatchedVideoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markWatched(entity: WatchedVideoEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM watched_videos WHERE videoId = :videoId)")
    suspend fun isWatched(videoId: String): Boolean

    @Query("SELECT videoId FROM watched_videos")
    suspend fun watchedVideoIds(): List<String>

    @Query("DELETE FROM watched_videos WHERE videoId = :videoId")
    suspend fun deleteWatched(videoId: String)
}

@Database(entities = [WatchedVideoEntity::class], version = 1, exportSchema = false)
abstract class WatchedDatabase : RoomDatabase() {
    internal abstract fun watchedVideoDao(): WatchedVideoDao

    companion object {
        // Separate storage preserves the installed history DB without requiring
        // migration options that the SDK's buildDatabase helper does not expose.
        const val NAME = "lightious-watched.db"
    }
}

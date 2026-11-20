// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Resume position + recently-played history. One row per media URI. */
@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val isVideo: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val uri: String, val addedAt: Long)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "playlist_items",
    foreignKeys = [ForeignKey(
        entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("playlistId")],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val isVideo: Boolean,
    val durationMs: Long,
    val sortOrder: Int,
)

@Entity(tableName = "folder_history")
data class FolderHistoryEntity(@PrimaryKey val path: String, val openedAt: Long)

data class PlaylistSummary(val id: Long, val name: String, val itemCount: Int)

@Dao
interface PlaybackDao {
    @Upsert suspend fun upsert(entity: PlaybackStateEntity)
    @Query("SELECT * FROM playback_state WHERE uri = :uri") suspend fun get(uri: String): PlaybackStateEntity?
    @Query("SELECT * FROM playback_state ORDER BY updatedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<PlaybackStateEntity>>
    @Query("SELECT * FROM playback_state WHERE isVideo = 1 AND positionMs > 5000 AND durationMs > 0 AND positionMs < durationMs * 0.95 ORDER BY updatedAt DESC LIMIT :limit")
    fun continueWatching(limit: Int): Flow<List<PlaybackStateEntity>>
    @Query("DELETE FROM playback_state WHERE uri IN (:uris)") suspend fun delete(uris: List<String>)
    @Query("DELETE FROM playback_state") suspend fun clear()
}

@Dao
interface FavoriteDao {
    @Query("SELECT uri FROM favorites ORDER BY addedAt DESC") fun all(): Flow<List<String>>
    @Query("SELECT COUNT(*) FROM favorites WHERE uri = :uri") suspend fun exists(uri: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun add(e: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE uri = :uri") suspend fun remove(uri: String)
}

@Dao
interface PlaylistDao {
    @Query("SELECT p.id AS id, p.name AS name, (SELECT COUNT(*) FROM playlist_items i WHERE i.playlistId = p.id) AS itemCount FROM playlists p ORDER BY p.createdAt DESC")
    fun summaries(): Flow<List<PlaylistSummary>>
    @Query("SELECT * FROM playlists WHERE id = :id") fun playlist(id: Long): Flow<PlaylistEntity?>
    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY sortOrder ASC") fun items(id: Long): Flow<List<PlaylistItemEntity>>
    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM playlist_items WHERE playlistId = :id") suspend fun maxOrder(id: Long): Int
    @Insert suspend fun insert(p: PlaylistEntity): Long
    @Insert suspend fun insertItems(items: List<PlaylistItemEntity>)
    @Update suspend fun updateItems(items: List<PlaylistItemEntity>)
    @Query("UPDATE playlists SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM playlist_items WHERE id = :itemId") suspend fun removeItem(itemId: Long)
}

@Dao
interface FolderHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: FolderHistoryEntity)
    @Query("SELECT path FROM folder_history ORDER BY openedAt DESC LIMIT 1") fun last(): Flow<String?>
}

@Database(
    entities = [PlaybackStateEntity::class, FavoriteEntity::class, PlaylistEntity::class,
        PlaylistItemEntity::class, FolderHistoryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DhvonDatabase : RoomDatabase() {
    abstract fun playback(): PlaybackDao
    abstract fun favorites(): FavoriteDao
    abstract fun playlists(): PlaylistDao
    abstract fun folderHistory(): FolderHistoryDao
}

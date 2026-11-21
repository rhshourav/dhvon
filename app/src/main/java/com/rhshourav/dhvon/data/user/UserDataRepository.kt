// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.data.user

import com.rhshourav.dhvon.data.local.*
import com.rhshourav.dhvon.domain.MediaFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Favorites, playlists, resume positions and folder history — everything stored in Room. */
@Singleton
class UserDataRepository @Inject constructor(
    private val playback: PlaybackDao,
    private val favoritesDao: FavoriteDao,
    private val playlistDao: PlaylistDao,
    private val folderDao: FolderHistoryDao,
    private val lastPlayedDao: FolderLastPlayedDao,
) {
    val recent: Flow<List<PlaybackStateEntity>> = playback.recent(30)
    val continueWatching: Flow<List<PlaybackStateEntity>> = playback.continueWatching(20)
    val favorites: Flow<Set<String>> = favoritesDao.all().map { it.toSet() }
    val playlists: Flow<List<PlaylistSummary>> = playlistDao.summaries()
    val lastFolder: Flow<String?> = folderDao.last()

    suspend fun positionFor(uri: String): PlaybackStateEntity? = playback.get(uri)
    suspend fun savePosition(e: PlaybackStateEntity) = playback.upsert(e)
    suspend fun clearHistory() = playback.clear()

    suspend fun toggleFavorite(uri: String) {
        if (favoritesDao.exists(uri) > 0) favoritesDao.remove(uri) else favoritesDao.add(FavoriteEntity(uri, System.currentTimeMillis()))
    }

    /** The file that was playing last in [path] (a folder path or pseudo-folder). */
    fun lastPlayedIn(path: String): Flow<String?> = lastPlayedDao.last(path)
    suspend fun setFolderLastPlayed(path: String, uri: String, title: String) =
        lastPlayedDao.upsert(FolderLastPlayedEntity(path, uri, title, System.currentTimeMillis()))

    suspend fun markFolderOpened(path: String) = folderDao.upsert(FolderHistoryEntity(path, System.currentTimeMillis()))

    fun playlist(id: Long) = playlistDao.playlist(id)
    fun playlistItems(id: Long) = playlistDao.items(id)
    suspend fun createPlaylist(name: String): Long = playlistDao.insert(PlaylistEntity(name = name.trim().ifBlank { "New playlist" }, createdAt = System.currentTimeMillis()))
    suspend fun renamePlaylist(id: Long, name: String) = playlistDao.rename(id, name.trim())
    suspend fun deletePlaylist(id: Long) = playlistDao.delete(id)
    suspend fun removePlaylistItem(itemId: Long) = playlistDao.removeItem(itemId)

    suspend fun addToPlaylist(playlistId: Long, files: List<MediaFile>) {
        var order = playlistDao.maxOrder(playlistId)
        playlistDao.insertItems(files.map {
            PlaylistItemEntity(playlistId = playlistId, uri = it.uri, title = it.title, artist = it.artist, isVideo = it.isVideo, durationMs = it.durationMs, sortOrder = ++order)
        })
    }

    /** Persists a drag-and-drop reorder. */
    suspend fun reorder(items: List<PlaylistItemEntity>) = playlistDao.updateItems(items.mapIndexed { i, e -> e.copy(sortOrder = i) })
}

fun PlaylistItemEntity.toMediaFile(): MediaFile = MediaFile(
    id = id, uri = uri, title = title, displayName = title, durationMs = durationMs, sizeBytes = 0, dateModifiedSec = 0,
    folderPath = "", isVideo = isVideo, artist = artist,
)

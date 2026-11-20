// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.domain

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import android.os.Bundle
import android.content.ContentUris
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A single playable file (video or audio) — from MediaStore or a SAF tree. */
data class MediaFile(
    val id: Long,
    val uri: String,
    val title: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    val folderPath: String,
    val isVideo: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val artist: String = "",
    val album: String = "",
    val albumId: Long = 0L,
    val mimeType: String = "",
) {
    val resolutionLabel: String?
        get() = if (isVideo && height > 0) "${height}p" else null

    /** "02:24:17 • 1080p" style metadata line. */
    val metaLine: String
        get() = listOfNotNull(formatDuration(durationMs), resolutionLabel).joinToString(" • ")

    fun toMediaItem(): MediaItem {
        val extras = Bundle().apply {
            putBoolean(EXTRA_VIDEO, isVideo)
            putLong(EXTRA_DURATION, durationMs)
        }
        val art: Uri? = if (!isVideo && albumId > 0)
            ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), albumId) else null
        return MediaItem.Builder()
            .setMediaId(uri)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist.ifBlank { null })
                    .setAlbumTitle(album.ifBlank { null })
                    .setArtworkUri(art)
                    .setIsPlayable(true)
                    .setExtras(extras)
                    .build()
            ).build()
    }

    companion object {
        const val EXTRA_VIDEO = "dhvon.video"
        const val EXTRA_DURATION = "dhvon.duration"
    }
}

val MediaItem.isVideoItem: Boolean get() = mediaMetadata.extras?.getBoolean(MediaFile.EXTRA_VIDEO) == true
val MediaItem.durationHint: Long get() = mediaMetadata.extras?.getLong(MediaFile.EXTRA_DURATION) ?: 0L

enum class FolderCategory(val label: String) {
    ALL("All"), MOVIES("Movies"), SERIES("Series"), MUSIC_VIDEOS("Music Videos"),
    CONCERTS("Concerts"), DOWNLOADS("Downloads"), OTHERS("Others");
}

data class MediaFolder(
    val path: String,
    val name: String,
    val itemCount: Int,
    val totalBytes: Long,
    val lastModifiedSec: Long,
    val coverUris: List<String>,
    val videoCount: Int,
    val audioCount: Int,
    val category: FolderCategory,
) {
    val summary: String get() = "$itemCount items • ${formatSize(totalBytes)}"
}

data class StorageInfo(val usedBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val label: String get() = "${formatSizeGb(usedBytes)} / ${formatSizeGb(totalBytes)}"
}

/** Smart folder categorisation used by the Library filter chips. */
fun categorize(path: String, name: String): FolderCategory {
    val p = path.lowercase(Locale.ROOT)
    val n = name.lowercase(Locale.ROOT)
    return when {
        "music video" in n || "musicvideo" in n || n == "mv" || n == "mvs" -> FolderCategory.MUSIC_VIDEOS
        "concert" in n || "live" == n || "live shows" in n -> FolderCategory.CONCERTS
        "series" in n || "tv" == n || "tv shows" in n || "episode" in n || "season" in n || "anime" in n -> FolderCategory.SERIES
        "/download" in p || n.startsWith("download") -> FolderCategory.DOWNLOADS
        "movie" in n || "film" in n || "/movies" in p -> FolderCategory.MOVIES
        else -> FolderCategory.OTHERS
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%d MB".format(bytes / (1L shl 20))
    bytes >= 1L shl 10 -> "%d KB".format(bytes / (1L shl 10))
    else -> "$bytes B"
}

fun formatSizeGb(bytes: Long): String = "%.1f GB".format(bytes / (1L shl 30).toDouble())

fun formatDate(sec: Long): String = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(sec * 1000))

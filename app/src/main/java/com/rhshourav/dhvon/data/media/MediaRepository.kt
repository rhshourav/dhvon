// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.data.media

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media as AudioMedia
import android.provider.MediaStore.Video.Media as VideoMedia
import androidx.core.net.toUri
import com.rhshourav.dhvon.data.settings.SettingsRepository
import com.rhshourav.dhvon.di.ApplicationScope
import com.rhshourav.dhvon.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface OpResult {
    data object Done : OpResult
    data class NeedsGrant(val sender: IntentSender) : OpResult
    data class Failed(val message: String) : OpResult
}

/**
 * Indexed MediaStore scanner (fast, no file walking) + SAF fallback for un-indexed folders / SD cards.
 * Never needs MANAGE_EXTERNAL_STORAGE: destructive operations go through the system consent dialogs
 * (MediaStore.createDeleteRequest / createWriteRequest).
 */
@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    data class Library(
        val videos: List<MediaFile> = emptyList(),
        val audios: List<MediaFile> = emptyList(),
        val loaded: Boolean = false,
    ) {
        val all: List<MediaFile> get() = videos + audios
        fun find(uri: String): MediaFile? = videos.firstOrNull { it.uri == uri } ?: audios.firstOrNull { it.uri == uri }
    }

    private val resolver: ContentResolver = context.contentResolver
    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()
    private val mutex = Mutex()
    private var started = false

    /** Begin indexing + live updates. Call once permission is granted (safe to call repeatedly). */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val saf = settings.settings.map { it.safTrees }.distinctUntilChanged().map { }
            merge(flowOf(Unit), mediaChanges(), saf).debounce(700).collect { refresh() }
        }
    }

    suspend fun refresh() = mutex.withLock {
        val trees = settings.settings.first().safTrees
        val lib = withContext(Dispatchers.IO) {
            val v = runCatching { queryVideos() }.getOrDefault(emptyList()).toMutableList()
            val a = runCatching { queryAudio() }.getOrDefault(emptyList()).toMutableList()
            trees.forEach { runCatching { scanTree(it.toUri(), v, a) } }
            Library(v.sortedByDescending { it.dateModifiedSec }, a.sortedByDescending { it.dateModifiedSec }, true)
        }
        _library.value = lib
    }

    private fun mediaChanges() = callbackFlow<Unit> {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, obs)
        resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, obs)
        awaitClose { resolver.unregisterContentObserver(obs) }
    }

    @Suppress("DEPRECATION")
    private fun queryVideos(): List<MediaFile> {
        val out = ArrayList<MediaFile>()
        val projection = arrayOf(VideoMedia._ID, VideoMedia.DISPLAY_NAME, VideoMedia.DURATION, VideoMedia.SIZE, VideoMedia.DATE_MODIFIED, VideoMedia.DATA, VideoMedia.WIDTH, VideoMedia.HEIGHT, VideoMedia.MIME_TYPE)
        resolver.query(VideoMedia.getContentUri("external"), projection, "${VideoMedia.SIZE} > 0", null, "${VideoMedia.DATE_MODIFIED} DESC")?.use { cur ->
            val iId = cur.getColumnIndexOrThrow(VideoMedia._ID); val iName = cur.getColumnIndexOrThrow(VideoMedia.DISPLAY_NAME)
            val iDur = cur.getColumnIndexOrThrow(VideoMedia.DURATION); val iSize = cur.getColumnIndexOrThrow(VideoMedia.SIZE)
            val iDate = cur.getColumnIndexOrThrow(VideoMedia.DATE_MODIFIED); val iData = cur.getColumnIndexOrThrow(VideoMedia.DATA)
            val iW = cur.getColumnIndexOrThrow(VideoMedia.WIDTH); val iH = cur.getColumnIndexOrThrow(VideoMedia.HEIGHT)
            val iMime = cur.getColumnIndexOrThrow(VideoMedia.MIME_TYPE)
            while (cur.moveToNext()) {
                val id = cur.getLong(iId)
                val name = cur.getString(iName) ?: "Video $id"
                val data = cur.getString(iData).orEmpty()
                out += MediaFile(
                    id = id, uri = ContentUris.withAppendedId(VideoMedia.getContentUri("external"), id).toString(),
                    title = name.substringBeforeLast('.'), displayName = name,
                    durationMs = cur.getLong(iDur), sizeBytes = cur.getLong(iSize), dateModifiedSec = cur.getLong(iDate),
                    folderPath = data.substringBeforeLast('/', "Unknown"), isVideo = true,
                    width = cur.getInt(iW), height = cur.getInt(iH), mimeType = cur.getString(iMime).orEmpty(),
                )
            }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun queryAudio(): List<MediaFile> {
        val out = ArrayList<MediaFile>()
        val projection = arrayOf(AudioMedia._ID, AudioMedia.DISPLAY_NAME, AudioMedia.TITLE, AudioMedia.ARTIST, AudioMedia.ALBUM, AudioMedia.ALBUM_ID, AudioMedia.DURATION, AudioMedia.SIZE, AudioMedia.DATE_MODIFIED, AudioMedia.DATA, AudioMedia.MIME_TYPE)
        val selection = "${AudioMedia.IS_RINGTONE}=0 AND ${AudioMedia.IS_NOTIFICATION}=0 AND ${AudioMedia.IS_ALARM}=0 AND ${AudioMedia.DURATION}>=5000 AND ${AudioMedia.SIZE}>0"
        resolver.query(AudioMedia.getContentUri("external"), projection, selection, null, "${AudioMedia.DATE_MODIFIED} DESC")?.use { cur ->
            val iId = cur.getColumnIndexOrThrow(AudioMedia._ID); val iName = cur.getColumnIndexOrThrow(AudioMedia.DISPLAY_NAME)
            val iTitle = cur.getColumnIndexOrThrow(AudioMedia.TITLE); val iArtist = cur.getColumnIndexOrThrow(AudioMedia.ARTIST)
            val iAlbum = cur.getColumnIndexOrThrow(AudioMedia.ALBUM); val iAlbumId = cur.getColumnIndexOrThrow(AudioMedia.ALBUM_ID)
            val iDur = cur.getColumnIndexOrThrow(AudioMedia.DURATION); val iSize = cur.getColumnIndexOrThrow(AudioMedia.SIZE)
            val iDate = cur.getColumnIndexOrThrow(AudioMedia.DATE_MODIFIED); val iData = cur.getColumnIndexOrThrow(AudioMedia.DATA)
            val iMime = cur.getColumnIndexOrThrow(AudioMedia.MIME_TYPE)
            while (cur.moveToNext()) {
                val id = cur.getLong(iId)
                val name = cur.getString(iName) ?: "Track $id"
                val artist = cur.getString(iArtist).orEmpty().let { if (it == "<unknown>") "" else it }
                val album = cur.getString(iAlbum).orEmpty().let { if (it == "<unknown>") "" else it }
                out += MediaFile(
                    id = id, uri = ContentUris.withAppendedId(AudioMedia.getContentUri("external"), id).toString(),
                    title = cur.getString(iTitle)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                    displayName = name, durationMs = cur.getLong(iDur), sizeBytes = cur.getLong(iSize),
                    dateModifiedSec = cur.getLong(iDate), folderPath = cur.getString(iData).orEmpty().substringBeforeLast('/', "Unknown"),
                    isVideo = false, artist = artist, album = album, albumId = cur.getLong(iAlbumId),
                    mimeType = cur.getString(iMime).orEmpty(),
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------- SAF fallback

    /** Walks a persisted SAF tree with one projected query per directory (much faster than DocumentFile). */
    private fun scanTree(tree: Uri, videos: MutableList<MediaFile>, audios: MutableList<MediaFile>) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = rootId.substringAfter(':').ifBlank { "Storage" }
        fun walk(docId: String, path: String, depth: Int) {
            if (depth > 8) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val dirs = ArrayList<Pair<String, String>>()
            resolver.query(children, cols, null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getString(0); val name = cur.getString(1) ?: continue; val mime = cur.getString(2).orEmpty()
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> dirs += id to name
                        mime.startsWith("video/") || mime.startsWith("audio/") -> {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            val isVideo = mime.startsWith("video/")
                            val meta = probe(docUri)
                            val f = MediaFile(
                                id = docUri.toString().hashCode().toLong(), uri = docUri.toString(),
                                title = name.substringBeforeLast('.'), displayName = name, durationMs = meta.durationMs,
                                sizeBytes = cur.getLong(3), dateModifiedSec = cur.getLong(4) / 1000, folderPath = "saf:$path",
                                isVideo = isVideo, width = meta.width, height = meta.height, mimeType = mime,
                            )
                            if (isVideo) videos += f else audios += f
                        }
                    }
                }
            }
            dirs.forEach { (id, name) -> walk(id, "$path/$name", depth + 1) }
        }
        walk(rootId, rootName, 0)
    }

    private data class Probe(val durationMs: Long, val width: Int, val height: Int)

    private fun probe(uri: Uri): Probe {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            Probe(
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
            )
        } catch (_: Exception) { Probe(0, 0, 0) } finally { runCatching { r.release() } }
    }

    // ---------------------------------------------------------------- storage + scoped file operations

    fun storageInfo(): StorageInfo = runCatching {
        val st = StatFs(Environment.getExternalStorageDirectory().path)
        StorageInfo(st.totalBytes - st.availableBytes, st.totalBytes)
    }.getOrDefault(StorageInfo(0, 0))

    fun isDocumentUri(uri: Uri): Boolean = runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)

    fun requestDelete(uris: List<Uri>): OpResult {
        val docs = uris.filter { DocumentsContract.isDocumentUri(context, it) }
        docs.forEach { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        val media = uris.filterNot { it in docs }
        if (media.isEmpty()) return OpResult.Done
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            OpResult.NeedsGrant(MediaStore.createDeleteRequest(resolver, media).intentSender)
        } else try {
            media.forEach { resolver.delete(it, null, null) }
            OpResult.Done
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException)
                OpResult.NeedsGrant(e.userAction.actionIntent.intentSender)
            else OpResult.Failed("Android blocked the delete")
        }
    }

    /** Rename/move need a write grant (Android 11+). Lower versions don't support these without broad storage access. */
    fun requestWrite(uris: List<Uri>): OpResult =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) OpResult.NeedsGrant(MediaStore.createWriteRequest(resolver, uris).intentSender)
        else OpResult.Failed("Rename and move need Android 11 or newer")

    fun renameGranted(uri: Uri, newName: String): Boolean = runCatching {
        if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.renameDocument(resolver, uri, newName) != null
        else resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null) > 0
    }.getOrDefault(false)

    fun moveGranted(uris: List<Uri>, relativePath: String): Boolean = runCatching {
        val rel = relativePath.trim().trim('/') + "/"
        uris.all { resolver.update(it, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, rel) }, null, null) > 0 }
    }.getOrDefault(false)

    companion object {
        fun foldersOf(files: List<MediaFile>): List<MediaFolder> =
            files.groupBy { it.folderPath }.map { (path, items) ->
                val name = path.substringAfterLast('/').ifBlank { path }
                MediaFolder(
                    path = path, name = name, itemCount = items.size, totalBytes = items.sumOf { it.sizeBytes },
                    lastModifiedSec = items.maxOf { it.dateModifiedSec },
                    coverUris = items.filter { it.isVideo }.take(4).map { it.uri },
                    videoCount = items.count { it.isVideo }, audioCount = items.count { !it.isVideo },
                    category = categorize(path, name),
                )
            }.sortedByDescending { it.lastModifiedSec }
    }
}

// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.player

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.rhshourav.dhvon.VideoPlayerActivity
import com.rhshourav.dhvon.data.local.PlaybackStateEntity
import com.rhshourav.dhvon.data.media.MediaRepository
import com.rhshourav.dhvon.data.settings.Keys
import com.rhshourav.dhvon.data.settings.SettingsRepository
import com.rhshourav.dhvon.data.user.UserDataRepository
import com.rhshourav.dhvon.di.ApplicationScope
import com.rhshourav.dhvon.domain.MediaFile
import com.rhshourav.dhvon.domain.durationHint
import com.rhshourav.dhvon.domain.isVideoItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** What the round button did when tapped. */
enum class ResumeResult { NONE, VIDEO, AUDIO }

data class QueueEntry(val uri: String, val title: String, val subtitle: String, val isVideo: Boolean, val durationMs: Long)

data class PlaybackUiState(
    val hasMedia: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val uri: String = "",
    val isVideo: Boolean = false,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = 0,
    val speed: Float = 1f,
)

/** UI-facing facade over the shared player: queue building, resume, transport and a Compose-friendly state flow. */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: PlayerHolder,
    private val userData: UserDataRepository,
    private val media: MediaRepository,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()
    val player: ExoPlayer get() = holder.player

    private var queueSaveJob: Job? = null
    /** The folder (or pseudo-folder) the current queue was started from — so auto-advance keeps its "last played" up to date. */
    private var queueContext: String? = null
    private var restored = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player, rebuildQueue = events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION))
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) scheduleQueueSave()
            else if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) saveSession(fullQueue = false)
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) recordLastPlayed(player.currentMediaItem)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) saveSession(fullQueue = false) }

        /** A queued/restored file that no longer exists shouldn't wedge the player: skip it, or clear the queue. */
        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND || error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION) {
                val p = holder.player
                if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare() } else { p.stop(); p.clearMediaItems() }
            }
        }
    }

    init {
        scope.launch { holder.playerFlow.collect { p -> p.addListener(listener); publish(p, true) } }
        // Position checkpoint every 5 s while playing, so a killed process loses at most a few seconds.
        scope.launch { while (true) { delay(5_000); if (holder.player.isPlaying) saveSession(fullQueue = false) } }
    }

    // ------------------------------------------------------------------ session persistence (background-play robustness)

    private fun scheduleQueueSave() {
        queueSaveJob?.cancel()
        queueSaveJob = scope.launch { delay(800); saveSession(fullQueue = true) }
    }

    private fun saveSession(fullQueue: Boolean) {
        val p = holder.player
        val index = p.currentMediaItemIndex.coerceAtLeast(0)
        val pos = p.currentPosition.coerceAtLeast(0L)
        val json = if (fullQueue) {
            JSONArray().also { arr ->
                for (i in 0 until p.mediaItemCount) {
                    val m = p.getMediaItemAt(i)
                    arr.put(JSONObject().put("u", m.mediaId).put("t", m.mediaMetadata.title?.toString().orEmpty())
                        .put("a", m.mediaMetadata.artist?.toString().orEmpty()).put("v", m.isVideoItem).put("d", m.durationHint))
                }
            }.toString()
        } else null
        scope.launch {
            if (json != null) settings.set(Keys.SESSION_QUEUE, json)
            settings.set(Keys.SESSION_INDEX, index)
            settings.set(Keys.SESSION_POS, pos)
        }
    }

    /**
     * Brings back the last queue (paused, at the saved position) after the process was killed.
     * Called once media access is available; does nothing if something is already loaded or the user turned it off.
     */
    fun restoreLastSession() {
        if (restored) return
        restored = true
        scope.launch {
            val prefs = settings.settings.first()
            if (!prefs.restoreSession || holder.player.mediaItemCount > 0) return@launch
            val json = settings.get(Keys.SESSION_QUEUE).orEmpty()
            if (json.length < 3) return@launch
            val index = settings.get(Keys.SESSION_INDEX) ?: 0
            val pos = settings.get(Keys.SESSION_POS) ?: 0L
            val files = runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    MediaFile(0, o.getString("u"), o.optString("t"), o.optString("t"), o.optLong("d"), 0, 0, "", o.optBoolean("v"), artist = o.optString("a"))
                }
            }.getOrNull().orEmpty()
            if (files.isEmpty()) return@launch
            holder.setDecoderMode(prefs.decoderMode)
            val p = holder.player
            p.setMediaItems(files.map { it.toMediaItem() }, index.coerceIn(files.indices), pos)
            p.playWhenReady = false
            p.prepare()
        }
    }

    /** Remembers the playing file for its own folder and for the folder view it was started from. */
    private fun recordLastPlayed(item: MediaItem?) {
        val m = item ?: return
        val uri = m.mediaId
        val title = m.mediaMetadata.title?.toString().orEmpty()
        val physical = media.library.value.find(uri)?.folderPath?.takeIf { it.isNotBlank() }
        val ctx = queueContext
        scope.launch {
            if (physical != null) userData.setFolderLastPlayed(physical, uri, title)
            if (ctx != null && ctx != physical) userData.setFolderLastPlayed(ctx, uri, title)
        }
    }

    private fun openVideoUi() {
        context.startActivity(Intent(context, VideoPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * The round button: resume whatever was playing last.
     * A loaded (paused / restored) queue just plays; otherwise the most recently played file is started inside its own
     * folder so playback continues with the files that follow it. Audio → caller opens Now Playing; video opens itself.
     */
    suspend fun resumeLast(): ResumeResult {
        val cur = state.value
        if (cur.hasMedia) {
            ensureService()
            val p = holder.player
            if (!p.isPlaying) { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
            return if (cur.isVideo) { openVideoUi(); ResumeResult.VIDEO } else ResumeResult.AUDIO
        }
        val lib = media.library.value
        val last = userData.recent.first().firstNotNullOfOrNull { e -> lib.find(e.uri) } ?: return ResumeResult.NONE
        val siblings = lib.all.filter { it.folderPath == last.folderPath && it.isVideo == last.isVideo }
            .sortedBy { it.displayName.lowercase() }.ifEmpty { listOf(last) }
        val idx = siblings.indexOfFirst { it.uri == last.uri }.coerceAtLeast(0)
        return if (last.isVideo) { playVideo(siblings, idx, last.folderPath); ResumeResult.VIDEO }
        else { playAudio(siblings, idx, false, last.folderPath); ResumeResult.AUDIO }
    }

    private fun ensureService() { runCatching { context.startService(Intent(context, DhvonPlaybackService::class.java)) } }

    private fun publish(p: Player, rebuildQueue: Boolean) {
        val item = p.currentMediaItem
        val old = _state.value
        val queue = if (rebuildQueue || old.queue.size != p.mediaItemCount) {
            (0 until p.mediaItemCount).map {
                val m = p.getMediaItemAt(it)
                QueueEntry(m.mediaId, m.mediaMetadata.title?.toString().orEmpty(), m.mediaMetadata.artist?.toString().orEmpty(), m.isVideoItem, m.durationHint)
            }
        } else old.queue
        _state.value = PlaybackUiState(
            hasMedia = item != null,
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            subtitle = item?.mediaMetadata?.artist?.toString().orEmpty(),
            uri = item?.mediaId.orEmpty(),
            isVideo = item?.isVideoItem == true,
            isPlaying = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            durationMs = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: item?.durationHint ?: 0L,
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            queue = queue,
            currentIndex = p.currentMediaItemIndex,
            speed = p.playbackParameters.speed,
        )
    }

    // ------------------------------------------------------------------ starting playback

    /** Opens the video player; resumes from the last saved position when enabled in settings. */
    fun playVideo(files: List<MediaFile>, index: Int, folderContext: String? = null) {
        if (files.isEmpty()) return
        scope.launch {
            val resume = if (settings.settings.first().resumePlayback) resumePosition(files[index].uri) else null
            setQueue(files, index, resume ?: C.TIME_UNSET, shuffle = false, folderContext = folderContext)
            context.startActivity(Intent(context, VideoPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Plays [file] inside [list] (queue = every item of the same type, in list order). */
    fun playFrom(file: MediaFile, list: List<MediaFile>, folderContext: String? = null) {
        val same = list.filter { it.isVideo == file.isVideo }.ifEmpty { listOf(file) }
        val idx = same.indexOfFirst { it.uri == file.uri }.coerceAtLeast(0)
        if (file.isVideo) playVideo(same, idx, folderContext) else playAudio(same, idx, false, folderContext)
    }

    fun playAudio(files: List<MediaFile>, index: Int, shuffle: Boolean = false, folderContext: String? = null) {
        if (files.isEmpty()) return
        val start = if (shuffle) Random.nextInt(files.size) else index.coerceIn(files.indices)
        scope.launch { setQueue(files, start, C.TIME_UNSET, shuffle, folderContext) }
    }

    /** Mixed audio/video queues (playlists). Opens the video screen only when the starting item is a video. */
    fun playMixed(files: List<MediaFile>, index: Int, shuffle: Boolean = false) {
        if (files.isEmpty()) return
        val start = if (shuffle) Random.nextInt(files.size) else index.coerceIn(files.indices)
        scope.launch {
            val first = files[start]
            val resume = if (first.isVideo && settings.settings.first().resumePlayback) resumePosition(first.uri) else null
            setQueue(files, start, resume ?: C.TIME_UNSET, shuffle)
            if (first.isVideo) context.startActivity(Intent(context, VideoPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** ACTION_VIEW / "Open with DHVON". Audio files opened this way are queued as audio (the video screen shows its audio mode). */
    fun playExternal(uri: String, title: String, isVideo: Boolean = true) {
        val f = MediaFile(0, uri, title, title, 0, 0, 0, "", isVideo = isVideo)
        scope.launch {
            val resume = if (settings.settings.first().resumePlayback) resumePosition(uri) else null
            setQueue(listOf(f), 0, resume ?: C.TIME_UNSET, false)
        }
    }

    private suspend fun resumePosition(uri: String): Long? =
        userData.positionFor(uri)?.takeIf { it.positionMs > 5_000 && (it.durationMs <= 0 || it.positionMs < it.durationMs * 0.95) }?.positionMs

    private suspend fun setQueue(files: List<MediaFile>, index: Int, startMs: Long, shuffle: Boolean, folderContext: String? = null) {
        queueContext = folderContext
        persistCurrent()
        val prefs = settings.settings.first()
        holder.setDecoderMode(prefs.decoderMode)   // rebuilds the player only if the mode actually changed
        holder.setSeekSnap(prefs.seekSnap)
        val p = holder.player
        p.setMediaItems(files.map { it.toMediaItem() }, index, startMs)
        p.shuffleModeEnabled = shuffle
        p.prepare()
        p.playWhenReady = true
        ensureService()
    }

    private fun persistCurrent() {
        val p = holder.player
        val item = p.currentMediaItem ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: item.durationHint
        val pos = p.currentPosition
        scope.launch {
            userData.savePosition(PlaybackStateEntity(item.mediaId, item.mediaMetadata.title?.toString().orEmpty(), item.isVideoItem, pos, dur, System.currentTimeMillis()))
        }
    }

    /** Resume position reported by an engine other than ExoPlayer (the LibVLC software fallback). */
    fun recordExternalPosition(positionMs: Long, durationMs: Long, finished: Boolean = false) {
        val item = holder.player.currentMediaItem ?: return
        val dur = durationMs.takeIf { it > 0 } ?: item.durationHint
        scope.launch {
            userData.savePosition(
                PlaybackStateEntity(
                    item.mediaId, item.mediaMetadata.title?.toString().orEmpty(), item.isVideoItem,
                    if (finished) 0L else positionMs.coerceAtLeast(0L), dur, System.currentTimeMillis(),
                )
            )
        }
    }

    // ------------------------------------------------------------------ transport

    fun togglePlayPause() {
        val p = holder.player
        if (p.isPlaying) p.pause() else { ensureService(); if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
    }
    fun next() = holder.player.seekToNextMediaItem()
    fun previous() { val p = holder.player; if (p.currentPosition > 3_000) p.seekTo(0) else p.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = holder.player.seekTo(ms.coerceAtLeast(0))
    fun seekBy(deltaMs: Long) {
        val p = holder.player
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, dur))
    }
    fun skipTo(index: Int) = holder.player.seekTo(index, C.TIME_UNSET)
    fun toggleShuffle() { val p = holder.player; p.shuffleModeEnabled = !p.shuffleModeEnabled }
    /** Off → Loop All → Loop One. */
    fun cycleRepeat() {
        val p = holder.player
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun setRepeat(mode: Int) { holder.player.repeatMode = mode }
    fun setSpeed(speed: Float) = holder.player.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
    fun stopAndClear() { persistCurrent(); holder.player.stop(); holder.player.clearMediaItems() }

    // ------------------------------------------------------------------ queue editing

    fun playNext(file: MediaFile) {
        val p = holder.player
        if (p.mediaItemCount == 0) playAudio(listOf(file), 0)
        else p.addMediaItem((p.currentMediaItemIndex + 1).coerceAtMost(p.mediaItemCount), file.toMediaItem())
    }
    fun addToQueue(file: MediaFile) {
        val p = holder.player
        if (p.mediaItemCount == 0) playAudio(listOf(file), 0) else p.addMediaItem(file.toMediaItem())
    }
    fun moveQueueItem(from: Int, to: Int) = holder.player.moveMediaItem(from, to)
    fun removeFromQueue(index: Int) = holder.player.removeMediaItem(index)
}

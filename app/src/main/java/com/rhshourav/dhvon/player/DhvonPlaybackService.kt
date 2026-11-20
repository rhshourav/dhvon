// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.rhshourav.dhvon.MainActivity
import com.rhshourav.dhvon.R
import com.rhshourav.dhvon.data.local.PlaybackStateEntity
import com.rhshourav.dhvon.data.user.UserDataRepository
import com.rhshourav.dhvon.domain.durationHint
import com.rhshourav.dhvon.domain.isVideoItem
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * Foreground MediaSessionService: owns the MediaSession around the shared ExoPlayer, which gives us
 * background audio, the lock-screen / notification controls, Bluetooth + headset-button handling and audio focus
 * (focus + "becoming noisy" are configured on the player itself). Resume positions are persisted to Room here.
 */
@AndroidEntryPoint
class DhvonPlaybackService : MediaSessionService() {

    @Inject lateinit var holder: PlayerHolder
    @Inject lateinit var userData: UserDataRepository
    @Inject lateinit var effects: AudioEffectsController

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var recorder: PlaybackRecorder

    override fun onCreate() {
        super.onCreate()
        effects.start()
        recorder = PlaybackRecorder(userData, scope)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_dhvon_mono) }
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val mediaSession = MediaSession.Builder(this, holder.player).setId("dhvon").setSessionActivity(openApp).build()
        session = mediaSession
        // The UI drives the shared player directly (no MediaController ever connects), so Media3 would never learn about this
        // session and would never post the media notification. Registering it here is what puts the controls
        // on the lock screen / shade — including when video is playing in background-audio mode.
        addSession(mediaSession)
        // Decoder switches rebuild the player — keep the session (and the recorder) pointed at the live one.
        scope.launch {
            holder.playerFlow.collect { p ->
                session?.player = p
                recorder.attach(p)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing only if audio is actually playing; otherwise there is nothing to keep alive.
        if (!holder.player.playWhenReady || holder.player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        recorder.flushNow()
        holder.player.pause()
        session?.release()
        session = null
        scope.cancel()
        super.onDestroy()
    }
}

/** Saves resume position every 5 s while playing, on pause, and on end/auto-advance. */
class PlaybackRecorder(private val repo: UserDataRepository, private val scope: CoroutineScope) : Player.Listener {
    private var player: Player? = null
    private var ticker: Job? = null

    fun attach(p: Player) {
        player?.removeListener(this)
        player = p
        p.addListener(this)
        ticker?.cancel()
        ticker = scope.launch { while (isActive) { delay(5_000); if (p.isPlaying) save() } }
    }

    fun flushNow() = save()

    override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) save() }

    override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) save(finished = true) }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            oldPosition.mediaItem?.let { persist(it, 0L, it.durationHint) } // finished → counts as watched
        }
    }

    private fun save(finished: Boolean = false) {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: item.durationHint
        persist(item, if (finished) 0L else p.currentPosition, duration)
    }

    private fun persist(item: MediaItem, positionMs: Long, durationMs: Long) {
        scope.launch {
            repo.savePosition(
                PlaybackStateEntity(
                    uri = item.mediaId, title = item.mediaMetadata.title?.toString().orEmpty(),
                    isVideo = item.isVideoItem, positionMs = positionMs, durationMs = durationMs,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }
}

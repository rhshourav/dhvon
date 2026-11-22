// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.player

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.rhshourav.dhvon.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sleep timer: after N minutes (or at the end of the current file) playback fades out and pauses.
 * With `closeApp` it then also stops the playback service and closes every DHVON screen, removing it from recents —
 * so the phone really is left alone. Your queue and position are saved first, so DHVON can restore them next time.
 */
@Singleton
class SleepTimer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: PlayerHolder,
    @ApplicationScope private val scope: CoroutineScope,
) {
    data class State(val active: Boolean = false, val remainingMs: Long = 0, val endOfTrack: Boolean = false, val closeApp: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private var eotListener: Player.Listener? = null
    private var eotPlayer: ExoPlayer? = null

    fun startMinutes(minutes: Int, closeApp: Boolean = false) {
        cancel()
        val end = System.currentTimeMillis() + minutes * 60_000L
        job = scope.launch {
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                _state.value = State(active = true, remainingMs = left, closeApp = closeApp)
                delay(1_000)
            }
            fadeOutAndPause()
            _state.value = State()
            if (closeApp) shutDownApp()
        }
    }

    fun startEndOfTrack(closeApp: Boolean = false) {
        cancel()
        val p = holder.player
        p.pauseAtEndOfMediaItems = true
        val l = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    val close = _state.value.closeApp
                    cancel()
                    if (close) shutDownApp()
                }
            }
        }
        p.addListener(l)
        eotListener = l; eotPlayer = p
        _state.value = State(active = true, endOfTrack = true, closeApp = closeApp)
    }

    fun cancel() {
        job?.cancel(); job = null
        eotListener?.let { eotPlayer?.removeListener(it); eotPlayer?.pauseAtEndOfMediaItems = false }
        eotListener = null; eotPlayer = null
        _state.value = State()
    }

    private suspend fun fadeOutAndPause() {
        val p = holder.player
        val start = p.volume
        for (i in 1..16) { p.volume = start * (1f - i / 16f); delay(250) }
        p.pause()
        p.volume = start
    }

    /** Stop the service and finish + remove every DHVON task (the video screen lives in its own task). */
    private fun shutDownApp() {
        holder.player.playWhenReady = false
        runCatching { context.stopService(Intent(context, DhvonPlaybackService::class.java)) }
        runCatching { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).appTasks.forEach { it.finishAndRemoveTask() } }
    }
}

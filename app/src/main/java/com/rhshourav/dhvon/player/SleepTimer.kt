// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.rhshourav.dhvon.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Sleep timer: pause after N minutes (with a 4 s fade-out) or at the end of the current track. */
@Singleton
class SleepTimer @Inject constructor(
    private val holder: PlayerHolder,
    @ApplicationScope private val scope: CoroutineScope,
) {
    data class State(val active: Boolean = false, val remainingMs: Long = 0, val endOfTrack: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private var eotListener: Player.Listener? = null
    private var eotPlayer: ExoPlayer? = null

    fun startMinutes(minutes: Int) {
        cancel()
        val end = System.currentTimeMillis() + minutes * 60_000L
        job = scope.launch {
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                _state.value = State(active = true, remainingMs = left)
                delay(1_000)
            }
            fadeOutAndPause()
            _state.value = State()
        }
    }

    fun startEndOfTrack() {
        cancel()
        val p = holder.player
        p.pauseAtEndOfMediaItems = true
        val l = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) cancel()
            }
        }
        p.addListener(l)
        eotListener = l; eotPlayer = p
        _state.value = State(active = true, endOfTrack = true)
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
}

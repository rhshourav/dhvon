// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rhshourav.dhvon.data.media.MediaKind
import com.rhshourav.dhvon.data.media.SupportedMediaFormats
import com.rhshourav.dhvon.data.settings.AppSettings
import com.rhshourav.dhvon.data.settings.SettingsRepository
import com.rhshourav.dhvon.data.settings.ThemeMode
import com.rhshourav.dhvon.player.PlaybackController
import com.rhshourav.dhvon.player.PlayerHolder
import com.rhshourav.dhvon.ui.screens.video.PlayerHost
import com.rhshourav.dhvon.ui.screens.video.VideoPlayerScreen
import com.rhshourav.dhvon.ui.screens.video.VideoPlayerViewModel
import com.rhshourav.dhvon.ui.theme.DhvonTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Full-screen video activity. It owns the things only an Activity can do: immersive mode, orientation, window
 * brightness, picture-in-picture, and the background-audio hand-off when you leave. By rhshourav.
 */
@AndroidEntryPoint
class VideoPlayerActivity : ComponentActivity(), PlayerHost {

    @Inject lateinit var holder: PlayerHolder
    @Inject lateinit var playback: PlaybackController
    @Inject lateinit var settingsRepo: SettingsRepository

    private val vm: VideoPlayerViewModel by viewModels()
    private val inPip = mutableStateOf(false)
    private var settings = AppSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        handleIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepo.settings.collect {
                    settings = it
                    if (it.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        setContent {
            val s by settingsRepo.settings.collectAsState(AppSettings())
            // The player is always dark, even when the app theme is Light.
            DhvonTheme(if (s.themeMode == ThemeMode.LIGHT) ThemeMode.OBSIDIAN else s.themeMode, s.accentCyan, s.compactDensity, s.accentArgb, s.textScale) {
                VideoPlayerScreen(vm, this@VideoPlayerActivity, inPip.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A fresh open starts from the phone's own brightness — not from whatever the previous video was dimmed to.
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        vm.resetDisplayToPhoneLevel()
        handleIntent(intent)
    }

    /** "Open with DHVON" from a file manager / browser download. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        val name = runCatching {
            contentResolver.query(data, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: data.lastPathSegment ?: "Video"
        // Audio opened from a file manager gets the audio-mode screen; a video resets it (the activity is single-task).
        val isAudio = SupportedMediaFormats.classify(name, runCatching { contentResolver.getType(data) }.getOrNull()) == MediaKind.AUDIO
        vm.setAudioMode(isAudio)
        playback.playExternal(data.toString(), name.substringBeforeLast('.'), isVideo = !isAudio)
    }

    // ---------------------------------------------------------------- lifecycle: PiP + background audio

    override fun onStart() {
        super.onStart()
        holder.setVideoEnabled(!vm.audioMode.value)   // audio mode keeps the video renderer asleep
        applyImmersive()
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        when {
            isInPictureInPictureMode -> holder.player.pause()                 // the PiP window was dismissed
            vm.audioMode.value || settings.backgroundPlay -> holder.setVideoEnabled(false)   // audio carries on, video renderer sleeps
            else -> holder.player.pause()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (settings.autoPip && holder.player.isPlaying && !isInPictureInPictureMode && !vm.audioMode.value) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    private fun applyImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------------------------------------------------------- PlayerHost

    override fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        // No window override yet → this is the phone's own brightness setting (0–255).
        return runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f).coerceIn(0.01f, 1f)
    }

    override fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.01f, 1f) }
    }

    override fun toggleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    override fun lockOrientationFor(width: Int, height: Int) {
        requestedOrientation = if (width >= height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    override fun enterPip() {
        if (vm.audioMode.value) return
        val size = holder.player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) (size.width.toFloat() / size.height).coerceIn(0.42f, 2.39f) else 16f / 9f
        runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000)).build())
        }
    }

    override fun finishPlayer() { finish() }
}

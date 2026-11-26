// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhshourav.dhvon.data.media.ArtworkLoader
import com.rhshourav.dhvon.data.media.MediaRepository
import com.rhshourav.dhvon.data.settings.AppSettings
import com.rhshourav.dhvon.data.settings.SettingsRepository
import com.rhshourav.dhvon.data.user.UserDataRepository
import com.rhshourav.dhvon.player.AudioEffectsController
import com.rhshourav.dhvon.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    val playback: PlaybackController,
    private val settingsRepo: SettingsRepository,
    private val media: MediaRepository,
    private val effects: AudioEffectsController,
    private val user: UserDataRepository,
    val artwork: ArtworkLoader,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** URIs that currently carry the NEW badge; provided to the whole UI through LocalNewUris. */
    val newUris: StateFlow<Set<String>> = user.newUris.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Called once the user has granted media access (or picked a SAF folder). */
    fun onMediaAccess() { media.start(); effects.start(); playback.restoreLastSession() }

    fun addSafTree(uri: Uri) { viewModelScope.launch { settingsRepo.addSafTree(uri.toString()) } }
}

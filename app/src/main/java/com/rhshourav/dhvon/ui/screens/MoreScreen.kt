// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhshourav.dhvon.player.SleepTimer
import com.rhshourav.dhvon.ui.components.DhvonWordmark
import com.rhshourav.dhvon.ui.components.SilverIcon
import com.rhshourav.dhvon.ui.components.WaveGlow
import com.rhshourav.dhvon.ui.nav.Routes
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MoreViewModel @Inject constructor(val sleep: SleepTimer) : ViewModel()

@Composable
fun MoreScreen(onNavigate: (String) -> Unit, onSwitchTab: (String) -> Unit, vm: MoreViewModel = hiltViewModel()) {
    val p = LocalDhvon.current
    val timer by vm.sleep.state.collectAsStateWithLifecycle()
    var sleepSheet by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(p.background)) {
        WaveGlow(Modifier.fillMaxWidth().height(260.dp).align(Alignment.BottomCenter))
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DhvonWordmark(Modifier.padding(top = 20.dp, bottom = 18.dp), logoHeight = 44.dp, fontSize = 24)
            MoreItem(Icons.Rounded.VideoLibrary, "Library", null) { onSwitchTab(Routes.LIBRARY) }
            MoreItem(Icons.AutoMirrored.Rounded.PlaylistPlay, "Playlists", null) { onSwitchTab(Routes.PLAYLISTS) }
            MoreItem(Icons.Rounded.Equalizer, "Equalizer", null) { onNavigate(Routes.EQUALIZER) }
            MoreItem(Icons.Rounded.Timer, "Sleep Timer", if (timer.active) (if (timer.endOfTrack) "End of track" else "${timer.remainingMs / 60_000 + 1} min left") else null) { sleepSheet = true }
            MoreItem(Icons.Rounded.Settings, "UI & Gesture Settings", null) { onNavigate(Routes.SETTINGS) }
            MoreItem(Icons.Rounded.Info, "About DHVON", null) { onNavigate(Routes.ABOUT) }
        }
    }

    if (sleepSheet) ModalBottomSheet({ sleepSheet = false }, containerColor = p.card) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sleep timer", color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
            Text(if (timer.active) "Playback will pause automatically." else "Pause playback automatically.", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            listOf(5, 10, 15, 30, 45, 60).forEach { m -> SleepOption("$m minutes") { vm.sleep.startMinutes(m); sleepSheet = false } }
            SleepOption("End of current track") { vm.sleep.startEndOfTrack(); sleepSheet = false }
            if (timer.active) SleepOption("Turn off timer") { vm.sleep.cancel(); sleepSheet = false }
        }
    }
}

@Composable
private fun SleepOption(label: String, onClick: () -> Unit) {
    val p = LocalDhvon.current
    Text(label, color = p.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp))
}

@Composable
private fun MoreItem(icon: ImageVector, title: String, trailing: String?, onClick: () -> Unit) {
    val p = LocalDhvon.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card.copy(alpha = 0.92f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(p.elevated), contentAlignment = Alignment.Center) { SilverIcon(icon, null, Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = p.accent, style = MaterialTheme.typography.labelLarge)
    }
}

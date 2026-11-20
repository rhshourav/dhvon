// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhshourav.dhvon.data.media.MediaRepository
import com.rhshourav.dhvon.domain.MediaFile
import com.rhshourav.dhvon.player.PlaybackController
import com.rhshourav.dhvon.ui.components.EmptyState
import com.rhshourav.dhvon.ui.components.MediaListRow
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(media: MediaRepository, val playback: PlaybackController) : ViewModel() {
    val query = MutableStateFlow("")
    val results: StateFlow<List<MediaFile>> = combine(media.library, query.debounce(150)) { lib, q ->
        val t = q.trim()
        if (t.length < 2) emptyList()
        else lib.all.filter { it.title.contains(t, true) || it.artist.contains(t, true) || it.album.contains(t, true) || it.displayName.contains(t, true) }.take(150)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun SearchScreen(onBack: () -> Unit, onOpenNowPlaying: () -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val p = LocalDhvon.current
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().background(p.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = p.textPrimary) }
            BasicTextField(
                query, { vm.query.value = it }, singleLine = true, modifier = Modifier.weight(1f).focusRequester(focus),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.textPrimary), cursorBrush = SolidColor(p.accent),
                decorationBox = { inner -> if (query.isEmpty()) Text("Search videos, songs, artists…", color = p.textSecondary); inner() },
            )
            if (query.isNotEmpty()) IconButton({ vm.query.value = "" }) { Icon(Icons.Rounded.Close, "Clear", tint = p.textSecondary) }
        }
        if (query.trim().length >= 2 && results.isEmpty()) EmptyState(Icons.Rounded.Search, "No results", "Nothing matches “${query.trim()}”.")
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(results, key = { it.uri }) { f ->
                MediaListRow(f, false, false, { vm.playback.playFrom(f, results); if (!f.isVideo) onOpenNowPlaying() }, {})
            }
        }
    }
}

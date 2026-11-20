// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhshourav.dhvon.data.local.PlaylistEntity
import com.rhshourav.dhvon.data.local.PlaylistItemEntity
import com.rhshourav.dhvon.data.local.PlaylistSummary
import com.rhshourav.dhvon.data.user.UserDataRepository
import com.rhshourav.dhvon.data.user.toMediaFile
import com.rhshourav.dhvon.domain.formatDuration
import com.rhshourav.dhvon.player.PlaybackController
import com.rhshourav.dhvon.ui.components.*
import com.rhshourav.dhvon.ui.nav.Routes
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(private val user: UserDataRepository) : ViewModel() {
    val playlists: StateFlow<List<PlaylistSummary>> = user.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favoriteCount: StateFlow<Int> = user.favorites.map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    fun create(name: String) = viewModelScope.launch { user.createPlaylist(name) }
    fun rename(id: Long, name: String) = viewModelScope.launch { user.renamePlaylist(id, name) }
    fun delete(id: Long) = viewModelScope.launch { user.deletePlaylist(id) }
}

@Composable
fun PlaylistsScreen(onOpenPlaylist: (Long) -> Unit, onOpenFolder: (String) -> Unit, vm: PlaylistsViewModel = hiltViewModel()) {
    val p = LocalDhvon.current
    val lists by vm.playlists.collectAsStateWithLifecycle()
    val favs by vm.favoriteCount.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistSummary?>(null) }
    var deleting by remember { mutableStateOf<PlaylistSummary?>(null) }

    Box(Modifier.fillMaxSize().background(p.background)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Text("Playlists", color = p.textPrimary, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 16.dp, bottom = 12.dp))
            }
            item {
                PlaylistRow(Icons.Rounded.Favorite, "Favorites", "$favs items", Modifier.clickable { onOpenFolder(Routes.FAVORITES) }, null)
            }
            if (lists.isEmpty()) item { EmptyState(Icons.AutoMirrored.Rounded.PlaylistPlay, "No playlists yet", "Create a playlist, then add songs or videos from any folder.") }
            items(lists, key = { it.id }) { pl ->
                var menu by remember { mutableStateOf(false) }
                PlaylistRow(Icons.AutoMirrored.Rounded.PlaylistPlay, pl.name, "${pl.itemCount} items", Modifier.clickable { onOpenPlaylist(pl.id) }) {
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, null, tint = p.textSecondary) }
                        DropdownMenu(menu, { menu = false }, containerColor = p.elevated) {
                            DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = pl }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                            DropdownMenuItem({ Text("Delete") }, { menu = false; deleting = pl }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { creating = true }, containerColor = p.accent, contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Rounded.Add, "New playlist") }
    }
    if (creating) TextInputDialog("New playlist", "", "Name", "Create", { vm.create(it); creating = false }, { creating = false })
    renaming?.let { pl -> TextInputDialog("Rename playlist", pl.name, "Name", "Save", { vm.rename(pl.id, it); renaming = null }, { renaming = null }) }
    deleting?.let { pl -> ConfirmDialog("Delete “${pl.name}”?", "The files themselves are not deleted.", "Delete", { vm.delete(pl.id); deleting = null }, { deleting = null }) }
}

@Composable
private fun PlaylistRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, modifier: Modifier, trailing: (@Composable () -> Unit)?) {
    val p = LocalDhvon.current
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(p.card), contentAlignment = Alignment.Center) { SilverIcon(icon, null, Modifier.size(26.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        trailing?.invoke()
    }
}

// ---------------------------------------------------------------------------------------------------- detail

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val user: UserDataRepository,
    val playback: PlaybackController,
) : ViewModel() {
    val id: Long = savedState.get<Long>("id") ?: 0L
    val playlist: StateFlow<PlaylistEntity?> = user.playlist(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val items: StateFlow<List<PlaylistItemEntity>> = user.playlistItems(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun persistOrder(list: List<PlaylistItemEntity>) = viewModelScope.launch { user.reorder(list) }
    fun remove(item: PlaylistItemEntity) = viewModelScope.launch { user.removePlaylistItem(item.id) }
    fun play(list: List<PlaylistItemEntity>, index: Int, shuffle: Boolean = false) = playback.playMixed(list.map { it.toMediaFile() }, index, shuffle)
}

@Composable
fun PlaylistDetailScreen(onBack: () -> Unit, onOpenNowPlaying: () -> Unit, vm: PlaylistDetailViewModel = hiltViewModel()) {
    val p = LocalDhvon.current
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val stored by vm.items.collectAsStateWithLifecycle()
    // Local copy so the list reorders instantly while dragging; persisted to Room on drop.
    val local = remember { mutableStateListOf<PlaylistItemEntity>() }
    LaunchedEffect(stored) { local.clear(); local.addAll(stored) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to -> local.add(to.index, local.removeAt(from.index)) }

    fun play(index: Int, shuffle: Boolean = false) {
        vm.play(local.toList(), index, shuffle)
        if (!shuffle && local.getOrNull(index)?.isVideo == false) onOpenNowPlaying()
        if (shuffle) onOpenNowPlaying()
    }

    Column(Modifier.fillMaxSize().background(p.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = p.textPrimary) }
            Text(playlist?.name.orEmpty(), color = p.textPrimary, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (local.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ play(0) }, colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White)) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play all")
                }
                OutlinedButton({ play(0, shuffle = true) }) { Icon(Icons.Rounded.Shuffle, null, tint = p.textPrimary); Spacer(Modifier.width(6.dp)); Text("Shuffle", color = p.textPrimary) }
            }
        }
        if (local.isEmpty()) EmptyState(Icons.AutoMirrored.Rounded.PlaylistPlay, "This playlist is empty", "Long-press files in any folder, then choose “Add to playlist”.")
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            items(local, key = { it.id }) { item ->
                ReorderableItem(reorder, key = item.id) { dragging ->
                    Row(
                        Modifier.fillMaxWidth().shadow(if (dragging) 8.dp else 0.dp).background(if (dragging) p.elevated else p.background)
                            .clickable { play(local.indexOf(item)) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton({}, modifier = Modifier.draggableHandle(onDragStopped = { vm.persistOrder(local.toList()) })) {
                            Icon(Icons.Rounded.DragHandle, "Reorder", tint = p.textSecondary)
                        }
                        MediaThumb(item.toMediaFile(), Modifier.size(width = if (item.isVideo) 84.dp else 52.dp, height = 52.dp), RoundedCornerShape(10.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = p.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(if (item.isVideo) "Video" else item.artist.ifBlank { "Audio" }, formatDuration(item.durationMs)).joinToString(" • "), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton({ vm.remove(item) }) { Icon(Icons.Rounded.Close, "Remove", tint = p.textSecondary) }
                    }
                }
            }
        }
    }
}

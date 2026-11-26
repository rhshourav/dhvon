// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhshourav.dhvon.data.local.PlaylistSummary
import com.rhshourav.dhvon.data.media.MediaRepository
import com.rhshourav.dhvon.data.media.OpResult
import com.rhshourav.dhvon.data.settings.*
import com.rhshourav.dhvon.data.user.UserDataRepository
import com.rhshourav.dhvon.domain.MediaFile
import com.rhshourav.dhvon.player.PlaybackController
import com.rhshourav.dhvon.ui.components.*
import com.rhshourav.dhvon.ui.nav.Routes
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface PendingOp {
    /** [folderPath] is set when a whole folder is being deleted, so the empty directory can be removed afterwards. */
    data class Delete(val uris: List<Uri>, val folderPath: String? = null) : PendingOp
    data class Rename(val uri: Uri, val newName: String) : PendingOp
    data class Move(val uris: List<Uri>, val relativePath: String) : PendingOp
}

sealed interface FileEvent {
    data class Grant(val sender: IntentSender, val op: PendingOp) : FileEvent
    data class Message(val text: String) : FileEvent
    /** Android 9 and older: ask for the legacy storage permission, then retry [op]. */
    data class NeedWritePermission(val op: PendingOp) : FileEvent
}

@HiltViewModel
class FolderViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val media: MediaRepository,
    private val settingsRepo: SettingsRepository,
    private val user: UserDataRepository,
    val playback: PlaybackController,
) : ViewModel() {
    val path: String = Routes.decodePath(savedState.get<String>("path").orEmpty())
    val title: String = when (path) {
        Routes.ALL_VIDEOS -> "Videos"; Routes.ALL_MUSIC -> "Music"; Routes.FAVORITES -> "Favorites"
        else -> path.removePrefix("saf:").substringAfterLast('/').ifBlank { path }
    }

    /** The file that was playing last in this folder — highlighted, scrolled to, and offered as "Continue". */
    val lastPlayed: StateFlow<String?> = user.lastPlayedIn(path).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val query = MutableStateFlow("")
    /** Selected URIs in the order they were tapped (that order is the "Play in order" queue). */
    private val _selected = MutableStateFlow<List<String>>(emptyList())
    val selected: StateFlow<List<String>> = _selected.asStateFlow()
    private val _events = Channel<FileEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    data class UiState(
        val items: List<MediaFile> = emptyList(), val loaded: Boolean = false, val settings: AppSettings = AppSettings(),
        val favorites: Set<String> = emptySet(), val playlists: List<PlaylistSummary> = emptyList(),
    )

    val state: StateFlow<UiState> = combine(media.library, query, settingsRepo.settings, user.favorites, user.playlists) { lib, q, s, favs, pls ->
        val base = when (path) {
            Routes.ALL_VIDEOS -> lib.videos
            Routes.ALL_MUSIC -> lib.audios
            Routes.FAVORITES -> lib.all.filter { it.uri in favs }
            else -> lib.all.filter { it.folderPath == path }
        }
        val filtered = if (q.isBlank()) base else base.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.displayName.contains(q, true) }
        UiState(sort(filtered, s), lib.loaded, s, favs, pls)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init { if (!path.startsWith("@")) viewModelScope.launch { user.markFolderOpened(path) } }

    private fun sort(list: List<MediaFile>, s: AppSettings): List<MediaFile> {
        val cmp: Comparator<MediaFile> = when (s.sortBy) {
            SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            SortBy.DATE -> compareBy<MediaFile> { it.dateModifiedSec }
            SortBy.SIZE -> compareBy<MediaFile> { it.sizeBytes }
            SortBy.DURATION -> compareBy<MediaFile> { it.durationMs }
        }
        return list.sortedWith(if (s.sortAscending) cmp else cmp.reversed())
    }

    // selection
    /** Unselected → appended to the end; selected → removed, the remaining items keep their relative order. */
    fun toggleSelect(uri: String) { _selected.update { if (uri in it) it - uri else it + uri } }
    fun selectAll(items: List<MediaFile>) { _selected.value = items.map { it.uri }.distinct() }
    fun clearSelection() { _selected.value = emptyList() }

    // preferences
    fun setSort(by: SortBy) = viewModelScope.launch {
        val s = state.value.settings
        if (s.sortBy == by) settingsRepo.set(Keys.SORT_ASC, !s.sortAscending) else settingsRepo.setEnum(Keys.SORT, by)
    }
    fun toggleDirection() = viewModelScope.launch { settingsRepo.set(Keys.SORT_ASC, !state.value.settings.sortAscending) }
    fun setViewMode(m: ViewMode) = viewModelScope.launch { settingsRepo.setEnum(Keys.VIEW, m) }

    // favorites + playlists
    fun toggleFavorite(f: MediaFile) = viewModelScope.launch { user.toggleFavorite(f.uri) }
    fun addToPlaylist(id: Long, files: List<MediaFile>) = viewModelScope.launch { user.addToPlaylist(id, files); _events.send(FileEvent.Message("Added ${files.size} to playlist")); clearSelection() }
    fun createPlaylistAndAdd(name: String, files: List<MediaFile>) = viewModelScope.launch {
        val id = user.createPlaylist(name); user.addToPlaylist(id, files); _events.send(FileEvent.Message("Added to “$name”")); clearSelection()
    }

    // scoped-storage operations
    fun deleteSelected(items: List<MediaFile>) = viewModelScope.launch {
        val uris = items.filter { it.uri in _selected.value }.map { it.uri.toUri() }
        if (uris.isNotEmpty()) handle(withContext(Dispatchers.IO) { media.requestDelete(uris) }, PendingOp.Delete(uris))
    }

    fun renameSelected(items: List<MediaFile>, newName: String) = viewModelScope.launch {
        val uri = items.firstOrNull { it.uri in _selected.value }?.uri?.toUri() ?: return@launch
        if (media.isDocumentUri(uri)) finishIf(withContext(Dispatchers.IO) { media.renameGranted(uri, newName) }, PendingOp.Rename(uri, newName))
        else handle(media.requestWrite(listOf(uri)), PendingOp.Rename(uri, newName))
    }

    fun moveSelected(items: List<MediaFile>, relativePath: String) = viewModelScope.launch {
        val uris = items.filter { it.uri in _selected.value }.map { it.uri.toUri() }
        if (uris.isEmpty()) return@launch
        if (uris.any { media.isDocumentUri(it) }) { _events.send(FileEvent.Message("Files from added folders can't be moved")); return@launch }
        handle(media.requestWrite(uris), PendingOp.Move(uris, relativePath))
    }

    fun onGrantResult(ok: Boolean, op: PendingOp) = viewModelScope.launch {
        if (!ok) { _events.send(FileEvent.Message("Cancelled")); return@launch }
        when (op) {
            // Android 11+ already deleted the files; on 10 and below the consent only unlocks them, so delete again.
            is PendingOp.Delete ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) finish(op)
                else handle(withContext(Dispatchers.IO) { media.requestDelete(op.uris) }, op)
            is PendingOp.Rename -> finishIf(withContext(Dispatchers.IO) { media.renameGranted(op.uri, op.newName) }, op)
            is PendingOp.Move -> finishIf(withContext(Dispatchers.IO) { media.moveGranted(op.uris, op.relativePath) }, op)
        }
    }

    private suspend fun handle(res: OpResult, op: PendingOp) = when (res) {
        OpResult.Done -> finish(op)
        is OpResult.NeedsGrant -> _events.send(FileEvent.Grant(res.sender, op))
        OpResult.NeedsStoragePermission -> _events.send(FileEvent.NeedWritePermission(op))
        is OpResult.Failed -> _events.send(FileEvent.Message(res.message))
    }

    private suspend fun finishIf(ok: Boolean, op: PendingOp) {
        if (ok) finish(op) else _events.send(FileEvent.Message(
            if (op is PendingOp.Move) "Move failed — pick a folder inside Movies/, DCIM/ or Music/" else "Couldn't complete that"
        ))
    }

    private suspend fun finish(op: PendingOp) {
        if (op is PendingOp.Delete && op.folderPath != null) withContext(Dispatchers.IO) { media.removeEmptyFolder(op.folderPath) }
        media.refresh(); _selected.value = emptyList()
        _events.send(FileEvent.Message(when (op) { is PendingOp.Delete -> "Deleted"; is PendingOp.Rename -> "Renamed"; is PendingOp.Move -> "Moved" }))
    }
}

@Composable
fun FolderBrowserScreen(onBack: () -> Unit, onOpenNowPlaying: () -> Unit, vm: FolderViewModel = hiltViewModel()) {
    val p = LocalDhvon.current
    val ctx = LocalContext.current
    val ui by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val lastUri by vm.lastPlayed.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val s = ui.settings
    val selecting = selected.isNotEmpty()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var scrolledToLast by rememberSaveable { mutableStateOf(false) }

    var searching by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var playlistTarget by remember { mutableStateOf<List<MediaFile>?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    FileEventHost(vm.events) { ok, op -> vm.onGrantResult(ok, op) }
    BackHandler(enabled = selecting || searching) { if (selecting) vm.clearSelection() else { searching = false; vm.query.value = "" } }

    // Tap order is preserved: orderOf[uri] is the 1-based number on the selection mark and the position in "Play in order".
    val orderOf = remember(selected) { selected.withIndex().associate { (i, uri) -> uri to i + 1 } }
    val itemsByUri = remember(ui.items) { ui.items.associateBy { it.uri } }
    val selectedFiles = remember(selected, itemsByUri) { selected.mapNotNull { itemsByUri[it] } }
    val actions = remember(ui.favorites) {
        MediaActions(vm.playback::playNext, vm.playback::addToQueue, { playlistTarget = listOf(it) }, { vm.toggleFavorite(it) }, { it.uri in ui.favorites })
    }
    fun open(f: MediaFile) {
        if (selecting) vm.toggleSelect(f.uri) else { vm.playback.playFrom(f, ui.items, vm.path); if (!f.isVideo) onOpenNowPlaying() }
    }
    // "Play in order": the selection becomes a temporary queue in exact tap order (audio, video or mixed); no playlist is touched.
    fun playSelectedInOrder() {
        val first = selectedFiles.firstOrNull() ?: return
        vm.playback.playOrdered(selectedFiles)
        vm.clearSelection()
        if (!first.isVideo) onOpenNowPlaying()
    }

    // Jump to the file that was playing last in this folder (once, when the list first shows).
    LaunchedEffect(lastUri, ui.items.isNotEmpty()) {
        if (!scrolledToLast && lastUri != null && ui.items.isNotEmpty()) {
            val idx = ui.items.indexOfFirst { it.uri == lastUri }
            if (idx >= 0) { if (s.viewMode == ViewMode.GRID) gridState.scrollToItem(idx) else listState.scrollToItem(idx); scrolledToLast = true }
        }
    }
    val lastFile = if (query.isBlank()) ui.items.firstOrNull { it.uri == lastUri } else null

    Column(Modifier.fillMaxSize().background(p.background)) {
        // ---- top bar
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                IconButton({ vm.clearSelection() }) { Icon(Icons.Rounded.Close, "Clear", tint = p.textPrimary) }
                Text("${selected.size} selected", color = p.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton({ vm.selectAll(ui.items) }) { Icon(Icons.Rounded.SelectAll, "Select all", tint = p.textPrimary) }
                IconButton({ playlistTarget = selectedFiles }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", tint = p.textPrimary) }
                IconButton({ shareFiles(ctx, selectedFiles) }) { Icon(Icons.Rounded.Share, "Share", tint = p.textPrimary) }
                if (selected.size == 1) IconButton({ showRename = true }) { Icon(Icons.Rounded.DriveFileRenameOutline, "Rename", tint = p.textPrimary) }
                IconButton({ showMove = true }) { Icon(Icons.Rounded.DriveFileMove, "Move", tint = p.textPrimary) }
                IconButton({ showDelete = true }) { Icon(Icons.Rounded.Delete, "Delete", tint = p.textPrimary) }
            } else if (searching) {
                IconButton({ searching = false; vm.query.value = "" }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search", tint = p.textPrimary) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                BasicTextField(
                    query, { vm.query.value = it }, singleLine = true, modifier = Modifier.weight(1f).focusRequester(focus),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.textPrimary), cursorBrush = SolidColor(p.accent),
                    decorationBox = { inner -> if (query.isEmpty()) Text("Search in ${vm.title}", color = p.textSecondary); inner() },
                )
                if (query.isNotEmpty()) IconButton({ vm.query.value = "" }) { Icon(Icons.Rounded.Close, "Clear", tint = p.textSecondary) }
            } else {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = p.textPrimary) }
                Text(vm.title, color = p.textPrimary, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton({ searching = true }) { SilverIcon(Icons.Rounded.Search, "Search", Modifier.size(24.dp)) }
                IconButton({ vm.setViewMode(if (s.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID) }) {
                    SilverIcon(if (s.viewMode == ViewMode.GRID) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, "View", Modifier.size(24.dp))
                }
                Box {
                    IconButton({ sortMenu = true }) { SilverIcon(Icons.AutoMirrored.Rounded.Sort, "Sort", Modifier.size(24.dp)) }
                    DropdownMenu(sortMenu, { sortMenu = false }, containerColor = p.elevated) {
                        SortBy.entries.forEach { by ->
                            DropdownMenuItem(
                                { Text(by.label + if (s.sortBy == by) (if (s.sortAscending) "  ↑" else "  ↓") else "", color = if (s.sortBy == by) p.accent else p.textPrimary) },
                                { vm.setSort(by); sortMenu = false },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem({ Text(if (s.sortAscending) "Ascending" else "Descending") }, { vm.toggleDirection(); sortMenu = false },
                            leadingIcon = { Icon(if (s.sortAscending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null) })
                    }
                }
            }
        }

        // ---- "Continue where you left off in this folder"
        if (lastFile != null && !selecting && !searching) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.accent.copy(alpha = 0.16f))
                    .clickable { open(lastFile) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = p.accent)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Continue where you left off", color = p.textSecondary, style = MaterialTheme.typography.labelMedium)
                    Text(lastFile.title, color = p.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // ---- content
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ui.loaded && ui.items.isEmpty() -> EmptyState(Icons.Rounded.FolderOff, if (query.isNotBlank()) "No matches" else "Nothing here yet", if (query.isNotBlank()) "Try a different search." else "This folder has no playable media.")
                s.viewMode == ViewMode.GRID -> LazyVerticalGrid(
                    GridCells.Adaptive(150.dp), state = gridState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(ui.items, key = { it.uri }) { f -> MediaGridCard(f, f.uri in orderOf, selecting, { open(f) }, { vm.toggleSelect(f.uri) }, lastPlayed = f.uri == lastUri, selectionOrder = orderOf[f.uri]) }
                }
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(ui.items, key = { it.uri }) { f -> MediaListRow(f, f.uri in orderOf, selecting, { open(f) }, { vm.toggleSelect(f.uri) }, actions = actions, lastPlayed = f.uri == lastUri, selectionOrder = orderOf[f.uri]) }
                }
            }
        }
        // ---- ordered multi-select: bottom action bar
        if (selectedFiles.isNotEmpty()) PlayQueueBar(selectedFiles.size, { playSelectedInOrder() })
    }

    // ---- dialogs
    playlistTarget?.let { files ->
        AddToPlaylistDialog(
            ui.playlists,
            onPick = { vm.addToPlaylist(it, files); playlistTarget = null },
            onCreate = { vm.createPlaylistAndAdd(it, files); playlistTarget = null },
            onDismiss = { playlistTarget = null },
        )
    }
    if (showRename) TextInputDialog("Rename", selectedFiles.firstOrNull()?.displayName.orEmpty(), "File name (with extension)", "Rename",
        onConfirm = { vm.renameSelected(ui.items, it); showRename = false }, onDismiss = { showRename = false })
    if (showMove) TextInputDialog("Move to folder", "Movies/", "Relative path, e.g. Movies/Action", "Move",
        onConfirm = { vm.moveSelected(ui.items, it); showMove = false }, onDismiss = { showMove = false })
    if (showDelete) ConfirmDialog("Delete ${selected.size} item(s)?", "Android will ask you to confirm. This can't be undone.", "Delete",
        onConfirm = { vm.deleteSelected(ui.items); showDelete = false }, onDismiss = { showDelete = false })
}

private fun shareFiles(ctx: Context, files: List<MediaFile>) {
    if (files.isEmpty()) return
    val uris = ArrayList(files.map { it.uri.toUri() })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = if (files.all { it.isVideo }) "video/*" else if (files.none { it.isVideo }) "audio/*" else "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(intent, "Share via"))
}

// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens

import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhshourav.dhvon.data.media.MediaRepository
import com.rhshourav.dhvon.data.settings.Keys
import com.rhshourav.dhvon.data.settings.SettingsRepository
import com.rhshourav.dhvon.data.settings.ViewMode
import com.rhshourav.dhvon.data.user.UserDataRepository
import com.rhshourav.dhvon.domain.FolderCategory
import com.rhshourav.dhvon.domain.MediaFolder
import com.rhshourav.dhvon.domain.StorageInfo
import com.rhshourav.dhvon.ui.components.*
import com.rhshourav.dhvon.ui.nav.Routes
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val media: MediaRepository,
    private val settingsRepo: SettingsRepository,
    user: UserDataRepository,
) : ViewModel() {
    private val category = MutableStateFlow(FolderCategory.ALL)

    data class UiState(
        val loaded: Boolean = false,
        val category: FolderCategory = FolderCategory.ALL,
        val folders: List<MediaFolder> = emptyList(),
        val videoCount: Int = 0, val audioCount: Int = 0, val favoriteCount: Int = 0,
        val lastFolder: MediaFolder? = null,
        val storage: StorageInfo = StorageInfo(0, 0),
        val viewMode: ViewMode = ViewMode.LIST,
    )

    val state: StateFlow<UiState> = combine(media.library, category, settingsRepo.settings, user.lastFolder, user.favorites) { lib, cat, s, last, favs ->
        val all = MediaRepository.foldersOf(lib.all)
        UiState(
            loaded = lib.loaded, category = cat,
            folders = if (cat == FolderCategory.ALL) all else all.filter { it.category == cat },
            videoCount = lib.videos.size, audioCount = lib.audios.size, favoriteCount = favs.size,
            lastFolder = all.firstOrNull { it.path == last },
            storage = media.storageInfo(), viewMode = s.viewMode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun setCategory(c: FolderCategory) { category.value = c }
    fun setViewMode(m: ViewMode) { viewModelScope.launch { settingsRepo.setEnum(Keys.VIEW, m) } }
}

@Composable
fun LibraryScreen(
    onOpenFolder: (String) -> Unit,
    onSearch: () -> Unit,
    onPickFolder: () -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val p = LocalDhvon.current
    val ui by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(p.background)) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Folders", color = p.textPrimary, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onPickFolder) { SilverIcon(Icons.Rounded.CreateNewFolder, "Add folder", Modifier.size(26.dp)) }
                IconButton(onClick = onSearch) { SilverIcon(Icons.Rounded.Search, "Search", Modifier.size(26.dp)) }
            }
        }
        ui.lastFolder?.let { last ->
            item {
                Row(
                    Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.accent.copy(alpha = 0.16f))
                        .clickable { onOpenFolder(last.path) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.History, null, tint = p.accent)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Continue in ${last.name}", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text("Last opened folder", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = p.textSecondary)
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(FolderCategory.entries.toList()) { c -> DhvonChip(c.label, ui.category == c, { vm.setCategory(c) }) }
            }
        }
        item {
            Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Folders", color = p.textPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.setViewMode(if (ui.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID) }) {
                    Icon(if (ui.viewMode == ViewMode.GRID) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, "Toggle view", tint = p.textSecondary)
                }
            }
        }
        if (ui.loaded && ui.folders.isEmpty()) {
            item { EmptyState(Icons.Rounded.FolderOff, "No folders here", "Try another category, or add a folder with the + button.") }
        } else if (ui.viewMode == ViewMode.LIST) {
            items(ui.folders, key = { it.path }) { f -> FolderRow(f, { onOpenFolder(f.path) }) }
        } else {
            items(ui.folders.chunked(2), key = { it.first().path }) { pair ->
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { f -> FolderCard(f, { onOpenFolder(f.path) }, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    StorageBar(ui.storage, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

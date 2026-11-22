// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhshourav.dhvon.data.local.PlaylistSummary
import com.rhshourav.dhvon.data.media.MediaRepository
import com.rhshourav.dhvon.data.settings.SettingsRepository
import com.rhshourav.dhvon.data.user.UserDataRepository
import com.rhshourav.dhvon.domain.MediaFolder
import com.rhshourav.dhvon.domain.MediaFile
import com.rhshourav.dhvon.player.PlaybackController
import com.rhshourav.dhvon.ui.components.*
import com.rhshourav.dhvon.ui.nav.Routes
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    media: MediaRepository,
    user: UserDataRepository,
    settingsRepo: SettingsRepository,
    val playback: PlaybackController,
) : ViewModel() {
    data class Resume(val file: MediaFile, val progress: Float)
    data class UiState(
        val loaded: Boolean = false,
        val featured: List<MediaFile> = emptyList(),
        val recent: List<MediaFile> = emptyList(),
        val resume: List<Resume> = emptyList(),
        val videos: List<MediaFile> = emptyList(),
        val music: List<MediaFile> = emptyList(),
        val playlists: List<PlaylistSummary> = emptyList(),
        val folders: List<MediaFolder> = emptyList(),
        val hidden: Set<String> = emptySet(),
    )

    val state: StateFlow<UiState> = combine(media.library, user.recent, user.continueWatching, user.playlists, settingsRepo.settings) { lib, recent, cont, pls, settings ->
        val recentFiles = recent.mapNotNull { lib.find(it.uri) }
        UiState(
            loaded = lib.loaded,
            featured = recentFiles.filter { it.isVideo }.ifEmpty { lib.videos }.take(3).ifEmpty { recentFiles.take(3) }.ifEmpty { lib.audios.take(3) },
            recent = recentFiles.ifEmpty { (lib.videos.take(6) + lib.audios.take(6)) }.take(12),
            resume = cont.mapNotNull { e -> lib.find(e.uri)?.let { Resume(it, e.progress) } },
            videos = lib.videos, music = lib.audios, playlists = pls,
            folders = MediaRepository.foldersOf(lib.all), hidden = settings.hiddenHomeSections,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())
}

private enum class HomeFilter(val label: String) { ALL("All"), VIDEOS("Videos"), MUSIC("Music"), FOLDERS("Folders"), PLAYLISTS("Playlists") }

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onSwitchTab: (String) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val p = LocalDhvon.current
    val ui by vm.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(HomeFilter.ALL) }
    var menu by remember { mutableStateOf(false) }

    fun play(file: MediaFile, list: List<MediaFile>) {
        vm.playback.playFrom(file, list)
        if (!file.isVideo) onOpenNowPlaying()
    }

    LazyColumn(Modifier.fillMaxSize().background(p.background), contentPadding = PaddingValues(bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                DhvonWordmark(Modifier.weight(1f))
                IconButton(onClick = { onNavigate(Routes.SEARCH) }) { SilverIcon(Icons.Rounded.Search, "Search", Modifier.size(26.dp)) }
                Box {
                    IconButton(onClick = { menu = true }) { SilverIcon(Icons.Rounded.MoreVert, "Menu", Modifier.size(26.dp)) }
                    DropdownMenu(menu, { menu = false }, containerColor = p.elevated) {
                        DropdownMenuItem({ Text("Equalizer") }, { menu = false; onNavigate(Routes.EQUALIZER) }, leadingIcon = { Icon(Icons.Rounded.Equalizer, null) })
                        DropdownMenuItem({ Text("Settings") }, { menu = false; onNavigate(Routes.SETTINGS) }, leadingIcon = { Icon(Icons.Rounded.Settings, null) })
                        DropdownMenuItem({ Text("About DHVON") }, { menu = false; onNavigate(Routes.ABOUT) }, leadingIcon = { Icon(Icons.Rounded.Info, null) })
                    }
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(HomeFilter.entries.toList()) { f ->
                    DhvonChip(f.label, filter == f, { filter = f }, icon = if (f == HomeFilter.ALL && filter == f) Icons.Rounded.PlayArrow else null)
                }
            }
        }

        if (ui.loaded && ui.videos.isEmpty() && ui.music.isEmpty() && filter != HomeFilter.PLAYLISTS && filter != HomeFilter.FOLDERS) {
            item { EmptyState(Icons.Rounded.VideoLibrary, "No media found", "Add videos or music to your device, or pick a folder from Library.") }
            return@LazyColumn
        }

        when (filter) {
            HomeFilter.ALL -> {
                if (ui.featured.isNotEmpty() && "hero" !in ui.hidden) item { HeroBanner(ui.featured) { play(it, ui.featured) } }
                if (ui.recent.isNotEmpty() && "recent" !in ui.hidden) {
                    item { SectionHeader("Recently Played", onSeeAll = { onOpenFolder(Routes.ALL_VIDEOS) }) }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(ui.recent, key = { it.uri }) { f -> MediaCard(f, onClick = { play(f, ui.recent) }) }
                        }
                    }
                }
                // Dedicated Folders section (turn it off under Settings → Home layout)
                if (ui.folders.isNotEmpty() && "folders" !in ui.hidden) {
                    item { SectionHeader("Folders", onSeeAll = { onSwitchTab(Routes.LIBRARY) }) }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(ui.folders.take(12), key = { "fo" + it.path }) { f -> FolderCard(f, { onOpenFolder(f.path) }, Modifier.width(168.dp)) }
                        }
                    }
                }
                if ("quick" !in ui.hidden) item { SectionHeader("Quick Access") }
                if ("quick" !in ui.hidden) item {
                    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickCard(Icons.Rounded.Movie, "Videos", Modifier.weight(1f)) { onOpenFolder(Routes.ALL_VIDEOS) }
                        QuickCard(Icons.Rounded.MusicNote, "Music", Modifier.weight(1f)) { onOpenFolder(Routes.ALL_MUSIC) }
                        QuickCard(Icons.AutoMirrored.Rounded.PlaylistPlay, "Playlists", Modifier.weight(1f)) { onSwitchTab(Routes.PLAYLISTS) }
                        QuickCard(Icons.Rounded.Folder, "Folders", Modifier.weight(1f)) { onSwitchTab(Routes.LIBRARY) }
                    }
                }
                if (ui.resume.isNotEmpty() && "continue" !in ui.hidden) {
                    item { SectionHeader("Continue Watching") }
                    items(ui.resume, key = { "r" + it.file.uri }) { r ->
                        ResumeCard(r, Modifier.padding(horizontal = 20.dp)) { play(r.file, ui.videos) }
                    }
                }
            }
            HomeFilter.VIDEOS -> items(ui.videos.take(80), key = { "v" + it.uri }) { f ->
                MediaListRow(f, false, false, { play(f, ui.videos) }, {})
            }
            HomeFilter.MUSIC -> items(ui.music.take(120), key = { "m" + it.uri }) { f ->
                MediaListRow(f, false, false, { play(f, ui.music) }, {})
            }
            HomeFilter.FOLDERS -> {
                if (ui.folders.isEmpty()) item { EmptyState(Icons.Rounded.Folder, "No folders yet", "Folders appear here once your videos and music are indexed.") }
                items(ui.folders, key = { "fo" + it.path }) { f -> FolderRow(f, { onOpenFolder(f.path) }) }
            }
            HomeFilter.PLAYLISTS -> {
                if (ui.playlists.isEmpty()) item { EmptyState(Icons.AutoMirrored.Rounded.PlaylistPlay, "No playlists yet", "Create one from the Playlists tab.") }
                items(ui.playlists, key = { "p" + it.id }) { pl ->
                    Row(Modifier.fillMaxWidth().clickable { onNavigate(Routes.playlist(pl.id)) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SilverIcon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, Modifier.size(34.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(pl.name, color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
                            Text("${pl.itemCount} items", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroBanner(files: List<MediaFile>, onPlay: (MediaFile) -> Unit) {
    val p = LocalDhvon.current
    val pager = rememberPagerState { files.size }
    Column {
        HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 20.dp), pageSpacing = 12.dp) { i ->
            val f = files[i]
            Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(20.dp)).clickable { onPlay(f) }) {
                MediaThumb(f, Modifier.fillMaxSize(), RoundedCornerShape(20.dp))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)), startY = 120f)))
                Column(Modifier.align(Alignment.BottomStart).padding(18.dp).fillMaxWidth(0.72f)) {
                    Text(f.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(f.metaLine, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(16.dp).size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(30.dp)) }
            }
        }
        if (files.size > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
                repeat(files.size) { i ->
                    val on = pager.currentPage == i
                    Box(Modifier.padding(horizontal = 3.dp).height(6.dp).width(if (on) 18.dp else 6.dp).clip(CircleShape).background(if (on) p.textPrimary else p.textSecondary.copy(alpha = 0.4f)))
                }
            }
        }
    }
}

@Composable
private fun QuickCard(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalDhvon.current
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(p.card).clickable(onClick = onClick).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SilverIcon(icon, label, Modifier.size(30.dp))
        Text(label, color = p.textPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun ResumeCard(r: HomeViewModel.Resume, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalDhvon.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        MediaThumb(r.file, Modifier.width(116.dp).height(66.dp), RoundedCornerShape(12.dp), showPlay = true)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.file.title, color = p.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradientBar(r.progress, Modifier.weight(1f), 4.dp, p.elevated)
                Spacer(Modifier.width(10.dp))
                Text("${(r.progress * 100).toInt()}%", color = p.textSecondary, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(42.dp).clip(CircleShape).background(p.elevated), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, "Resume", tint = p.textPrimary)
        }
    }
}

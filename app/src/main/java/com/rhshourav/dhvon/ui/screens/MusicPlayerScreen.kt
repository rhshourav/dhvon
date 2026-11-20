// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import android.content.Context
import android.content.Intent
import com.rhshourav.dhvon.data.media.*
import com.rhshourav.dhvon.data.settings.AppSettings
import com.rhshourav.dhvon.data.settings.Keys
import com.rhshourav.dhvon.data.settings.ProgressStyle
import com.rhshourav.dhvon.data.settings.SettingsRepository
import com.rhshourav.dhvon.data.user.UserDataRepository
import com.rhshourav.dhvon.domain.MediaFile
import com.rhshourav.dhvon.domain.formatDuration
import com.rhshourav.dhvon.player.AudioEffectsController
import com.rhshourav.dhvon.player.PlaybackController
import com.rhshourav.dhvon.player.PlayerHolder
import com.rhshourav.dhvon.ui.components.*
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import javax.inject.Inject

@HiltViewModel
class MusicPlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val playback: PlaybackController,
    val effects: AudioEffectsController,
    private val holder: PlayerHolder,
    private val user: UserDataRepository,
    private val settingsRepo: SettingsRepository,
    private val waveform: WaveformExtractor,
    private val artwork: ArtworkLoader,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val currentUri = playback.state.map { it.uri }.distinctUntilChanged()

    val peaks: StateFlow<FloatArray?> = currentUri
        .mapLatest { if (it.isEmpty()) null else waveform.peaks(it, 96) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Dominant colour of the album art → ambient background tint. */
    val ambient: StateFlow<Int?> = currentUri.mapLatest { uri ->
        if (uri.isEmpty()) null else artwork.audioArt(uri, 128)?.let { bmp ->
            withContext(Dispatchers.Default) {
                val pal = Palette.from(bmp).maximumColorCount(12).generate()
                (pal.darkVibrantSwatch ?: pal.darkMutedSwatch ?: pal.dominantSwatch)?.rgb
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isFavorite: StateFlow<Boolean> = combine(currentUri, user.favorites) { uri, favs -> uri in favs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val lyricsRefresh = MutableStateFlow(0)
    val lyrics: StateFlow<List<LrcLine>> = combine(currentUri, lyricsRefresh) { uri, _ -> uri }
        .mapLatest { uri -> if (uri.isEmpty()) emptyList() else loadLyrics(uri) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Peak output level for the live waveform pulse (read once per frame by the UI). */
    fun liveLevel(): Float = holder.audioProcessor.level

    fun toggleFavorite() { val uri = playback.state.value.uri; if (uri.isNotEmpty()) viewModelScope.launch { user.toggleFavorite(uri) } }

    private suspend fun loadLyrics(trackUri: String): List<LrcLine> = withContext(Dispatchers.IO) {
        val doc = settingsRepo.get(Keys.lrcFor(trackUri.hashCode())) ?: return@withContext emptyList()
        runCatching {
            context.contentResolver.openInputStream(doc.toUri())?.use { LrcParser.parse(it.readBytes().toString(Charsets.UTF_8)) }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** Remembers which .lrc file belongs to the current track (kept across restarts). */
    fun attachLyrics(lrc: Uri) = viewModelScope.launch {
        val track = playback.state.value.uri
        if (track.isEmpty()) return@launch
        runCatching { context.contentResolver.takePersistableUriPermission(lrc, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        settingsRepo.set(Keys.lrcFor(track.hashCode()), lrc.toString())
        lyricsRefresh.update { it + 1 }
    }
}

@Composable
fun NowPlayingScreen(onBack: () -> Unit, vm: MusicPlayerViewModel = hiltViewModel()) {
    val p = LocalDhvon.current
    val st by vm.playback.state.collectAsStateWithLifecycle()
    val peaks by vm.peaks.collectAsStateWithLifecycle()
    val ambientInt by vm.ambient.collectAsStateWithLifecycle()
    val favorite by vm.isFavorite.collectAsStateWithLifecycle()
    val position by rememberPositionMs(vm.playback)
    val settings by vm.settings.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }

    val ambient by animateColorAsState(ambientInt?.let { Color(it) } ?: p.accent.copy(alpha = 0.35f), tween(700), label = "ambient")
    val progress = if (st.durationMs > 0) (position.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f
    val shownMs = scrubFraction?.let { (it * st.durationMs).toLong() } ?: position

    if (!st.hasMedia) { LaunchedEffect(Unit) { onBack() }; return }

    Box(Modifier.fillMaxSize().background(p.background)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(ambient.copy(alpha = 0.75f), p.background), endY = 1500f)))
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { SilverIcon(Icons.Rounded.KeyboardArrowDown, "Close", Modifier.size(30.dp)) }
                Spacer(Modifier.weight(1f))
                Text("Now Playing", color = p.textSecondary, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                IconButton({ sheet = Sheet.EQ }) { SilverIcon(Icons.Rounded.GraphicEq, "Equalizer", Modifier.size(26.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            val art = remember(st.uri) { MediaFile(0, st.uri, st.title, st.title, st.durationMs, 0, 0, "", false) }
            Crossfade(st.uri, label = "art") { _ ->
                MediaThumb(art, Modifier.fillMaxWidth().aspectRatio(1f).shadow(24.dp, RoundedCornerShape(28.dp), ambientColor = ambient, spotColor = ambient), RoundedCornerShape(28.dp))
            }
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(st.title, color = p.textPrimary, fontSize = 24.sp, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(st.subtitle.ifBlank { "Unknown artist" }, color = p.textSecondary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(vm::toggleFavorite) {
                    Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite", tint = if (favorite) p.accentSecondary else p.textPrimary, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            DhvonSeekBar(
                fraction = progress, style = settings.musicProgressStyle,
                peaks = peaks, levelProvider = vm::liveLevel,
                onScrub = { scrubFraction = it },
                onSeekFinished = { f -> scrubFraction = null; vm.playback.seekTo((f * st.durationMs).toLong()) },
                modifier = Modifier.fillMaxWidth().height(if (settings.musicProgressStyle == ProgressStyle.WAVE) 64.dp else 40.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(formatDuration(shownMs), color = p.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(formatDuration(st.durationMs), color = p.textSecondary, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(vm.playback::toggleShuffle) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (st.shuffle) p.accent else p.textPrimary, modifier = Modifier.size(26.dp)) }
                IconButton(vm.playback::previous, Modifier.size(56.dp)) { SilverIcon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(38.dp)) }
                Box(Modifier.size(76.dp).clip(CircleShape).background(Color(0xFFF5F7FA)).clickable { vm.playback.togglePlayPause() }, contentAlignment = Alignment.Center) {
                    Icon(if (st.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", tint = Color(0xFF08090D), modifier = Modifier.size(44.dp))
                }
                IconButton(vm.playback::next, Modifier.size(56.dp)) { SilverIcon(Icons.Rounded.SkipNext, "Next", Modifier.size(38.dp)) }
                IconButton(vm.playback::cycleRepeat) {
                    Icon(if (st.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat",
                        tint = if (st.repeatMode == Player.REPEAT_MODE_OFF) p.textPrimary else p.accent, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                ActionPill(Icons.Rounded.Lyrics, "Lyrics") { sheet = Sheet.LYRICS }
                ActionPill(Icons.Rounded.Tune, "Equalizer") { sheet = Sheet.EQ }
                ActionPill(Icons.AutoMirrored.Rounded.QueueMusic, "Queue") { sheet = Sheet.QUEUE }
            }
        }
    }

    sheet?.let { which ->
        ModalBottomSheet({ sheet = null }, containerColor = p.card, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = which != Sheet.EQ)) {
            when (which) {
                Sheet.QUEUE -> QueueSheet(vm)
                Sheet.LYRICS -> LyricsSheet(vm, position)
                Sheet.EQ -> EqualizerPanel(vm.effects, Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp))
            }
        }
    }
}

private enum class Sheet { QUEUE, LYRICS, EQ }

@Composable
private fun ActionPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    val p = LocalDhvon.current
    Row(Modifier.clip(CircleShape).background(p.elevated).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SilverIcon(icon, null, Modifier.size(18.dp))
        Text(label, color = p.textPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun QueueSheet(vm: MusicPlayerViewModel) {
    val p = LocalDhvon.current
    val st by vm.playback.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to -> vm.playback.moveQueueItem(from.index, to.index) }
    LaunchedEffect(Unit) { if (st.currentIndex > 2) listState.scrollToItem(st.currentIndex - 1) }

    Column(Modifier.fillMaxHeight(0.8f)) {
        Text("Up next · ${st.queue.size} tracks", color = p.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            // Keys must stay unique even if the same file is queued twice.
            val seen = HashMap<String, Int>()
            val keys = st.queue.map { e -> val n = (seen[e.uri] ?: 0); seen[e.uri] = n + 1; e.uri + "#" + n }
            itemsIndexed(st.queue, key = { i, _ -> keys[i] }) { index, entry ->
                ReorderableItem(reorder, key = keys[index]) { dragging ->
                    val current = index == st.currentIndex
                    Row(
                        Modifier.fillMaxWidth().background(if (dragging) p.elevated else if (current) p.accent.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable { vm.playback.skipTo(index) }.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton({}, modifier = Modifier.draggableHandle()) { Icon(Icons.Rounded.DragHandle, "Reorder", tint = p.textSecondary) }
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, color = if (current) p.accent else p.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(entry.subtitle.ifBlank { "Unknown artist" }, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        Text(formatDuration(entry.durationMs), color = p.textSecondary, style = MaterialTheme.typography.labelMedium)
                        IconButton({ vm.playback.removeFromQueue(index) }) { Icon(Icons.Rounded.Close, "Remove", tint = p.textSecondary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsSheet(vm: MusicPlayerViewModel, positionMs: Long) {
    val p = LocalDhvon.current
    val lines by vm.lyrics.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.attachLyrics(uri) }
    val active = lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    LaunchedEffect(active) { if (lines.isNotEmpty()) listState.animateScrollToItem((active - 2).coerceAtLeast(0)) }

    Column(Modifier.fillMaxHeight(0.8f).padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics", color = p.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton({ picker.launch(arrayOf("*/*")) }) { Text(if (lines.isEmpty()) "Load .lrc" else "Change .lrc", color = p.accent) }
        }
        if (lines.isEmpty()) {
            EmptyState(Icons.Rounded.Lyrics, "No synced lyrics", "Pick a .lrc file for this track — DHVON remembers it and highlights each line in time.")
        } else LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 40.dp)) {
            itemsIndexed(lines) { i, line ->
                Text(
                    line.text.ifBlank { "♪" }, textAlign = TextAlign.Center,
                    color = if (i == active) p.textPrimary else p.textSecondary.copy(alpha = 0.7f),
                    fontSize = if (i == active) 22.sp else 18.sp, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth().clickable { vm.playback.seekTo(line.timeMs) }.padding(vertical = 8.dp),
                )
            }
        }
    }
}

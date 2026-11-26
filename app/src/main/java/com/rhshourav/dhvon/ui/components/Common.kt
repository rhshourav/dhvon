// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.videoFrameMillis
import com.rhshourav.dhvon.data.media.ArtworkLoader
import com.rhshourav.dhvon.domain.*
import com.rhshourav.dhvon.player.PlaybackController
import com.rhshourav.dhvon.player.PlaybackUiState
import com.rhshourav.dhvon.ui.theme.LocalCompact
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import kotlinx.coroutines.delay

val LocalArtworkLoader = staticCompositionLocalOf<ArtworkLoader?> { null }

/** URIs that currently carry the NEW badge. Provided once at the app root; only the rows that read it recompose when it changes. */
val LocalNewUris = compositionLocalOf<Set<String>> { emptySet() }

/** Folder paths containing at least one NEW file (see RootViewModel.newFolders). */
val LocalNewFolderPaths = compositionLocalOf<Set<String>> { emptySet() }

/** Polls the live playback position (the player doesn't push position ticks). */
@Composable
fun rememberPositionMs(player: Player, intervalMs: Long = 250L): State<Long> =
    produceState(0L, player) { while (true) { value = player.currentPosition; delay(intervalMs) } }

@Composable
fun rememberPositionMs(controller: PlaybackController, intervalMs: Long = 250L): State<Long> =
    produceState(0L, controller) { while (true) { value = controller.player.currentPosition; delay(intervalMs) } }

// ------------------------------------------------------------------------------------------------ basics

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null) {
    val p = LocalDhvon.current
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (onSeeAll != null) {
            Row(Modifier.clip(CircleShape).clickable(onClick = onSeeAll).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("See All", color = p.textSecondary, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = p.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun DhvonChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val p = LocalDhvon.current
    val bg by animateColorAsState(if (selected) p.accent else p.elevated, label = "chip")
    val fg = if (selected) Color.White else p.textPrimary
    Row(
        modifier.clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Icon filled with the silver metallic gradient (matches the DHVON emblem). */
@Composable
fun SilverIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier) {
    val p = LocalDhvon.current
    Icon(
        icon, contentDescription,
        modifier.graphicsLayer(alpha = 0.99f).drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(listOf(p.silverStart, p.silverEnd)), blendMode = BlendMode.SrcIn)
        },
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val p = LocalDhvon.current
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SilverIcon(icon, null, Modifier.size(54.dp))
        Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
        Text(message, color = p.textSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun GradientBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp, track: Color = LocalDhvon.current.elevated) {
    val p = LocalDhvon.current
    Box(modifier.height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(CircleShape).background(p.accent))
    }
}

/** Compact "NEW" pill for files discovered by a scan and not played yet. */
@Composable
fun NewBadge(modifier: Modifier = Modifier) {
    val p = LocalDhvon.current
    Text(
        "NEW", color = Color.White, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
        maxLines = 1, softWrap = false,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(p.accent).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Shuffle toggle shared by the music player, the video player (controls + playlist panel) and audio mode.
 * On: accent-tinted icon with a small indicator dot underneath. Off: [offTint] (secondary text colour by default).
 */
@Composable
fun ShuffleToggleButton(
    active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    offTint: Color = LocalDhvon.current.textSecondary, iconSize: Dp = 26.dp, enabled: Boolean = true,
) {
    val p = LocalDhvon.current
    val tint by animateColorAsState(if (active) p.accent else offTint, label = "shuffle_tint")
    IconButton(onClick, modifier.alpha(if (enabled) 1f else 0.35f), enabled = enabled) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Shuffle, if (active) "Shuffle on" else "Shuffle off", tint = tint, modifier = Modifier.size(iconSize))
            if (active) Box(Modifier.align(Alignment.BottomCenter).offset(y = 7.dp).size(4.dp).clip(CircleShape).background(p.accent))
        }
    }
}

/** Bottom action bar of a multi-select: starts the temporary queue in the exact order the items were tapped. */
@Composable
fun PlayQueueBar(count: Int, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalDhvon.current
    Row(
        modifier.fillMaxWidth().background(p.card).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("$count in queue", color = p.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text("Plays in the order you tapped", color = p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(onPlay, colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White)) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Play in order")
        }
    }
}

@Composable
fun StorageBar(info: StorageInfo, modifier: Modifier = Modifier, label: String = "Internal Storage") {
    val p = LocalDhvon.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SilverIcon(Icons.Rounded.SdStorage, null, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, color = p.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(info.label, color = p.textSecondary, style = MaterialTheme.typography.labelLarge)
        }
        GradientBar(info.fraction, Modifier.fillMaxWidth(), 8.dp)
    }
}

// ------------------------------------------------------------------------------------------------ artwork

@Composable
fun VideoFrame(uri: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val request = remember(uri) { ImageRequest.Builder(ctx).data(uri).videoFrameMillis(1_000).crossfade(true).build() }
    AsyncImage(model = request, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

@Composable
fun AudioArt(uri: String, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp), sizePx: Int = 256) {
    val p = LocalDhvon.current
    val loader = LocalArtworkLoader.current
    val bmp by produceState<Bitmap?>(null, uri) { value = loader?.audioArt(uri, sizePx) }
    Box(modifier.clip(shape).background(p.elevated), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else SilverIcon(Icons.Rounded.MusicNote, null, Modifier.fillMaxSize(0.45f))
    }
}

@Composable
fun MediaThumb(
    file: MediaFile, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(14.dp),
    showPlay: Boolean = false, showDuration: Boolean = false,
) {
    val p = LocalDhvon.current
    Box(modifier.clip(shape).background(p.elevated)) {
        if (file.isVideo) VideoFrame(file.uri, Modifier.fillMaxSize()) else AudioArt(file.uri, Modifier.fillMaxSize(), RectangleShape)
        if (showPlay) {
            Box(Modifier.align(Alignment.BottomStart).padding(8.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(if (file.isVideo) Icons.Rounded.PlayArrow else Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        if (showDuration && file.durationMs > 0) {
            Text(
                formatDuration(file.durationMs), color = Color.White, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------ media rows / cards

class MediaActions(
    val playNext: (MediaFile) -> Unit,
    val addToQueue: (MediaFile) -> Unit,
    val addToPlaylist: (MediaFile) -> Unit,
    val toggleFavorite: (MediaFile) -> Unit,
    val isFavorite: (MediaFile) -> Boolean,
)

@Composable
fun MediaMenu(file: MediaFile, actions: MediaActions) {
    val p = LocalDhvon.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = p.textSecondary) }
        DropdownMenu(open, { open = false }, containerColor = p.elevated) {
            if (!file.isVideo) {
                DropdownMenuItem({ Text("Play next") }, { actions.playNext(file); open = false }, leadingIcon = { Icon(Icons.Rounded.SkipNext, null) })
                DropdownMenuItem({ Text("Add to queue") }, { actions.addToQueue(file); open = false }, leadingIcon = { Icon(Icons.Rounded.Queue, null) })
            }
            DropdownMenuItem({ Text("Add to playlist") }, { actions.addToPlaylist(file); open = false }, leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) })
            val fav = actions.isFavorite(file)
            DropdownMenuItem({ Text(if (fav) "Remove favorite" else "Favorite") }, { actions.toggleFavorite(file); open = false },
                leadingIcon = { Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null) })
        }
    }
}

/** Round selection marker. With [orderNumber] (1-based) it shows the tap order instead of a check, so a multi-select doubles as a play order. */
@Composable
fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier, orderNumber: Int? = null) {
    val p = LocalDhvon.current
    Box(
        modifier.size(22.dp).clip(CircleShape).background(if (selected) p.accent else Color.Black.copy(alpha = 0.45f))
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected && orderNumber != null) {
            Text(
                orderNumber.toString(), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, lineHeight = 12.sp,
                fontSize = when { orderNumber >= 100 -> 8.sp; orderNumber >= 10 -> 10.sp; else -> 12.sp },
            )
        } else if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

@Composable
fun MediaListRow(
    file: MediaFile, selected: Boolean, selectionMode: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier, actions: MediaActions? = null, lastPlayed: Boolean = false,
    selectionOrder: Int? = null,
) {
    val p = LocalDhvon.current
    val pad = if (LocalCompact.current) 6.dp else 10.dp
    val isNew = file.uri in LocalNewUris.current
    Row(
        modifier.fillMaxWidth().background(if (selected) p.accent.copy(alpha = 0.16f) else if (lastPlayed) p.accent.copy(alpha = 0.08f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = pad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            MediaThumb(file, Modifier.then(if (file.isVideo) Modifier.width(96.dp).height(56.dp) else Modifier.size(56.dp)), RoundedCornerShape(12.dp), showDuration = file.isVideo)
            if (selectionMode) SelectionMark(selected, Modifier.align(Alignment.TopStart).padding(4.dp), selectionOrder)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    file.title, color = if (lastPlayed) p.accent else p.textPrimary, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (isNew) { Spacer(Modifier.width(6.dp)); NewBadge() }
            }
            val sub = if (file.isVideo) listOfNotNull(file.resolutionLabel, formatSize(file.sizeBytes)).joinToString(" • ")
            else listOf(file.artist.ifBlank { "Unknown artist" }, formatDuration(file.durationMs)).joinToString(" • ")
            Text(if (lastPlayed) "Last played · $sub" else sub, color = if (lastPlayed) p.accent else p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (actions != null && !selectionMode) MediaMenu(file, actions)
    }
}

@Composable
fun MediaGridCard(
    file: MediaFile, selected: Boolean, selectionMode: Boolean, onClick: () -> Unit, onLongClick: () -> Unit,
    modifier: Modifier = Modifier, lastPlayed: Boolean = false, selectionOrder: Int? = null,
) {
    val p = LocalDhvon.current
    val isNew = file.uri in LocalNewUris.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box {
            MediaThumb(file, Modifier.fillMaxWidth().aspectRatio(if (file.isVideo) 1.6f else 1f), RoundedCornerShape(14.dp), showDuration = file.isVideo)
            if (selected) Box(Modifier.matchParentSize().clip(RoundedCornerShape(14.dp)).background(p.accent.copy(alpha = 0.35f)))
            if (lastPlayed) Box(Modifier.matchParentSize().border(2.dp, p.accent, RoundedCornerShape(14.dp)))
            if (selectionMode) SelectionMark(selected, Modifier.align(Alignment.TopStart).padding(8.dp), selectionOrder)
            if (isNew) NewBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(file.title, color = if (lastPlayed) p.accent else p.textPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (lastPlayed) "Last played" else if (file.isVideo) formatSize(file.sizeBytes) else file.artist.ifBlank { formatDuration(file.durationMs) }, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

/** Horizontal-carousel card: thumbnail + title + duration. */
@Composable
fun MediaCard(file: MediaFile, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 148.dp) {
    val p = LocalDhvon.current
    val isNew = file.uri in LocalNewUris.current
    Column(modifier.width(width).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth()) {
            MediaThumb(file, Modifier.fillMaxWidth().aspectRatio(1.45f), RoundedCornerShape(14.dp), showPlay = true)
            if (isNew) NewBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(file.title, color = p.textPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatDuration(file.durationMs), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

// ------------------------------------------------------------------------------------------------ folders

/** The "⋮" on a folder: today just "Delete folder". [onMedia] gives it a dark chip so it stays readable on thumbnails. */
@Composable
private fun FolderMenu(onDelete: () -> Unit, modifier: Modifier = Modifier, onMedia: Boolean = false) {
    val p = LocalDhvon.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.size(36.dp).then(if (onMedia) Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)) else Modifier),
        ) { Icon(Icons.Rounded.MoreVert, "Folder options", tint = if (onMedia) Color.White else p.textSecondary, modifier = Modifier.size(20.dp)) }
        DropdownMenu(open, { open = false }, containerColor = p.elevated) {
            DropdownMenuItem({ Text("Delete folder") }, { open = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
        }
    }
}

/** [onDelete] adds a "⋮ → Delete folder" menu and makes a long-press do the same. */
@Composable
fun FolderRow(folder: MediaFolder, onClick: () -> Unit, modifier: Modifier = Modifier, onDelete: (() -> Unit)? = null) {
    val p = LocalDhvon.current
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onDelete).padding(horizontal = 20.dp, vertical = if (LocalCompact.current) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Folder, null, tint = p.accent, modifier = Modifier.size(46.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(folder.name, color = p.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (folder.path in LocalNewFolderPaths.current) { Spacer(Modifier.width(8.dp)); NewBadge() }
            }
            Text(folder.summary, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Text(formatDate(folder.lastModifiedSec), color = p.textSecondary, style = MaterialTheme.typography.labelMedium)
        if (onDelete != null) FolderMenu(onDelete, Modifier.padding(start = 4.dp))
    }
}

@Composable
fun FolderCard(folder: MediaFolder, onClick: () -> Unit, modifier: Modifier = Modifier, onDelete: (() -> Unit)? = null) {
    val p = LocalDhvon.current
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(p.card).combinedClickable(onClick = onClick, onLongClick = onDelete)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).background(p.elevated), contentAlignment = Alignment.Center) {
            val covers = folder.coverUris
            if (covers.isEmpty()) Icon(Icons.Rounded.Folder, null, tint = p.accent, modifier = Modifier.size(56.dp))
            else Column(Modifier.fillMaxSize()) {
                covers.chunked(2).forEach { rowCovers ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        rowCovers.forEach { VideoFrame(it, Modifier.weight(1f).fillMaxHeight()) }
                        if (rowCovers.size == 1 && covers.size > 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (onDelete != null) FolderMenu(onDelete, Modifier.align(Alignment.TopEnd).padding(6.dp), onMedia = true)
            if (folder.path in LocalNewFolderPaths.current) NewBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Column(Modifier.padding(12.dp)) {
            Text(folder.name, color = p.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(folder.summary, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}
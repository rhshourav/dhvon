// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.rhshourav.dhvon.domain.MediaFile
import com.rhshourav.dhvon.domain.formatDuration
import com.rhshourav.dhvon.player.QueueEntry
import com.rhshourav.dhvon.ui.components.MediaThumb
import com.rhshourav.dhvon.ui.components.rememberSymmetricInsets
import com.rhshourav.dhvon.ui.theme.LocalDhvon

/**
 * The player's playlist: the queue that is playing right now (e.g. everything in the folder you opened), shown in the
 * same glass style as the settings window and sliding up from the bottom. Tap a row to jump to it.
 */
@Composable
fun VideoPlaylistPanel(
    entries: List<QueueEntry>,
    current: Int,
    onPick: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalDhvon.current
    val listState = rememberLazyListState()
    val safe = rememberSymmetricInsets()
    val side = safe.calculateLeftPadding(LayoutDirection.Ltr)   // same inset left and right (no camera-side-only gap); the panel sits flush with the top of its sheet
    LaunchedEffect(current, entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem((current - 1).coerceIn(0, entries.lastIndex))
    }
    Column(
        modifier.clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(p.card.copy(alpha = 0.97f))
            .padding(start = side, end = side, bottom = safe.calculateBottomPadding()),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(width = 38.dp, height = 4.dp).clip(CircleShape).background(p.textSecondary.copy(alpha = 0.4f)))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Playlist", color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text("${entries.size} item${if (entries.size == 1) "" else "s"} · now playing ${(current + 1).coerceAtLeast(1)}", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClose) { Icon(Icons.Rounded.Close, "Close", tint = p.textSecondary) }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(entries, key = { i, e -> "$i:${e.uri}" }) { index, e ->
                val isCurrent = index == current
                val file = remember(e.uri) { MediaFile(0, e.uri, e.title, e.title, e.durationMs, 0, 0, "", e.isVideo, artist = e.subtitle) }
                Row(
                    Modifier.fillMaxWidth().background(if (isCurrent) p.accent.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { onPick(index) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        MediaThumb(file, Modifier.size(width = if (e.isVideo) 96.dp else 56.dp, height = 56.dp), RoundedCornerShape(10.dp))
                        if (isCurrent) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.GraphicEq, "Playing", tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, color = if (isCurrent) p.accent else p.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(formatDuration(e.durationMs), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton({ onRemove(index) }) { Icon(Icons.Rounded.Close, "Remove from playlist", tint = p.textSecondary) }
                }
            }
        }
    }
}

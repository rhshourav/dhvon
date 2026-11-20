// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rhshourav.dhvon.data.local.PlaylistSummary
import com.rhshourav.dhvon.ui.theme.LocalDhvon

@Composable
fun TextInputDialog(
    title: String, initial: String, label: String, confirmText: String,
    onConfirm: (String) -> Unit, onDismiss: () -> Unit,
) {
    val p = LocalDhvon.current
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = p.card, titleContentColor = p.textPrimary,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton({ if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(confirmText, color = p.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = p.textSecondary) } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalDhvon.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = p.card, titleContentColor = p.textPrimary, textContentColor = p.textSecondary,
        title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onConfirm) { Text(confirmText, color = p.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = p.textSecondary) } },
    )
}

/** Pick an existing playlist or create a new one on the spot. */
@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistSummary>, onPick: (Long) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit,
) {
    val p = LocalDhvon.current
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextInputDialog("New playlist", "", "Name", "Create", onConfirm = { onCreate(it) }, onDismiss = { creating = false })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = p.card, titleContentColor = p.textPrimary,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Add, null, tint = p.accent); Spacer(Modifier.width(12.dp))
                        Text("New playlist…", color = p.accent, style = MaterialTheme.typography.titleSmall)
                    }
                }
                items(playlists, key = { it.id }) { pl ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(pl.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SilverIcon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, Modifier.size(24.dp)); Spacer(Modifier.width(12.dp))
                        Column { Text(pl.name, color = p.textPrimary); Text("${pl.itemCount} items", color = p.textSecondary, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Close", color = p.textSecondary) } },
    )
}

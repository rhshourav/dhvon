// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens

import android.Manifest
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow

/**
 * Hosts everything the system asks for while files are deleted / renamed / moved, so every screen that does it
 * (folder browser, folders tab) behaves the same:
 *  - the system consent dialog (Android 10+),
 *  - the legacy storage permission (Android 9 and older),
 *  - toasts.
 * [onResult] reports whether the user said yes, with the operation that was waiting.
 */
@Composable
fun FileEventHost(events: Flow<FileEvent>, onResult: (granted: Boolean, op: PendingOp) -> Unit) {
    val ctx = LocalContext.current
    val report by rememberUpdatedState(onResult)
    var pending by remember { mutableStateOf<PendingOp?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        pending?.let { report(r.resultCode == Activity.RESULT_OK, it) }
        pending = null
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending?.let { report(granted, it) }
        pending = null
    }

    LaunchedEffect(events) {
        events.collect { e ->
            when (e) {
                is FileEvent.Grant -> { pending = e.op; consentLauncher.launch(IntentSenderRequest.Builder(e.sender).build()) }
                is FileEvent.NeedWritePermission -> { pending = e.op; permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
                is FileEvent.Message -> Toast.makeText(ctx, e.text, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

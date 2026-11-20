// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rhshourav.dhvon.ui.components.DhvonLogo
import com.rhshourav.dhvon.ui.components.WaveGlow
import com.rhshourav.dhvon.ui.theme.LocalDhvon

@Composable
fun PermissionScreen(onGrant: () -> Unit, onPickFolder: () -> Unit) {
    val p = LocalDhvon.current
    Box(Modifier.fillMaxSize().background(p.background)) {
        WaveGlow(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            DhvonLogo(height = 84.dp)
            Spacer(Modifier.height(28.dp))
            Text("Bring in your library", color = p.textPrimary, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Text(
                "DHVON plays what's already on your device — fully offline, no ads, no account. " +
                    "Allow access to your videos and music so they can be listed. Nothing ever leaves your phone.",
                color = p.textSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onGrant, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White),
            ) { Text("Allow media access") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Text("Pick a folder instead", color = p.textPrimary)
            }
        }
    }
}

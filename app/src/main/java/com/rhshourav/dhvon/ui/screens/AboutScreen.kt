// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.rhshourav.dhvon.BuildConfig
import com.rhshourav.dhvon.ui.components.DhvonLogo
import com.rhshourav.dhvon.ui.components.WaveGlow
import com.rhshourav.dhvon.ui.theme.LocalDhvon

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val p = LocalDhvon.current
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(p.background)) {
        WaveGlow(Modifier.fillMaxWidth().height(260.dp).align(Alignment.BottomCenter))
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = p.textPrimary) }
                Text("About DHVON", color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                DhvonLogo(height = 96.dp)
                Spacer(Modifier.height(16.dp))
                Text("DHVON", color = p.textPrimary, style = MaterialTheme.typography.headlineMedium, letterSpacing = androidx.compose.ui.unit.TextUnit(6f, androidx.compose.ui.unit.TextUnitType.Sp))
                Text("Every Sound. Every Frame.", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
                Text("Version ${BuildConfig.VERSION_NAME}", color = p.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Created by", color = p.textSecondary, style = MaterialTheme.typography.labelMedium)
                    Text(BuildConfig.AUTHOR, color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
                    Text("An offline-first, ad-free video and music player — no accounts, no tracking, no ads.", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, BuildConfig.REPO_URL.toUri())) },
                    modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White),
                ) { Text("github.com/${BuildConfig.AUTHOR}/DHVON") }
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Open-source credits", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "AndroidX Media3 / ExoPlayer · Jellyfin FFmpeg decoder for Media3 · Jetpack Compose · Hilt · Room · Coil · " +
                            "Calvin Reorderable.\n\nLicensed GPL-3.0-or-later (required by the bundled FFmpeg decoder). " +
                            "Source code is available at the link above.",
                        color = p.textSecondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

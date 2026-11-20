// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhshourav.dhvon.R
import androidx.compose.ui.res.stringResource
import com.rhshourav.dhvon.ui.components.DhvonLogo
import com.rhshourav.dhvon.ui.components.WaveGlow
import com.rhshourav.dhvon.ui.theme.DhvonColors
import kotlinx.coroutines.delay

/** Deep-black splash: silver emblem, "DHVON" wordmark, tagline, and the purple→cyan wave glow. */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        delay(900)
        onFinished()
    }
    Box(Modifier.fillMaxSize().background(DhvonColors.BackgroundPrimary)) {
        WaveGlow(Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.Center).alpha(appear.value).scale(0.92f + 0.08f * appear.value),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DhvonLogo(height = 120.dp)
            Spacer(Modifier.height(22.dp))
            Text("DHVON", color = DhvonColors.TextPrimary, fontSize = 26.sp, letterSpacing = 9.sp)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.tagline), color = DhvonColors.TextSecondary, fontSize = 13.sp, letterSpacing = 3.sp)
        }
        CircularProgressIndicator(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp).size(26.dp).alpha(appear.value),
            color = DhvonColors.TextSecondary, strokeWidth = 2.dp,
        )
    }
}

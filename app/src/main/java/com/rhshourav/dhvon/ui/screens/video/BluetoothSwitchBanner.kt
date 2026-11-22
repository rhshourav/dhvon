// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens.video

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

/** Fixed visible time of the pill, restarted when another device connects while it is showing. */
private const val BANNER_VISIBLE_MS = 2600L

/**
 * "Switched to Bluetooth" HUD pill. It springs in from the top edge, shows the device name, and dismisses itself after
 * 2.6 s. Mount it at `Alignment.TopCenter` with `statusBarsPadding()`; it never intercepts touches.
 */
@Composable
fun BluetoothSwitchBanner(
    bluetoothEvents: SharedFlow<String>,
    modifier: Modifier = Modifier,
) {
    val p = LocalDhvon.current
    var visible by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf("Bluetooth Audio") }
    var triggerKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(bluetoothEvents) {
        bluetoothEvents.collect { name ->
            deviceName = name
            visible = true
            triggerKey++
        }
    }

    LaunchedEffect(triggerKey) {
        if (triggerKey > 0) {
            delay(BANNER_VISIBLE_MS)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(
            initialOffsetY = { -it - 24 },
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        ) + scaleIn(initialScale = 0.88f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) +
            fadeIn(animationSpec = tween(220)),
        exit = slideOutVertically(targetOffsetY = { -it - 16 }, animationSpec = tween(260, easing = FastOutSlowInEasing)) +
            scaleOut(targetScale = 0.92f, animationSpec = tween(260)) + fadeOut(animationSpec = tween(220)),
    ) {
        // The pulse lives inside the visible content, so it only animates while the pill is on screen.
        val pulse = rememberInfiniteTransition(label = "bt_pulse")
        val pulseScale by pulse.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "bt_pulse_scale",
        )
        Row(
            modifier = Modifier
                .shadow(16.dp, CircleShape, ambientColor = p.accent, spotColor = p.accent)
                .clip(CircleShape)
                .background(p.card.copy(alpha = 0.90f))
                .border(1.dp, Brush.horizontalGradient(listOf(p.accent, p.accentSecondary)), CircleShape)
                .padding(start = 10.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(34.dp).scale(pulseScale).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(p.accentSecondary.copy(alpha = 0.33f), Color.Transparent)))
                )
                Box(Modifier.size(30.dp).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.BluetoothAudio, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                }
            }
            Column {
                Text("Switched to Bluetooth", color = p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                AnimatedContent(
                    targetState = deviceName,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                    label = "bt_device_name",
                ) { name ->
                    Text(name, color = p.accentSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

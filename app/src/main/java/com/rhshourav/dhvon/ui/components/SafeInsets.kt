// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.components

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Safe-area padding that is the SAME on opposite edges.
 *
 * The video player hides the system bars, which makes `navigationBarsPadding()` / `systemBarsPadding()` collapse to 0, while
 * `displayCutoutPadding()` keeps adding the camera's depth on *one* edge only — so the controls sat far below the camera and hard
 * against the other edge (top vs bottom in portrait, left vs right in landscape). This measures the system bars (as if they were
 * showing) plus the cutout, then uses the larger of each opposite pair for both: top = bottom, left = right.
 */
@OptIn(ExperimentalLayoutApi::class)   // systemBarsIgnoringVisibility is still marked experimental
@Composable
fun rememberSymmetricInsets(minVertical: Dp = 8.dp): PaddingValues {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val vertical = maxOf(insets.getTop(density), insets.getBottom(density))
    val horizontal = maxOf(insets.getLeft(density, direction), insets.getRight(density, direction))
    return with(density) { PaddingValues(horizontal = horizontal.toDp(), vertical = maxOf(vertical.toDp(), minVertical)) }
}

// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhshourav.dhvon.ui.RootViewModel
import com.rhshourav.dhvon.ui.components.LocalArtworkLoader
import com.rhshourav.dhvon.ui.components.LocalNewUris
import com.rhshourav.dhvon.ui.nav.DhvonRoot
import com.rhshourav.dhvon.ui.theme.DhvonTheme
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import dagger.hilt.android.AndroidEntryPoint

/** DHVON main entry — hosts splash, permission flow and the bottom-nav shell. By rhshourav. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: RootViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val newUris by vm.newUris.collectAsStateWithLifecycle()
            DhvonTheme(settings.themeMode, settings.accentCyan, settings.compactDensity, settings.accentArgb, settings.textScale) {
                val dark = LocalDhvon.current.isDark
                // Keep status/navigation bar icon contrast in sync with the *app* theme, not the system theme.
                LaunchedEffect(dark) {
                    val t = android.graphics.Color.TRANSPARENT
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
                        navigationBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
                    )
                }
                CompositionLocalProvider(LocalArtworkLoader provides vm.artwork, LocalNewUris provides newUris) { DhvonRoot(vm) }
            }
        }
    }
}

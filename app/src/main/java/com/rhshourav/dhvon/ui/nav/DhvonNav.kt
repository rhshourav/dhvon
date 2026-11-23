// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.nav

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import com.rhshourav.dhvon.player.ResumeResult
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rhshourav.dhvon.VideoPlayerActivity
import com.rhshourav.dhvon.ui.RootViewModel
import com.rhshourav.dhvon.ui.screens.*
import com.rhshourav.dhvon.ui.theme.LocalDhvon

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val MORE = "more"
    const val NOW_PLAYING = "nowplaying"
    const val EQUALIZER = "equalizer"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val SEARCH = "search"
    const val FOLDER = "folder/{path}"
    const val PLAYLIST = "playlist/{id}"

    // Pseudo-folders reuse the folder browser for "all videos", "all music" and favorites.
    const val ALL_VIDEOS = "@videos"
    const val ALL_MUSIC = "@music"
    const val FAVORITES = "@favorites"

    /** Folder paths contain '/', so they're sent URL-safe-base64 encoded (exact round-trip, no escaping surprises). */
    fun folder(path: String) = "folder/" + Base64.encodeToString(path.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun decodePath(arg: String): String = String(Base64.decode(arg, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
    fun playlist(id: Long) = "playlist/$id"
}

fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

fun hasMediaPermission(ctx: Context): Boolean {
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    return if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_VIDEO) || granted(Manifest.permission.READ_MEDIA_AUDIO)
    else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
fun DhvonRoot(vm: RootViewModel) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var splashDone by rememberSaveable { mutableStateOf(false) }
    var granted by remember { mutableStateOf(hasMediaPermission(ctx)) }
    val hasSaf = settings.safTrees.isNotEmpty()

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasMediaPermission(ctx) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.addSafTree(uri)
        }
    }

    val access = granted || hasSaf
    LaunchedEffect(access) { if (access) vm.onMediaAccess() }
    LaunchedEffect(access, splashDone) {
        if (access && splashDone && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    when {
        !splashDone -> SplashScreen { splashDone = true }
        !access -> PermissionScreen(onGrant = { permLauncher.launch(mediaPermissions()) }, onPickFolder = { treeLauncher.launch(null) })
        else -> MainScaffold(vm, onPickFolder = { treeLauncher.launch(null) })
    }
}

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

@Composable
private fun MainScaffold(vm: RootViewModel, onPickFolder: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalDhvon.current
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val playback by vm.playback.state.collectAsStateWithLifecycle()
    val items = remember {
        listOf(
            NavItem(Routes.HOME, "Home", Icons.Rounded.Home),
            NavItem(Routes.LIBRARY, "Folders", Icons.Rounded.Folder),
            NavItem(Routes.PLAYLISTS, "Playlists", Icons.AutoMirrored.Rounded.PlaylistPlay),
            NavItem(Routes.MORE, "More", Icons.Rounded.MoreHoriz),
        )
    }
    val topLevel = route in items.map { it.route }

    fun go(r: String) = nav.navigate(r) { launchSingleTop = true }
    fun goTop(r: String) = nav.navigate(r) {
        popUpTo(Routes.HOME) { saveState = true }; launchSingleTop = true; restoreState = true
    }
    val openNowPlaying = { go(Routes.NOW_PLAYING) }
    val scope = rememberCoroutineScope()
    val playLast: () -> Unit = {
        scope.launch {
            when (vm.playback.resumeLast()) {
                ResumeResult.AUDIO -> openNowPlaying()
                ResumeResult.VIDEO -> {}
                ResumeResult.NONE -> Toast.makeText(ctx, "Nothing played yet — play something and this button will resume it", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        containerColor = p.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            Column(Modifier.background(p.background)) {
                if (topLevel) {
                    NavigationBar(containerColor = p.background, tonalElevation = 0.dp) {
                        items.forEach { item ->
                            NavigationBarItem(
                                selected = route == item.route, onClick = { goTop(item.route) },
                                icon = { Icon(item.icon, item.label) }, label = { Text(item.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = p.accent, selectedTextColor = p.accent, indicatorColor = Color.Transparent,
                                    unselectedIconColor = p.textSecondary, unselectedTextColor = p.textSecondary,
                                ),
                            )
                        }
                    }
                } else Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize()) {
            composable(Routes.HOME) {
                HomeScreen(onNavigate = ::go, onOpenFolder = { go(Routes.folder(it)) }, onOpenNowPlaying = { openNowPlaying() }, onSwitchTab = ::goTop)
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(onOpenFolder = { go(Routes.folder(it)) }, onSearch = { go(Routes.SEARCH) }, onPickFolder = onPickFolder)
            }
            composable(Routes.PLAYLISTS) {
                PlaylistsScreen(onOpenPlaylist = { go(Routes.playlist(it)) }, onOpenFolder = { go(Routes.folder(it)) })
            }
            composable(Routes.MORE) { MoreScreen(onNavigate = ::go, onSwitchTab = ::goTop) }
            composable(Routes.FOLDER, arguments = listOf(navArgument("path") { type = NavType.StringType })) {
                FolderBrowserScreen(onBack = { nav.popBackStack() }, onOpenNowPlaying = { openNowPlaying() })
            }
            composable(Routes.PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                PlaylistDetailScreen(onBack = { nav.popBackStack() }, onOpenNowPlaying = { openNowPlaying() })
            }
            composable(
                Routes.NOW_PLAYING,
                enterTransition = { slideInVertically { it } },
                exitTransition = { slideOutVertically { it } },
                popEnterTransition = { slideInVertically { it } },
                popExitTransition = { slideOutVertically { it } },
            ) { NowPlayingScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.EQUALIZER) { EqualizerScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }, onPickFolder = onPickFolder, onAbout = { go(Routes.ABOUT) }) }
            composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SEARCH) { SearchScreen(onBack = { nav.popBackStack() }, onOpenNowPlaying = { openNowPlaying() }) }
        }
        // Sticky round button on the main tabs (hidden on full-screen / detail screens so it never covers content).
        if (topLevel) NavFab(playing = playback.isPlaying, onPlayLast = playLast, modifier = Modifier.fillMaxSize())
        }
    }
}

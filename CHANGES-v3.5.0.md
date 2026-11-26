Implement three features on top of the current **DHVON 3.5.0** codebase (`com.rhshourav.dhvon`):
1. `NEW` badges on newly added media files (both MediaStore and SAF folders) that clear once played while still tagging future additions as `NEW`.
2. Ordered multi-select temporary queue playback (`1, 2, 3…` tap order) in any folder or playlist without modifying saved playlists or breaking Round 3's batch delete flow.
3. A Shuffle toggle button in both the Audio Player (`MusicPlayerScreen.kt`) and Video Player (`VideoPlayerScreen.kt` + `VideoPlaylistPanel`), using Round 4's `ExoPlayer.shuffleFromCurrent()` so shuffled queues never end early.

Inspect **only** the specific files referenced below. Edit files in-place, do not print full file contents in your response, and preserve all Round 3–7 behavior (`SafeInsets.kt`, `FileOps.kt`, `LibVlcFallbackPlayer.kt`, `NavFab.kt`, and `DhvonNav.kt`).

---

### 1. `NEW` Badge Lifecycle for Newly Added Files

#### A. Room Entity, DAO & Non-Destructive Migration (`data/local/Database.kt` & `di/AppModule.kt`)
- Check the current `version` on `DhvonDatabase` in `data/local/Database.kt`, increment it by `1` (e.g., `N -> N + 1`), register `KnownMediaEntity::class`, and expose `abstract fun knownMedia(): KnownMediaDao`.
- Add the corresponding `Migration(N, N + 1)` to `Room.databaseBuilder(...)` in `di/AppModule.kt` so existing history, favorites, and playlists remain intact.

```kotlin
// data/local/Database.kt
@Entity(tableName = "known_media")
data class KnownMediaEntity(
    @PrimaryKey val uri: String,
    val firstSeenAt: Long,
    val isNew: Boolean,
)

@Dao
interface KnownMediaDao {
    @Query("SELECT uri FROM known_media")
    suspend fun allUris(): List<String>

    @Query("SELECT uri FROM known_media WHERE isNew = 1")
    fun observeNewUris(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<KnownMediaEntity>)

    @Query("UPDATE known_media SET isNew = 0 WHERE uri = :uri")
    suspend fun markPlayed(uri: String)
}

// di/AppModule.kt — match current DhvonDatabase version N -> N + 1
val MIGRATION_KNOWN_MEDIA = object : Migration(CURRENT_DB_VERSION, CURRENT_DB_VERSION + 1) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `known_media` (`uri` TEXT NOT NULL, `firstSeenAt` INTEGER NOT NULL, `isNew` INTEGER NOT NULL, PRIMARY KEY(`uri`))"
        )
    }
}
```

#### B. Scan Baseline & Future Diffing (`data/media/MediaRepository.kt`)
- Call `syncKnownMedia(allFiles.map { it.uri })` at the end of every combined MediaStore + SAF folder scan in `MediaRepository.kt`, and expose `val newUris: Flow<Set<String>> = knownMediaDao.observeNewUris().map { it.toSet() }`.

```kotlin
private suspend fun syncKnownMedia(scannedUris: List<String>) {
    if (scannedUris.isEmpty()) return
    val existing = knownMediaDao.allUris().toHashSet()
    val now = System.currentTimeMillis()
    if (existing.isEmpty()) {
        // First-launch baseline: mark pre-existing library files as seen so the UI isn't flooded
        knownMediaDao.insertAll(scannedUris.map { KnownMediaEntity(it, now, isNew = false) })
    } else {
        val added = scannedUris.filter { it !in existing }
        if (added.isNotEmpty()) {
            knownMediaDao.insertAll(added.map { KnownMediaEntity(it, now, isNew = true) })
        }
    }
}
```

#### C. Clear `NEW` Badge on Playback (`player/PlaybackController.kt` & `VideoPlayerActivity.kt`)
- Call `knownMediaDao.markPlayed(uri)` (or route through `UserDataRepository.markPlayed(uri)`) whenever any audio or video track starts playing:
  - In `PlaybackController`: inside `playFrom(...)`, `Player.Listener.onMediaItemTransition(...)`, and when `LibVlcFallbackPlayer` starts a track.
  - In `VideoPlayerActivity`: inside both `onCreate` and `onNewIntent` for external `VIEW` intents or direct launches.
- Once `isNew` becomes `0`, that file never regains the `NEW` badge, while any files added in later scans automatically receive `isNew = true`.

#### D. `LocalNewUris` & `NewBadge` (`ui/components/Common.kt` & `ui/nav/DhvonNav.kt`)
- Expose `newUris: StateFlow<Set<String>>` from `RootViewModel` and wrap `MainScaffold` in `DhvonNav.kt` with `CompositionLocalProvider(LocalNewUris provides newUris)` without altering the bottom `NavigationBar` or `NavFab`.
- Render `NewBadge` in `MediaListRow`, `MediaGridCard`, and `MediaCard` (`Common.kt`) whenever `file.uri in LocalNewUris.current`:

```kotlin
// ui/components/Common.kt
val LocalNewUris = staticCompositionLocalOf<Set<String>> { emptySet() }

@Composable
fun NewBadge(modifier: Modifier = Modifier) {
    val p = LocalDhvon.current
    Text(
        text = "NEW",
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(p.accent)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
```
- Place `NewBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))` on `MediaGridCard` / `MediaCard` thumbnails, and inline beside the title in `MediaListRow` when `file.uri in LocalNewUris.current`.

---

### 2. Ordered Multi-Select Temporary Queue (Folders & Playlists)

#### A. Numbered Selection Indicator (`ui/components/Common.kt`)
- Update `SelectionMark`, `MediaListRow`, and `MediaGridCard` to accept `orderNumber: Int? = null`:

```kotlin
@Composable
private fun SelectionMark(selected: Boolean, orderNumber: Int? = null, modifier: Modifier = Modifier) {
    val p = LocalDhvon.current
    Box(
        modifier.size(22.dp).clip(CircleShape)
            .background(if (selected) p.accent else Color.Black.copy(alpha = 0.45f))
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            if (orderNumber != null) {
                Text(
                    text = orderNumber.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}
```

#### B. Ordered Selection & Temporary Queue Playback (`ui/screens/FolderBrowserScreen.kt` & `ui/screens/PlaylistsScreen.kt`)
- In `FolderBrowserScreen.kt`, **keep Round 3's existing batch Delete action intact**, and upgrade the multi-select state from `Set<String>` to an ordered `List<String>` so tapping items appends `file.uri` in exact tap sequence (`1, 2, 3…`):

```kotlin
var selectedUris by remember { mutableStateOf<List<String>>(emptyList()) }
val selectionMode = selectedUris.isNotEmpty()

fun toggleSelect(uri: String) {
    selectedUris = if (uri in selectedUris) selectedUris - uri else selectedUris + uri
}

fun playOrderedTemporaryQueue(allFiles: List<MediaFile>) {
    val orderedQueue = selectedUris.mapNotNull { uri -> allFiles.find { it.uri == uri } }
    if (orderedQueue.isNotEmpty()) {
        playback.playFrom(orderedQueue.first(), orderedQueue)
        if (!orderedQueue.first().isVideo) onOpenNowPlaying()
    }
    selectedUris = emptyList()
}
```
- Pass `orderNumber = selectedUris.indexOf(file.uri).takeIf { it >= 0 }?.plus(1)` into `MediaListRow` and `MediaGridCard`.
- Add a **"Play Queue"** action button (`Icons.Rounded.PlayArrow`) next to the existing Delete action in `FolderBrowserScreen.kt`'s selection bar, and implement the exact same ordered multi-select + **"Play Queue"** action inside `PlaylistsScreen.kt` (playlist detail view).
- Do **not** save or alter any Room playlist when `playOrderedTemporaryQueue` runs—play directly as an active temporary queue.

---

### 3. Shuffle Button in Audio Player & Video Player (Preserving `shuffleFromCurrent()`)

#### A. Controller & Fallback Sync (`player/PlaybackController.kt`, `LibVlcFallbackPlayer.kt`, `ui/screens/video/VideoPlayerViewModel.kt`)
- Ensure `PlaybackUiState` exposes `val shuffleEnabled: Boolean = false` synced via `Player.Listener.onShuffleModeEnabledChanged`.
- In `PlaybackController.toggleShuffle()`, use Round 4's `player.shuffleFromCurrent()` from `PlayerHolder.kt` whenever enabling shuffle so the current track stays first and every remaining track plays once:

```kotlin
// player/PlaybackController.kt
override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
    _state.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
}

fun toggleShuffle() {
    val next = !player.shuffleModeEnabled
    player.shuffleModeEnabled = next
    if (next) {
        player.shuffleFromCurrent()
    }
    vlcFallback?.setShuffleEnabled(next)
    _state.update { it.copy(shuffleEnabled = next) }
}
```
- In `LibVlcFallbackPlayer.kt`, add `setShuffleEnabled(enabled: Boolean)` so enabling shuffle randomizes the remaining unplayed queue items after the current index (mirroring `shuffleFromCurrent()`) and disabling restores original queue order.
- Expose `shuffleEnabled` and `toggleShuffle()` on `VideoPlayerViewModel`.

#### B. Player UI Controls (`ui/screens/MusicPlayerScreen.kt`, `ui/screens/video/VideoPlayerScreen.kt`, `VideoPlaylistPanel.kt`)
- **Audio Player (`MusicPlayerScreen.kt`):** Ensure the Shuffle toggle `IconButton` (`Icons.Rounded.Shuffle`) is visible in the main transport controls row and highlights with `p.accent` when `shuffleEnabled == true`.
- **Video Player (`VideoPlayerScreen.kt` & `VideoPlaylistPanel.kt`):** Do **not** add a 10th button to the `360 dp` portrait top bar. Instead, place the Shuffle toggle `IconButton` (`Icons.Rounded.Shuffle`) in the **video bottom transport bar** (alongside Previous / Play-Pause / Next) and in the **`VideoPlaylistPanel` header**, tinted `p.accent` when active and `p.textSecondary` when off:

```kotlin
IconButton(onClick = onToggleShuffle) {
    Icon(
        imageVector = Icons.Rounded.Shuffle,
        contentDescription = "Shuffle",
        tint = if (shuffleEnabled) p.accent else p.textSecondary,
    )
}
```

---

### 4. Verify
- Run `./gradlew assembleDebug` and fix any compilation errors.
- Reply only with a concise bullet list of modified files and confirmation of the debug build.
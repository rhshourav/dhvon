# DHVON 3.0.1 — change notes

Drop the `app/` tree from the zip over your repo (paths mirror the repo). **Nothing here has been compiled** — the build
container had no Kotlin compiler or Android SDK and no network. Run `./gradlew assembleDebug` first; see "Not verified".

## Files

**New:** `SupportedMediaFormats.kt`, `LibVlcFallbackPlayer.kt`, `BluetoothSwitchBanner.kt`

**Rewritten:** `DhvonAudioProcessor.kt`, `AudioEffectsController.kt`, `EqualizerScreen.kt`

**Patched / merged:** `Common.kt`, `DhvonNav.kt`, `NavFab.kt`, `MoreScreen.kt`, `VideoQuickSettings.kt`, `build.gradle.kts` (3.0.1 / code 3, libvlc, `libc++_shared` pickFirst), `proguard-rules.pro`, `AndroidManifest.xml`,
`MediaRepository.kt`, `PlayerHolder.kt`, `PlaybackController.kt`, `SleepTimer.kt`, `SettingsRepository.kt`,
`VideoPlayerActivity.kt`, `VideoPlayerViewModel.kt`, `VideoPlayerScreen.kt`, `AboutScreen.kt`, `MusicPlayerScreen.kt`

**Deliberately untouched:** `view_player_texture.xml` (already `texture_view`), `VideoQuickSettings.kt` (exposes no EQ presets),
`AppModule.kt` (`DhvonAudioProcessor` is `@Singleton @Inject`, so Hilt needs no binding). `AboutScreen` shows the version from
`BuildConfig.VERSION_NAME`, so it reads 3.0.1 automatically; I only added the LibVLC credit.

## Your earlier changes, merged in

Merged against the project snapshot I built from (3-way for files we both edited), so nothing of yours or mine was overwritten.

| Your change | Files | Result |
|---|---|---|
| Sleep timer can close the app (`closeApp`, `shutDownApp`) | `SleepTimer.kt`, `SettingsRepository.kt` (`sleepCloseApp` / `SLEEP_CLOSE`), `MoreScreen.kt` | Yours, plus my `fired` signal (so VLC stops when a timer fires) |
| Sleep timer page in the video quick-settings | `VideoQuickSettings.kt`, `VideoPlayerViewModel.kt` (`val sleepTimer`, `setSleepCloseApp`) | Applied from your diff; the constructor param is public as you had it |
| Bottom `NavigationBar` + simple round button, mini player removed | `DhvonNav.kt`, `NavFab.kt`, `Common.kt` | Taken as you uploaded them |

`HomeScreen`, `LibraryScreen`, `PlaylistsScreen` and `VideoPlayerScreen` were byte-identical to the snapshot (your padding and
layout changes were already in it), and `resumeLast()` / `ResumeResult` were already in `PlaybackController`, so those aren't repeated here.

One new interaction: **"End of this video"** listened only to ExoPlayer, so it would never have fired while the LibVLC fallback
was playing. `SleepTimer.onExternalTrackEnded()` now lets VLC report its end (with "Close DHVON" honoured).

## Round 3 — video buttons, dimming, headphone safety, delete

**Lo-Fi and Auto EQ buttons** sit right after Background play in the video top bar (`VideoPlayerScreen`). They glow with the accent colour when
on and toast what they did. Auto EQ toggles the adaptive EQ (and wakes the EQ chain if it was off). Lo-Fi also slows the *video* to 0.88×,
because it is a playback-speed effect. Both are unavailable while the VLC engine plays (the toast says so). All eight top icons are now 40 dp so
they still fit a 360 dp phone.

**Brightness → extra dim.** One continuous swipe axis on the left half: swiping down lowers the real brightness to its minimum and *stays
there* ("0 %"); swiping further deepens a black "extra dim" overlay (up to 85 %) with a **blue** HUD bar ("Dim 40 %"). Swiping up undoes the dim
first, then raises brightness. The overlay is touch-transparent, hidden in picture-in-picture, and the controls fade a little under it.
It is a software layer: on OLED it really is darker, on LCD it dims the picture but not the backlight. Not saved between videos.

**Phone-level brightness on open.** A new video starts at the phone's own brightness with no dim. `onNewIntent` clears the window brightness override
(a previous video's setting no longer leaks into the next open) and the screen re-reads the phone level.

**Headphone volume safety.** When headphones (wired, USB, Bluetooth A2DP/LE, hearing aid) connect while DHVON has something loaded, the system
media volume is capped at 40 % (`SAFE_VOLUME_FRACTION` in `AudioEffectsController`) and the sound fades in over ~1.2 s. If playback is paused —
the usual unplug → re-plug → press play case — the cap is armed and applied the instant play starts. DHVON never touches the volume while it has
nothing loaded. Switch: **Settings → Playback → Safe headphone volume**. The volume swipe now re-syncs, so it doesn't jump after the cap.
Limit: the cap runs when Android announces the new device, so a few tens of milliseconds of the old level are possible.

**Delete files and folders.**
- *Folders:* each folder in the Folders tab has a "⋮ → Delete folder" menu (and long-press) with a confirm dialog. It deletes every media file
  DHVON knows in that folder in one system consent dialog, then removes the directory if nothing else is left in it (best effort; non-media
  files such as subtitles keep the folder alive).
- *Files:* the existing select-and-trash flow now actually works everywhere. It was broken on Android 10 (consent was never followed by a
  retry), on Android 9 and older (no write permission → always "blocked"; the manifest now has `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion=28` and the
  app asks for it when needed), and for folders added with "Add folder" (only a read grant was kept, and failures were swallowed behind a
  "Deleted" toast). New grants include write access; folders added before need removing and adding once more, and the app now says so instead of
  pretending.
- Deleting is **permanent** (Android's own dialog, no recycle bin). Android 11+ shows one dialog for the whole batch.

New file: `FileOps.kt` (shared consent / permission host). Touched: `Common.kt` (folder menu), `LibraryScreen.kt`, `FolderBrowserScreen.kt`,
`MediaRepository.kt`, `AudioEffectsController.kt`, `SettingsRepository.kt`, `SettingsScreen.kt`, `VideoPlayerActivity.kt`, `VideoPlayerViewModel.kt`,
`VideoPlayerScreen.kt`, `DhvonNav.kt`, `AndroidManifest.xml`. `LibraryScreen`, `FolderBrowserScreen` and `SettingsScreen` were taken from your snapshot
(you hadn't changed them) — diff them if you edited those locally.

## Round 4 — shuffle fix, Crop/Fill default, aspect button

**Shuffle played only a few tracks, then ended.** Cause: ExoPlayer's default shuffle order is a random permutation in which the *current* item sits at
a random position, and playback only walks forward from there. Start on a track that lands near the end of the permutation and the queue runs out after a
couple of tracks. Fixed with `ExoPlayer.shuffleFromCurrent()` (in `PlayerHolder.kt`): the current item goes first, then every other item exactly once.
It is applied in all three places that had the bug — starting a shuffled queue (playlist Shuffle, shuffled folder/music), the Shuffle button in Now Playing
(turning it on mid-queue), and the automatic player rebuild on a decoder switch. With repeat off the queue now ends only after every track has played.

**Crop / Fill by default.** Every video opens in Crop / Fill. Your aspect choice (and pinch-zoom) sticks while you move through the queue and resets on the next open,
the same rule as brightness.

**Aspect-ratio button** in the video top bar, right after Auto EQ: each tap cycles Fit → Crop/Fill → Stretch → 16:9 → 4:3 → 100 % and toasts the mode
(it also works for the VLC engine). With nine buttons, portrait icons are 36 dp (landscape keeps 40 dp) so they fit a 360 dp phone.

## Round 5 — uneven video player spacing

**Symptom:** a big gap on the camera side (top in portrait, one side in landscape) and controls hard against the opposite edge.
**Cause:** the player hides the system bars, so `navigationBarsPadding()` / `systemBarsPadding()` collapsed to 0 while `displayCutoutPadding()` kept adding the
camera's depth on one edge only. Audio mode and the in-player playlist panel had the same pattern.
**Fix:** new `rememberSymmetricInsets()` (`ui/components/SafeInsets.kt`) measures the system bars *as if they were showing* plus the cutout and uses the larger of each
opposite pair for both edges (top = bottom, left = right, minimum 8 dp). Applied to the top bar, bottom bar, quick-settings menu, lock pill, Bluetooth pill offset,
audio mode and the playlist panel. The window also now draws under the camera on every edge (`LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` on Android 11+), so Android can't
letterbox a black bar on the notch side. On a phone with no notch the spacing is unchanged (8 dp minimum).

`systemBarsIgnoringVisibility` is still `@ExperimentalLayoutApi`, so `rememberSymmetricInsets` carries an `@OptIn` for it.

## Round 6 — English audio by default

When a video has more than one audio track, DHVON now picks the English one automatically. Switch: **Settings → Playback → Prefer English audio** (on by default).

How it decides (`AudioTrackPicker.kt`, pure Kotlin, shared by ExoPlayer and the VLC engine):
- **Language tag:** `en`, `eng`, `en-US`, `en_GB`, `english` (tag `und` is ignored).
- **Track name:** "English", "ENG", "Eng 5.1", "[EN] Dolby", "DubEnglish", and other languages' word for it (Inglés, Anglais, Englisch, Inglese, 英语 …).
- **Typos:** "Englsih", "Enlgish", "Englis" — one edit or one swapped pair away from "english", only for words of similar length, so "Bengali", "Finnish", "Spanish", "Enemy" and "Engine" never match.
- **Never commentary:** tracks flagged as commentary / audio description, or named "Commentary", "Director", "Descriptive", "Narration"… are skipped.
- **Ties:** the container's default track, then more channels (up to 8), then the earlier track.

When it acts: only with 2+ audio tracks, once per file (re-checked if more tracks appear later, as in transport streams). It leaves things alone when the current track is already English,
when nothing looks English, or after you pick a track yourself in Quick settings → Audio Track (a manual choice wins for that video). ExoPlayer also gets the plain `en` language hint, so
properly tagged files start on the English track from the first frame; the name matching covers files with missing or wrong tags. In the VLC engine the same rules run on VLC's track names.

## Where I changed the prompt's own code, and why

| Prompt code | Problem | What I did |
|---|---|---|
| New `DhvonAudioProcessor` | Dropped the **audio-delay** (A/V sync stepper) and the **level meter** (waveform pulse) that the app already uses; threw on non-16-bit PCM | Merged: delay + level + spectral analyzer; other encodings return `NOT_SET` (processor skipped) |
| `setMbcAllChannelsTo(Mbc(..))` on every apply | Replaces the Mbc object, **resetting the three compressor bands** just configured | Bands are rewritten with their on/off flag instead |
| `Virtualizer` / `PresetReverb` unguarded | Throws on devices without them → crash | `runCatching` around every effect |
| Full re-apply every 400 ms | ~55 binder calls per window | Cache of applied values; only changed bands cross the binder |
| EQ band frequencies used as cutoffs | DynamicsProcessing bands are cut at their *upper* edge, so "1 kHz" moved everything below 1 kHz | Cutoffs at centre × √2 (44 … 20000 Hz); labels stay 31 Hz … 16 kHz |
| Custom bands start at 0 dB | First slider drag wiped the visible signature curve | Drag starts from the curve on screen |
| Bluetooth callback | Android replays already-connected devices as "added" | Only devices not connected at start-up trigger the pill |
| Pill pulse animation | Ran every frame while hidden | Lives inside the visible content |
| `pathPattern` in the existing filter | Android ignores path attributes without a host, and it would have demanded an extension in every `content://…/media/123` | Two separate filters with `host="*"` (with and without `*/*`), 3 dot-depths per extension, generated by script |
| `.ts` indexing | `.ts` is also TypeScript | Tiny duration-less `.ts` files are skipped |
| `!group.isSupported` | `isSupported()` is false for *exceeds hardware capability* too → needless VLC for 4K HEVC | Only "no decoder at all" triggers VLC |
| `--no-drop-late-frames --no-skip-frames` | Slow CPUs drift out of A/V sync | Removed; late-frame dropping stays on |
| `Media(libVlc, uri)` | LibVLC cannot open `content://` | Opened through a `ParcelFileDescriptor` |
| MBC `postGain = 1 dB` | Adds level the headroom budget doesn't account for | 0 dB |

Behaviour kept from the original app: global EQ settings persist (new DSP keys only — **no per-headphone profiles**), the 100–150 %
volume boost (`LoudnessEnhancer`) still works, `HW`/`HW+`/`SW` keep their different extension-renderer modes (I did not collapse
them to `PREFER`), and **Settings → Seek precision** still governs every seek outside scrubbing.

## Things you should know

- **Default sound changes.** DSP ships *on* with Harman + adaptive EQ, which lowers input gain by the largest band boost (3.5 dB for
  Harman) so nothing can clip. Previously the EQ was off by default. To ship neutral, set `enabled = false` in `AudioDspState`.
- **VLC mode bypasses the DSP.** It decodes its own audio. A "VLC" badge shows in the top bar. Embedded subtitles aren't shown
  (VLC's renderer is off so DHVON's synced external subtitles work); external `.srt/.ass/.vtt` loading works.
- Lo-Fi mode is per-session (not saved). The old Bass-boost slider is gone — "Sub-Bass Boost" is a signature now.
- Scrubbing: `CLOSEST_SYNC` while dragging / double-tapping, one `EXACT` seek on release, then your configured precision returns.
- External audio files (VIEW intent) now open in the video screen's audio mode.
- `MediaStore.Files` extras query: on **Android 11+ scoped storage hides non-media files** (`.ac3`, `.dts`, …) from apps without
  all-files access, which DHVON deliberately never requests. For those, use **Add folder** — the SAF scanner knows every extension.
- The Now Playing equalizer sheet now opens full height and scrolls (the new panel is taller).

## Not verified (please check)

1. Compilation. Most likely friction: `libvlc-all:3.6.0` resolving (bump to latest 3.6.x if not), and VLC API names (`attachViews`,
   `setVideoScale`, `Event.Vout`) if a newer libvlc moved them.
2. **APK size**: libvlc-all adds all four ABIs (tens of MB). Consider `splits`/`abiFilters` for release.
3. **16 KB page size**: confirm the libvlc build you pick ships 16 KB-aligned `.so` files if you publish on Google Play (targetSdk 36).
4. `DynamicsProcessing` / `PresetReverb` behaviour varies by OEM; `PresetReverb` as a session insert effect can sound wetter on some devices.
5. Manifest `pathPattern`s are case-sensitive (lower-case extensions only).

## Device test checklist

- MKV with Japanese + English tracks (tags only, then names only with tag `und`): the English one plays; Quick settings → Audio Track highlights it; picking Japanese by hand sticks until the next video.
- A file whose only English track is "Commentary": the original track is left alone.

- Notch/punch-hole phone: portrait and landscape — top and bottom (and left and right) control margins should now match; nothing hides under the camera.

- Playlist with ~10 items → Shuffle: it should play all 10 in random order, then stop. Try Now Playing's Shuffle button mid-queue too.
- Open any video: it starts in Crop / Fill; tap the aspect button to cycle; open another video from the app and it is Crop / Fill again.

- Video top bar: Lo-Fi and Auto EQ appear after the headphones icon and toggle; with the VLC badge they explain why they can't.
- Left-half swipe down: brightness hits 0 % and stays; keep swiping → blue "Dim" bar and a darker screen; swipe up → dim first, then brightness.
- Open another video from the app: starts at the phone's brightness, no dim.
- Play at a high volume on speakers, plug headphones: volume drops to ~40 % and fades in; pause → unplug → re-plug → press play: same.
- Folders tab: "⋮ → Delete folder" on a test folder; also try select-and-delete inside a folder; test once on an Android 9/10 device if you have one.

- DivX/Xvid `.avi` and an MPEG-2 `.ts`: should flip to VLC with a toast; seek bar, double-tap, speed, pinch-zoom, PiP all work; "Next" starts the next item.
- A normal H.264 `.mp4` must **not** switch to VLC.
- Equalizer: Harman → cyan bars move while music plays; Flat + adaptive off → no cyan; Lo-Fi → 0.88× and reverb; turn it off → previous speed returns.
- Connect Bluetooth headphones during a video → pill appears for 2.6 s, once.
- Drag the seek bar on a long-GOP `.mkv`: preview snaps instantly, release lands exactly.

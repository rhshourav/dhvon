# Changelog

All notable changes to **DHVON** — by [rhshourav](https://github.com/rhshourav).

## 2.0.0
**Navigation**
- A sticky round DHVON button (the app icon) on the right: **tap = play / reopen whatever you played last**. The ring spins while playing.
  It replaces the old mini-player bar above the navigation bar.
- "Library" became **Folders** (no Videos/Music/Favorites rows; storage bar pinned at the bottom).
- Home gained a dedicated **Folders** section and a Folders-only filter; each Home section can be hidden.
- Every folder remembers the **last file played** (highlighted, scrolled to, "Continue where you left off").

**Video player**
- Background play, Audio mode, Pop-up and Playlist buttons in the top bar; Previous / Next on the transport row.
- Compact bottom controls: seek bar and one transport row (lock · prev · −10 · play · +10 · next · rotate).
- **Audio mode** transforms the player into an audio UI (and rotates to portrait); video renderer sleeps.
- **Sleep timer** in the settings menu (5 min – 2 h, custom minutes, or end of video) that can also **close DHVON** and remove it from recents.
- Playlist panel slides up from the bottom. Centered brightness / volume bars. Edge buffer so Android's back swipe never seeks.

**Customisation** — progress-bar style (Line / Flat / Waveform), expandable colour picker, text size, per-gesture switches,
Home layout toggles, controls auto-hide, remaining-time display.

**Reliability** — media notification + lock-screen controls for background play, session restore after process death,
skips missing files, battery-settings shortcut, Room migration v1 → v2.

## 1.0.0
Initial release: offline-first video + audio player (Media3 + FFmpeg decoder), MediaStore/SAF library, pro gestures,
subtitles with sync, equalizer, playlists, Obsidian / AMOLED / Light / Material You themes.

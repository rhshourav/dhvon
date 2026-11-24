# Contributing to DHVON

Thanks for wanting to help. DHVON is a small, focused project, and good bug reports, ideas and pull requests all make it better.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Found a security problem? Please read the [security policy](SECURITY.md) and report it privately.

## Ways to help

- **Report a bug.** Use the [issue forms](https://github.com/rhshourav/dhvon/issues/new/choose). For a file that won't play, the *Playback or format problem* form asks for the codec details that matter. Please don't attach copyrighted media.
- **Suggest a feature.** Explain the problem first. Ideas that keep DHVON offline-first and ad-free are the easiest to accept.
- **Send a pull request.** For anything bigger than a small fix, open an issue first so we can agree on the approach.

## Principles

DHVON stays **offline-first** and **ad-free**, and it never asks for all-files access (`MANAGE_EXTERNAL_STORAGE`). Changes that add ads, trackers, accounts, analytics or network access need a discussion in an issue first.

## Building

You need the latest stable Android Studio and the JDK it bundles.

```bash
git clone https://github.com/rhshourav/dhvon
cd dhvon
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.

## Project layout

- Kotlin and Jetpack Compose (Material 3), built with Gradle Kotlin DSL and a version catalog
- MVVM with Clean Architecture: `data`, `domain` and `ui`
- Hilt for dependency injection, Room and DataStore for storage
- Playback on AndroidX Media3, with FFmpeg extensions and a libVLC fallback
- The project website lives in `docs/` and deploys to GitHub Pages

## Pull requests

1. Fork the repo and create a branch from `main`.
2. Keep each pull request focused on one change.
3. Follow the official Kotlin style, and keep composables small with state hoisted.
4. Write clear commit messages in the imperative, such as “Fix shuffle ending early”.
5. Make sure `./gradlew assembleDebug` succeeds, and test on a real device if you can.
6. Fill in the pull request template, with screenshots for anything visual in both light and dark themes.
7. Never commit keystores, signing configs, API keys or personal data.

## Smoke tests for playback changes

If you touch playback, the player UI or the library, please try the ones that apply:

- A normal H.264 `.mp4` plays on Media3 and does **not** switch to VLC.
- A DivX/Xvid `.avi` or an MPEG-2 `.ts` switches to VLC, and seek, double-tap, speed, pinch-zoom and Picture-in-Picture still work.
- A playlist of about 10 items with Shuffle plays every item once, then stops.
- A video with several audio tracks picks the English one, and a manual choice sticks.
- The player controls have even margins on a phone with a notch, in portrait and landscape.
- Plugging in headphones caps the volume and fades in. Select-and-delete and Delete folder both ask for consent and work.

## Questions

Not sure whether something is a bug or how it should work? Open an issue and ask.

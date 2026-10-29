# synthX

A local, dark-only music player for Android, built for big offline libraries.

synthX plays the music files you already have. There is no account, no streaming and no ads.

## Features

- Browse by Library, Songs, Artists, Albums and Starred, and search across everything.
- Playlists and playlist groups.
- A queue with shuffle and repeat modes, and playback that resumes where you left off.
- A mini player that expands into a full player with a soft background built from the album art.
- An optional "New Arrivals" folder for newly added music.
- Replace a song with another file and keep its playlists, or delete songs from inside the app.
- Backups of your playlists and library data to a folder you choose, and restore from them.

## Privacy

synthX does not ask for the Internet permission, so it cannot connect to the network. It has no analytics, no crash reporting and no ads. Your music is read through Android's folder picker, so the app needs no storage permission. Backups go to a folder you choose; if another app syncs that folder, that app does the uploading.

## Requirements

- Android 12L (API 32) or newer.
- A folder of music files: FLAC, MP3, AAC, M4A (AAC), WAV or Ogg. Decoding uses your phone's built-in codecs. Other formats, such as Opus, WMA, APE and ALAC, are not scanned.

## Install

1. Download `synthX_<version>.apk` from the Releases page.
2. Optional: compare its SHA-256 with the value in the release notes.
3. Open the file on your phone. Android will ask you to allow installs from your browser or file manager.
4. On first launch, choose "Fresh install" (or "Recover from backup" if you have one), pick your music folder, and wait for the first scan.

## Build from source

You need the Android SDK (platform 37, build-tools 36.0.0) and JDK 17 to start Gradle. Gradle downloads the other JDK it needs on the first build. Create a `local.properties` file with `sdk.dir=/path/to/Android/Sdk`; Android Studio does this for you.

```
./gradlew assembleDebug
```

The debug build installs as "synthX dev" next to a release install, with its own data. A release build needs your own signing key.

`scripts/check.sh` runs formatting, a compile with warnings as errors, lint and the unit tests. `scripts/format.sh` formats the code. The instrumented tests need a device or an emulator.

Built with Kotlin, Jetpack Compose, Room, Media3 (ExoPlayer) and WorkManager, using MVVM with manual dependency injection.

## Contributing

Issues and pull requests are welcome. Please read CONTRIBUTING.md first. CLAUDE.md is a guide for AI coding agents; its rules apply to people too. Questions and ideas go in Discussions.

## How it's made

code in this repository was written with help of AI coding agents, mainly Claude Code, working under the maintainer's direction. The maintainer tests the app on real devices.

## License

Copyright (C) 2026 v1per-one. synthX is free software under the GNU General Public License, version 3 or (at your option) any later version; see LICENSE. Third-party components and the Outfit font keep their own licenses; see THIRD_PARTY_NOTICES.md.
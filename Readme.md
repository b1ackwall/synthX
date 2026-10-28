# synthX

A cyberpunk-themed, local-first Android music player built for a single, real use case: managing a large personal FLAC library (2000+ songs, 68 hand-curated playlists) with the polish and reliability of a daily driver — not a general-purpose app for the store.

synthX is not published or intended for distribution. It's built, tested, and used by one person, on one device, on purpose.

## What it does

- Plays a local FLAC library via ExoPlayer/Media3, with a hand-tuned player transition (drag-driven, lerped geometry, no framework shared-element hacks).
- Manages playlists, starring, and library scanning with a strong emphasis on **never silently losing data** — hash-based song identity, flags-missing instead of cascade-delete on file changes, atomic backups before risky operations.
- Splits the library into two zones: a stable root library, and a **New Arrivals** inbox folder for fresh downloads (fed by a companion downloader app). New Arrivals doubles as a Starred/inbox workflow — listen, curate into playlists, then graduate the song into the main library with a verified file move.
- Surfaces its own health: scan status, backup outcomes, and library issues (missing files, orphaned art, failed backups) are all visible in-app, not buried in logs.

## Design principles

- **Dark, restrained, intentional.** Near-black background, a single neon accent (`#32CC3F`) used in five deliberate places: the wordmark X, the selected bottom-nav item, the playing indicator bar in song rows, the ripple on clickable rows, and the Hairline (the accent at 8% alpha) for dividers and borders. It's not a decorative color, it's a signal.
- **Refined-instant-cut navigation.** Detail and create screens that load data on entry hold on solid black until content is ready, then reveal instantly. Other screens show their own loading state.
- **Correctness over cleverness.** Every architectural decision favors not losing the user's library over saving engineering effort — this shows up throughout the codebase (safety snapshots before migrations, a backup schema contract for every database version, verified file moves for graduation).

## Tech stack

- **Language/UI:** Kotlin, Jetpack Compose
- **Architecture:** Strict MVVM, manual dependency injection (no Hilt/Koin)
- **Persistence:** Room
- **Playback:** ExoPlayer / Media3
- **Background work:** WorkManager (library scanning, verification)
- **Build:** AGP, Gradle, Kotlin 2.4, Compose BOM 2026.09

## Companion app

The companion downloader app is a separate app/repo that downloads tracks and drops them directly into synthX's New Arrivals folder — the two apps are deliberately decoupled, communicating only through a shared filesystem location, not a direct integration.

## Project status

Actively developed and used daily. Development follows a strict workflow: read-only discovery before any change, a locked plan, a self-contained implementation, on-device verification on a real library, then commit. No shortcuts on data-safety-critical paths.

## License

Copyright (C) 2026 v1per-one. synthX is free software under the GNU General Public License, version 3 or (at your option) any later version; see LICENSE. Third-party components and the Outfit font keep their own licenses; see THIRD_PARTY_NOTICES.md.
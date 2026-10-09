# synthX

A cyberpunk-themed, local-first Android music player built for a single, real use case: managing a large personal FLAC library (2000+ songs, 68 hand-curated playlists) with the polish and reliability of a daily driver — not a general-purpose app for the store.

synthX is not published or intended for distribution. It's built, tested, and used by one person, on one device, on purpose.

## What it does

- Plays a local FLAC library via ExoPlayer/Media3, with a hand-tuned player transition (drag-driven, lerped geometry, no framework shared-element hacks).
- Manages playlists, starring, and library scanning with a strong emphasis on **never silently losing data** — hash-based song identity, flags-missing instead of cascade-delete on file changes, atomic backups before risky operations.
- Splits the library into two zones: a stable root library, and a **New Arrivals** inbox folder for fresh downloads (fed by a companion downloader app, ). New Arrivals doubles as a Starred/inbox workflow — listen, curate into playlists, then graduate the song into the main library with a verified file move.
- Surfaces its own health: scan status, backup outcomes, and library issues (missing files, orphaned art, failed backups) are all visible in-app, not buried in logs.

## Design principles

- **Dark, restrained, intentional.** Black background, a single neon accent (`#CAFF00`) used in exactly four deliberate places — it's not a decorative color, it's a signal.
- **Refined-instant-cut navigation.** Screen transitions hold on solid black until content is ready, then reveal instantly. No loading spinners bleeding into half-rendered screens.
- **Correctness over cleverness.** Every architectural decision favors not losing the user's library over saving engineering effort — this shows up throughout the codebase (safety snapshots before migrations, dual-version backup schema contracts, verified file moves for graduation).

## Tech stack

- **Language/UI:** Kotlin, Jetpack Compose
- **Architecture:** Strict MVVM, manual dependency injection (no Hilt/Koin)
- **Persistence:** Room
- **Playback:** ExoPlayer / Media3
- **Background work:** WorkManager (library scanning, verification)
- **Build:** AGP, Gradle, Kotlin 2.x, Compose BOM 2026.08

## Companion app

**** (synthX ) is a separate app/repo that downloads tracks and drops them directly into synthX's New Arrivals folder — the two apps are deliberately decoupled, communicating only through a shared filesystem location, not a direct integration.

## Project status

Actively developed and used daily. Development follows a strict workflow: read-only discovery before any change, a locked plan, a self-contained implementation, on-device verification on a real library, then commit. No shortcuts on data-safety-critical paths.

## License

Personal project, not currently licensed for reuse.
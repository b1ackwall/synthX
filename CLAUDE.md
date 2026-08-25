# synthX — Claude Code Instructions

## Git — DO NOT TOUCH
Claude Code must never run any git command (add, commit, push, checkout, branch, etc.).
 handles all git operations manually. Report changes made; do not stage or commit them.

## Environment Setup (machine-aware)

First, check the machine:
    $env:COMPUTERNAME

Then set JAVA_HOME for this session accordingly:
- --15 → $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
- V1PER → $env:JAVA_HOME = "C:\Users\\.jdks\jbr-21.0.11"

Verify before building:
    & "$env:JAVA_HOME\bin\java.exe" -version
Expect OpenJDK 21.x.

## Build Command (verified working on )
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    .\gradlew.bat assembleDebug --console=plain
Expect: BUILD SUCCESSFUL. ~19s warm, longer on cold/first build.

Do not probe for JDKs in Program Files\Java, Adoptium, Zulu, Corretto, or .jdks folders —
the path above is verified and correct. If it fails, stop and report the exact error;
do not attempt alternate JDK paths on your own.

## Project Structure
Single-module Android project. Root: app/

app/src/main/java/com/v1per/synthx/
├── MainActivity.kt
├── data/
│   ├── db/          — Room entities, DAOs, SynthXDatabase (v8, 9 entities)
│   ├── repository/  — domain models + SongRepository/SongRepositoryImpl
│   └── scanner/     — FolderScanner, HashUtils (SHA-256 fingerprinting)
├── navigation/       — Screen.kt (nav routes)
├── playback/         — PlaybackEngine → ExoPlayerEngine → PlaybackController → PlaybackViewModel,
│                       SynthXPlaybackService, GlitchBitmapGenerator
├── preferences/      — PreferencesManager
└── ui/
    ├── MainScaffold.kt
    ├── common/       — UiState.kt
    ├── components/   — shared composables (PlayerSheet, SongRow, TopBars, Wordmark, etc.)
    ├── screens/      — one dir per screen, each with <Name>Screen.kt + <Name>ViewModel.kt
    │   (albumdetail, albumlist, artistdetail, artistlist, createplaylist,
    │    groupdetail, home, issues, librarymanagement, library, playlistdetail, playlistlist,
    │    restore, search, settings, songs, starred)
    └── theme/        — Color, Motion, Shape, Spacing, Theme, Type (design tokens)

res/: drawable/, font/, mipmap-anydpi-v26/, values/, xml/ — fully Compose, no layout/ XML.

Single Activity (MainActivity), one foreground service (SynthXPlaybackService) for media playback.
Versions centralized in gradle/libs.versions.toml.

## Architecture (do not violate)
- Strict MVVM throughout.
- Playback chain: PlaybackEngine (interface) → ExoPlayerEngine (impl) → PlaybackController → PlaybackViewModel.
  Never bypass this chain or call ExoPlayer APIs directly from ViewModels or UI.
- Manual DI ONLY — no Hilt, no Dagger, no other DI framework.
  Current pattern: each ViewModel has a companion object Factory that builds its own
  dependency graph, typically via SynthXDatabase.getDatabase(context).
  There is intentionally no Application subclass and no di/ package — do not add one
  unless explicitly instructed.
- Room DB, version 8, 9 entities: songs, artists, albums, song_artists, playlist_groups,
  playlists, playlist_songs, queue_items, queue_baseline_items. (listen_history was dropped
  in an earlier version, replaced by queue_items/queue_baseline_items.)
- SHA-256 song fingerprinting: first 64KB + last 64KB + file size (via HashUtils.kt).
- Compose compiler reports are enabled (`composeCompiler { reportsDestination }` in
  app/build.gradle.kts) — regenerate via a fresh `compileDebugKotlin`; reports land in
  app/build/compose_reports/ (composables list, classes list, stability summary).

## Design System (locked — no scope creep)
- Dark-only theme.
- #CAFF00 neon accent used ONLY in these 5 contexts — never elsewhere:
  1. Wordmark "X"
  2. Selected bottom nav item
  3. Playing indicator bar
  4. Play/pause icon when playing
  5. Ripple on clickable rows
- Outfit font (body/UI), Bitcount Grid Double (wordmark only).
- 2dp corner radius via MaterialTheme.shapes.small — single source of truth, don't hardcode radii.
- Apple Music-inspired UX: smooth navigation, instant cut transitions (not slow fades).

## Conventions
- Incremental, debuggable sub-tasks — do not attempt large multi-file rewrites in one pass.
- Detailed, specific error messages preferred over generic ones.
- Full ViewModel per screen, even if initially near-empty — no screens without one.
- If a build fails, attempt to fix it and continue — don't stop and wait for approval mid-task.
- Always end with a report covering:
  - What was done (files touched, changes made)
  - Any deviations from the original prompt
  - Any assumptions made where the prompt was ambiguous
  - Any alternate approaches taken instead of what was asked, with reasoning why
  This report gets reviewed before the next task is planned — accuracy here matters
  more than brevity.

# synthX — Claude Code Instructions

## Git — DO NOT TOUCH
Claude Code must never run any git command (add, commit, push, checkout, branch, etc.).
 handles all git operations manually. Report changes made; do not stage or commit them.

## Environment Setup (machine-aware)

First, check the machine:
    $env:COMPUTERNAME

Then set JAVA_HOME for this session accordingly:
- --15 → $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.15.6-hotspot\"
- V1PER → $env:JAVA_HOME = "C:\Users\\.jdks\jbr-21.0.11"

Verify before building:
    & "$env:JAVA_HOME\bin\java.exe" -version
Expect OpenJDK 17.x on --15, OpenJDK 21.x on V1PER.

## Build Command (verified working on )
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.15.6-hotspot\"
    .\gradlew.bat assembleDebug --console=plain
Expect: BUILD SUCCESSFUL.

Do not probe for JDKs in Program Files\Java, Adoptium, Zulu, Corretto, or .jdks folders —
the path above is verified and correct. If it fails, stop and report the exact error;
do not attempt alternate JDK paths on your own.

## Project Structure

Single-module Android project. Root: app/

Top-level packages under app/src/main/java/com/v1per/synthx/:

- MainActivity.kt, SynthXApplication.kt
- data/backup/ - BackupManager, RestoreCoordinator, RestoreTransaction, DatabaseStartupGate, RepositoryDbFlow/RepositoryDbOperation (see Architecture section - do not bypass these)
- data/db/ - Room entities, DAOs, SynthXDatabase (v9, see Architecture)
- data/playlistseed/ - PlaylistSeedBootstrapper (default playlist seeding from assets/playlist_seed_manifest_v1.json)
- data/repository/ - domain models + SongRepository/SongRepositoryImpl
- data/scanner/ - FolderScanner, HashUtils (SHA-256 fingerprinting)
- navigation/ - Screen.kt (nav routes)
- playback/ - PlaybackEngine -> ExoPlayerEngine -> PlaybackController -> PlaybackViewModel, SynthXPlaybackService, QueuePersistence, GlitchBitmapGenerator
- preferences/ - PreferencesManager
- ui/MainScaffold.kt - shell-level gating on RestoreCoordinator.Mode/Terminal; switches between normal nav graph, restore-maintenance surface, and terminal (restart-required) surface
- ui/common/ - UiState.kt
- ui/components/ - shared composables (PlayerSheet, SongRow, TopBars, Wordmark, etc.)
- ui/player/ - PlayerSheetSkeleton + 7 sibling files: the live player UI, float-lerp based (hand-lerp pattern per namida/Tear Music reference), independent of the ReadinessCoverOverlay standard below - known, deliberate gap, not yet reconciled
- ui/screens/ - one dir per screen, each with <Name>Screen.kt + <Name>ViewModel.kt: albumdetail, albumlist, artistdetail, artistlist, backupsandrestore, createplaylist, groupdetail, home, issues, librarymanagement, library, playlistdetail, playlistlist, restore, search, settings, songs, starred
- ui/theme/ - Color, Motion, Shape, Spacing, Theme, Type (design tokens)

res/: drawable/, font/, mipmap-anydpi-v26/, values/, xml/ - fully Compose, no layout/ XML.

Single Activity (MainActivity), one foreground service (SynthXPlaybackService) for media playback. Versions centralized in gradle/libs.versions.toml.

## Architecture (do not violate)
- Strict MVVM throughout.
- Playback chain: PlaybackEngine (interface) → ExoPlayerEngine (impl) → PlaybackController → PlaybackViewModel.
  Never bypass this chain or call ExoPlayer APIs directly from ViewModels or UI.
- Manual DI ONLY — no Hilt, no Dagger, no other DI framework.
  Current pattern: each ViewModel has a companion object Factory that builds its own
  dependency graph, typically via SongRepositoryImpl.create(context) →
  SynthXDatabase.getInstance(context).
  There is intentionally no di/ package — do not add one unless explicitly instructed.
- Room DB, version 9. Entities: songs (incl. album_art_full_uri, in_inbox), artists, albums,
  song_artists, playlist_groups, playlists, playlist_songs, queue_items, queue_baseline_items.
  (listen_history was dropped in an earlier version, replaced by queue_items/queue_baseline_items.)
- ALL_MIGRATIONS in SynthXDatabase.kt is the single canonical migration chain — both the
  normal Room builder and createStandalone() (used for offline restore-candidate validation)
  must reference this same shared list. Never give createStandalone() or any other builder
  its own independent migration list — this has caused a real production bug before.
- Backup/restore admission model (data/backup/): every production path that touches live
  Room must route through one of: DatabaseStartupGate (startup/open gating),
  RepositoryDbOperation.run (finite writes/mutations), or RepositoryDbFlow.observe
  (reactive Flow-returning reads) — the DAO Flow/operation must be constructed *inside*
  the lambda passed to these wrappers, never built outside and captured. This lets
  RestoreCoordinator safely quiesce/drain live DB work during a restore. The only
  legitimate bypasses are restore's own internal machinery (getInstanceForRestore,
  createStandalone, RestoreTransaction) operating under RestoreCoordinator.exclusive —
  never bypass these gates for ordinary application/repository/scanner/worker code.
- BackupSchemaContracts (data/backup/BackupSchemaContract.kt) must have an entry for
  every schema version — current database version must always resolve via
  BackupSchemaContracts.current, or backup creation, safety snapshots, and restore
  validation all break. When bumping SynthXDatabase's version, add the new migration
  to ALL_MIGRATIONS and a new contract entry in the same change.
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
- "refined-instant-cut" navigation standard (ReadinessCoverOverlay in SynthXNavHost) is the
  locked standard for applicable destinations — note the live player UI (ui/player/) is a
  known, deliberate exception, see Project Structure above.

## Conventions
- Incremental, debuggable sub-tasks — do not attempt large multi-file rewrites in one pass.
- Detailed, specific error messages preferred over generic ones.
- Full ViewModel per screen, even if initially near-empty — no screens without one.
- If a build fails, attempt to fix it and continue — don't stop and wait for approval mid-task.
- Mutation-test critical fixes where practical: deliberately break the fix, confirm the
  test catches it, then restore — a passing test that can't fail is not verification.
- Always end with a report covering:
  - What was done (files touched, changes made)
  - Any deviations from the original prompt
  - Any assumptions made where the prompt was ambiguous
  - Any alternate approaches taken instead of what was asked, with reasoning why
  This report gets reviewed before the next task is planned — accuracy here matters
  more than brevity.
# synthX — Claude Code Instructions

## Git
- On the `claude-branch` branch: Claude Code may use any git command (add, commit, push,
  fetch, pull, merge, etc.) as needed for the task. Check the current branch first
  (`git branch --show-current`); this permission applies only while on `claude-branch`.
- On any other branch: DO NOT TOUCH. Claude Code must never run any git command (add, commit,
  push, checkout, branch, etc.).  handles all git operations manually. Report changes
  made; do not stage or commit them.

## Connected Device — VERIFY BEFORE TOUCHING
Run `adb devices -l` to see what's actually connected before running any adb command that
touches app data or packages — do not assume which device is plugged in.
- As of the last environment check on this machine (CachyOS), the only device attached was
  serial `` (`model:Redmi_Note_7_Pro`, `device:violet`), and `pm list packages` on it
  showed **only** `com.v1per.synthx.debug` installed — no `com.v1per.synthx` (production)
  package was present on this device at all.
  **OPEN QUESTION for  to confirm**: is this Redmi Note 7 Pro actually the daily-use
  production phone (just currently without the production build installed), or a separate
  test/debug-only device? Don't assume either answer — confirm before treating any device as
  "the production phone" the safety rules below are protecting.
- `com.v1per.synthx` (no suffix) = PRODUCTION, wherever it is actually installed. NEVER run
  `adb shell pm clear`, `adb uninstall`, `adb shell pm uninstall`, or any other
  destructive/data-touching adb command against this package, under any circumstances, even to
  "rule out stale state" while debugging a test.
- `com.v1per.synthx.debug` (note the `.debug` suffix) = the debug build package
  (`applicationIdSuffix`), used by `assembleDebug` / `connectedDebugAndroidTest` / instrumented
  tests. This one is fair game — clear it, uninstall it, wipe it, reinstall it freely as needed
  for debugging or testing, no need to ask first.
- Before any adb command that touches app data or packages, double-check the package name
  argument is the `.debug`-suffixed one. If a command's target package is ambiguous, or more
  than one device is attached, or you're about to run something broad (e.g. an adb command
  without an explicit package filter), stop and ask first rather than guessing.

## Environment Setup (machine-aware)

First, check the machine:
    hostname

Then set JAVA_HOME for this session accordingly:
- cachyOS → set -x JAVA_HOME /usr/lib/jvm/java-17-openjdk

Verify before building:
    $JAVA_HOME/bin/java -version
Expect OpenJDK 17.x on cachyOS.

## Build Command (verified working on cachyOS)
    set -x JAVA_HOME /usr/lib/jvm/java-17-openjdk
    ./gradlew assembleDebug --console=plain
Expect: BUILD SUCCESSFUL.

Do not probe for JDKs in Program Files\Java, Adoptium, Zulu, Corretto, or .jdks folders —
the path above is verified and correct. If it fails, stop and report the exact error;
do not attempt alternate JDK paths on your own.

## Project Structure

Single-module Android project. Root: app/

Top-level packages under app/src/main/java/com/v1per/synthx/:

- MainActivity.kt, SynthXApplication.kt
- data/backup/ - BackupManager, RestoreCoordinator, RestoreTransaction, DatabaseStartupGate, RepositoryDbFlow/RepositoryDbOperation (see Architecture section - do not bypass these)
- data/db/ - Room entities, DAOs, SynthXDatabase (v16, see Architecture)
- data/playlistart/ - generated playlist cover art support: PlaylistArtHue (perceptually spaced hue selection), PlaylistArtColor (OkLab palette helpers), PlaylistArtBackfill (fills art for playlists that predate the art columns). The drawing itself lives in ui/components/PlaylistArt*.kt
- data/playlistseed/ - PlaylistSeedBootstrapper (default playlist seeding from assets/playlist_seed_manifest_v1.json)
- data/repository/ - domain models + SongRepository/SongRepositoryImpl
- data/scanner/ - FolderScanner, HashUtils (SHA-256 fingerprinting)
- glitch/ - GlitchPattern.kt
- navigation/ - Screen.kt (nav routes)
- playback/ - PlaybackEngine -> ExoPlayerEngine -> PlaybackController -> PlaybackViewModel, SynthXPlaybackService, QueuePersistence, GlitchBitmapGenerator
- preferences/ - PreferencesManager
- ui/MainScaffold.kt - shell-level gating on RestoreCoordinator.Mode/Terminal; switches between normal nav graph, restore-maintenance surface, and terminal (restart-required) surface
- ui/common/ - UiState.kt
- ui/components/ - shared composables (PlayerSheet, SongRow, TopBars, Wordmark, etc.)
- ui/player/ - PlayerSheetSkeleton + 7 sibling files: the live player UI, float-lerp based (hand-lerp pattern per namida/Tear Music reference), independent of the ReadinessCoverOverlay standard below - known, deliberate gap, not yet reconciled
- ui/screens/ - one dir per screen, each with <Name>Screen.kt + <Name>ViewModel.kt: albumdetail, albumlist, artistdetail, artistlist, backupsandrestore, createplaylist, developersettings, groupdetail, home, issues, librarymanagement, library, onboarding, playlistdetail, playlistlist, replacesong, restore, search, settings, songs, starred
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
- Room DB, version 16. Entities: songs (incl. album_art_full_uri, in_inbox), artists, albums,
  song_artists, playlist_groups, playlists (incl. art_hue, art_structure_seed), playlist_songs,
  queue_items, queue_baseline_items, pending_cleanups,
  playback_cursor (single-row resume cursor + shuffle/repeat mode, see PlaybackCursorEntity).
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
  validation all break. Bumping SynthXDatabase's version is a checklist, not a single
  edit — do all of the following in the same change:
    1. Bump SynthXDatabase.VERSION (and the @Database(version = ...) annotation).
    2. Add the new migration to ALL_MIGRATIONS (see above — never a separate list).
    3. Add the corresponding BackupSchemaContracts entry: the new userVersion, the Room
       identity hash copied from the freshly exported app/schemas/.../<N>.json, and the
       updated expectedTables set.
    4. Add a MigrationTestHelper-based test for the new leg — Migration{N}To{N+1}Test.kt,
       following Migration8To9Test.kt's pattern (seed the old schema, run the single
       migration, validate the new schema, assert real pre/post data survives — not
       just that the migration object exists).
    5. Confirm MigrationFullChainTest and MigrationCompletenessTest (app/src/androidTest
       and app/src/test under data/db) still pass. Note: MigrationCompletenessTest's
       "a dedicated test file exists" check only enforces this from version 8 onward —
       a deliberate scoping decision (versions 1-7 predate that convention and were not
       retroactively backfilled) — so it will not catch a missing file for an
       already-old leg, only for the new one you just added.
    6. Run PhaseBRestoreJournalUpgradeInstrumentedTest to verify a retained COMPLETE restore
       journal from the prior schema version does not block startup or the normal Room migration.
    7. Never write a destructive migration (one that deletes or overwrites existing data
       with no backfill path) unless there is a genuinely unavoidable reason — see the
       note above ALL_MIGRATIONS in SynthXDatabase.kt. Treat it as an exceptional,
       carefully reviewed decision, not routine practice: every migration since
       MIGRATION_4_5 has been additive-only, and this app now holds years of hand-curated
       user data with no external backup of future changes.
    Also grep for tests that hardcode the *current* schema's Room identity hash as a
    literal (e.g. BackupPrimitivesTest's "schema contracts resolve current and known
    migration versions", BackupSnapshotInstrumentedTest's
    "productionSchemaSnapshotPassesFullLocalValidation") and update them too — those
    literals go stale on every version bump exactly like a skipped BackupSchemaContracts
    entry would, and neither MigrationFullChainTest nor MigrationCompletenessTest catches it.
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

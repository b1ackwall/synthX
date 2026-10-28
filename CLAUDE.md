# synthX — Claude Code Instructions

## Git
- The working branch is `code-work`. Check the current branch first (`git branch --show-current`).
- Agents never run git write commands (add, commit, push, checkout, branch, stash, mv, reset,
  merge, etc.) and never run `gh` write commands (issue/PR create, edit, comment, close, etc.).
  The owner () commits and pushes. A task prompt may grant a scoped exception for that task
  only; the grant ends with the task. Report changes made; do not stage or commit them.
- Read-only git (status, diff, log, show, ls-files, archive, `apply --check`) and read-only `gh`
  are fine on any branch.

## Connected Device — VERIFY BEFORE TOUCHING
Every device task starts with a gate: run `adb devices -l` and confirm the Redmi Note 7 Pro is
listed. If it is missing, STOP and report; do not fall back to the emulator.
- Devices:
  - Redmi Note 7 Pro (serial ``, `device:violet`): the main judge for instrumented
    tests. Only `com.v1per.synthx.debug` is installed. Dev-only device, safe to reinstall and
    test on.
  - Samsung Galaxy S25 Ultra (serial ``): OFF-LIMITS to agents. It holds the
    production package `com.v1per.synthx`.
  - Emulator `emulator-5554` (Pixel_9a AVD): used only when the owner says so. Known unreliable
    at API 37.
- Rules:
  1. Run `adb devices -l` and confirm the targeted device before ANY adb command.
  2. Always pass `-s <serial>` to `adb`; never rely on `ANDROID_SERIAL` or on a single attached
     device.
  3. Never run Gradle `connected*` tasks (for example `connectedDebugAndroidTest`): they ignore
     `ANDROID_SERIAL` and run on every attached device.
  4. Never run any command that targets `com.v1per.synthx` (production).
- `com.v1per.synthx` (no suffix) = PRODUCTION, wherever it is actually installed. NEVER run
  `adb shell pm clear`, `adb uninstall`, `adb shell pm uninstall`, or any other
  destructive/data-touching adb command against this package, under any circumstances, even to
  "rule out stale state" while debugging a test.
- `com.v1per.synthx.debug` (note the `.debug` suffix) = the debug build package
  (`applicationIdSuffix` in app/build.gradle.kts), used by `assembleDebug` and instrumented
  tests. This one is fair game on the Redmi — clear it, uninstall it, wipe it, reinstall it
  freely as needed for debugging or testing, no need to ask first.
- Before any adb command that touches app data or packages, double-check the package name
  argument is the `.debug`-suffixed one. If a command's target package is ambiguous, or more
  than one device is attached, or you're about to run something broad (e.g. an adb command
  without an explicit package filter), stop and ask first rather than guessing.

## Environment Setup (machine-aware)

The owner's terminal is fish, so set env vars there with `set -gx VAR value`, not `export`.
The agent tool shell varies by session (zsh or fish): do not rely on `$SHELL`. Write helper
scripts with a bash shebang and run them by absolute path.

Check that the JDK exists, then set JAVA_HOME for this session:
    ls -d /usr/lib/jvm/java-17-openjdk
    set -gx JAVA_HOME /usr/lib/jvm/java-17-openjdk

Verify before building:
    $JAVA_HOME/bin/java -version
Expect OpenJDK 17.x. That is the launcher JDK; the Gradle daemon itself builds on JDK 25
(gradle/gradle-daemon-jvm.properties, auto-provisioned).

## Build Command (verified working on cachyOS)
    set -gx JAVA_HOME /usr/lib/jvm/java-17-openjdk
    ./gradlew assembleDebug --console=plain
Expect: BUILD SUCCESSFUL.

Do not probe for JDKs in Program Files\Java, Adoptium, Zulu, Corretto, or .jdks folders —
the path above is verified and correct. If it fails, stop and report the exact error;
do not attempt alternate JDK paths on your own.

## Project Structure

Two Gradle modules: app/ (the application) and benchmark/ (macrobenchmarks).

Packages under app/src/main/java/com/v1per/synthx/:

- Root: MainActivity.kt, DatabaseStartupContent.kt (startup-failure composables MainActivity shows), SynthXApplication.kt
- data/ (root files) - shared data-layer helpers: file-operation coordination, song deletion/remap events, safe flow collection
- data/backup/ - backup, restore and startup gating: BackupManager (catalog and verification in BackupManager*.kt siblings), RestoreCoordinator (the admission authority), RestoreTransaction, DatabaseStartupGate
- data/db/ - Room entities, DAOs, SynthXDatabase (see Architecture), SynthXLegacyMigrations (frozen MIGRATION_1_2 to MIGRATION_7_8; the chain itself stays in SynthXDatabase.ALL_MIGRATIONS)
- data/playlistart/ - generated playlist cover art support (hue selection, palette helpers, backfill for playlists that predate the art columns). The drawing itself lives in ui/components/PlaylistArt*.kt
- data/repository/ - domain models, SongRepository/SongRepositoryImpl (override bodies in SongRepositoryImpl*.kt siblings), and the admission wrappers RepositoryDbOperation/RepositoryDbFlow (see Architecture - do not bypass these)
- data/scanner/ - FolderScanner (scan body in FolderScannerScan.kt), scan workers, HashUtils (SHA-256 fingerprinting)
- navigation/ - Screen.kt (nav routes)
- perf/ - perf trace section names and test tags shared by app code and benchmarks
- playback/ - PlaybackEngine -> ExoPlayerEngine (split across ExoPlayerEngine*.kt siblings) -> PlaybackController -> PlaybackViewModel, SynthXPlaybackService, QueuePersistence
- preferences/ - PreferencesManager and the small trackers/gates built on it
- ui/ (root files) - MainScaffold.kt does shell-level gating on RestoreCoordinator.Mode/Terminal and switches between the normal nav graph, the restore-maintenance surface and the terminal (restart-required) surface (the two restore surfaces live in RestoreShellSurfaces.kt); SynthXNavHost.kt plus the *Routes.kt files hold the nav graph
- ui/common/ - UiState and small shared UI helpers
- ui/components/ - shared composables (SongRow, TopBars, Wordmark, etc.)
- ui/player/ - the live player UI (PlayerSheetSkeleton and its siblings), float-lerp based (hand-lerp pattern per namida/Tear Music reference), independent of the ReadinessCoverOverlay standard below - known, deliberate gap, not yet reconciled
- ui/screens/ - one dir per screen, each with <Name>Screen.kt + <Name>ViewModel.kt
- ui/theme/ - design tokens, one file per token family (Color.kt is the source of truth for colours)

res/: drawable/, font/, mipmap-anydpi/, values/, xml/ - fully Compose, no layout/ XML.

Single Activity (MainActivity). Foreground services in the app manifest: SynthXPlaybackService
(mediaPlayback) and WorkManager's SystemForegroundService (dataSync), which the manifest
re-declares to add the missing type. Versions centralized in gradle/libs.versions.toml.

## Architecture (do not violate)
- Strict MVVM throughout.
- Documented exception: the startup recovery screens (DatabaseStartupContent.kt:
  DatabaseStartupFailureContent, MigrationRecoveryContent, RestoreTerminalContent) have no ViewModel.
  They run before Room exists and launch their operations (listBackups, emergencyRestore,
  MigrationRecoveryAction.restoreFromInternalSnapshot) from a composition-scoped coroutine scope
  (rememberCoroutineScope), then call exitProcess(0). Known gaps: their state (availableBackups,
  actionError, actionRunning, isRestoring) resets if the Activity is recreated mid-operation, and
  restoreFromInternalSnapshot has no mutex, so a second run is possible after recreation. Their
  text comes from string resources (startup_recovery_strings.xml, strings.xml), shared with
  RestoreShellSurfaces.kt.
- Playback chain: PlaybackEngine (interface) → ExoPlayerEngine (impl) → PlaybackController → PlaybackViewModel.
  Never bypass this chain or call ExoPlayer APIs directly from ViewModels or UI.
- Manual DI ONLY — no Hilt, no Dagger, no other DI framework.
  Current pattern: each ViewModel builds its own dependency graph in a factory, typically via
  SongRepositoryImpl.create(context) → SynthXDatabase.getInstance(context). Two shapes exist:
  a nested `class Factory` (ViewModelProvider.Factory) or a companion `fun factory(...)`
  returning one (used where the route passes an id). Follow the shape of the neighbouring screen.
  There is intentionally no di/ package — do not add one unless explicitly instructed.
- Room DB, version 16. Entities: songs (incl. album_art_full_uri, in_inbox), artists, albums,
  song_artists, playlist_groups, playlists (incl. art_hue, art_structure_seed), playlist_songs,
  queue_items, queue_baseline_items, pending_cleanups,
  playback_cursor (single-row resume cursor + shuffle/repeat mode, see PlaybackCursorEntity).
- ALL_MIGRATIONS in SynthXDatabase.kt is the single canonical migration chain — both the
  normal Room builder and createStandalone() (used for offline restore-candidate validation)
  must reference this same shared list. Never give createStandalone() or any other builder
  its own independent migration list — this has caused a real production bug before.
- Backup/restore admission model: all DB access that can overlap a restore must go through
  RestoreCoordinator admission (withDbOperation, acquireOperation, acquireReactiveOperation).
  Use the named entry points: DatabaseStartupGate (data/backup, startup/open gating),
  RepositoryDbOperation.run (data/repository, finite writes/mutations), or
  RepositoryDbFlow.observe (data/repository, reactive Flow-returning reads) — the DAO
  Flow/operation must be constructed *inside* the lambda passed to these wrappers, never
  built outside and captured. Other direct entry points that call RestoreCoordinator
  themselves: WorkerDatabaseAccess.run (workers), PlaylistExportMaterialization.run,
  FolderScannerAdmission.direct, FolderScanner.verify, BackupManager, SongGraduation and
  QueuePersistence. This lets RestoreCoordinator safely quiesce/drain live DB work during a
  restore. The only legitimate bypasses are restore's own internal machinery
  (getInstanceForRestore, createStandalone, RestoreTransaction) operating under
  RestoreCoordinator.exclusive — never bypass these gates for ordinary
  application/repository/scanner/worker code.
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
       carefully reviewed decision, not routine practice. Existing destructive legs: 2-to-3
       and 3-to-4 wipe song-derived data (before the app held real user data), 6-to-7 drops
       listen_history and two songs columns, 11-to-12 recreates the two queue tables empty
       and clears the cursor's slot id (position and shuffle/repeat are kept), 15-to-16 merges
       case-duplicate albums (repoints their songs, deletes the duplicate rows). All other legs are additive. This app now
       holds years of hand-curated user data with no external backup of future changes.
    Also grep for tests that hardcode the *current* schema's Room identity hash as a
    literal (e.g. BackupPrimitivesTest's "schema contracts resolve current and known
    migration versions", BackupSnapshotInstrumentedTest's
    "productionSchemaSnapshotPassesFullLocalValidation") and update them too — those
    literals go stale on every version bump exactly like a skipped BackupSchemaContracts
    entry would, and neither MigrationFullChainTest nor MigrationCompletenessTest catches it.
- SHA-256 song fingerprinting: the whole file up to 64KB, the first 64KB from 64KB to 128KB, the first
  and last 64KB above 128KB; the file size is always included; via HashUtils.kt. Accepted limitation:
  distinct files of 64KB to 128KB with the same size and first 64KB share an id.
- Compose compiler reports are off by default. Pass `-PcomposeReports` to a Gradle compile
  (via freeCompilerArgs in app/build.gradle.kts); reports land in
  app/build/compose-reports/<compilation>/ (composables list, classes list, stability summary).
- Lazy-flow trap: reading `.value` of a `WhileSubscribed` or `Lazily` StateFlow that nobody is
  collecting returns the stale initial value. Collect it, or use `Eagerly` when code reads
  `.value` (see the Eagerly copy of activeSongId in PlaybackViewModel).
- Unit tests (app/src/test) cannot reach `String.toUri()`: with core-ktx 1.19 it fails on the JVM
  android.net.Uri stub. 33 call sites exist (buildArtworkData, LibraryScanWorker.doWork,
  ArtRecovery.defaultExtractArt, DestructiveFilePreflight, BackupManager). Test those paths on the
  Redmi, or switch the one call a unit test needs to `Uri.parse`. Remove this bullet when #279 is fixed.
- `UiState<T>.isReady()` (ui/common/UiState.kt) exists to gate destination-level readiness
  covers (ReadinessCoverOverlay): everything past the initial load, success or failure, is ready.

## Design System (locked — no scope creep)
- Dark-only theme.
- #32CC3F neon accent (`Accent` in ui/theme/Color.kt, the source of truth for all colours)
  used ONLY in these five contexts — never elsewhere:
  1. Wordmark "X"
  2. Selected bottom nav item
  3. Playing indicator bar in song rows
  4. Ripple on clickable rows
  5. Hairline (Color.kt: Accent at 8% alpha) for dividers, borders and the theme outline
- The theme's primary and secondary are Accent, so a TextButton or other primary-coloured
  control without an explicit colour inherits it.
- Outfit font (body/UI). The wordmark is the fixed-colour vector drawable ic_wordmark (no font).
- 2dp corner radius via MaterialTheme.shapes.small — single source of truth, don't hardcode radii.
- Apple Music-inspired UX: smooth navigation, instant cut transitions (not slow fades).
- "refined-instant-cut" navigation standard: ReadinessCoverOverlay (ui/components) wraps nine
  destinations that load data on entry, applied per route in BrowseRoutes (Songs, Starred,
  ArtistList, AlbumList, ArtistDetail, AlbumDetail) and PlaylistRoutes (CreatePlaylist,
  GroupDetail, PlaylistDetail). Issues, Restore and PlaylistList keep the LoadingIndicator
  because their loads can be long. Other destinations show their own loading state. The live
  player UI (ui/player/) is a known, deliberate exception, see Project Structure above.

## File Size
- No source file should exceed 500 raw lines (as counted by `wc -l`).
- When a file crosses it, do a discovery-first refactor: plan the seams, then split it by
  move-only moves into sibling files in the same package — no behavior change, no renames.

## Checks
- After editing Kotlin, run `scripts/format.sh` (formats the changed files with the pinned ktlint 1.8.0).
- Before finishing any task that changed code, run `scripts/check.sh`: ktlint (app, benchmark,
  *.kts), compile of all source sets (including androidTest and the benchmark module) with
  warnings as errors, `:app:lintDebug` (`checkAllWarnings`, warnings as errors), and the quick
  unit suite. It stops at the first failure. No device is involved.
- No baselines and no blanket suppressions. Fix the finding, or record a narrow, commented
  ignore (`@SuppressLint` with a reason line, or an entry in `app/lint.xml`).
- The instrumented suite on the Redmi (see Connected Device) stays a manual proof for risky
  changes; `scripts/check.sh` does not replace it.

## Gesture and Animation Rules
- Use `rememberUpdatedState` for callbacks read inside no-key `remember` blocks — frozen
  closures are the most expensive recurring bug.
- Never gate `anchoredDraggable`'s `enabled` parameter on dynamic conditions — it tears down
  the gesture node.
- When an element must leave hit-testing, compose it conditionally instead of alpha-fading it.
  Accepted exception: ui/player fades content with alpha while deriving `enabled` from that alpha.
- Resolve song identity at the UI layer before async boundaries.
- Prefer reactive `LaunchedEffect` keying over timer-based self-healing.

## Working Style
- Gesture and animation work: small changes, one variable at a time.
- Design tokens only — no raw `dp` literals in new code. Accepted exception: ui/player may use
  raw dp literals for its own geometry (sizes, thresholds, corner targets). New screens still
  use design tokens.
- Comments: KDoc on non-trivial APIs and safety rationale may be long; inline `//` comments stay
  short; every comment describes current behaviour only (no history, no planning IDs).

## Conventions
- Files importing `androidx.annotation.OptIn` shadow Kotlin's own `OptIn`; use the fully qualified
  `kotlin.OptIn` there.
- Incremental, debuggable sub-tasks — do not attempt large multi-file rewrites in one pass.
- Detailed, specific error messages preferred over generic ones.
- Full ViewModel per screen, even if initially near-empty — no screens without one.
- If a build fails, attempt to fix it and continue — don't stop and wait for approval mid-task.
- Mutation-test critical fixes where practical: deliberately break the fix, confirm the
  test catches it, then restore — a passing test that can't fail is not verification.
- Commented-out blocks marked "DEAD CODE: <why>. Delete in a cleanup pass. Ask  before
  deleting." must never be deleted without asking  first. Whenever  mentions a cleanup
  pass, offer to list and remove them.
- Always end with a report covering:
  - What was done (files touched, changes made)
  - Any deviations from the original prompt
  - Any assumptions made where the prompt was ambiguous
  - Any alternate approaches taken instead of what was asked, with reasoning why
  This report gets reviewed before the next task is planned — accuracy here matters
  more than brevity.

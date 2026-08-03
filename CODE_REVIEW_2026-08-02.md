# synthX — Comprehensive Code Review

**Date:** 2026-08-02
**Branch:** `v2` (HEAD `8117216`)
**Scope:** all 77 Kotlin files under `app/src/main/java/com/v1per/synthx/` (~7,180 LOC), plus `AndroidManifest.xml`, `build.gradle.kts`, `libs.versions.toml`, `res/values/`, and the exported Room schemas.
**Method:** full read of every source file. No changes were made.

---

## Severity legend

| | Meaning |
|---|---|
| 🔴 **Critical** | Can crash the app, corrupt/lose data, or leak resources unboundedly |
| 🟡 **Medium** | Degrades UX, performance, correctness, or maintainability in a user-visible way |
| 🟢 **Minor** | Code quality, consistency, cleanup |

## Tally

| Severity | Count |
|---|---|
| 🔴 Critical | 12 |
| 🟡 Medium | 58 |
| 🟢 Minor | 34 |
| **Total** | **104** |

## Executive summary

The codebase is unusually clean for its stage — consistent file layout, genuinely useful comments explaining non-obvious decisions, one-way data flow, and Compose previews on most screens. The design language is coherent and the animation work in `MainScaffold`/`PlayerSheet` is careful.

But there are real, shipped-user-facing defects underneath the polish:

1. **Song identity collides for whole albums.** The 64KB prefix hash means every track of an album with embedded cover art larger than ~64KB gets the same primary key, and the scanner then *overwrites* rows. An album can collapse to a single song. (§2.1)
2. **Delete Playlist is a silent no-op** in two screens. The menu item is visible, tappable, and does nothing. (§7.1)
3. **Playlist ordering is destroyed** every time songs are added, and edits aren't transactional. (§2.4, §2.5)
4. **A `MediaController` and service binding leak on every configuration change.** (§1.1)
5. **The N+1 query pattern is still present** in three of the repository's read paths, and the remaining "fixed" path does full-table work on the main thread. (§2.2, §2.3)
6. **Every user-facing string is hardcoded** (`strings.xml` has exactly one entry), and the app has **zero tests**.

Nothing here is unfixable, and most of the 🔴 items are small diffs. The hash and the playlist-ordering issues should be fixed before any real user puts a library into this app, because both corrupt data that can't be recovered from afterwards.

---

# 1. Architecture & MVVM compliance

### 🔴 1.1 — `ExoPlayerEngine` + `MediaController` leak on every configuration change
`MainActivity.kt:30-34`

```kotlin
private val playbackViewModel: PlaybackViewModel by viewModels {
    PlaybackViewModel.Factory(PlaybackController(ExoPlayerEngine(applicationContext)))
}
```

`ViewModelLazy` invokes `factoryProducer()` on **first access within each Activity instance**, before it checks whether the ViewModel already exists in the retained store. On every rotation / theme change / font-scale change:

- a new `ExoPlayerEngine` is constructed,
- its `init` block fires `MediaController.Builder(...).buildAsync()`, binding to `SynthXPlaybackService`,
- the retained `PlaybackViewModel` keeps the **original** controller,
- the new controller is never referenced again and `release()` is never called on it.

`onCleared()` releases only the first one. Each rotation adds a leaked `MediaController`, its `Player.Listener`, and a live service binding. This accumulates for the whole process lifetime.

**Fix direction:** build the engine once at Application scope (or in a `CreationExtras`-aware factory that no-ops when the VM exists), not in the `viewModels` factory lambda.

### 🔴 1.2 — `MainScaffold` resolves `PlaybackViewModel` with no factory
`MainScaffold.kt:108`, `MainActivity.kt:40`

```kotlin
val playbackViewModel = viewModel<PlaybackViewModel>()
```

This only works because `MainActivity.onCreate` touches `playbackViewModel` first (there's a comment saying so). `PlaybackViewModel` has no no-arg constructor, so if that ordering ever changes — an early `return`, a split-screen entry path, a refactor that moves `setContent` — this throws `RuntimeException: Cannot create an instance of class PlaybackViewModel` at composition time. Load-bearing side effect, enforced only by a comment.

### 🟡 1.3 — Every ViewModel builds its own `SongRepositoryImpl`
`SongRepositoryImpl.kt:201`, and 10 call sites (`AddSongsViewModel.kt:61`, `ArtistDetailViewModel.kt:45`, `CreatePlaylistViewModel.kt:43`, `AlbumDetailViewModel.kt:45`, `GroupDetailViewModel.kt:52`, `ArtistListViewModel.kt:47`, `AlbumListViewModel.kt:47`, `LibraryViewModel.kt:81`, `PlaylistDetailViewModel.kt:43`, `PlaylistListViewModel.kt:91`, `SearchViewModel.kt:87`, `SongsViewModel.kt:85`, `SettingsScreen.kt:45`, `MainActivity.kt:54`)

Each `create()` allocates a new repository **and a new `FolderScanner`**. The DB is a singleton so it isn't a correctness bug, but there is no single repository instance, no place to add caching, no place to serialise scans, and no seam for testing. The repository pattern is structurally present and semantically absent.

### 🟡 1.4 — `SettingsScreen` re-creates its whole dependency graph on every recomposition
`SettingsScreen.kt:42-47`

```kotlin
val viewModel: SettingsViewModel = viewModel(
    factory = SettingsViewModel.Factory(
        PreferencesManager(context),
        SongRepositoryImpl.create(context),   // <- eager args, evaluated every recomposition
    ),
)
```

Both arguments are evaluated eagerly on **every recomposition**, even though the ViewModel already exists. That means a `getSharedPreferences` call, a `Room.databaseBuilder` singleton lookup, DAO accessors, and a `FolderScanner` allocation per frame of any state change on this screen. The `factory(LocalContext.current)` variants elsewhere have the same shape but allocate less.

### 🟡 1.5 — `SongsViewModel` holds a reference to another ViewModel
`SongsViewModel.kt:34, 43, 76`

```kotlin
class SongsViewModel(private val repository: SongRepository, private val playbackViewModel: PlaybackViewModel)
val currentlyPlayingSongId: StateFlow<String?> = playbackViewModel.currentSongId
fun onSongTap(song: Song) { playbackViewModel.playSong(song) }
```

A NavBackStackEntry-scoped ViewModel depending on an Activity-scoped ViewModel. It doesn't leak (the dependency points at the longer-lived object), but it couples two VMs, makes `SongsViewModel` untestable without a real `PlaybackViewModel`, and is inconsistent with every other screen, which takes `playbackViewModel` as a composable parameter instead.

### 🟡 1.6 — Presentation strings built inside a ViewModel
`SettingsViewModel.kt:37, 41, 44-48`

```kotlin
_rescanStatus.value = "Done — ${result.added} added, ${result.updated} updated, ${result.missing} missing"
```

English copy assembled in the ViewModel. Should be a state object (`ScanState.Done(added, updated, missing)`) rendered by the composable.

### 🟡 1.7 — Navigation routed through a ViewModel for four buttons, and inconsistently
`LibraryViewModel.kt:23-28, 53-75`; `LibraryScreen.kt:85-94, 109-113`

Four categories emit `NavigationEvent`s through a `SharedFlow` → collected in a `LaunchedEffect` → calls the nav lambda. But `onSeeAllClick = onNavigateToSongs` in the same file calls the identical destination **directly**. Two mechanisms for the same thing in one screen. The event indirection buys nothing here (no business logic between tap and navigate).

### 🟡 1.8 — `MutableSharedFlow` with zero buffer will suspend and replay stale intents
`LibraryViewModel.kt:34, 37`

```kotlin
private val _navigationEvents = MutableSharedFlow<NavigationEvent>()   // replay=0, extraBufferCapacity=0
```

`emit` suspends until a collector is attached. Taps made while the collector is detached (during a transition, or before `LaunchedEffect` runs) queue on `viewModelScope` and fire later, potentially after the user has navigated elsewhere. Rapid taps queue multiple navigations. Use `extraBufferCapacity = 1` with `BufferOverflow.DROP_OLDEST`, or `Channel(Channel.CONFLATED)`.

### 🟢 1.9 — Business-ish logic in composables
- `SongsScreen.kt:117-123` — `SortOrder.label` mapping lives in the screen file (defensible, but the enum lives in the VM file).
- `HomeScreen.kt:61-76` — `onArrowClick` orchestrates navigation + two animations + a re-entrancy guard inside the composable. Acceptable for pure motion, but it is ~15 lines of imperative logic in a composable body.
- `PlayerSheet.kt:290` — `if (duration > 0) currentPosition / duration else 0f` is presentation math in the view; fine, but the same expression is not shared with anything else that needs it.

### 🟢 1.10 — Eight near-identical `ViewModelProvider.Factory` boilerplate blocks
Each is 8 lines of `object : ViewModelProvider.Factory { ... as T }` with `@Suppress("UNCHECKED_CAST")`. Extract one generic helper (`viewModelFactory { ... }` from `androidx.lifecycle.viewmodel` is already on the classpath).

### 🟢 1.11 — No `modelClass` validation in any factory
All 9 factories ignore `modelClass` and blind-cast. A mismatched request produces a `ClassCastException` at the call site instead of a clear error.

---

# 2. Database layer

### 🔴 2.1 — Song identity collides across an entire album
`HashUtils.kt:9-31`, `FolderScanner.kt:52-78`

```kotlin
private const val SAMPLE_SIZE_BYTES = 65536
// Song identity = SHA-256 of the first 64KB of the file
```

MP3/FLAC files carry their ID3v2/Vorbis tag block — including embedded cover art — **at the head of the file**. Album artwork is routinely 100–500KB. For any album whose tracks share the same cover image and similar tag padding, the first 64KB is byte-identical across every track, so every track hashes to the **same primary key**.

The scanner then makes it worse:

```kotlin
val existing = songDao.getSongById(id)
if (existing != null) {
    when {
        existing.filePath != path -> {
            songDao.upsertSong(existing.copy(filePath = path, ...))   // overwrites track 1 with track 2's path
            updated++
        }
```

Track 2's scan **rewrites track 1's row** to point at track 2's file. Track 3 rewrites it again. A 12-track album becomes **one song** whose title is track 1 and whose audio is track 12. The scan reports `updated: 11` and looks successful. Playlists referencing the "lost" tracks silently point at the survivor.

This is unrecoverable without a rescan against a fixed hash, and it will hit most real libraries.

**Fix direction:** hash file size + a mid-file sample + an end sample, or skip the tag block, or (simplest) include `size` and a sample at `length/2` in the digest.

### 🔴 2.2 — N+1 queries: still present in three read paths
`SongRepositoryImpl.kt:155-175`

```kotlin
private suspend fun SongEntity.toDomain(): Song {
    val artists = artistDao.getArtistsForSong(id)      // 1 query per song
    val album = albumId?.let { albumDao.getAlbumById(it) }  // 1 more query per song
```

Callers:
- `getSongsForArtist` (`:42`) — artist detail screen
- `getSongsForAlbum` (`:57`) — album detail screen
- `getSongsForPlaylist` (`:125`) — playlist detail, **and it's a `Flow`, so the N+1 re-runs on every database invalidation**

A 20-track album detail screen issues **41 queries**. On an album screen every song shares the same `albumId`, so 20 of those `getAlbumById` calls are literally identical. A playlist open during a background scan re-runs the whole thing on each emission.

The comment on `getSongs()` ("2 queries total regardless of library size") applies only to that one method; the other three were not migrated.

### 🔴 2.3 — Missing indices on three hot lookup columns
`SongEntity.kt:19`, `ArtistEntity.kt`, `AlbumEntity.kt`

| Query | Column | Index? |
|---|---|---|
| `SongDao.getSongByPath` — `WHERE file_path = :path` | `songs.file_path` | ❌ none |
| `ArtistDao.getArtistByNormalizedName` — `WHERE name_normalized = :n` | `artists.name_normalized` | ❌ none |
| `AlbumDao.getAlbumByTitle` — `WHERE title = :t COLLATE NOCASE` | `albums.title` | ❌ none |

All three are called **inside the scanner's per-file loop**. `getSongByPath` runs once per newly-seen file and `getArtistByNormalizedName` once per artist per file — every one is a full table scan. On a 5,000-song library the scan degrades quadratically.

`songs.file_path` should additionally be `unique = true`: `getSongByPath` returns a single row and `FolderScanner.kt:74` assumes uniqueness, but nothing enforces it.

### 🔴 2.4 — `setPlaylistSongs` is not transactional and destroys ordering
`SongRepositoryImpl.kt:133-145`

```kotlin
override suspend fun setPlaylistSongs(playlistId: String, songIds: List<String>) {
    playlistDao.clearPlaylistSongs(playlistId)          // <- deletes everything first
    songIds.forEachIndexed { index, songId ->
        playlistDao.insertPlaylistSong(PlaylistSongEntity(..., position = index, addedAt = System.currentTimeMillis()))
    }
}
```

Three defects in seven lines:

1. **Not `@Transaction`.** A crash, process death, or cancellation between the `DELETE` and the last `INSERT` leaves the playlist truncated or empty. The delete is already committed.
2. **`addedAt` is reset to `now` for every song on every edit**, and `getSongsForPlaylist` orders by `added_at DESC`. Adding one song therefore **re-timestamps and reorders the entire playlist**. Worse, all rows get near-identical millisecond timestamps, so the resulting order is effectively arbitrary (SQLite's tiebreak on equal keys is unspecified).
3. **N separate transactions.** Each `insertPlaylistSong` is its own implicit transaction and its own Room invalidation, so `getSongsForPlaylist` re-emits (and re-runs the N+1 of §2.2) up to N times during a single save.

### 🔴 2.5 — `position` is written everywhere and read nowhere
`PlaylistSongEntity.kt:30`, `PlaylistDao.kt:51-60`

`PlaylistSongEntity.position` is populated by `setPlaylistSongs` but **no query ever orders by it**. `getSongsForPlaylist` orders by `added_at DESC` instead. The column that exists specifically to hold user-defined playlist order is dead, and the column being used in its place is destroyed on every edit (§2.4).

Same for `PlaylistEntity.position` (always `0`, `PlaylistDao.kt:28` orders by name) and `PlaylistGroupEntity.position` (always `0`, ordered by name).

### 🔴 2.6 — Concurrent scans can duplicate albums and artists
`SettingsViewModel.kt:35-51`, `FolderScanner.kt:167-189`

Nothing guards against two scans running at once — `rescanLibrary()` can be tapped repeatedly, and `MainActivity.scanLibraryOnLaunch` fires an independent scan on launch that can overlap a manual one.

`resolveAlbum` and `resolveArtist` are read-then-write with no transaction:

```kotlin
albumDao.getAlbumByTitle(title)?.let { return it }
val album = AlbumEntity(id = UUID.randomUUID().toString(), ...)
albumDao.upsertAlbum(album)
```

Two coroutines that both miss the lookup both insert, producing **duplicate album/artist rows with different UUIDs** for the same name. Since `getAlbumByTitle` uses `LIMIT 1`, the duplicates are invisible in the UI but permanently split the album's tracks.

### 🟡 2.7 — `is_missing` filtering is inconsistent across queries

| Query | Filters `is_missing`? |
|---|---|
| `AlbumDao.getSongsForAlbum` | ✅ |
| `ArtistDao.getSongsForArtist` | ✅ |
| `PlaylistDao.getSongsForPlaylist` | ✅ |
| `SongDao.getAllSongsWithDetails` (Songs screen, Search, Library recents, Add Songs) | ❌ |
| `AlbumDao.getAllAlbums` | ❌ (albums with all tracks missing still listed) |
| `ArtistDao.getAllArtists` | ❌ (artists with all tracks missing still listed) |

So a missing song is hidden from the album screen but visible (greyed out) on the Songs screen, invisible in the playlist it belongs to, and selectable in Add Songs. `SongRow` has dedicated missing-state rendering (`SongRow.kt:60-87`) that most screens can never trigger. Pick one policy.

### 🟡 2.8 — Album identity is title-only
`AlbumDao.kt:14`, `FolderScanner.kt:168`

```kotlin
@Query("SELECT * FROM albums WHERE title = :title COLLATE NOCASE LIMIT 1")
```

Two different albums named "Greatest Hits" by different artists merge into one row, inheriting the first one's artwork and year. Album identity should be (normalised title, primary artist) at minimum.

### 🟡 2.9 — `ListenHistory` is a fully dead table
`ListenHistoryDao.kt:1-8`, `ListenHistoryEntity.kt`, `SynthXDatabase.kt:19, 31`

```kotlin
@Dao
interface ListenHistoryDao {
    // TODO v2 Library cluster: record plays, recently played, most played queries
}
```

An empty DAO, an entity, a table, a foreign key, and an index — all created, none used. `listenHistoryDao()` is never called anywhere. Shipping an empty schema surface is cheap, but it's currently indistinguishable from a feature someone forgot to wire up.

### 🟡 2.10 — Dead / unused DAO methods
- `SongDao.getAllSongs()` (`:21`) — never called; superseded by `getAllSongsWithDetails`
- `SongDao.getSongArtistRefs(songId)` (`:28`) — never called; `getAllSongArtistRefs` is used instead. The comment in `SongWithDetails.kt:8` still points callers at it.
- `SongDao.getUnverifiedSongs()` (`:43`) — never called; the `verified`/`verified_at` columns are never written either.

### 🟡 2.11 — `getAllAlbums` correlated subquery runs per album, per emission
`AlbumDao.kt:24-43`

The primary-artist subquery joins `songs` × `song_artists` and sorts, **once per album row**, on every emission of the flow. With no index on the join path beyond `song_artists.artist_id`, this is the most expensive query in the app and it re-runs on every database write anywhere (Room invalidates by table, and `songs` changes constantly during a scan).

### 🟡 2.12 — Missing-song counting is wrong and non-transactional
`FolderScanner.kt:80-89`

```kotlin
for (song in songDao.getAllSongsOnce()) {
    if (song.filePath !in foundPaths) {
        if (!song.isMissing) songDao.markMissing(song.id, now)
        missing++          // counts already-missing songs too
    }
}
```

The reported `missing` count includes songs that were already flagged missing in previous scans, so "0 missing" never appears once anything has ever gone missing. The loop also issues one `UPDATE` per missing song outside a transaction.

### 🟢 2.13 — Migration `1 → 2` is correct
Verified against the exported schemas. `1.json` already contains `is_missing`, `missing_since`, `verified`, `verified_at`; `2.json` adds only `track_number` as a nullable `INTEGER` with no default, which is exactly what `MIGRATION_1_2` does. Index sets are identical. ✅

Two caveats:
- There is **no migration test** (no `androidTest` source set exists at all), so this correctness is unverified by CI.
- `fallbackToDestructiveMigration` is (correctly) not set, which means the *next* schema bump without a migration is a hard crash on open rather than a silent wipe. Good default; make sure it's intentional.

### 🟢 2.14 — Deprecated migration parameter name
`SynthXDatabase.kt:37` — `override fun migrate(database: SupportSQLiteDatabase)`. Room 2.6 renamed the parameter to `db`; the old name still compiles but is deprecated.

### 🟢 2.15 — Columns written but never read
`SongEntity.year`, `SongEntity.verified`, `SongEntity.verifiedAt`, `AlbumEntity.year`, `PlaylistEntity.description` (always `null`), `PlaylistEntity.coverArtUri` (always `null` — so `Playlist.albumArtUri` is permanently null and every playlist renders a glitch placeholder), `PlaylistEntity.createdAt`, `PlaylistGroupEntity.createdAt`.

---

# 3. Playback layer

### 🔴 3.1 — `playSong` silently no-ops before the service connects, but the UI says otherwise
`ExoPlayerEngine.kt:82-83`, `PlaybackViewModel.kt:40-43`

```kotlin
override fun playSong(song: Song) {
    val ctrl = controller ?: return      // silent drop
```
```kotlin
fun playSong(song: Song) {
    _currentSong.value = song            // set unconditionally
    controller.playSong(song)
}
```

If the user taps a song before `controllerFuture` resolves (cold start is the common case — the Library screen renders well before the service binds), the engine drops the request but the ViewModel has **already** set `_currentSong`. Result: the mini player slides up showing the song, the play/pause button is live, the seek bar sits at 0:00, and nothing plays. Tapping play/pause calls `resume()` on a null controller — another silent no-op. There is no recovery path short of tapping a different song.

The same happens permanently if `controllerFuture.get()` throws — the catch block comments "playback methods will be no-ops until retry" but **there is no retry**.

### 🟡 3.2 — Two infinite 2 Hz polling loops that never stop while subscribed
`ExoPlayerEngine.kt:31-43`

```kotlin
override val currentPosition: Flow<Long> = flow {
    while (true) { emit(controller?.currentPosition ?: 0L); delay(500) }
}.distinctUntilChanged()

override val duration: Flow<Long> = flow {
    while (true) { emit(controller?.duration?.takeIf { it > 0 } ?: 0L); delay(500) }
}.distinctUntilChanged()
```

- Two separate coroutines both waking every 500ms, regardless of whether anything is playing, whether the sheet is expanded, or whether the app is foregrounded.
- `duration` is a property of the loaded item, not a time-varying signal — polling it is pure waste; it should be pushed from `onMediaItemTransition` / `onPlaybackStateChanged`.
- Because **no screen uses `collectAsStateWithLifecycle`** (§6.5), the `stateIn(WhileSubscribed(5_000))` subscribers stay active while the app is backgrounded, so the polling continues off-screen.
- 500ms granularity also makes the seek bar visibly step rather than glide.

### 🟡 3.3 — Bitmap encode on the main thread in `playSong`
`ExoPlayerEngine.kt:92-96`

```kotlin
val bitmap = GlitchBitmapGenerator.generateGlitchBitmap(song.id)
val stream = ByteArrayOutputStream()
bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
```

`playSong` is invoked directly from composable click handlers, so this runs on the main thread: allocate a 256×256 ARGB_8888 bitmap (256KB), draw to it, then PNG-encode at quality 100. Tens of milliseconds of jank on the first frame after every tap on an art-less song. The bitmap is also never recycled and the result is never cached, so it's recomputed for the same song every time.

### 🟡 3.4 — Seeking floods the controller and fights the poller
`PlayerSheet.kt:289-293`

```kotlin
Slider(
    value = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f,
    onValueChange = { fraction -> onSeek((fraction * duration).toLong()) },
```

`onValueChange` fires on every drag pixel, issuing a `MediaController.seekTo` per event — dozens per drag. Simultaneously the 500ms poller keeps overwriting `value` with the player's actual position, so the thumb jumps backwards under the user's finger. Correct shape: local drag state + `onValueChangeFinished` to commit the seek.

### 🟡 3.5 — No queue: playback stops dead after every song
`ExoPlayerEngine.kt:104`, `PlayerSheet.kt:326-337, 351-362`

`playSong` uses `setMediaItem` (singular) and `onPlaybackStateChanged` calls `stop()` on `STATE_ENDED`, which nulls `currentSongId` and therefore `currentSong`, which makes `AnimatedVisibility(visible = showBottomBar && currentSong != null)` hide the whole player. Every track ends with the player vanishing.

Meanwhile the expanded player renders **Previous, Next, Queue, and More buttons whose `onClick` is `{}`** — four visible, enabled, ripple-responding controls that do nothing. Users will tap them.

### 🟡 3.6 — Media session accepts any caller
`AndroidManifest.xml` (service `android:exported="true"`), `SynthXPlaybackService.kt:29`

```kotlin
object : MediaLibrarySession.Callback {}
```

The default `onConnect` accepts every connection request. Combined with `exported="true"` and no permission attribute, any installed app can bind to the session and control playback (and, since this is a `MediaLibraryService`, browse it). `exported=true` is required for Android Auto/Assistant, but `onConnect` should validate `controllerInfo.packageName` against an allowlist, or the service should be a plain `MediaSessionService` if external browsing isn't wanted.

### 🟡 3.7 — `MediaController` thread affinity is enforced only by a comment
`ExoPlayerEngine.kt:29-30`

```kotlin
// Cold polling flows: collected on the caller's dispatcher (the ViewModel collects on
// Main.immediate via stateIn), which satisfies MediaController's main-thread requirement.
```

`MediaController` throws `IllegalStateException` if touched off its application looper. The engine's contract is "callers must collect on Main", enforced nowhere. Any future `.flowOn(Dispatchers.Default)`, `withContext`, or a test collecting on a test dispatcher crashes. Add `.flowOn(Dispatchers.Main.immediate)` inside the engine so the guarantee is structural.

### 🟡 3.8 — No `onTaskRemoved` handling
`SynthXPlaybackService.kt`

`MediaLibraryService` doesn't stop itself when the task is swiped away while paused. Without an `onTaskRemoved` override that calls `stopSelf()` when the player isn't playing, a dangling paused notification can survive app dismissal.

### 🟢 3.9 — `PlaybackController` is a pass-through with no behaviour
`PlaybackController.kt` — 19 lines, every member forwards verbatim to `PlaybackEngine`. It adds an indirection layer without adding a seam (both are already interfaces/abstractions). Either give it a job (queue management, play-count recording) or delete it and inject `PlaybackEngine` directly.

### 🟢 3.10 — `togglePlayPause` reads a possibly-stale `StateFlow`
`PlaybackViewModel.kt:53-55` — `isPlaying.value` from a `WhileSubscribed(5_000)` flow. The value is retained after unsubscription (default `replayExpiration` is infinite), so this is safe today, but it's reading through a flow whose upstream may be stopped. Reading the engine directly would be unambiguous.

### 🟢 3.11 — Design tokens duplicated as raw ints in the playback layer
`GlitchBitmapGenerator.kt:16, 33-37`

```kotlin
canvas.drawColor(Color.parseColor("#1F1F1F"))
0 -> Color.argb(153, 202, 255, 0)   // Accent @ 60%
```

The accent and surface colours restated as decimal RGB in a non-Compose file, with comments explaining which token they mirror. Any change to `Color.kt` silently desynchronises the notification artwork. Also the whole hash/strip algorithm is a **verbatim duplicate** of `GlitchPlaceholder.kt:34-49` — two copies that must stay in lockstep for the notification art to match the in-app placeholder.

---

# 4. Compose UI

### 🔴 4.1 — Add Songs silently discards the user's selections
`AddSongsViewModel.kt:31-41`

```kotlin
init {
    viewModelScope.launch {
        val existingIds = repository.getSongIdsForPlaylist(playlistId).toSet()
        repository.getSongs().collect { allSongs ->
            _songs.value = allSongs.map { SongItem(song = it, isChecked = it.id in existingIds) }
        }
    }
}
```

`getSongs()` is a hot `Flow` backed by Room invalidation. **Every** database write anywhere in `songs`, `albums`, `artists`, or `song_artists` re-emits and rebuilds `_songs` from `existingIds`, wiping every checkbox the user has ticked. The launch scan (`MainActivity.kt:52`) runs in the background on every cold start and writes constantly, so this reliably fires while the user is mid-selection. The user then taps Done and saves a state they didn't choose — and because `setPlaylistSongs` is a full replace (§2.4), it can *remove* songs too.

### 🟡 4.2 — `GlitchPlaceholder` keeps the Compose frame loop running forever
`GlitchPlaceholder.kt:57-68`

```kotlin
// Always running — value only applied when isPlaying
val infiniteTransition = rememberInfiniteTransition(label = "glitch")
val animOffset by infiniteTransition.animateFloat(...)
val pixelOffset = if (isPlaying) animOffset else 0f
```

The comment is correct that the *value* is gated, but `rememberInfiniteTransition` schedules `withInfiniteAnimationFrameNanos` callbacks regardless of who reads it. Every visible art-less row instantiates its own infinite transition, so a screen with 15 placeholders requests animation frames at display rate **permanently**, whether or not anything is playing. Gate the transition itself on `isPlaying`.

Separately, when `isPlaying` *is* true, `animOffset` is read in the **composition** scope, so the row fully recomposes at 60fps. Move the read into the draw phase (read `.value` inside the `Canvas` lambda) so only the draw phase invalidates.

### 🟡 4.3 — `AnimatedGradientBackground` rebuilds a shader and blur filter every frame, forever
`AnimatedGradientBackground.kt:56-72, 84-95`

```kotlin
.drawWithCache {
    val paint = Paint().apply { asFrameworkPaint().apply {
        shader = LinearGradientShader(from = Offset(startX.value * size.width, ...), ...)
        maskFilter = BlurMaskFilter(BlurRadiusPx, BlurMaskFilter.Blur.NORMAL)
    }}
```

The cache block reads four `Animatable.value`s, so it re-executes on **every animation frame**, allocating a new `Paint`, a new native `LinearGradientShader`, and a new 200px `BlurMaskFilter` each time. A 200px `BlurMaskFilter` over a full-screen rect is already one of the most expensive draws available on Android; rebuilding the filter per frame compounds it.

The `LaunchedEffect(Unit) { while (true) { ... } }` loop never terminates, so this runs continuously for as long as Home is composed. The comment claims the cache rebuilds "only while the points are actually moving" — the points are *always* moving; the loop has no idle state.

### 🟡 4.4 — Bottom nav offset recomposes instead of re-laying-out
`MainScaffold.kt:598-600`

```kotlin
Column(modifier = Modifier.offset(y = (playerProgress * 70).dp))
```

The `Dp` overload of `offset` reads state during **composition**. `playerProgress` changes every frame of a player drag, so `SynthXBottomNav` (and everything it composes: 3 `NavigationBarItem`s, icons, labels, `NavigationBarItemDefaults.colors`) recomposes every frame. Use `Modifier.offset { IntOffset(0, ...) }` so the read happens in the layout phase.

### 🟡 4.5 — `playerProgress` invalidates the entire `MainScaffold` every drag frame
`MainScaffold.kt:116, 535, 577`; `PlayerSheet.kt:152-154`

```kotlin
// MainScaffold
var playerProgress by remember { mutableStateOf(0f) }
...
onProgressChanged = { playerProgress = it }

// PlayerSheet
LaunchedEffect(progress) { onProgressChanged(progress) }
```

Two problems: `playerProgress` is read in `MainScaffold`'s own body, so the whole scaffold function recomposes per frame of the drag; and `LaunchedEffect(progress)` **cancels and relaunches a coroutine on every frame** just to invoke a synchronous callback. Use a lambda-based progress provider (`() -> Float`) or `snapshotFlow`.

### 🟡 4.6 — The full-screen player is always composed, measured, and loading full-res artwork
`PlayerSheet.kt:257-262, 266, 420-429`

```kotlin
Column(modifier = Modifier.fillMaxSize().alpha(fullPlayerAlpha) ...) {
    AlbumArt(song = song, isPlaying = isPlaying, size = artSize, fullResolution = true)
```

`alpha(0f)` still composes, measures, lays out, and draws. So while the mini player is showing, the app is holding a **`Size.ORIGINAL` decode of the album artwork** (potentially a multi-megapixel bitmap) plus the entire expanded UI tree. On a device with several MB of cover art this is a meaningful, permanent memory cost for content that is invisible. Gate the expanded content on `progress > 0f`.

### 🟡 4.7 — `derivedStateOf` misuse
`MainScaffold.kt:135-143`

```kotlin
val chromeBottomPadding by remember(currentSong, showBottomBar) {
    derivedStateOf { when { !showBottomBar -> 0.dp; currentSong != null -> ...; else -> ... } }
}
```

The lambda reads only the `remember` **keys**, not any `State`. `derivedStateOf` therefore does nothing except add an observer allocation — the `remember` keys are already the invalidation mechanism. Also, keying on the whole `currentSong` object means every song change invalidates, though only nullity matters; key on `currentSong != null`.

### 🟡 4.8 — Route sets and item lists allocated on every recomposition
`MainScaffold.kt:92-105` (`bottomBarRoutes`), `MainScaffold.kt:590-594` (`items`), `MainScaffold.kt:629-640` (a second inline route set), `GlitchPlaceholder.kt:51-55` (`glitchColors`)

None are `remember`ed. The two route sets are 12-element `LinkedHashSet`s rebuilt per recomposition of a function that recomposes on every navigation *and* every drag frame (§4.5).

### 🟡 4.9 — Composables doing far too much
| File | Lines | Problem |
|---|---|---|
| `MainScaffold.kt` | 683 | 14 inline `composable{}` blocks, each with an inline `topBar`, inline nav lambdas, plus the bottom nav, plus the player overlay, plus transition choreography. Route registration should be extracted per feature (`NavGraphBuilder.libraryGraph(...)`). |
| `PlayerSheet.kt` | 503 | `PlayerSheetContent` is a single ~220-line composable containing the mini row, the art, the metadata, the seek section, the transport row, and the bottom action row. Every one of those is independently extractable. |
| `SearchScreen.kt` | 386 | Screen + 4 `LazyListScope` extensions + 2 row composables + all the filter-chip config inline. |
| `PlaylistListScreen.kt` | 352 | Screen + create-group card + group row + dropdown menu + two button styles inline. |

### 🟡 4.10 — Missing minimum touch targets
- `LibraryScreen.kt:194-199` — "See All" is a bare `Text` with `clickable`, no `sizeIn(minHeight = 48.dp)`. Roughly a 20dp tap target.
- `CreatePlaylistScreen.kt:113-119` — group selection rows use `Spacing.xxl` (48dp), which is exactly at the limit but with no `minimumInteractiveComponentSize`.

### 🟡 4.11 — `LibraryScreenContent` is a non-scrollable `Column`
`LibraryScreen.kt:130`

Title + 4 category rows + a "Recently Added" section, with no `verticalScroll`. On a small screen, in landscape, or at large font scales, the recently-added carousel is clipped off the bottom with no way to reach it.

### 🟢 4.12 — Lazy list `key`s are present but `contentType` is absent everywhere
All 14 `items(...)` call sites supply a `key` ✅ (the empty-state `item {}` at `PlaylistListScreen.kt:168` doesn't, which is harmless). None supply `contentType`, so heterogeneous lists — notably `PlaylistListScreen`'s groups + playlists and `SearchScreen`'s ALL mode with 4 row types — can't reuse composition slots across types.

### 🟢 4.13 — Only 4 of ~40 composables accept a `modifier` parameter
`PlayerSheet`, `GlitchPlaceholder`, `AnimatedGradientBackground`, `LibraryScreenContent`. `SongRow`, `PlaylistRow`, `SynthXTopBar`, `DetailTopBar`, `CategoryRow`, `AlbumRow`, `ArtistRow`, `AddSongRow`, `RecentSongTile`, and every screen composable omit it. This is the single most-cited Compose API guideline.

### 🟢 4.14 — `AnimatedVisibility` wrapping a nullable that empties during the exit animation
`MainScaffold.kt:545-580`

```kotlin
AnimatedVisibility(visible = showBottomBar && currentSong != null, exit = fadeOut(...)) {
    currentSong?.let { song -> PlayerSheet(...) }
}
```

When `currentSong` becomes null the exit animation starts, but the content immediately renders nothing, so the fade-out animates an empty box. Capture the last non-null song in a `remember` for the duration of the exit.

### 🟢 4.15 — `Scaffold` with empty `topBar`/`bottomBar`
`MainScaffold.kt:146-151` — a `Scaffold` whose only contributions are `containerColor` and window-inset padding. A `Box` with `background` + `windowInsetsPadding` would be clearer about intent.

### 🟢 4.16 — Nested `Scaffold`
`LibraryScreen.kt:96` sits inside `MainScaffold`'s `Scaffold`. Two inset-consuming scaffolds in one tree; it works because of the `consumeWindowInsets` call, but it's fragile.

### 🟢 4.17 — Verbose re-implementation of `Modifier.padding(PaddingValues)`
`LibraryScreen.kt:102-107` — four `calculate*Padding` calls hardcoding `LayoutDirection.Ltr` (which also breaks RTL, despite `supportsRtl="true"` in the manifest) where `Modifier.padding(innerPadding)` would do.

### 🟢 4.18 — `remember` without `rememberSaveable`, everywhere
Zero uses of `rememberSaveable` in the codebase. Lost on process death or configuration change: `AnchoredDraggableState` (sheet snaps back to collapsed), `showSortSheet`, `isRenaming` / `renameValue`, `name` / `selectedGroupId` in Create Playlist, `showCreateGroup`, and `playerProgress`.

### 🟢 4.19 — Preview composables ship in the release APK
`TypeScalePreview.kt` and 9 `@Preview` functions live in `src/main`. Harmless with R8 off, but they're dead weight in the production DEX.

---

# 5. Navigation

### 🟡 5.1 — The expanded player has no back handling
No `BackHandler` exists anywhere in the codebase. With the `PlayerSheet` dragged to full-screen, pressing back navigates the underlying `NavHost` (or exits the app from Library) while the sheet stays expanded on top of the newly-navigated screen. Standard behaviour is: back collapses the sheet first.

### 🟡 5.2 — Route strings are declared twice for all 5 parameterised destinations
`Screen.kt:12-14, 19-21, 27-29, 33-35, 39-41`

```kotlin
object ArtistDetail : Screen("artist_detail/{artistId}/{artistName}") {
    // Must mirror the constructor arg — a member isn't referencable in the supertype call
    const val ROUTE = "artist_detail/{artistId}/{artistName}"
```

The comment explains *why* but not that it's avoidable: make the constructor take the `const` from a companion, or make `Screen` an interface with `val route`, or just use `Screen.X.route` everywhere and delete `ROUTE`. Currently a typo in one of the two literals produces a route that registers but never matches — a silent blank screen, not a compile error.

`ROUTE` and `route` are also used inconsistently across `MainScaffold` (`ROUTE` at `:97,99,102,103,104,322,361,421,447,472`, `route` elsewhere), so the two must be kept in sync by hand.

### 🟡 5.3 — The 12-route "is this a Library sub-route?" set is duplicated
`MainScaffold.kt:92-105` (`bottomBarRoutes`) and `MainScaffold.kt:629-640` (inline `setOf(...)` in the nav-item click handler)

Two hand-maintained lists of the same destinations, minus `Screen.Library` in the second. Adding a route requires remembering both; forgetting the first hides the bottom bar, forgetting the second breaks the Library-tab pop behaviour. Neither failure is a compile error.

### 🟡 5.4 — Tapping "Home" from Search lands on Library
`MainScaffold.kt:615-628`

```kotlin
item.screen.route == Screen.Home.route -> {
    if (currentRoute == Screen.Library.route) navController.popBackStack(Screen.Home.route, inclusive = false)
    else navController.navigate(Screen.Library.route) { ... }
}
```

The comment explains this preserves the Home↔Library crossfade, but the user-visible result is that the Home tab does not go Home. From Search it takes two taps (Search → Library → Home); from a detail screen it takes two or three. Meanwhile the Home tab item never renders as `selected` (Home isn't in `bottomBarRoutes`, so the bar isn't shown on Home at all).

Also, the `navigate` branch sets `restoreState = true` without a matching `saveState = true` in its `popUpTo`, so there is nothing to restore.

### 🟡 5.5 — Missing/blank navigation arguments are not handled
`MainScaffold.kt:328-329, 367-368, 427-428, 453-454, 477`

```kotlin
val artistId = entry.arguments?.getString("artistId") ?: ""
```

Every parameterised destination falls back to `""`, which is then passed to a repository query that returns an empty list, rendering "No songs found" with no indication that anything went wrong. More importantly, `createRoute` doesn't encode the *ids* — only the display names — so an id containing `/` (not possible with the current UUIDs, but nothing enforces UUIDs) produces an unmatched route.

An **empty display name** is worse: `Screen.ArtistDetail.createRoute(id, "")` builds `artist_detail/<id>/`, which does not match the `{artistName}` pattern, so `navigate` throws `IllegalArgumentException: Navigation destination that matches request ... cannot be found`. An untitled artist or a playlist renamed to whitespace is a crash path.

### 🟡 5.6 — Navigation arguments carry display data
Artist name, album title, playlist name, and group name are all passed through the route purely so the top bar can show them before the ViewModel loads. Consequences: the title goes stale if the entity is renamed while the screen is open; the URL-encoded name shows up in back-stack state; and every rename in `GroupDetail`/`PlaylistList` leaves the previous screen's title wrong until re-navigation.

### 🟢 5.7 — `previousRoute` is written in a `LaunchedEffect`, read during composition
`MainScaffold.kt:121-131`

The comment carefully justifies the frame-ordering, and it works — but it's a read-before-write ordering dependency between a composition-scope read and an effect-scope write, which is exactly the kind of thing that breaks silently when Compose's effect scheduling changes. `navController.previousBackStackEntry?.destination?.route` gives the same answer without the race.

### 🟢 5.8 — `DetailTopBar` titles have no `maxLines`/`overflow`
`TopBars.kt:125-131` — a long album or artist name pushes the actions off-screen or wraps to multiple lines.

### 🟢 5.9 — No deep links, no `savedStateHandle`
Detail ViewModels take their id as a constructor arg through a factory rather than reading `SavedStateHandle`, so nothing survives process death and no destination is deep-linkable.

---

# 6. Performance

### 🔴 6.1 — Search filters the entire library on the main thread on every keystroke
`SearchViewModel.kt:48-72`

```kotlin
viewModelScope.launch {                       // Dispatchers.Main.immediate
    combine(_query.debounce(300), repository.getSongs(), repository.getArtists(),
            repository.getAlbums(), repository.getAllPlaylists()) { query, songs, artists, albums, playlists ->
        val q = query.trim().lowercase()
        SearchResults(
            songs = songs.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) },
            ...
```

- Runs on **`Dispatchers.Main`** — no `flowOn`, no `Dispatchers.Default`.
- Allocates two new `String`s per song per keystroke (`title.lowercase()`, `artist.lowercase()`), plus one per artist, two per album, one per playlist.
- Keeps **four** database flows hot for the ViewModel's entire lifetime — including `getSongs()`, which itself does a full `song_artists` table load and `groupBy` on every emission (§6.2).
- `combine` re-runs the whole filter when **any** of the five sources emits, so a background scan writing to `songs` re-filters the library repeatedly, debounce notwithstanding.

On a 5,000-song library this is hundreds of milliseconds of main-thread work per keystroke. Should be a SQL `LIKE`/FTS query.

### 🔴 6.2 — `getSongs()` does full-table work on the main thread on every emission
`SongRepositoryImpl.kt:30-35`

```kotlin
override fun getSongs(): Flow<List<Song>> =
    songDao.getAllSongsWithDetails().map { entries ->
        val refsBySongId = songDao.getAllSongArtistRefs().groupBy { it.songId }
        entries.map { it.toDomain(refsBySongId[it.song.id] ?: emptyList()) }
    }
```

No `flowOn`. Room's `Flow` runs the *query* on its own executor, but the `map` operator runs in the **collector's** context, which is `viewModelScope` = `Dispatchers.Main.immediate` for all five consumers. So on every emission the main thread performs: a `groupBy` over the entire `song_artists` table, then per song an `associate` + a `sortedBy` + a `joinToString`. For 5,000 songs × 2 artists that's ~10,000 map entries and 5,000 sorts per emission — and emissions fire continuously during a scan.

Add `.flowOn(Dispatchers.Default)`.

### 🟡 6.3 — Sorting the whole library on the main thread
`SongsViewModel.kt:55-73`, `LibraryViewModel.kt:44-50`

```kotlin
combine(_sortOrder, repository.getSongs()) { order, songs -> songs.sorted(order) }   // Main
SortOrder.TITLE -> sortedBy { it.title.lowercase() }                                  // + N allocations
```

```kotlin
repository.getSongs().map { it.sortedByDescending { it.dateAdded }.take(20) }         // Main
```

`LibraryViewModel` in particular loads and sorts the **entire library** to display **20 items**, on the main thread, on every database change. That should be `ORDER BY date_added DESC LIMIT 20` in SQL.

Note also that `sortedBy { it.title.lowercase() }` uses Java's default ordinal comparison while the DAOs use `COLLATE NOCASE`, so the same list sorts differently depending on which path produced it.

### 🟡 6.4 — Cover art is re-encoded to PNG at quality 100 into `cacheDir`
`FolderScanner.kt:199-211`

```kotlin
val artFile = File(context.cacheDir, "art_$songId.png")
FileOutputStream(artFile).use { fos -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos) }
```

Four problems:
1. **PNG re-encode of a JPEG source** typically triples the file size. A 300KB JPEG cover becomes ~1MB.
2. **One file per song**, not per album — a 12-track album writes 12 identical ~1MB files. A 5,000-song library can write multiple gigabytes.
3. **`cacheDir` is evictable by the OS.** When Android reclaims it, every `album_art_uri` in the database becomes a dangling `file://` URI and all artwork silently disappears. Nothing detects or repairs this.
4. **Orphaned files are never cleaned up** when a song is deleted or its hash changes.

Use `filesDir`, key by album, and keep the original bytes.

### 🟡 6.5 — Every flow collection ignores lifecycle
29 `collectAsState()` calls across 14 files; zero `collectAsStateWithLifecycle`. `lifecycle-runtime-compose` isn't even a declared dependency. Every screen keeps collecting while the app is backgrounded, which keeps the `WhileSubscribed(5_000)` position/duration pollers (§3.2) awake and keeps every hot repository flow alive.

### 🟡 6.6 — Nine hot flows with no `WhileSubscribed`
Every screen ViewModel uses `init { viewModelScope.launch { flow.collect { _state.value = it } } }` rather than `stateIn(viewModelScope, WhileSubscribed(5_000), initial)`. Combined with §6.5, once a screen has been visited its database flows run for the lifetime of its ViewModel regardless of visibility. `SearchViewModel` holds four of them.

### 🟡 6.7 — Unbounded recursion in the folder walk
`FolderScanner.kt:94-104`

```kotlin
private fun collectAudioFiles(dir: DocumentFile): List<DocumentFile> {
    for (child in dir.listFiles()) {
        when { child.isDirectory -> result.addAll(collectAudioFiles(child)) ... }
```

No depth limit and no cycle protection. A deeply nested tree (or a provider that exposes a symlink loop) overflows the stack. `DocumentFile.listFiles()` is also a slow `ContentResolver` query per directory, executed serially, with no cancellation check (`ensureActive()`) anywhere in the scan.

### 🟡 6.8 — Launch scan is tied to the Activity lifecycle
`MainActivity.kt:52-63`

```kotlin
lifecycleScope.launch(Dispatchers.IO) { repository.scanLibrary(...) }
```

`lifecycleScope` is cancelled when the Activity is destroyed — including on **rotation**. A long scan is aborted mid-way (leaving partial state, since the scan isn't transactional) and restarted from scratch by the new Activity instance. Rotating repeatedly means the scan never completes. This belongs in a `WorkManager` job or an Application-scoped coroutine.

### 🟢 6.9 — No image size hints on most `AsyncImage` calls
`SongRow.kt:93`, `AlbumListScreen.kt:121`, `SearchScreen.kt:348`, `PlaylistRow.kt:77`, `AddSongsScreen.kt:148`, `LibraryScreen.kt:228` all pass a bare URI string. Coil infers size from the layout node, which is correct, but there's no `crossfade`, no `placeholder`, and no `error` drawable — a failed/slow load renders an empty box with no fallback to the glitch placeholder that exists for the null case.

### 🟢 6.10 — `String.format` without a locale in the hash
`HashUtils.kt:26` — `digest.joinToString("") { "%02x".format(it) }` uses the default locale. Hex digits are locale-stable in practice, but this is also 64 `String.format` invocations per file scanned (one of the slowest formatting paths in Java). A lookup-table hex encoder is ~50× faster and locale-proof.

---

# 7. Error handling

### 🔴 7.1 — "Delete" is a visible, tappable no-op in two screens
`PlaylistListViewModel.kt:83-85`, `GroupDetailViewModel.kt:44-46`

```kotlin
fun deletePlaylist(id: String) {
    // Placeholder — no-op for now, secure delete deferred
}
```

Both `PlaylistListScreen.kt:158` and `GroupDetailScreen.kt:52` wire the three-dots **Delete** menu item to these. The user taps Delete, the menu closes, and the playlist remains — no error, no toast, no explanation. `SongRepository.deletePlaylist` exists and works (`SongRepositoryImpl.kt:122`); it's simply never called.

Either wire it up (with a confirmation dialog) or remove the menu item.

### 🔴 7.2 — Four screens have no error state and will crash on a database exception
| File | Missing |
|---|---|
| `PlaylistDetailViewModel.kt:29-37` | no `.catch`, no `UiState.Error` |
| `GroupDetailViewModel.kt:29-37` | no `.catch`, no `UiState.Error` |
| `CreatePlaylistViewModel.kt:26-28` | no `.catch` |
| `SearchViewModel.kt:48-72` | no `.catch` on a 5-way `combine` |
| `LibraryViewModel.kt:44-50` | no `.catch` |
| `AddSongsViewModel.kt:32-40` | no `.catch` on the collect or the `getSongIdsForPlaylist` call |

An exception in any of these propagates out of `viewModelScope`, hits the default handler, and **crashes the app**. `SongsViewModel`, `AlbumListViewModel`, and `ArtistListViewModel` do have `.catch`, so the inconsistency is clearly an oversight rather than a decision.

### 🔴 7.3 — `takePersistableUriPermission` is unguarded
`SettingsScreen.kt:53-60`

```kotlin
uri?.let {
    context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    viewModel.saveMusicFolder(it)
}
```

Providers that return a tree URI without a persistable grant (some cloud/DAV document providers, and MTP) cause `takePersistableUriPermission` to throw `SecurityException`, crashing the app from inside an activity-result callback. Wrap in `try/catch` and surface a message.

### 🟡 7.4 — `catch (e: Exception)` swallows `CancellationException`
`SettingsViewModel.kt:47`, `MainActivity.kt:59`, `FolderScanner.kt:124, 208`, `HashUtils.kt:28`

`CancellationException` is an `Exception`. Catching it breaks structured concurrency: cancelling the ViewModel mid-scan reports "Scan failed" to the user, and `FolderScanner`'s inner catches swallow cancellation entirely, so a cancelled scan keeps grinding through files. Catch `Exception` but rethrow `CancellationException`, or catch specific types.

### 🟡 7.5 — Errors are terminal; there is no retry anywhere
`SongsViewModel.kt:58-60`, `AlbumListViewModel.kt:33-35`, `ArtistListViewModel.kt:33-35`

`Flow.catch` **terminates** the flow. Once any error occurs the screen is permanently stuck on the error text — no retry button, no re-subscription, and the flow will never emit again even after the underlying problem clears. Navigating away and back recreates the ViewModel, which is the only recovery, and it isn't discoverable.

### 🟡 7.6 — Raw exception messages shown to users
`SongsViewModel.kt:59`, `AlbumListViewModel.kt:34`, `ArtistListViewModel.kt:34`, `AlbumDetailViewModel.kt:36`, `ArtistDetailViewModel.kt:36`

```kotlin
UiState.Error(e.message ?: "Failed to load songs")
```

`e.message` for a Room/SQLite failure is a stack-flavoured string like `"no such column: track_number (code 1 SQLITE_ERROR)"`, rendered centred on screen in `bodyLarge`. Map to user-facing copy.

### 🟡 7.7 — Silent failures throughout the scan pipeline
| Location | Swallowed |
|---|---|
| `FolderScanner.kt:39-40` | `DocumentFile.fromTreeUri` returns null → returns an all-zero `ScanResult` indistinguishable from "nothing changed". This is the **revoked-permission path** and it's invisible. |
| `FolderScanner.kt:52` | `computeSongId` returns null → `continue`, file skipped, not counted, not reported |
| `FolderScanner.kt:124-125` | metadata extraction failure → falls back silently |
| `FolderScanner.kt:208-210` | artwork extraction failure → `null` |
| `MainActivity.kt:59-61` | launch-scan failure → `Log.e` only |

Net effect: if the persisted URI grant is lost (app data cleared, SD card removed, provider revoked), the user sees an empty library, a Settings screen that still shows the folder path, and a "Rescan" that reports success. There is no way to diagnose it from inside the app.

### 🟡 7.8 — Destructive actions have no confirmation
- Delete group (`PlaylistListScreen.kt:147`) — deletes immediately; `ON DELETE SET NULL` orphans its playlists to ungrouped
- Delete playlist — currently a no-op (§7.1), but the intended path has no confirmation either
- Add Songs "Done" with everything unchecked (`AddSongsScreen.kt:106`) — silently empties the playlist via `setPlaylistSongs`

No undo, no snackbar, no dialog anywhere in the app.

### 🟡 7.9 — Blank-input validation fails silently
`PlaylistListViewModel.kt:58, 66, 79`, `CreatePlaylistViewModel.kt:32`

```kotlin
fun createGroup(name: String) { if (name.isBlank()) return; ... }
```

Tapping "Create" with an empty name does nothing at all — the card stays open, the button is still enabled, no error text appears. Same for rename (`PlaylistRow.kt:102`, `PlaylistListScreen.kt:282`): pressing Done on an empty rename field closes the editor and keeps the old name with no feedback. Disable the button or show an error.

### 🟡 7.10 — Notification permission requested without an SDK guard
`MainActivity.kt:70-79`

```kotlin
val permissionState = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
LaunchedEffect(Unit) { if (!permissionState.status.isGranted) permissionState.launchPermissionRequest() }
```

`minSdk = 32`, but `POST_NOTIFICATIONS` only exists from API 33. On Android 12L the permission isn't a runtime permission, so `isGranted` is false and a request is dispatched that resolves to denied — harmless but pointless. More importantly, there's no rationale UI and no handling of permanent denial: if the user declines, media notifications are gone forever with no explanation and no path to Settings.

### 🟢 7.11 — Loading states are inconsistent and mostly invisible
`SongsScreen.kt:133-136` shows a `CircularProgressIndicator`. `AlbumListScreen.kt:73`, `ArtistListScreen.kt:65`, `AlbumDetailScreen.kt:56`, `ArtistDetailScreen.kt:56`, `GroupDetailScreen.kt:37`, `PlaylistDetailScreen.kt:79` all render `-> Unit`: a blank screen. `AddSongsScreen` and `SearchScreen` have no loading state at all.

### 🟢 7.12 — `PlaylistRow` rename has no cancel path
`PlaylistRow.kt:93-111` — once `isRenaming` is true, the only exit is pressing Done on the keyboard. Tapping elsewhere, scrolling away, or pressing back leaves the field open indefinitely with unsaved text.

---

# 8. Code quality

### 🟡 8.1 — Dead code

| Item | Location |
|---|---|
| `HomeViewModel` — entire class, never referenced | `HomeViewModel.kt` |
| `UiEvent` / `_uiEvents` / `uiEvents` — declared, collected in `LibraryScreen.kt:77-83`, **never emitted**; the `SnackbarHost` and `SnackbarHostState` exist solely to serve it | `LibraryViewModel.kt:19-21, 34-35` |
| `Motion.DurationFast` (150), `Motion.DurationSlow` (500) | `Motion.kt:8, 10` |
| `SongDao.getAllSongs()`, `getSongArtistRefs()`, `getUnverifiedSongs()` | `SongDao.kt:21, 28, 43` |
| `ListenHistoryDao` — empty interface, table never touched | `ListenHistoryDao.kt` |
| `androidx-ui-text-google-fonts` — in the catalog, not in `build.gradle.kts` | `libs.versions.toml:26` |
| `PlaylistRow.onMoveToGroup(null)` — the "remove from group" path has no UI to invoke it | `PlaylistRow.kt:56`, `PlaylistThreeDotsMenu` only offers concrete groups |
| `SongsScreen` `topBar` receives `sortOrder`, which the only caller ignores | `SongsScreen.kt:43` vs `MainScaffold.kt:285` |
| `SongRow`'s `MoreVert` icon — rendered with `contentDescription = null` and **no click handler** | `SongRow.kt:131-136` |
| `AddSongsScreen`'s disabled search field — "Frozen search bar — placeholder only" | `AddSongsScreen.kt:71-94` |
| Player transport: Previous, Next, Queue, More — all `onClick = {}` | `PlayerSheet.kt:327, 352, 372, 380` |

### 🟡 8.2 — Code duplication

**a) The "row + hairline divider + accent ripple" pattern — 8 copies**
`SongRow.kt:54-144`, `PlaylistRow.kt:63-137`, `LibraryScreen.kt:269-300` (`CategoryRow`), `AlbumListScreen.kt:107-158` (`AlbumRow`), `ArtistListScreen.kt:99-124` (`ArtistRow`), `SearchScreen.kt:299-324` (`ArtistResultRow`), `SearchScreen.kt:334-385` (`AlbumResultRow`), `PlaylistListScreen.kt:253-314` (`GroupRow`), `AddSongsScreen.kt:128-185` (`AddSongRow`).

`AlbumRow`/`AlbumResultRow` and `ArtistRow`/`ArtistResultRow` are **byte-for-byte duplicates** across two packages.

**b) `UiState` sealed class — 6 near-identical definitions**
`SongsViewModel.kt:18-23`, `AlbumListViewModel.kt:20-25`, `ArtistListViewModel.kt:20-25`, `AlbumDetailViewModel.kt:20-25`, `ArtistDetailViewModel.kt:20-25`, `PlaylistDetailViewModel.kt:20-24`, `GroupDetailViewModel.kt:20-24`. Two of them silently omit `Error` (§7.2).

**c) The `Box { when (uiState) { Loading/Empty/Error/Success } }` content composable — 5 copies**
`SongsScreen.kt:126-156`, `AlbumListScreen.kt:67-98`, `ArtistListScreen.kt:59-90`, `AlbumDetailScreen.kt:49-83`, `ArtistDetailScreen.kt:49-83`.

**d) The glitch hash/strip algorithm — 2 copies that must match exactly**
`GlitchPlaceholder.kt:34-49` and `GlitchBitmapGenerator.kt:19-41`.

**e) The wordmark `buildAnnotatedString` — 2 copies**
`HomeScreen.kt:114-134` and `TopBars.kt:64-87`. These are the two ends of a shared-element transition, so a divergence is a visible morph glitch.

**f) `ViewModelProvider.Factory` boilerplate — 9 copies** (§1.10).

### 🟡 8.3 — Every user-facing string is hardcoded
`strings.xml` contains exactly one entry (`app_name`). Roughly 60 literals are hardcoded in Kotlin, including: "Library", "Recently Added", "See All", "Playlists", "Artists", "Albums", "Songs", "Settings", "New Playlist", "New Group", "Add Songs", "Sort by", "Recently Added"/"Title"/"Artist"/"Album", "No songs found", "No albums found", "No artists found", "No playlists yet", "No results found", "Search your library", "Music folder", "No folder selected", "Rescan library", "Scanning…", "Scan failed", "Rename", "Delete", "Cancel", "Create", "Done", "Group name", "My Playlist", "Playlist name", "Add to group (optional)", "No groups yet", "Unknown Artist", "Unknown Album", "Unknown", plus every `contentDescription`.

No localisation is possible, and the accessibility strings can't be reviewed in one place.

### 🟡 8.4 — Artist splitting mangles legitimate names
`FolderScanner.kt:191-197, 216-217`

```kotlin
private val ARTIST_DELIMITERS = listOf(" feat. ", " ft. ", " featuring ", " & ", " and ", ",")
```

Applied unconditionally, in order, to every artist tag:

| Tag | Result |
|---|---|
| `Earth, Wind & Fire` | `Earth` / `Wind` / `Fire` — three artists |
| `Tyler, The Creator` | `Tyler` / `The Creator` |
| `Simon and Garfunkel` | `Simon` / `Garfunkel` |
| `Florence + the Machine` | (unsplit — `+` isn't handled) |
| `AC/DC` | (unsplit, correctly) |

Every affected album fragments into phantom artists that then appear in the Artists list and Search. Note `","` has no surrounding spaces, so it also splits inside words with no whitespace.

### 🟡 8.5 — Naming inconsistencies
- `SongRepository.getSongs()` vs `getAllPlaylists()` vs `getAllGroups()` — three prefixes for the same concept.
- `GlitchPlaceholder(songId = album.id)` / `songId = playlist.id` — the parameter is named `songId` but is used for albums (`AlbumListScreen.kt:131`, `SearchScreen.kt:358`) and playlists (`PlaylistRow.kt:87`). It's really a `seed`.
- `Playlist.albumArtUri` maps from `PlaylistEntity.coverArtUri` — two names for one field.
- `SongsViewModel.UiState` is top-level; every other `UiState` is nested in its ViewModel.
- `LibraryViewModel.factory(context)` (companion function) vs `SongsViewModel.Factory(...)` (nested class) vs `SettingsViewModel.Factory(...)` — three factory conventions.
- `PlaylistDetailScreen` takes `onAddSongsClick`; `LibraryScreen` takes `onNavigateToSongs`; `AlbumListScreen` takes `onAlbumClick` — three naming schemes for navigation callbacks.

### 🟡 8.6 — Risky / stale TODOs
| TODO | Risk |
|---|---|
| `PlaylistListViewModel.kt:84` "no-op for now, secure delete deferred" | 🔴 ships a broken button (§7.1) |
| `GroupDetailViewModel.kt:45` same | 🔴 same |
| `AddSongsScreen.kt:71` "Frozen search bar — placeholder only" | 🟡 ships dead UI |
| `SongDao.kt:49` "v2.x: verification pass, sort variants, missing-song queries" | 🟢 the `verified` columns exist unused |
| `AlbumDao.kt:56`, `ArtistDao.kt:41` "v2.x: orphan cleanup" | 🟡 albums/artists with zero songs accumulate forever and appear in lists |
| `ListenHistoryDao.kt:7` "v2 Library cluster" | 🟢 dead table |
| `Theme.kt:22` "light theme — not in scope" | 🟢 fine, but see §9.5 |
| `HomeViewModel.kt:6` "v2.1: recently played, queue state" | 🟢 dead class |

The two `deletePlaylist` placeholders are the only ones that are actively harmful.

### 🟢 8.7 — Import hygiene
`LibraryScreen.kt:1-11` — three imports at the top, a blank line, then the rest, breaking the alphabetical block. `TypeScalePreview.kt:51` uses a fully-qualified `androidx.compose.ui.text.TextStyle` inline instead of importing it. `PlayerSheet.kt:94` imports `java.util.Locale` after the Kotlin imports.

### 🟢 8.8 — Unused parameters
`SongsScreen.kt:43` `topBar(sortOrder, ...)` — `sortOrder` never used by the caller. `GlitchPlaceholder.kt:32` `modifier` is accepted then immediately overridden by `.size(size)`, so a caller-supplied size is silently ignored.

### 🟢 8.9 — No tests of any kind
No `src/test`, no `src/androidTest`. Nothing verifies: the Room migration, `HashUtils` collision behaviour, `splitArtists`, the repository mappers, `formatDuration`, or any ViewModel state machine. For a project with this much data-layer logic that's the single largest quality gap after the 🔴 items.

### 🟢 8.10 — Release build is unshrunk and unobfuscated
`build.gradle.kts:22` — `isMinifyEnabled = false`, `proguard-rules.pro` is empty. Full class/method names and all dead code ship to production.

---

# 9. Design system compliance

I could not find a design specification in the repository (no `docs/`, no `DESIGN.md`, no `README`), so I cannot verify the "5 defined contexts" rule against its source. What follows is the complete inventory of accent usage plus the token violations that are objectively checkable against `ui/theme/`.

### 🟡 9.1 — Accent (`#CAFF00`) appears in 19 distinct contexts
| # | Context | Location |
|---|---|---|
| 1 | Wordmark "X" | `HomeScreen.kt:128`, `TopBars.kt:78` |
| 2 | Selected bottom-nav icon + label | `MainScaffold.kt:666, 668` |
| 3 | Currently-playing indicator bar | `SongRow.kt:79` |
| 4 | Play/pause icon tint while playing | `PlayerSheet.kt:250, 346` |
| 5 | Slider thumb + active track | `PlayerSheet.kt:295-296` |
| 6 | Every hairline divider (`outline` = `Hairline`, neon @ 8%) | `Theme.kt:19`, 9 `HorizontalDivider` sites |
| 7 | Player sheet top edge | `PlayerSheet.kt:210` |
| 8 | Ripple colour on **every** clickable row | 8 sites |
| 9 | Glitch placeholder strips | `GlitchPlaceholder.kt:52` |
| 10 | Notification artwork strips | `GlitchBitmapGenerator.kt:34` |
| 11 | Home animated gradient (as a raw hex, §9.2) | `AnimatedGradientBackground.kt:28, 30` |
| 12 | Selected sort-order row text | `SongsScreen.kt:110` |
| 13 | Selected filter chip background | `SearchScreen.kt:141` |
| 14 | Focused text-field border + cursor (5 fields) | `PlaylistRow.kt:108-109`, `SearchScreen.kt:112-113`, `PlaylistListScreen.kt:217-218`, `CreatePlaylistScreen.kt:92-93` |
| 15 | Outlined button border + icon + label | `PlaylistListScreen.kt:95-107`, `PlaylistDetailScreen.kt:60-72` |
| 16 | Filled button background | `PlaylistListScreen.kt:233`, `CreatePlaylistScreen.kt:157`, `AddSongsScreen.kt:112` |
| 17 | Checkbox checked colour | `AddSongsScreen.kt:141` |
| 18 | Selected group row text + check icon | `CreatePlaylistScreen.kt:127, 136` |
| 19 | Loading spinner | `SongsScreen.kt:134` |

If the rule is five contexts, this is roughly 4× over. The dividers alone (context 6) put neon on essentially every horizontal line in the app.

### 🟡 9.2 — The accent is restated as raw hex in two places
`AnimatedGradientBackground.kt:28-31`
```kotlin
private val GradientColors = listOf(
    Color(0xFFCAFF00).copy(alpha = 0.4f),
    Color(0xFF000000),
    Color(0xFFCAFF00).copy(alpha = 0.4f),
)
```
`GlitchBitmapGenerator.kt:16, 34` — `Color.parseColor("#1F1F1F")`, `Color.argb(153, 202, 255, 0)`.

Three literals duplicating `Accent`, `Background`, and `SurfaceVariant`. Changing the brand colour requires finding these by hand. Also `AnimatedGradientBackground.kt:74` uses `Color.Black` instead of the `Background` token.

### 🟡 9.3 — The shape token is defined and then bypassed 19 times
`Shape.kt` defines `small`/`medium`/`large` all as `RoundedCornerShape(2.dp)`, wired into `MaterialTheme(shapes = SynthXShapes)`. But `RoundedCornerShape(2.dp)` is written out literally 19 times across 11 files (`AddSongsScreen` ×3, `PlaylistListScreen` ×4, `SearchScreen` ×3, `PlayerSheet` ×2, and one each in `CreatePlaylistScreen`, `PlaylistDetailScreen`, `LibraryScreen`, `PlaylistRow`, `AlbumListScreen`, `GlitchPlaceholder`, `SongRow`). `MaterialTheme.shapes.small` is never referenced. The token exists purely decoratively.

### 🟡 9.4 — Two typography tokens are used but not defined
`SynthXTypography` (`Type.kt:19-90`) defines 10 of Material 3's 15 styles. Missing: `displaySmall`, `headlineLarge`, `headlineMedium`, `headlineSmall`, `labelMedium`.

Two of the missing ones are actually used, so they silently fall back to Material's **Roboto** defaults instead of Outfit:

| Usage | Location | Effect |
|---|---|---|
| `typography.headlineLarge` | `TopBars.kt:86`, `HomeScreen.kt:134` | The wordmark's base style. The `SpanStyle` overrides `fontFamily`, so the glyphs are still Bitcount — but the 32sp size, 40sp line height, and 0sp letter spacing all come from Roboto's defaults, not from a synthX token. |
| `typography.labelMedium` | `LibraryScreen.kt:196` ("See All"), `:246` (recent song title) | **Renders in Roboto Medium 12sp.** Visibly different typeface from every neighbouring label on the Library screen. |

### 🟡 9.5 — The platform theme is a *light* theme
`res/values/themes.xml`
```xml
<style name="Theme.SynthX" parent="android:Theme.Material.Light.NoActionBar" />
```
For an app that is dark-only by explicit design (`Theme.kt:22`). This is the window background used for the cold-start splash before Compose draws its first frame — so every launch flashes white before going black. It also gives the system light-mode defaults for any platform-drawn surface (dialogs, the permission prompt at `MainActivity.kt:77`).

### 🟡 9.6 — Spacing tokens bypassed with magic numbers
`Spacing` defines `xs/sm/md/lg/xl/xxl/ItemSpacing/HorizontalPadding`. Raw `.dp` values used instead:

| Value | Where |
|---|---|
| `70.dp` nav height, `71.dp` footprint | `MainScaffold.kt:83, 599, 608`, `PlayerSheet.kt:84` — and `70` vs `71` are inconsistent between the offset and the reservation |
| `64.dp` | `PlayerSheet.kt:81`, `AlbumListScreen.kt:111`, `SearchScreen.kt:338` |
| `56.dp` | `PlaylistRow.kt:67`, `PlaylistListScreen.kt:257`, `AddSongsScreen.kt:132` |
| `44.dp` | `PlayerSheet.kt:187, 225`, `AlbumListScreen.kt:126, 133`, `SearchScreen.kt:353, 361`, `AddSongsScreen.kt:153, 161` |
| `48.dp`, `72.dp`, `32.dp`, `28.dp`, `24.dp` | icon/button sizes across `MainScaffold`, `PlayerSheet`, `HomeScreen` |
| `100.dp` tile, `6.dp` spacer | `LibraryScreen.kt:224, 233, 241, 243` |
| `2.dp`, `4.dp` | `SettingsScreen.kt:116, 157`, `TopBars.kt:92`, `SongRow.kt:76` |
| `200.dp`, `1.dp` | `CreatePlaylistScreen.kt:109`, all 9 dividers |

There is also no size/dimension token set at all — `Spacing.xxl` (48dp) is repurposed as a **row height** (`ArtistListScreen.kt:103`, `SearchScreen.kt:303`, `SongsScreen.kt:100`, `CreatePlaylistScreen.kt:116`) and a **button height** (`CreatePlaylistScreen.kt:155`, `AddSongsScreen.kt:110`), which conflates spacing with sizing.

### 🟡 9.7 — Colour access is split between two conventions
Roughly half the UI uses `MaterialTheme.colorScheme.*` and half imports the raw `Color.kt` vals directly (`TextPrimary`, `TextSecondary`, `Accent`, `Hairline`, `Surface`, `SurfaceVariant`, `OnAccent`, `TextTertiary`). `SongRow.kt` uses **both in the same composable** — `MaterialTheme.colorScheme.onBackground` at `:117` and `TextTertiary` at `:134`. The direct imports bypass any future theming/preview override.

### 🟢 9.8 — Hardcoded `fontSize` bypassing typography
`MainScaffold.kt:663` — `Text(text = item.label, fontSize = 14.sp)` in the bottom nav, the only place in the app that sets a raw font size. `labelSmall`/`labelLarge` exist.

### 🟢 9.9 — Semantically wrong `contentDescription`
`TopBars.kt:95-97` — the Library top-bar action is described as "More options" but its `onClick` is `onSettingsClick`. Screen-reader users are told it opens a menu; it navigates to Settings.

`SongRow.kt:133` — the trailing `MoreVert` has `contentDescription = null` (correct for decoration) but visually promises a menu that doesn't exist (§8.1).

---

# 10. Security & data

### 🟡 10.1 — Old folder grants are never released
`SettingsScreen.kt:51-61`

When the user picks a new music folder, `takePersistableUriPermission` is called for the new tree but `releasePersistableUriPermission` is never called for the old one. Persisted grants accumulate in the system's grant table (which is capped at 128 per package, and is per-user global state). After enough folder changes, `takePersistableUriPermission` starts throwing (§7.3) — and the app is holding read access to directories the user believes it has forgotten.

### 🟡 10.2 — No verification that the persisted grant still holds
`MainActivity.kt:52-63`, `FolderScanner.kt:39-40`

Nothing calls `contentResolver.persistedUriPermissions` to check the saved URI is still valid. When the grant is gone (app data cleared, external volume unmounted, provider uninstalled), `DocumentFile.fromTreeUri` returns null or an unreadable file and the scan quietly reports zeros. Settings continues to display the folder path as if everything is fine.

### 🟡 10.3 — Media session is bindable by any app
See §3.6. `android:exported="true"` + a default-accept `MediaLibrarySession.Callback` means any installed application can bind, control playback, and issue library-browse requests. The `MediaLibrarySession` surface is unimplemented today, so the browse exposure is empty — but that changes the moment `onGetLibraryRoot` is implemented, and the playback control is live now.

### 🟡 10.4 — `allowBackup="true"` with no rules will restore a dead folder URI
`AndroidManifest.xml`

`android:allowBackup="true"` with no `android:dataExtractionRules` / `android:fullBackupContent`. So `synthx_prefs` — containing `music_folder_uri` — is backed up and restored onto a different device, where the SAF grant does not exist. The app then shows a configured folder that produces an empty library forever, with no error (§7.7). Either exclude the prefs from backup or validate the URI on restore.

### 🟡 10.5 — Album art written to world-readable app cache with predictable names
`FolderScanner.kt:203`

```kotlin
val artFile = File(context.cacheDir, "art_$songId.png")
val uri = Uri.fromFile(artFile)
```

Two notes:
1. `Uri.fromFile` produces a `file://` URI. It's only ever consumed in-process by Coil, so no `FileUriExposedException` today — but any future attempt to hand that URI to another app (share sheet, widget, `MediaMetadata.artworkUri` consumed by a remote controller — which §3.6 makes reachable) throws on API 24+. Use a `FileProvider` content URI.
2. `MediaMetadata.setArtworkUri(Uri.parse(song.albumArtUri))` at `ExoPlayerEngine.kt:90` hands exactly this `file://` URI to the media session, where **external controllers** (Android Auto, Assistant, Wear) will try to load it and fail — they have no read access to your `cacheDir`. The `setArtworkData` fallback path (used only when art is null) is the one that actually works cross-process.

### 🟢 10.6 — No sensitive data handled
No credentials, tokens, network calls, analytics, or PII. The only persisted user data is the folder URI and the local music database. `SharedPreferences` uses `MODE_PRIVATE` ✅. No `WRITE_EXTERNAL_STORAGE` / `READ_MEDIA_AUDIO` requested — SAF-only is the right call ✅.

### 🟢 10.7 — `versionCode` never incremented
`build.gradle.kts:14-15` — `versionCode = 1`, `versionName = "1.0"` on a branch called `v2` with a database at schema version 2. Not a security issue, but it means no shipped build can be distinguished from another.

---

# Recommended fix order

**Before any user puts a real library in the app:**

1. §2.1 — Song identity hash collides across albums *(data loss, unrecoverable)*
2. §2.4 / §2.5 — `setPlaylistSongs` is non-transactional and destroys ordering *(data loss)*
3. §7.1 — `deletePlaylist` no-ops in two screens *(broken shipped feature)*
4. §4.1 — Add Songs discards user selections *(data loss)*
5. §2.6 — Concurrent scans duplicate albums/artists *(data corruption)*

**Next:**

6. §1.1 — `MediaController` leak per configuration change
7. §7.2 — Six ViewModels with no error handling *(crashes)*
8. §6.1 / §6.2 / §6.3 — Move all list mapping, filtering, and sorting off the main thread
9. §2.2 / §2.3 — Fix the remaining N+1 paths and add the three missing indices
10. §3.1 — Playback state desync when the service hasn't connected

**Then the systemic items:** extract `strings.xml` (§8.3), add `collectAsStateWithLifecycle` (§6.5), fix the light platform theme (§9.5), define the missing typography tokens (§9.4), replace the 19 literal `RoundedCornerShape(2.dp)` with the shape token (§9.3), and stand up a test source set with a migration test and `HashUtils`/`splitArtists` unit tests (§8.9).

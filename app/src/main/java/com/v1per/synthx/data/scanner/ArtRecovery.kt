package com.v1per.synthx.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.db.queryInBatches
import com.v1per.synthx.data.artFilePresence
import com.v1per.synthx.data.compressedArtFileExists
import com.v1per.synthx.data.fullResArtFileExists
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val TAG = "SynthXArtRecovery"

/**
 * Same memory-bound reasoning as [ScanExtraction]'s EXTRACTION_PARALLELISM — an in-flight
 * [AlbumArtExtractor.extract] call has the same decode-cost profile, so this recovery pass is
 * capped the same way rather than reusing that private constant across files.
 */
private val ART_RECOVERY_PARALLELISM = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

/** Outcome of one [ArtRecovery.recover] pass. Public — surfaced through [com.v1per.synthx.data.repository.SongRepository]. */
data class ArtRecoveryResult(
    val checked: Int,
    val recovered: Int,
    val skipped: Int,
)

/**
 * Detects and repairs songs whose art file(s) have gone missing from `filesDir/art/` while
 * their row still points at them — the state a restored library is left in, since album art is
 * deliberately excluded from backups, and a normal rescan's identity resolution treats a
 * restored row as "existing, unchanged" (see `SongIngestor.retagIfChanged`), which never
 * re-extracts art for it.
 */
internal object ArtRecovery {

    /**
     * Pure/deterministic: which of [songs] are missing a variant of their art file right now.
     * The two variants are checked independently via [compressedExists]/[fullResExists] — a
     * song is flagged the moment EITHER expected variant is gone, not only when both are. A
     * variant only counts as "expected" when the corresponding row field
     * ([SongEntity.albumArtUri]/[SongEntity.albumArtFullUri]) is non-null; a null field means
     * that variant was never produced in the first place (a legitimate partial original
     * extraction outcome, see [com.v1per.synthx.data.scanner.AlbumArtExtractor]), not something
     * to recover. A song with `isMissing = true` (its audio file itself is gone) is never
     * included — there is no source to re-extract art from. Split out from the [Context]-taking
     * overload below so it's testable with plain fakes, no Android framework involved.
     */
    internal fun findMissingArt(
        songs: List<SongEntity>,
        compressedExists: (songId: String) -> Boolean,
        fullResExists: (songId: String) -> Boolean,
    ): List<SongEntity> = songs.filter { song ->
        !song.isMissing && (
            (song.albumArtUri != null && !compressedExists(song.id)) ||
                (song.albumArtFullUri != null && !fullResExists(song.id))
            )
    }

    /**
     * [findMissingArt] wired to the real on-disk checks.
     *
     * Resolves both variants from ONE listing of `filesDir/art/` via [artFilePresence] rather
     * than calling the per-song helpers, which would cost a full directory listing per song
     * (see [artFilePresence]'s kdoc). Every caller of this overload — the Issues scan worker,
     * the Issues screen's Fix action and the post-restore sweep, all of which reach it through
     * [recover] or `SongRepositoryImpl.findMissingArtSongIds` — gets that for free; there is
     * nothing per-call-site to change.
     */
    fun findMissingArt(context: Context, songs: List<SongEntity>): List<SongEntity> {
        val presence = artFilePresence(context)
        return findMissingArt(
            songs,
            compressedExists = presence::hasCompressed,
            fullResExists = presence::hasFullRes,
        )
    }

    /**
     * Re-extracts art for [songIds], or the whole library when null, with bounded parallelism
     * mirroring [extractScanData]'s concurrency shape (a semaphore-gated `async` per candidate,
     * awaited together). Candidates whose audio file can't be resolved on disk are skipped, not
     * treated as an error — there is nothing to extract art from.
     *
     * [extractArt] is the file-I/O + decode step (resolve the source document, read embedded
     * art via [AlbumArtExtractor.extract]) — defaulted to the real implementation below, but
     * swappable so a test can control what a recovery attempt "found" without a real embedded-art
     * audio fixture.
     * Returns null when nothing usable came back (source unresolvable, or extraction produced
     * neither variant) — both collapse to [RecoveryOutcome.FAILED] the same way the inline
     * pre-refactor code did.
     */
    suspend fun recover(
        context: Context,
        database: SynthXDatabase,
        songIds: List<String>? = null,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> },
        extractArt: suspend (Context, SongEntity) -> Pair<String?, String?>? = ::defaultExtractArt,
    ): ArtRecoveryResult {
        val songDao = database.songDao()
        val candidates = if (songIds != null) {
            songIds.queryInBatches(songDao::getSongsByIds)
        } else {
            songDao.getAllSongsOnce()
        }
        val missingArt = findMissingArt(context, candidates)
        if (missingArt.isEmpty()) {
            return ArtRecoveryResult(checked = candidates.size, recovered = 0, skipped = 0)
        }

        val total = missingArt.size
        val completed = AtomicInteger(0)
        val gate = Semaphore(ART_RECOVERY_PARALLELISM)

        val outcomes = coroutineScope {
            missingArt.map { song ->
                async(Dispatchers.IO) {
                    val outcome = gate.withPermit {
                        currentCoroutineContext().ensureActive()
                        recoverOneSong(context, database, song, extractArt)
                    }
                    onProgress(completed.incrementAndGet(), total)
                    outcome
                }
            }.awaitAll()
        }

        val recoveredCount = outcomes.count { it == RecoveryOutcome.FULL }
        val partialCount = outcomes.count { it == RecoveryOutcome.PARTIAL }
        Log.d(
            TAG,
            "Art recovery complete — checked=${candidates.size} missingArt=$total " +
                "recovered=$recoveredCount partial=$partialCount skipped=${total - recoveredCount - partialCount}",
        )
        return ArtRecoveryResult(
            checked = candidates.size,
            recovered = recoveredCount,
            skipped = total - recoveredCount,
        )
    }

    /**
     * Per-song outcome of one [recoverOneSong] attempt. [PARTIAL] exists so a song that still
     * ends up missing a variant after recovery is never silently folded into "recovered" — see
     * [recoverOneSong].
     */
    private enum class RecoveryOutcome { FULL, PARTIAL, FAILED }

    /**
     * Field-by-field merge of a fresh [AlbumArtExtractor.extract] result into [current]'s row —
     * either half coming back null (a fresh extraction failure, or simply a variant this song
     * never had) must never erase the OTHER field's still-good existing value, and must never
     * overwrite an already-good value with a fresh null either: each field only changes when
     * this attempt actually produced a new non-null URI for it. Split out from [recoverOneSong]
     * so the merge itself is testable with a plain fake, no Android framework involved — same
     * shape as [findMissingArt]'s split.
     */
    internal fun mergeArtRecoveryResult(
        current: SongEntity,
        compressedUri: String?,
        fullArtUri: String?,
    ): SongEntity = current.copy(
        albumArtUri = compressedUri ?: current.albumArtUri,
        albumArtFullUri = fullArtUri ?: current.albumArtFullUri,
    )

    /**
     * Re-extracts both art variants for [song], merges the result into its row via
     * [mergeArtRecoveryResult], and reports [RecoveryOutcome.PARTIAL] rather than
     * [RecoveryOutcome.FULL] when the merged row still fails the same [findMissingArt] check
     * that selected it for recovery in the first place — reusing that exact predicate (rather
     * than a second hand-written one) so detection and partial-outcome classification can never
     * drift apart. A still-broken variant is therefore never silently reported as a full
     * success; the caller's next [findMissingArt] pass (e.g. the Issues screen's post-Fix
     * re-check) will surface it again.
     */
    private suspend fun recoverOneSong(
        context: Context,
        database: SynthXDatabase,
        song: SongEntity,
        extractArt: suspend (Context, SongEntity) -> Pair<String?, String?>?,
    ): RecoveryOutcome {
        val songDao = database.songDao()
        return try {
            val (compressedUri, fullArtUri) = extractArt(context, song) ?: return RecoveryOutcome.FAILED

            // Extraction/decode happens above, outside the transaction. Re-read and merge
            // inside one short transaction so a concurrent deletion, retag, missing-status flip
            // or another fix landing in the gap between the original read (above, for
            // extraction) and this write can never be silently clobbered by this stale
            // whole-row upsert — and so a row deleted in that window is never resurrected by it
            // either.
            //
            // "Still needs art recovery" is a DISK check (findMissingArt), and
            // AlbumArtExtractor.extract already wrote the real art files to disk above, before
            // this transaction opened — so re-running that
            // same disk predicate in here would read "already fixed" on every successful attempt
            // and silently no-op the write, defeating recovery entirely. The re-check that's
            // actually meaningful here is whether the SOURCE this extraction ran against is still
            // what the row points at: if filePath changed concurrently (a graduation/rename, or a
            // rescan retagging a different physical file onto this id) between the pre-extraction
            // read and now, this attempt's art no longer corresponds to the row's current audio,
            // so it's dropped rather than written — the next findMissingArt pass will pick the row
            // up fresh against whatever it now points at.
            val updated = database.withTransaction {
                val current = songDao.getSongById(song.id) ?: return@withTransaction null
                if (current.filePath != song.filePath) return@withTransaction null
                val merged = mergeArtRecoveryResult(current, compressedUri, fullArtUri)
                songDao.upsertSong(merged)
                merged
            } ?: return RecoveryOutcome.FAILED

            val stillMissing = findMissingArt(
                listOf(updated),
                compressedExists = { compressedArtFileExists(context, song.id) },
                fullResExists = { fullResArtFileExists(context, song.id) },
            ).isNotEmpty()
            if (stillMissing) {
                Log.w(TAG, "Art recovery partial for song ${song.id}")
                RecoveryOutcome.PARTIAL
            } else {
                RecoveryOutcome.FULL
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Art recovery failed for song ${song.id}", e)
            RecoveryOutcome.FAILED
        }
    }

    /**
     * Real [extractArt] implementation: resolve [song]'s source document and pull embedded art
     * out of it via [AlbumArtExtractor.extract]. Split out from [recoverOneSong] purely so the
     * latter is testable against an injected fake — see [recover]'s kdoc.
     */
    private suspend fun defaultExtractArt(context: Context, song: SongEntity): Pair<String?, String?>? {
        val uri = runCatching { song.filePath.toUri() }.getOrNull() ?: return null
        val file = DocumentFile.fromSingleUri(context, uri)?.takeIf { it.exists() } ?: return null

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, file.uri)
            val (compressedUri, fullArtUri) = AlbumArtExtractor.extract(context, retriever, song.id)
            if (compressedUri == null && fullArtUri == null) null else compressedUri to fullArtUri
        } finally {
            retriever.release()
        }
    }
}

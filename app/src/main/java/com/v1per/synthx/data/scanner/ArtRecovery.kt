package com.v1per.synthx.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.v1per.synthx.data.db.SongDao
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.songHasArtFile
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
     * Pure/deterministic: which of [songs] are missing their art file right now, per
     * [artExists]. A song with `isMissing = true` (its audio file itself is gone) is never
     * included — there is no source to re-extract art from. Split out from the [Context]-taking
     * overload below so it's testable with a plain fake, no Android framework involved.
     */
    internal fun findMissingArt(
        songs: List<SongEntity>,
        artExists: (songId: String) -> Boolean,
    ): List<SongEntity> = songs.filter { !it.isMissing && !artExists(it.id) }

    /** [findMissingArt] wired to the real on-disk check. */
    fun findMissingArt(context: Context, songs: List<SongEntity>): List<SongEntity> =
        findMissingArt(songs) { songId -> songHasArtFile(context, songId) }

    /**
     * Re-extracts art for [songIds], or the whole library when null, with bounded parallelism
     * mirroring [extractScanData]'s concurrency shape (a semaphore-gated `async` per candidate,
     * awaited together). Candidates whose audio file can't be resolved on disk are skipped, not
     * treated as an error — there is nothing to extract art from.
     */
    suspend fun recover(
        context: Context,
        songDao: SongDao,
        songIds: List<String>? = null,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> },
    ): ArtRecoveryResult {
        val candidates = if (songIds != null) songDao.getSongsByIds(songIds) else songDao.getAllSongsOnce()
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
                    val recovered = gate.withPermit {
                        currentCoroutineContext().ensureActive()
                        recoverOneSong(context, songDao, song)
                    }
                    onProgress(completed.incrementAndGet(), total)
                    recovered
                }
            }.awaitAll()
        }

        val recoveredCount = outcomes.count { it }
        Log.d(
            TAG,
            "Art recovery complete — checked=${candidates.size} " +
                "missingArt=$total recovered=$recoveredCount skipped=${total - recoveredCount}",
        )
        return ArtRecoveryResult(
            checked = candidates.size,
            recovered = recoveredCount,
            skipped = total - recoveredCount,
        )
    }

    /** True only once new art has actually been written and the row updated. */
    private suspend fun recoverOneSong(context: Context, songDao: SongDao, song: SongEntity): Boolean {
        val uri = runCatching { Uri.parse(song.filePath) }.getOrNull() ?: return false
        val file = DocumentFile.fromSingleUri(context, uri)?.takeIf { it.exists() } ?: return false

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, file.uri)
            val (compressedUri, fullArtUri) = AlbumArtExtractor.extract(context, retriever, song.id)
            if (compressedUri == null && fullArtUri == null) return false

            val current = songDao.getSongById(song.id) ?: return false
            songDao.upsertSong(current.copy(albumArtUri = compressedUri, albumArtFullUri = fullArtUri))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Art recovery failed for song ${song.id}", e)
            false
        } finally {
            retriever.release()
        }
    }
}

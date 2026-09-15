package com.v1per.synthx.data.scanner

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.db.queryInBatches
import com.v1per.synthx.ui.components.ExtractedSwatchHsl
import com.v1per.synthx.ui.components.extractArtworkSwatches
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

private const val TAG = "SynthXPaletteRecovery"
private val PALETTE_RECOVERY_PARALLELISM = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

data class PaletteRecoveryResult(val checked: Int, val recovered: Int, val skipped: Int)

/** Backfills raw swatches from existing artwork, under the caller's restore admission. */
internal object PaletteRecovery {
    /** Same predicate as SongDao's palette count and ID queries. Null art is not fixable. */
    internal fun findMissingPalette(songs: List<SongEntity>): List<SongEntity> = songs.filter { song ->
        !song.isMissing && (song.albumArtUri != null || song.albumArtFullUri != null) &&
            (song.swatch1Hue == null || song.swatch1Saturation == null || song.swatch1Lightness == null ||
                song.swatch2Hue == null || song.swatch2Saturation == null || song.swatch2Lightness == null ||
                song.swatch3Hue == null || song.swatch3Saturation == null || song.swatch3Lightness == null)
    }

    suspend fun recover(
        context: Context,
        database: SynthXDatabase,
        songIds: List<String>,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> },
        extractSwatches: suspend (String) -> List<ExtractedSwatchHsl> = { uri ->
            extractArtworkSwatches(context, uri)
        },
    ): PaletteRecoveryResult {
        val songDao = database.songDao()
        // A migrated library can exceed SQLite's bind limit. Keep the existing DAO lookup,
        // bounded in the same way as repository queue hydration.
        val candidates = songIds.queryInBatches(songDao::getSongsByIds)
        val missingPalette = findMissingPalette(candidates)
        val total = missingPalette.size
        val completed = AtomicInteger(0)
        val gate = Semaphore(PALETTE_RECOVERY_PARALLELISM)
        val outcomes = coroutineScope {
            missingPalette.map { candidate ->
                async(Dispatchers.IO) {
                    val recovered = gate.withPermit {
                        currentCoroutineContext().ensureActive()
                        recoverOneSong(database, candidate.id, extractSwatches)
                    }
                    onProgress(completed.incrementAndGet(), total)
                    recovered
                }
            }.awaitAll()
        }
        val recovered = outcomes.count { it }
        Log.d(TAG, "Palette recovery complete — checked=${candidates.size} recovered=$recovered skipped=${total - recovered}")
        return PaletteRecoveryResult(candidates.size, recovered, total - recovered)
    }

    /** An unavailable/incomplete extraction preserves the entire current row. */
    internal fun mergePaletteRecoveryResult(
        current: SongEntity,
        swatches: List<ExtractedSwatchHsl>?,
    ): SongEntity {
        if (swatches?.size != 3) return current
        return current.copy(
            swatch1Hue = swatches[0].hue,
            swatch1Saturation = swatches[0].saturation,
            swatch1Lightness = swatches[0].lightness,
            swatch2Hue = swatches[1].hue,
            swatch2Saturation = swatches[1].saturation,
            swatch2Lightness = swatches[1].lightness,
            swatch3Hue = swatches[2].hue,
            swatch3Saturation = swatches[2].saturation,
            swatch3Lightness = swatches[2].lightness,
        )
    }

    private suspend fun recoverOneSong(
        database: SynthXDatabase,
        songId: String,
        extractSwatches: suspend (String) -> List<ExtractedSwatchHsl>,
    ): Boolean {
        val songDao = database.songDao()
        return try {
            val song = songDao.getSongById(songId) ?: return false
            if (findMissingPalette(listOf(song)).isEmpty()) return false
            val uri = song.albumArtFullUri ?: song.albumArtUri ?: return false
            val swatches = extractSwatches(uri)
            if (swatches.size != 3) return false

            // Decode outside the transaction. Re-read and merge inside one short transaction
            // so a concurrent deletion, retag, art repair or another fix cannot be overwritten.
            database.withTransaction {
                val current = songDao.getSongById(songId) ?: return@withTransaction false
                if (findMissingPalette(listOf(current)).isEmpty() ||
                    (current.albumArtFullUri ?: current.albumArtUri) != uri
                ) return@withTransaction false
                songDao.upsertSong(mergePaletteRecoveryResult(current, swatches))
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Palette recovery failed for song $songId", e)
            false
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OutOfMemoryError recovering palette for song $songId", e)
            false
        }
    }
}

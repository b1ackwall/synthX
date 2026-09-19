package com.v1per.synthx.data.playlistart

import android.content.Context
import com.v1per.synthx.data.db.PlaylistDao
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.RepositoryDbOperation
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gives every playlist that predates the v13->v14 schema (and anything the seed bootstrapper
 * inserted, which deliberately leaves both columns null) its one-time hue and structure seed.
 *
 * Runs on every launch and is a single cheap SELECT when there is nothing left to do, so it
 * needs no persisted "already ran" flag: the null columns *are* the work list. Each playlist is
 * written in its own statement rather than one big transaction, so a process death partway
 * through leaves the finished rows finished and the rest for the next launch.
 */
internal object PlaylistArtBackfill {

    // P8-T1/R6-C1: there is conceptually only ever one backfill "operation" — no per-key
    // granularity is needed, unlike FileOperationCoordinator's per-song locks. This alone
    // doesn't make an overlapping pass safe (see setPlaylistArt's own IS NULL guard for that);
    // it exists to stop the *wasted, quality-degrading* half of the problem — two passes each
    // independently snapshotting getExistingArtHues() with no visibility into each other's
    // in-flight picks — from ever actually happening in the first place.
    private val singleFlight = Mutex()

    /** Returns how many playlists were given art on this pass. */
    suspend fun run(context: Context): Int = RepositoryDbOperation.run {
        // Constructed inside the admission wrapper, never captured from outside it — see
        // CLAUDE.md's backup/restore admission model.
        backfill(SynthXDatabase.getInstance(context).playlistDao(), Random.Default)
    }

    /**
     * The pure-DAO body, split out so it can be exercised without a Context or an Android
     * runtime — which also means nothing here may touch [android.util.Log]. The one line worth
     * logging is written by MainActivity, which has the result anyway.
     *
     * Held for the whole pass, not per-row: [singleFlight] only needs to stop two passes from
     * running at once, not protect any individual row — that per-row safety already comes from
     * [PlaylistDao.setPlaylistArt]'s own guard. Each row is still its own separate write inside
     * this single lock hold, deliberately NOT wrapped in one shared transaction (see this
     * object's class doc) — a process death partway through still leaves finished rows finished.
     */
    internal suspend fun backfill(playlistDao: PlaylistDao, random: Random): Int = singleFlight.withLock {
        val missing = playlistDao.getPlaylistIdsMissingArt()
        if (missing.isEmpty()) return@withLock 0

        // Seeded with what the library already uses, then grown as this pass assigns hues, so a
        // whole batch spreads against itself rather than every row in it distancing against the
        // same pre-backfill set and collapsing onto the same answer.
        val assignedHues = playlistDao.getExistingArtHues().toMutableList()
        missing.forEach { id ->
            val hue = selectDistancedHue(assignedHues, random)
            playlistDao.setPlaylistArt(id, hue, random.nextLong())
            assignedHues += hue
        }
        missing.size
    }
}

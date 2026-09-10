package com.v1per.synthx.data.playlistart

import android.content.Context
import com.v1per.synthx.data.db.PlaylistDao
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.RepositoryDbOperation
import kotlin.random.Random

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
     */
    internal suspend fun backfill(playlistDao: PlaylistDao, random: Random): Int {
        val missing = playlistDao.getPlaylistIdsMissingArt()
        if (missing.isEmpty()) return 0

        // Seeded with what the library already uses, then grown as this pass assigns hues, so a
        // whole batch spreads against itself rather than every row in it distancing against the
        // same pre-backfill set and collapsing onto the same answer.
        val assignedHues = playlistDao.getExistingArtHues().toMutableList()
        missing.forEach { id ->
            val hue = selectDistancedHue(assignedHues, random)
            playlistDao.setPlaylistArt(id, hue, random.nextLong())
            assignedHues += hue
        }
        return missing.size
    }
}

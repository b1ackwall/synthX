package com.v1per.synthx.playback

import android.util.Log
import com.v1per.synthx.data.PlaybackCacheRevision
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.SongRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Re-verification after a suspended queue load: a queue-loading path (fresh play, saved restore,
 * running-timeline adoption) captures [PlaybackCacheRevision.current] before it suspends for
 * artwork/DB/controller readiness, then calls this immediately after installing its snapshot.
 * A bump during the suspension means a remap or deletion (from ANY producer, not just the one
 * this instance happened to be subscribed to at the time) may have landed against a cache this
 * operation had not installed yet and so correctly, but now stalely, ignored — see
 * [PlaybackCacheRevision]'s kdoc for why a global revision bump alone is enough to trigger this,
 * independent of which song(s) actually changed.
 *
 * A no-op when the revision did not move — the overwhelmingly common case — so this costs nothing
 * on the vast majority of queue loads.
 */
internal suspend fun ExoPlayerEngine.reconcileIfRevisionChanged(revisionBefore: Long) {
    if (PlaybackCacheRevision.current() == revisionBefore) return
    Log.d(
        ExoPlayerEngine.TAG,
        "Cache revision advanced during a suspended queue load — reconciling against the database",
    )
    reconcileCacheWithDatabase()
}

/**
 * Re-derives every cached [Song] this engine holds directly from the database, rather than
 * trusting whatever bus events did or didn't arrive. Used both by [reconcileIfRevisionChanged]
 * and by the re-sync each time the Activity reaches STARTED (the general-purpose recovery mechanism
 * for a missed emission, e.g. a worker interrupted after its DB commit but before it finished
 * emitting).
 *
 * Must run on the main thread — it touches `songsBySlotId`/`orderStore`/the controller, all of
 * which are main-thread-confined (see [ExoPlayerEngine.songsBySlotId]'s kdoc).
 */
internal suspend fun ExoPlayerEngine.reconcileCacheWithDatabase() {
    if (songsBySlotId.isEmpty()) return
    val idsToCheck = songsBySlotId.values.mapTo(LinkedHashSet()) { it.id }
    val fresh = try {
        SongRepositoryImpl.create(appContext).getSongsByIdsForReconciliation(idsToCheck.toList())
    } catch (e: Exception) {
        Log.w(ExoPlayerEngine.TAG, "Cache reconciliation query failed; leaving cache as-is", e)
        return
    }
    val freshById = fresh.associateBy { it.id }

    withContext(Dispatchers.Main.immediate) {
        // Ids with no matching row any more are exactly what a deletion notice would have
        // reported — route them through the same removal path so slots/cursor/window all react
        // identically regardless of whether the original event ever arrived.
        val deletedIds = idsToCheck.filter { it !in freshById }
        deletedIds.forEach { removeDeletedSongInternal(it) }

        // Remaining ids: refresh any whose cached Song actually differs (retag, path move via
        // remap producers this instance's revision comparison could have missed).
        var changed = false
        val ctrl = controller
        songsBySlotId.entries.toList().forEach { (slotId, cached) ->
            val current = freshById[cached.id] ?: return@forEach
            if (current == cached) return@forEach
            songsBySlotId[slotId] = current
            artworkLoadedSlotIds.remove(slotId)
            val index = ctrl?.let { indexOfMediaId(it, slotId) }
            if (index != null) {
                ctrl.replaceMediaItem(index, buildMediaItem(slotId, current, artworkData = null))
            }
            changed = true
        }
        if (changed) {
            refreshQueue()
            refreshUpcomingQueue()
            _currentSlotId.value?.let { publishCurrentSlot(it) }
            persistQueue()
            prefetchArtwork()
        }
    }
}

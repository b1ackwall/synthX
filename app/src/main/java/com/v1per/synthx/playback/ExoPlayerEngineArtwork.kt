package com.v1per.synthx.playback

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.QueueSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Fills in artwork for the current item and the one after it, replacing their
 * MediaItems in place. Only the metadata differs (same mediaId, same URI), so media3
 * updates the item without interrupting playback.
 *
 * Window-safe: it only ever addresses `currentMediaItemIndex` and `nextMediaItemIndex`, both of
 * which are positions in the loaded window by definition, and the window always keeps the
 * current item plus lookahead. `maintainWindow` deliberately does not call this; its call sites
 * do, right after it reconciles, so a newly loaded next item picks its artwork up.
 */
internal fun ExoPlayerEngine.prefetchArtwork() {
    val ctrl = controller ?: return
    val targets = buildList {
        ctrl.currentMediaItemIndex.let { if (it != C.INDEX_UNSET) add(it) }
        ctrl.nextMediaItemIndex.let { if (it != C.INDEX_UNSET) add(it) }
    }.filter { it < ctrl.mediaItemCount }

    val pending = targets
        .map { ctrl.getMediaItemAt(it).mediaId }
        .filter { it !in artworkLoadedSlotIds }
        .mapNotNull { slotId ->
            songsBySlotId[slotId]?.let { song -> QueueSlot(slotId, song) }
        }
    if (pending.isEmpty()) return

    // A transition invalidates the previous window, so the in-flight job is dropped
    // rather than left to write artwork for songs that have since moved out of range.
    artworkJob?.cancel()
    artworkJob = engineScope.launch {
        for (slot in pending) {
            val artwork = buildArtworkData(slot.song)
            val live = controller ?: return@launch
            val index = indexOfMediaId(live, slot.slotId) ?: continue
            live.replaceMediaItem(index, buildMediaItem(slot.slotId, slot.song, artwork))
            artworkLoadedSlotIds.add(slot.slotId)
        }
    }
}

/**
 * Load artwork bytes in-process and embed directly — cross-process consumers
 * (notification, lock screen, Android Auto) cannot read our app-private files, so an
 * artwork URI would silently resolve to nothing on their side.
 */
internal suspend fun ExoPlayerEngine.buildArtworkData(song: Song): ByteArray {
    val artworkBytes: ByteArray? = withContext(Dispatchers.IO) {
        song.albumArtUri?.let { uriString ->
            try {
                appContext.contentResolver
                    .openInputStream(uriString.toUri())
                    ?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
        }
    }

    return withContext(Dispatchers.Default) {
        artworkBytes ?: run {
            val bitmap = GlitchBitmapGenerator.generateGlitchBitmap(song.id)
            val stream = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        }
    }
}

/** Stateless — no [ExoPlayerEngine] state involved, just a [Song] -> [MediaItem] mapping. */
internal fun buildMediaItem(slotId: String, song: Song, artworkData: ByteArray?): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(song.title)
        .setArtist(song.artist)
        .apply {
            artworkData?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        }
        .build()

    return MediaItem.Builder()
        .setMediaId(slotId)
        .setUri(song.filePath.toUri())
        .setMediaMetadata(metadata)
        .build()
}

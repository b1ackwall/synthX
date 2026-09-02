package com.v1per.synthx.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.QueueSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

internal const val MEDIA_METADATA_SONG_ID = "com.v1per.synthx.SONG_ID"

/**
 * Fills in artwork for the current item and the one after it, replacing their
 * MediaItems in place. Only the metadata differs (same mediaId, same URI), so media3
 * updates the item without interrupting playback.
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
                    .openInputStream(Uri.parse(uriString))
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
        .setExtras(Bundle().apply { putString(MEDIA_METADATA_SONG_ID, song.id) })
        .build()

    return MediaItem.Builder()
        .setMediaId(slotId)
        .setUri(Uri.parse(song.filePath))
        .setMediaMetadata(metadata)
        .build()
}

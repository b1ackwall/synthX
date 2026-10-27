package com.v1per.synthx.playback

import android.annotation.SuppressLint
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.ui.theme.SurfaceVariant
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

/** Edge of the flat square embedded for a song with no readable art. */
private const val FALLBACK_ARTWORK_PX = 256

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
            val bitmap = createBitmap(FALLBACK_ARTWORK_PX, FALLBACK_ARTWORK_PX).apply {
                eraseColor(SurfaceVariant.toArgb())
            }
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

    // Uri.parse, not toUri(): the JVM unit-test Uri stub returns null, which String.toUri() rejects.
    @SuppressLint("UseKtx")
    val uri = Uri.parse(song.filePath)

    return MediaItem.Builder()
        .setMediaId(slotId)
        .setUri(uri)
        .setMediaMetadata(metadata)
        .build()
}

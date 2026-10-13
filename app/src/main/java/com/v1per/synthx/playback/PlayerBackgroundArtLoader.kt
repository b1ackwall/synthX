package com.v1per.synthx.playback

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.tracing.trace
import com.v1per.synthx.perf.PerfTraceSections
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "PlayerBackgroundArt"

/** Side of the square bitmap the player background draws from, in px. */
internal const val PlayerBackgroundArtSidePx = 128

/** One song's identity and art URIs, resolved by the UI before the decode starts. */
data class PlayerBackgroundArtRequest(
    val songId: String,
    val compressedUri: String?,
    val fullUri: String?,
) {
    companion object {
        fun from(song: Song): PlayerBackgroundArtRequest =
            PlayerBackgroundArtRequest(song.id, song.albumArtUri, song.albumArtFullUri)
    }
}

/** Decode order: the compressed copy first, then the full-resolution original. */
internal fun PlayerBackgroundArtRequest.decodeSources(): List<String> =
    listOfNotNull(compressedUri, fullUri).distinct()

/** Largest power-of-two sample size that keeps the decoded short side at or above [side]. */
internal fun backgroundArtSampleSize(width: Int, height: Int, side: Int): Int {
    var sample = 1
    while (minOf(width, height) / (sample * 2) >= side) sample *= 2
    return sample
}

/** A centered square region of a bitmap. */
internal data class SquareCrop(val x: Int, val y: Int, val side: Int)

/** The largest centered square inside a [width] x [height] bitmap. */
internal fun centerSquareCrop(width: Int, height: Int): SquareCrop {
    val side = minOf(width, height)
    return SquareCrop((width - side) / 2, (height - side) / 2, side)
}

/**
 * Decodes a song's art into a [PlayerBackgroundArtSidePx] square, software ARGB_8888 bitmap for
 * the player background, reading the FileProvider URIs through [contentResolver].
 */
internal class PlayerBackgroundArtLoader(private val contentResolver: ContentResolver) {

    /** Null when the song has no art or no source decodes. */
    suspend fun load(request: PlayerBackgroundArtRequest): Bitmap? = withContext(Dispatchers.IO) {
        for (source in request.decodeSources()) {
            ensureActive()
            trace(PerfTraceSections.ArtDecode) { decode(source, PlayerBackgroundArtSidePx) }
                ?.let { return@withContext it }
        }
        null
    }

    private fun decode(uriString: String, side: Int): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "Player background art has no readable bounds: $uriString")
                return null
            }
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = backgroundArtSampleSize(bounds.outWidth, bounds.outHeight, side)
            }
            val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            if (decoded == null) {
                Log.w(TAG, "Player background art failed to decode (${bounds.outWidth}x${bounds.outHeight}): $uriString")
                return null
            }
            val crop = centerSquareCrop(decoded.width, decoded.height)
            val square = Bitmap.createBitmap(decoded, crop.x, crop.y, crop.side, crop.side)
            Bitmap.createScaledBitmap(square, side, side, true)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read player background art from $uriString", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OutOfMemoryError decoding player background art from $uriString", e)
            null
        }
    }
}

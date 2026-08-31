package com.v1per.synthx.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "AlbumArtExtractor"

/** Longest edge cap for stored album art — well above any on-screen display size. */
private const val MAX_ART_DIMENSION_PX = 1000

/**
 * Extracts embedded cover art out of an audio file and persists it under `filesDir/art/`.
 *
 * Pure file-system work — no DB access, no transaction involvement, never throws (every
 * failure path degrades to a null URI so the song stays tracked without art).
 */
internal object AlbumArtExtractor {

    fun extract(
        context: Context,
        retriever: MediaMetadataRetriever,
        songId: String,
    ): Pair<String?, String?> {
        return try {
            val artBytes = retriever.embeddedPicture ?: return null to null

            val artDir = File(context.filesDir, "art").also { it.mkdirs() }

            // Full-resolution copy — the untouched original bytes, no decode/re-encode, kept
            // for the player sheet's full-width display. Written to a temp name first and
            // renamed into place only once the write is complete, so a crash mid-write can
            // never leave a partial file sitting at the final, referenced name.
            val fullExt = sniffImageExtension(artBytes)
            val fullArtFile = File(artDir, "art_full_${songId}.$fullExt")
            val fullArtTempFile = File(artDir, "art_full_${songId}.${UUID.randomUUID()}.tmp")
            FileOutputStream(fullArtTempFile).use { fos -> fos.write(artBytes) }
            if (!fullArtTempFile.renameTo(fullArtFile)) {
                fullArtTempFile.delete()
                return null to null
            }
            val fullArtUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                fullArtFile,
            ).toString()

            // Bounds-only pre-check — reads the image's dimensions without allocating a
            // bitmap, so a huge/malformed embedded image can't blow up memory before its
            // size is even known.
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                return null to fullArtUri
            }

            // Decode already downsampled to roughly the target size — the real (expensive)
            // decode below never allocates more than ~4x the target pixel budget, regardless
            // of how large the source image is.
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(
                    boundsOptions.outWidth,
                    boundsOptions.outHeight,
                    MAX_ART_DIMENSION_PX,
                    MAX_ART_DIMENSION_PX,
                )
            }
            val decoded = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, decodeOptions)
                ?: return null to fullArtUri

            // inSampleSize only guarantees powers of two, so the sampled decode can still land
            // somewhat above the cap — this final downscale is a precise last-mile adjustment,
            // now operating on an already-small bitmap rather than the full source resolution.
            val longestSide = maxOf(decoded.width, decoded.height)
            val bitmap = if (longestSide > MAX_ART_DIMENSION_PX) {
                val scale = MAX_ART_DIMENSION_PX.toFloat() / longestSide
                val scaled = Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                decoded.recycle()
                scaled
            } else {
                decoded
            }

            // Store in filesDir/art/ — permanent, survives cache clears. JPEG at quality 90 —
            // lossless PNG at 100 was often larger than the embedded source for photographic
            // covers; 90 is visually indistinguishable at the sizes this art is ever displayed
            // at (up to full player-sheet width) while still compressing meaningfully smaller.
            // Same temp-then-rename pattern as the full-resolution copy above.
            val artFile = File(artDir, "art_$songId.jpg")
            val artTempFile = File(artDir, "art_${songId}.${UUID.randomUUID()}.tmp")

            FileOutputStream(artTempFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            bitmap.recycle()
            if (!artTempFile.renameTo(artFile)) {
                artTempFile.delete()
                return null to fullArtUri
            }

            // Return content:// URI via FileProvider — safe across process boundaries
            val compressedUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                artFile,
            ).toString()
            compressedUri to fullArtUri
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null to null
        } catch (e: OutOfMemoryError) {
            // A single oversized/malformed embedded image must never take down the whole
            // scan — degrade to "no art for this song" instead of letting an Error escape
            // and roll back the entire scan transaction.
            Log.w(TAG, "OutOfMemoryError extracting album art for song $songId — skipping art", e)
            null to null
        }
    }
}

/**
 * Standard Android downsampling calculation: the largest power-of-two inSampleSize such that
 * the decoded bitmap's dimensions are still >= the requested target — i.e. the smallest
 * power-of-two-subsampled decode that doesn't undershoot the target size.
 */
private fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, reqWidth: Int, reqHeight: Int): Int {
    var inSampleSize = 1
    if (rawHeight > reqHeight || rawWidth > reqWidth) {
        var halfHeight = rawHeight / 2
        var halfWidth = rawWidth / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

/** Sniffs leading magic bytes to pick a file extension — never throws, defaults to "jpg". */
private fun sniffImageExtension(bytes: ByteArray): String {
    return when {
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "png"
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "webp"
        else -> "jpg"
    }
}

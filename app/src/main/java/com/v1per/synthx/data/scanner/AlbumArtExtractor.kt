package com.v1per.synthx.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

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
            // for the player sheet's full-width display.
            val fullExt = sniffImageExtension(artBytes)
            val fullArtFile = File(artDir, "art_full_${songId}.$fullExt")
            FileOutputStream(fullArtFile).use { fos -> fos.write(artBytes) }
            val fullArtUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                fullArtFile,
            ).toString()

            val decoded = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size) ?: return null to fullArtUri

            // Downscale only — never upscale art that's already smaller than the cap.
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
            val artFile = File(artDir, "art_$songId.jpg")

            FileOutputStream(artFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            bitmap.recycle()

            // Return content:// URI via FileProvider — safe across process boundaries
            val compressedUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                artFile,
            ).toString()
            compressedUri to fullArtUri
        } catch (e: Exception) {
            null to null
        }
    }
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

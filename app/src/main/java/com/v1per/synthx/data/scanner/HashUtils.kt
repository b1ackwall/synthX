package com.v1per.synthx.data.scanner

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest

object HashUtils {

    private const val SAMPLE_SIZE_BYTES = 65536

    /**
     * Song identity = SHA-256 of the first 64KB of the file (whole file if smaller),
     * as a lowercase hex string. Returns null if the stream cannot be opened.
     */
    fun computeSongId(file: DocumentFile, context: Context): String? {
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                val buffer = ByteArray(SAMPLE_SIZE_BYTES)
                var totalRead = 0
                while (totalRead < SAMPLE_SIZE_BYTES) {
                    val read = stream.read(buffer, totalRead, SAMPLE_SIZE_BYTES - totalRead)
                    if (read < 0) break
                    totalRead += read
                }
                val digest = MessageDigest.getInstance("SHA-256").digest(buffer.copyOf(totalRead))
                digest.joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            null
        }
    }
}

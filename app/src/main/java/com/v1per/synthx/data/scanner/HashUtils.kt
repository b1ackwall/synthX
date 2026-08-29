package com.v1per.synthx.data.scanner

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.security.MessageDigest

object HashUtils {

    private const val SAMPLE_SIZE = 65536 // 64KB

    /**
     * Song identity = SHA-256 of the first 64KB + the last 64KB + the file size,
     * as a lowercase hex string. Sampling both ends plus the size keeps identity
     * stable across metadata rewrites while separating files that happen to share
     * a header (same encoder settings, same leading silence, padded ID3 tags).
     *
     * The tail is only mixed in when the file is larger than two samples, so it
     * never overlaps the head. Returns null for empty files, if the stream cannot
     * be opened, or if the tail can't be read even after one retry — a hash
     * computed from head+size alone is weaker and more collision-prone than the
     * full identity, so it is never returned silently; a file whose tail genuinely
     * can't be read is treated the same as one that can't be read at all.
     */
    fun computeSongId(file: DocumentFile, context: Context): String? {
        val fileSize = file.length()
        if (fileSize == 0L) return null

        return try {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                val hasher = MessageDigest.getInstance("SHA-256")

                // First 64KB (whole file if smaller)
                val headSize = minOf(SAMPLE_SIZE.toLong(), fileSize).toInt()
                val firstChunk = ByteArray(headSize)
                val firstRead = stream.readFully(firstChunk)
                if (firstRead > 0) hasher.update(firstChunk, 0, firstRead)

                // Last 64KB — only if the file is large enough to have a distinct tail.
                // Retried once in full (fresh stream, fresh skip, fresh read) before
                // giving up: a transient provider hiccup on the first attempt shouldn't
                // permanently degrade this file's identity every time it's scanned.
                if (fileSize > SAMPLE_SIZE * 2L) {
                    val tail = readTail(file, context, fileSize)
                        ?: readTail(file, context, fileSize)
                        ?: return null
                    hasher.update(tail, 0, tail.size)
                }

                // File size as 8 bytes big-endian
                val sizeBytes = ByteArray(8)
                for (i in 7 downTo 0) {
                    sizeBytes[i] = (fileSize shr ((7 - i) * 8)).toByte()
                }
                hasher.update(sizeBytes)

                hasher.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * One attempt at reading the last 64KB of [file]: opens a fresh stream, skips to the
     * tail offset, and reads the sample. Returns null on any failure along the way (stream
     * won't open, skip falls short, or the final read comes back empty) so the caller can
     * retry the whole sequence rather than patch up a partial one.
     */
    private fun readTail(file: DocumentFile, context: Context, fileSize: Long): ByteArray? {
        context.contentResolver.openInputStream(file.uri)?.use { tailStream ->
            val skipAmount = fileSize - SAMPLE_SIZE
            var skipped = 0L
            while (skipped < skipAmount) {
                val s = tailStream.skip(skipAmount - skipped)
                if (s <= 0) break
                skipped += s
            }
            if (skipped != skipAmount) return null

            val lastChunk = ByteArray(SAMPLE_SIZE)
            val lastRead = tailStream.readFully(lastChunk)
            if (lastRead <= 0) return null
            return if (lastRead == SAMPLE_SIZE) lastChunk else lastChunk.copyOf(lastRead)
        }
        return null
    }

    /**
     * Fills [buffer] from the stream, looping past short reads, and returns the
     * number of bytes actually read. A single [InputStream.read] may return fewer
     * bytes than asked for, which would make the same file hash differently on
     * different scans.
     */
    private fun InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}

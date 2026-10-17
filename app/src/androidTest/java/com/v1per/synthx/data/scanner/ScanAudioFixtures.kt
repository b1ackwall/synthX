package com.v1per.synthx.data.scanner

import java.io.ByteArrayOutputStream

/**
 * Tiny real audio files written byte by byte, so tag reading goes through the platform's own
 * `MediaMetadataRetriever` with no binary checked into git.
 *
 * MP3: an ID3v2.3 tag followed by silent MPEG-1 Layer III frames.
 * FLAC: STREAMINFO, a VORBIS_COMMENT block, a PADDING block, then silent CONSTANT frames.
 */
internal object ScanAudioFixtures {

    /**
     * [frames] maps ID3 frame ids (TIT2, TPE1, TALB, TYER, TRCK) to ASCII text. [fillerBeforeFrames]
     * puts a PRIV frame of that many bytes ahead of the text frames, so the text frames sit past the
     * first 64 KB that song identity hashes. [audioFrames] silent frames of 417 bytes follow the tag.
     */
    fun mp3(
        frames: Map<String, String>,
        fillerBeforeFrames: Int = 0,
        audioFrames: Int = 40,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        if (fillerBeforeFrames > 0) {
            body.write(id3Frame("PRIV", byteArrayOf('x'.code.toByte(), 0) + ByteArray(fillerBeforeFrames)))
        }
        frames.forEach { (id, text) ->
            body.write(id3Frame(id, byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)))
        }
        body.write(ByteArray(64)) // padding inside the tag
        val tagBody = body.toByteArray()

        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray())
        out.write(byteArrayOf(3, 0, 0))
        out.write(syncSafe(tagBody.size))
        out.write(tagBody)
        repeat(audioFrames) { out.write(MP3_FRAME) }
        return out.toByteArray()
    }

    /**
     * [comments] are Vorbis comment pairs (TITLE, ARTIST, ALBUM, DATE, TRACKNUMBER). [padding] is the
     * PADDING block size, which a tag editor uses to grow or shrink comments in place.
     */
    fun flac(comments: Map<String, String>, padding: Int = 8192, audioFrames: Int = 4): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())

        out.write(blockHeader(last = false, type = 0, length = 34))
        out.write(streamInfo(totalSamples = audioFrames.toLong() * FLAC_BLOCK))

        val vorbis = ByteArrayOutputStream()
        val vendor = "synthx-test".toByteArray()
        vorbis.write(le32(vendor.size))
        vorbis.write(vendor)
        vorbis.write(le32(comments.size))
        comments.forEach { (key, value) ->
            val entry = "$key=$value".toByteArray(Charsets.UTF_8)
            vorbis.write(le32(entry.size))
            vorbis.write(entry)
        }
        val vorbisBytes = vorbis.toByteArray()
        out.write(blockHeader(last = false, type = 4, length = vorbisBytes.size))
        out.write(vorbisBytes)

        out.write(blockHeader(last = true, type = 1, length = padding))
        out.write(ByteArray(padding))

        repeat(audioFrames) { out.write(flacFrame(it)) }
        return out.toByteArray()
    }

    private fun id3Frame(id: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray())
        out.write(be32(content.size))
        out.write(byteArrayOf(0, 0))
        out.write(content)
        return out.toByteArray()
    }

    private fun syncSafe(size: Int) = byteArrayOf(
        ((size shr 21) and 0x7f).toByte(),
        ((size shr 14) and 0x7f).toByte(),
        ((size shr 7) and 0x7f).toByte(),
        (size and 0x7f).toByte(),
    )

    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun blockHeader(last: Boolean, type: Int, length: Int) = byteArrayOf(
        ((if (last) 0x80 else 0) or type).toByte(),
        (length shr 16).toByte(),
        (length shr 8).toByte(),
        length.toByte(),
    )

    /** 4096-sample blocks, 44.1 kHz, mono, 16 bits per sample, MD5 left zero. */
    private fun streamInfo(totalSamples: Long): ByteArray {
        val out = ByteArray(34)
        out[0] = (FLAC_BLOCK shr 8).toByte(); out[1] = FLAC_BLOCK.toByte()
        out[2] = (FLAC_BLOCK shr 8).toByte(); out[3] = FLAC_BLOCK.toByte()
        // bytes 4..9: min and max frame size, unknown (0)
        // 20 bits sample rate | 3 bits channels-1 | 5 bits bps-1 | 36 bits total samples
        val packed = (44100L shl 44) or (0L shl 41) or (15L shl 36) or (totalSamples and 0xFFFFFFFFFL)
        for (i in 0 until 8) out[10 + i] = (packed shr (56 - 8 * i)).toByte()
        return out
    }

    private fun flacFrame(index: Int): ByteArray {
        val header = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0xC9.toByte(), 0x08, index.toByte())
        val withCrc8 = header + crc8(header)
        // CONSTANT subframe, value 0
        val frame = withCrc8 + byteArrayOf(0x00, 0x00, 0x00)
        val crc16 = crc16(frame)
        return frame + byteArrayOf((crc16 shr 8).toByte(), crc16.toByte())
    }

    private fun crc8(data: ByteArray): Byte {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc.toByte()
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    private const val FLAC_BLOCK = 4096

    /** One silent MPEG-1 Layer III frame: 128 kbps, 44.1 kHz, stereo, 417 bytes. */
    private val MP3_FRAME = ByteArray(417).also {
        it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte(); it[2] = 0x90.toByte(); it[3] = 0x00
    }
}

package com.v1per.synthx.ui.testing

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** The colour of test art: far from the SurfaceVariant placeholder and from every theme colour. */
internal val ArtTestColor = Color(0xFF2050E0)

/** A second test art colour, for a test that must tell two images apart. */
internal val ArtTestOtherColor = Color(0xFFE08020)

/**
 * Flat-colour album art stored the way AlbumArtExtractor stores real art: a JPEG under
 * filesDir/art, addressed by a content:// string from the app's FileProvider. Every file gets a
 * fresh name, so real art is never overwritten; [deleteAll] removes what this instance wrote.
 */
internal class ArtFiles(private val context: Context) {
    private val written = mutableListOf<File>()

    /** The prefix the app's FileProvider gives every file under filesDir/art. */
    val uriPrefix: String = "content://${context.packageName}.fileprovider/album_art/"

    /** Writes a [width] x [height] JPEG of one [color] and returns its FileProvider URI. */
    fun jpeg(color: Color, width: Int = EDGE_PX, height: Int = width): String {
        val file = newFile()
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color.toArgb())
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            bitmap.recycle()
        }
        return uriFor(file)
    }

    /** A FileProvider URI of the same shape whose file does not exist. */
    fun missing(): String = uriFor(newFile())

    fun deleteAll() {
        written.forEach { it.delete() }
        written.clear()
    }

    private fun newFile(): File {
        val dir = File(context.filesDir, ART_DIR).also { it.mkdirs() }
        return File(dir, "art_test_${UUID.randomUUID()}.jpg").also { written += it }
    }

    private fun uriFor(file: File): String =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()

    private companion object {
        const val ART_DIR = "art"
        const val EDGE_PX = 64
        const val JPEG_QUALITY = 100
    }
}

/** True when every RGB channel is within a few steps of [expected]; JPEG moves a flat colour slightly. */
internal fun Color.isNear(expected: Color): Boolean {
    val actual = toArgb()
    val wanted = expected.toArgb()
    return RGB_SHIFTS.all { shift -> abs((actual shr shift and 0xFF) - (wanted shr shift and 0xFF)) <= JPEG_TOLERANCE }
}

/** True when at least three quarters of a [edgePx]-square's worth of pixels are near [color]. */
internal fun PixelMap.showsSquareOf(color: Color, edgePx: Int): Boolean {
    var near = 0
    for (x in 0 until width) {
        for (y in 0 until height) {
            if (this[x, y].isNear(color)) near++
        }
    }
    return near * SQUARE_DENOMINATOR >= edgePx * edgePx * SQUARE_NUMERATOR
}

private val RGB_SHIFTS = listOf(16, 8, 0)
private const val JPEG_TOLERANCE = 12
private const val SQUARE_NUMERATOR = 3
private const val SQUARE_DENOMINATOR = 4

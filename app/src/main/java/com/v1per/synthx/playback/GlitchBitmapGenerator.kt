package com.v1per.synthx.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

object GlitchBitmapGenerator {

    fun generateGlitchBitmap(songId: String, sizePx: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Background — SurfaceVariant #1F1F1F
        canvas.drawColor(Color.parseColor("#1F1F1F"))

        // Same XOR hash chain as GlitchPlaceholder composable
        val hash = songId.hashCode()
        val count = 4 + ((hash and Int.MAX_VALUE) % 3)

        for (i in 0 until count) {
            val h1 = hash xor (i * 0x9e3779b9.toInt())
            val h2 = h1 * 0x6c62272e.toInt()
            val h3 = h2 xor (h2 ushr 16)

            val yFrac = (h1 and Int.MAX_VALUE).toFloat() / Int.MAX_VALUE.toFloat()
            val heightFrac = 0.04f + ((h2 and Int.MAX_VALUE).toFloat() / Int.MAX_VALUE.toFloat()) * 0.22f
            val widthFrac = 0.3f + ((h3 and Int.MAX_VALUE).toFloat() / Int.MAX_VALUE.toFloat()) * 0.7f
            val colorIndex = (h1 and Int.MAX_VALUE) % 3

            // Accent #CAFF00 @ 60% alpha (153), TextTertiary #545B66 @ 40% (102), SurfaceVariant @ 80% (204)
            paint.color = when (colorIndex) {
                0 -> Color.argb(153, 202, 255, 0)
                1 -> Color.argb(102, 84, 91, 102)
                else -> Color.argb(204, 31, 31, 31)
            }

            val top = yFrac * sizePx
            canvas.drawRect(0f, top, widthFrac * sizePx, top + heightFrac * sizePx, paint)
        }

        return bitmap
    }
}

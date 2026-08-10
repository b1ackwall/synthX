package com.v1per.synthx.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.v1per.synthx.glitch.generateGlitchStrips

object GlitchBitmapGenerator {

    fun generateGlitchBitmap(songId: String, sizePx: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Background — SurfaceVariant #1F1F1F
        canvas.drawColor(Color.parseColor("#1F1F1F"))

        for (strip in generateGlitchStrips(songId)) {
            // Accent #CAFF00 @ 60% alpha (153), TextTertiary #545B66 @ 40% (102), SurfaceVariant @ 80% (204)
            paint.color = when (strip.colorIndex) {
                0 -> Color.argb(153, 202, 255, 0)
                1 -> Color.argb(102, 84, 91, 102)
                else -> Color.argb(204, 31, 31, 31)
            }

            val top = strip.yFrac * sizePx
            canvas.drawRect(0f, top, strip.widthFrac * sizePx, top + strip.heightFrac * sizePx, paint)
        }

        return bitmap
    }
}

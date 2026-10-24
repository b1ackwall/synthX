package com.v1per.synthx.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import com.v1per.synthx.glitch.generateGlitchStrips

object GlitchBitmapGenerator {

    fun generateGlitchBitmap(songId: String, sizePx: Int = 256): Bitmap {
        val bitmap = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Background: literal #0F2E12
        canvas.drawColor("#0F2E12".toColorInt())

        for (strip in generateGlitchStrips(songId)) {
            // Strip literals: #1AE62B @ 60% alpha (153), #D1F0D4 @ 40% (102), #0F2E12 @ 80% (204)
            paint.color = when (strip.colorIndex) {
                0 -> Color.argb(153, 26, 230, 43)
                1 -> Color.argb(102, 209, 240, 212)
                else -> Color.argb(204, 15, 46, 18)
            }

            val top = strip.yFrac * sizePx
            canvas.drawRect(0f, top, strip.widthFrac * sizePx, top + strip.heightFrac * sizePx, paint)
        }

        return bitmap
    }
}

package com.v1per.synthx.glitch

/**
 * One strip of the deterministic glitch pattern, normalized so any renderer (Compose
 * DrawScope, Android Canvas) can scale it to its own target size.
 */
data class GlitchStrip(
    val yFrac: Float,
    val heightFrac: Float,
    val widthFrac: Float,
    val colorIndex: Int,
)

/**
 * Deterministically derives a 4-6 strip glitch pattern from [songId] via an XOR/multiply
 * hash chain — same [songId] always yields the same strips, so the placeholder and the
 * MediaSession fallback art for a given song never desync.
 */
fun generateGlitchStrips(songId: String): List<GlitchStrip> {
    val hash = songId.hashCode()
    val count = 4 + ((hash and Int.MAX_VALUE) % 3)
    return (0 until count).map { i ->
        val h1 = hash xor (i * 0x9e3779b9.toInt())
        val h2 = h1 * 0x6c62272e.toInt()
        val h3 = h2 xor (h2 ushr 16)
        GlitchStrip(
            yFrac = (h1 and Int.MAX_VALUE).toFloat() / Int.MAX_VALUE.toFloat(),
            heightFrac = 0.04f + ((h2 and Int.MAX_VALUE).toFloat() / Int.MAX_VALUE.toFloat()) * 0.22f,
            widthFrac = 0.3f + ((h3 and Int.MAX_VALUE).toFloat() / Int.MAX_VALUE.toFloat()) * 0.7f,
            colorIndex = (h1 and Int.MAX_VALUE) % 3,
        )
    }
}

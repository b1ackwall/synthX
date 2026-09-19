package com.v1per.synthx.data.playlistart

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistArtColorTest {

    private val roles = mapOf("litStart" to LitStartRole, "litEnd" to LitEndRole, "diamond" to DiamondRole)

    @Test
    fun `OkLab conversion agrees with published reference values`() {
        // Ottosson's reference: sRGB white is L=1 with no chroma; sRGB red is (0.6280, 0.2249, 0.1258).
        val white = hslToOkLab(0f, 0f, 1f)
        assertEquals(1f, white[0], 0.002f)
        assertEquals(0f, white[1], 0.002f)
        assertEquals(0f, white[2], 0.002f)
        val red = hslToOkLab(0f, 1f, 0.5f)
        assertEquals(0.6280f, red[0], 0.002f)
        assertEquals(0.2249f, red[1], 0.002f)
        assertEquals(0.1258f, red[2], 0.002f)
        assertEquals(0f, hslToOkLab(0f, 0f, 0f)[0], 0.002f)
    }

    @Test
    fun `the HSL formula here matches the one Color hsl renders with`() {
        // Same colour by both routes, so lightness solved here is the lightness that is drawn.
        listOf(0f, 37f, 90f, 143f, 200f, 261f, 333f).forEach { hue ->
            listOf(0.15f, 0.5f, 0.85f).forEach { l ->
                val rendered = Color.hsl(hue, 0.6f, l).convert(ColorSpaces.Oklab)
                val here = hslToOkLab(hue, 0.6f, l)
                // Color.hsl packs 8 bits per channel and Oklab colours pack half-floats, so this
                // is only good to a few thousandths.
                assertEquals(here[0], rendered.red, 0.006f, "L at hue $hue lightness $l")
            }
        }
    }

    @Test
    fun `every hue reaches every role's target lightness`() {
        roles.forEach { (name, role) ->
            for (hue in 0 until 360) {
                val l = role.hslLightness(hue.toFloat())
                assertTrue(l > 0.02f && l < 0.98f, "$name at $hue needed HSL lightness $l - out of reach")
                assertEquals(
                    role.targetOkLabLightness,
                    hslToOkLab(hue.toFloat(), role.saturation, l)[0],
                    0.001f,
                    "$name at $hue",
                )
            }
        }
    }

    @Test
    fun `rendered role colours have near-constant perceived lightness across the palette`() {
        roles.forEach { (name, role) ->
            val rendered = PlaylistArtHues.map { hue ->
                Color.hsl(hue.toFloat(), role.saturation, role.hslLightness(hue.toFloat()))
                    .convert(ColorSpaces.Oklab).red
            }
            val spread = rendered.max() - rendered.min()
            // Before compensation these spanned 0.155 (litStart), 0.203 (litEnd), 0.205 (diamond).
            assertTrue(spread < 0.015f, "$name still drifts by $spread in perceived lightness")
        }
    }

    @Test
    fun `without compensation the same roles would drift, which is what the test above guards`() {
        val nominal = mapOf("litStart" to 0.2f, "litEnd" to 0.8f, "diamond" to 0.5f)
        roles.forEach { (name, role) ->
            val ls = PlaylistArtHues.map { hslToOkLab(it.toFloat(), role.saturation, nominal.getValue(name))[0] }
            assertTrue(ls.max() - ls.min() > 0.12f, "$name baseline drift only ${ls.max() - ls.min()}")
        }
    }

    @Test
    fun `perceptual distance treats out-of-range hues as the hue they wrap to`() {
        assertEquals(0f, perceptualHueDistance(40, 400))
        assertEquals(perceptualHueDistance(350, 20), perceptualHueDistance(-10, 20))
        assertTrue(perceptualHueDistance(0, 180) > 150f)
    }
}

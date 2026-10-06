package com.v1per.synthx.ui.components

import androidx.compose.ui.graphics.BlendMode
// DEAD CODE: compares the player's palette blobs with Home's; neither follows a palette any more. Delete in a cleanup pass. Ask  before deleting.
// import com.v1per.synthx.ui.player.playerAmbientBackgroundState
// import com.v1per.synthx.ui.screens.home.applyHomeAmbientColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SharedAmbientBackgroundTest {
    // DEAD CODE: compares the player's palette blobs with Home's; neither follows a palette any more. Delete in a cleanup pass. Ask  before deleting.
    // private val swatches = listOf(
    //     ExtractedSwatchHsl(20f, 0.4f, 0.3f),
    //     ExtractedSwatchHsl(140f, 0.5f, 0.4f),
    //     ExtractedSwatchHsl(260f, 0.6f, 0.5f),
    // )
    //
    // @Test fun `one shared derivation gives Home and player identical slot assignments`() {
    //     val arrangement = generateBlobColorArrangement("same-song")
    //     val colors = requireNotNull(applyBlobColorArrangement(arrangement, swatches))
    //     val player = requireNotNull(playerAmbientBackgroundState(colors)).blobs
    //     val homeTemplate = BlobBackgroundDebugState(
    //         blobs = DefaultInitialBlobs.mapIndexed { index, blob ->
    //             blob.copy(sizeFraction = blob.sizeFraction / (index + 1), pointCount = 8)
    //         },
    //     )
    //     val home = applyHomeAmbientColors(homeTemplate, colors).blobs
    //
    //     assertEquals(player.map { it.hue }, home.map { it.hue })
    //     assertEquals(player.map { it.saturation }, home.map { it.saturation })
    //     assertEquals(player.map { it.lightness }, home.map { it.lightness })
    //     assertEquals(arrangement, colors.arrangement)
    //     assertNotEquals(player.map { it.sizeFraction }, home.map { it.sizeFraction })
    // }

    @Test fun `production Home and player style is locked to one non-debug policy`() {
        assertEquals(150f, ProductionAmbientBackgroundStyle.BlurRadiusDp)
        assertEquals(0.3f, ProductionAmbientBackgroundStyle.ScrimSaturation)
        assertEquals(0.05f, ProductionAmbientBackgroundStyle.ScrimLightness)
        assertEquals(0.5f, ProductionAmbientBackgroundStyle.ScrimAlpha)
        assertEquals(BlendMode.SrcOver, ProductionAmbientBackgroundStyle.ScrimBlendMode)
        assertNotEquals(
            BlobBackgroundDebugState().blurRadiusDp,
            ProductionAmbientBackgroundStyle.BlurRadiusDp,
        )
    }
}

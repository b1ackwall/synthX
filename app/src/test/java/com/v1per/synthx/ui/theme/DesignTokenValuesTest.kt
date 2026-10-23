package com.v1per.synthx.ui.theme

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins each dp token to the value the screens were drawn with. */
class DesignTokenValuesTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun strokesKeepTheirWidths() {
        assertEquals(1f, Strokes.Hairline.value)
        assertEquals(2f, Strokes.ProgressRing.value)
    }

    @Test fun gapsKeepTheirHeights() {
        assertEquals(2f, Gaps.TextLine.value)
    }

    @Test fun iconSizesKeepTheirEdges() {
        assertEquals(24f, IconSizes.Standard.value)
    }

    @Test fun artSizesKeepTheirEdgesAndStayDistinct() {
        assertEquals(40f, ArtSizes.Playlist.value)
        assertEquals(44f, ArtSizes.Album.value)
        assertEquals(48f, ArtSizes.Song.value)
    }

    @Test fun ringSizesKeepTheirDiameters() {
        assertEquals(20f, RingSizes.Small.value)
        assertEquals(28f, RingSizes.Medium.value)
        assertEquals(32f, RingSizes.Large.value)
    }
}

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
        assertEquals(1f, Strokes.ButtonBorder.value)
    }

    @Test fun buttonSizesKeepTheirValues() {
        assertEquals(112f, ButtonSizes.Compact.value)
        assertEquals(168f, ButtonSizes.Standard.value)
        assertEquals(240f, ButtonSizes.Wide.value)
        assertEquals(64f, ButtonSizes.DialogMinWidth.value)
        assertEquals(48f, ButtonSizes.ScreenMinHeight.value)
        assertEquals(40f, ButtonSizes.DialogMinHeight.value)
        assertEquals(24f, ButtonSizes.HorizontalPadding.value)
        assertEquals(12f, ButtonSizes.ScreenGap.value)
        assertEquals(8f, ButtonSizes.DialogGap.value)
        assertEquals(8f, ButtonSizes.DialogTapAreaReserve.value)
        assertEquals(0f, ButtonSizes.StackedDialogLayoutGap.value)
        assertEquals(600f, ButtonSizes.PairSideBySideMinWidth.value)
        assertEquals(48f, ButtonSizes.PlayerToggle.value)
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

    @Test fun tileSizesKeepTheirValues() {
        assertEquals(168f, TileSizes.MaxWidth.value)
        assertEquals(12f, TileSizes.MinGap.value)
        assertEquals(24f, TileSizes.MaxGap.value)
        assertEquals(16f, TileSizes.SideMargin.value)
    }
}

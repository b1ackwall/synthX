package com.v1per.synthx.ui.player

import androidx.compose.ui.unit.IntSize
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerAltStageReducedSizeTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test fun divisibleSidesDivideExactly() {
        assertEquals(IntSize(270, 600), altStageReducedSize(IntSize(1080, 2400), 4))
    }

    @Test fun remaindersRoundUp() {
        assertEquals(IntSize(270, 569), altStageReducedSize(IntSize(1080, 2274), 4))
        assertEquals(IntSize(541, 1137), altStageReducedSize(IntSize(1081, 2274), 2))
    }

    @Test fun scaledBackUpTheReducedLayerCoversTheFullLayerWithLessThanOneReducedPixelSpare() {
        for (divisor in listOf(2, 4)) {
            for (side in listOf(1, 3, 1079, 1080, 1081, 2273, 2274, 3120)) {
                val reduced = altStageReducedSize(IntSize(side, side), divisor).width
                assertTrue(reduced * divisor >= side, "side $side divisor $divisor")
                assertTrue((reduced - 1) * divisor < side, "side $side divisor $divisor")
            }
        }
    }

    @Test fun divisorOneKeepsTheSize() {
        assertEquals(IntSize(1080, 2274), altStageReducedSize(IntSize(1080, 2274), 1))
    }

    @Test fun emptyStaysEmpty() {
        assertEquals(IntSize.Zero, altStageReducedSize(IntSize.Zero, 4))
    }

    @Test fun lockedDivisorIsFour() {
        assertEquals(4, PlayerAltStageStyle.FilterResolutionDivisor)
    }
}

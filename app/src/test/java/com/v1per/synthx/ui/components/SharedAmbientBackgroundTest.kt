package com.v1per.synthx.ui.components

import androidx.compose.ui.graphics.BlendMode
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SharedAmbientBackgroundTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

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

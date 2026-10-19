package com.v1per.synthx.ui.player

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueueArtPositioningTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test
    fun `fixed production weights produce the finalized expanded layout`() {
        val position = position()

        assertDpEquals(80.dp, position.artOffsetY)
        assertDpEquals(336.dp, position.finalArtSize)
        assertDpEquals(443.2.dp, position.titleRowY)
    }

    @Test
    fun `tightening the title transfers its gap below the row without moving art`() {
        val original = position()
        val controlsTop = 500.dp
        val reservedTitleHeight = 52.dp

        for ((tightness, expectedTitleY) in listOf(1f to 448.dp, 0.5f to 432.dp, 0f to 416.dp)) {
            val actual = position(titleTightness = tightness)
            assertEquals(original.copy(titleRowY = actual.titleRowY), actual)
            assertDpEquals(expectedTitleY, actual.titleRowY)

            val artToTitleGap = actual.titleRowY - actual.artOffsetY - actual.finalArtSize
            val titleToOverlayGap = controlsTop - actual.titleRowY - reservedTitleHeight
            assertTrue(artToTitleGap >= 0.dp)
            assertTrue(titleToOverlayGap >= 0.dp)
            assertDpEquals(32.dp, artToTitleGap + titleToOverlayGap)
        }
    }

    @Test
    fun `title tightness leaves art and queue anchors fixed throughout both transitions`() {
        for (height in listOf(700.dp, 800.dp, 1000.dp)) {
            for (progress in listOf(0f, 0.5f, 1f)) {
                for (queueProgress in listOf(0f, 0.5f, 1f)) {
                    val original = position(height = height, progress = progress, queueProgress = queueProgress)
                    val tightened = position(
                        height = height,
                        progress = progress,
                        queueProgress = queueProgress,
                        titleTightness = 0f,
                    )
                    assertEquals(original.copy(titleRowY = tightened.titleRowY), tightened)
                    if (progress == 0f) assertEquals(original, tightened)
                }
            }
        }
    }

    @Test
    fun `art balance moves both art and a tightened title while preserving the full gap title anchor`() {
        val upperArt = position(aboveFraction = 0.25f, titleTightness = 0.5f)
        val lowerArt = position(aboveFraction = 0.75f, titleTightness = 0.5f)

        assertDpEquals(40.dp, lowerArt.artOffsetY - upperArt.artOffsetY)
        assertDpEquals(20.dp, lowerArt.titleRowY - upperArt.titleRowY)
        assertDpEquals(
            position(aboveFraction = 0.25f, titleTightness = 1f).titleRowY,
            position(aboveFraction = 0.75f, titleTightness = 1f).titleRowY,
        )
    }

    @Test
    fun `queue resting geometry is independent of both tuning sliders`() {
        val original = position(queueProgress = 1f)
        for (aboveFraction in listOf(0f, 0.6f, 1f)) {
            for (tightness in listOf(0f, 0.5f, 1f)) {
                val actual = position(
                    aboveFraction = aboveFraction,
                    titleTightness = tightness,
                    queueProgress = 1f,
                )
                assertEquals(original.copy(titleRowY = actual.titleRowY), actual)
            }
        }
    }

    @Test
    fun `zero flexible space makes both sliders inert`() {
        val original = position(height = 700.dp)
        assertDpEquals(original.artOffsetY + original.finalArtSize, original.titleRowY)
        for (aboveFraction in listOf(0f, 0.6f, 1f)) {
            for (tightness in listOf(0f, 0.5f, 1f)) {
                assertEquals(
                    original,
                    position(height = 700.dp, aboveFraction = aboveFraction, titleTightness = tightness),
                )
            }
        }
    }

    private fun position(
        aboveFraction: Float = 0.6f,
        titleTightness: Float = 0.85f,
        height: Dp = 800.dp,
        progress: Float = 1f,
        queueProgress: Float = 0f,
    ): ArtPosition = computeArtPosition(
        progress = progress,
        queueProgressValue = queueProgress,
        maxWidth = 400.dp,
        maxHeight = height,
        sheetHeight = lerp(64.dp, height, progress),
        titleRowReservedHeight = 52.dp,
        expandedTopMargin = 32.dp,
        artWeightAbove = aboveFraction,
        artWeightBelow = 1f - aboveFraction,
        titleGapWeightAboveTitle = titleTightness,
        titleGapWeightBelowTitle = 1f - titleTightness,
        controlsOverlayHeight = 300.dp,
        density = Density(1f),
    )

    private fun assertDpEquals(expected: Dp, actual: Dp) {
        assertEquals(expected.value, actual.value, absoluteTolerance = 0.001f)
    }
}

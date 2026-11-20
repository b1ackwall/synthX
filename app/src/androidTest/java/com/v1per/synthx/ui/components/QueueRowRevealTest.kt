package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.graphics.get
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Up Next row's tap still plays it, and the swipe's reveal icon is drawn in the [Background]
 * token on the [Error] reveal, never in a raw white. The ripple colour is checked by eye.
 */
@RunWith(AndroidJUnit4::class)
class QueueRowRevealTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private var clicks = 0
    private val song = Song("a", "Title a", "Artist", "Album", 1000L, "audio/a")

    private fun setUp() {
        composeRule.setContent {
            Box(Modifier.width(360.dp)) {
                QueueRow(
                    slotId = "a",
                    song = song,
                    queue = persistentListOf(QueueSlot("a", song)),
                    onClick = { clicks++ },
                    onRemove = {},
                    dragHandleModifier = Modifier,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun row() = composeRule.onNode(hasTestTag(PerfTestTags.QueueRow))

    @Test fun aTapOnTheRowStillInvokesItsAction() {
        setUp()

        row().performClick()
        composeRule.waitForIdle()

        assertEquals(1, clicks)
    }

    @Test fun theRevealIconUsesTheBackgroundTokenNotWhite() {
        setUp()
        val width = row().fetchSemanticsNode().size.width.toFloat()

        // Held 40% of the way left: short of the remove threshold, so the strip stays open.
        row().performTouchInput {
            down(center)
            repeat(10) {
                advanceEventTime(16)
                moveBy(Offset(-width * 0.04f, 0f))
            }
        }
        composeRule.waitForIdle()
        val image = row().captureToImage().asAndroidBitmap()
        row().performTouchInput { up() }

        val strip = (image.width * 0.65f).toInt() until image.width
        val pixels = strip.flatMap { x -> (0 until image.height).map { y -> image[x, y] } }
        assertTrue("the reveal is showing", pixels.count { it == Error.toArgb() } > pixels.size / 2)
        assertTrue("no white in the reveal strip", pixels.none { it == Color.White.toArgb() })
        assertTrue("the icon is drawn in the Background token", pixels.any { it == Background.toArgb() })
    }
}

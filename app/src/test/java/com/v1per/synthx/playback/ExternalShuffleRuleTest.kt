package com.v1per.synthx.playback

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the engine does with a shuffle command from another controller: the in-app toggle, except
 * that an OFF is refused while the queue was reordered while shuffled (no dialog can ask there).
 */
class ExternalShuffleRuleTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun onWhileOffApplies() {
        assertEquals(
            ExternalShuffleAction.APPLY,
            externalShuffleAction(requested = true, current = false, reordered = false),
        )
        // The reorder flag belongs to a shuffled queue; it never blocks shuffle ON.
        assertEquals(
            ExternalShuffleAction.APPLY,
            externalShuffleAction(requested = true, current = false, reordered = true),
        )
    }

    @Test fun offWhileOnAppliesWithoutAReorder() {
        assertEquals(
            ExternalShuffleAction.APPLY,
            externalShuffleAction(requested = false, current = true, reordered = false),
        )
    }

    @Test fun offWhileOnIsRefusedAfterAReorder() {
        assertEquals(
            ExternalShuffleAction.REFUSE,
            externalShuffleAction(requested = false, current = true, reordered = true),
        )
    }

    @Test fun aRequestForTheCurrentStateIsANoOp() {
        for (reordered in listOf(false, true)) {
            assertEquals(ExternalShuffleAction.NONE, externalShuffleAction(requested = true, current = true, reordered))
            assertEquals(
                ExternalShuffleAction.NONE,
                externalShuffleAction(requested = false, current = false, reordered),
            )
        }
    }
}

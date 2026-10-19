package com.v1per.synthx.ui

import com.v1per.synthx.data.backup.RestoreCoordinator.Mode
import com.v1per.synthx.data.backup.RestoreCoordinator.Terminal
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class RestoreShellPolicyTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test
    fun `normal admission with no terminal renders the ordinary shell`() {
        assertEquals(
            RestoreShellSurface.Normal,
            RestoreShellPolicy.decide(Mode.NORMAL, Terminal.NORMAL),
        )
    }

    @Test
    fun `restore preparing renders maintenance even though terminal is still normal`() {
        // The exact state in which the shell must not render the live nav graph.
        assertEquals(
            RestoreShellSurface.Maintenance,
            RestoreShellPolicy.decide(Mode.RESTORE_PREPARING, Terminal.NORMAL),
        )
    }

    @Test
    fun `the gap inside markClosed never falls through to the normal shell`() {
        // markClosed() writes mode then terminal as two separate StateFlow updates, so a
        // recomposition can land on RESTORE_CLOSED while terminal is still NORMAL.
        assertEquals(
            RestoreShellSurface.Maintenance,
            RestoreShellPolicy.decide(Mode.RESTORE_CLOSED, Terminal.NORMAL),
        )
    }

    @Test
    fun `terminal outranks every mode once it is published`() {
        val published = listOf(
            Terminal.RESTART_REQUIRED,
            Terminal.ROLLED_BACK_RESTART_REQUIRED,
            Terminal.EMERGENCY,
        )
        for (terminal in published) {
            for (mode in Mode.entries) {
                assertEquals(
                    RestoreShellSurface.Terminal,
                    RestoreShellPolicy.decide(mode, terminal),
                    "mode=$mode terminal=$terminal must stay on the terminal surface",
                )
            }
        }
    }

    @Test
    fun `every mode other than NORMAL is a maintenance surface before any terminal exists`() {
        for (mode in Mode.entries - Mode.NORMAL) {
            assertEquals(
                RestoreShellSurface.Maintenance,
                RestoreShellPolicy.decide(mode, Terminal.NORMAL),
                "mode=$mode must not render the live nav graph",
            )
        }
    }

    @Test
    fun `normal is reachable only from the fully normal pair`() {
        val normalPairs = Mode.entries.flatMap { mode ->
            Terminal.entries.map { terminal -> mode to terminal }
        }.filter { (mode, terminal) ->
            RestoreShellPolicy.decide(mode, terminal) == RestoreShellSurface.Normal
        }
        assertEquals(listOf(Mode.NORMAL to Terminal.NORMAL), normalPairs)
    }
}

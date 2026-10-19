package com.v1per.synthx.data.backup

import com.v1per.synthx.data.backup.RestoreTransaction.Phase
import com.v1per.synthx.data.backup.RestoreTransaction.RollbackSource
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Rows of the startup recovery decision tables that RestorePrimitivesTest does not hold: an
 * invalid live after a rollback, a parked `old` next to a fresh live, a rollback with no recorded
 * source, and the stateless order (old before safety).
 */
class RestoreRecoveryPlannerRowsTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun rolledBackWithValidLiveCompletesTheRollback() {
        listOf(Phase.ROLLED_BACK, Phase.STATELESS_RECOVERED).forEach { phase ->
            assertEquals(
                RestoreRecoveryAction.COMPLETE_ROLLBACK_AND_NORMAL,
                RestoreRecoveryPlanner.decide(
                    phase,
                    facts(liveExists = true, liveValid = true, oldExists = true, oldValid = true),
                    RollbackSource.OLD,
                ),
                "$phase with valid live",
            )
        }
    }

    @Test fun rolledBackWithInvalidLiveFallsBackInsteadOfCompleting() {
        listOf(Phase.ROLLED_BACK, Phase.STATELESS_RECOVERED).forEach { phase ->
            val invalidLive = facts(liveExists = true, liveValid = false)
            assertEquals(
                RestoreRecoveryAction.PROMOTE_OLD,
                RestoreRecoveryPlanner.decide(
                    phase,
                    invalidLive.copy(oldExists = true, oldValid = true),
                    RollbackSource.OLD,
                ),
                "$phase, invalid live, valid old",
            )
            assertEquals(
                RestoreRecoveryAction.PROMOTE_SAFETY,
                RestoreRecoveryPlanner.decide(
                    phase,
                    invalidLive.copy(safetyValid = true),
                    RollbackSource.OLD,
                ),
                "$phase, invalid live, valid safety only",
            )
            assertEquals(
                RestoreRecoveryAction.EMERGENCY,
                RestoreRecoveryPlanner.decide(phase, invalidLive, RollbackSource.OLD),
                "$phase, nothing valid",
            )
        }
    }

    @Test fun preparedOrMoveIntentWithParkedOldNeverCleansUpAsNormal() {
        listOf(Phase.PREPARED, Phase.LIVE_MOVE_INTENT).forEach { phase ->
            val freshLiveAndOld = facts(liveExists = true, liveValid = true, oldExists = true)
            assertEquals(
                RestoreRecoveryAction.PROMOTE_OLD,
                RestoreRecoveryPlanner.decide(phase, freshLiveAndOld.copy(oldValid = true)),
                "$phase with a valid old present",
            )
            assertEquals(
                RestoreRecoveryAction.PROMOTE_SAFETY,
                RestoreRecoveryPlanner.decide(phase, freshLiveAndOld.copy(safetyValid = true)),
                "$phase with an invalid old present",
            )
        }
    }

    @Test fun rollbackIntentWithoutRecordedSourceUsesTheFallbackOrder() {
        listOf(Phase.ROLLBACK_INTENT, Phase.STATELESS_RECOVERY_INTENT).forEach { phase ->
            assertEquals(
                RestoreRecoveryAction.PROMOTE_OLD,
                RestoreRecoveryPlanner.decide(
                    phase,
                    facts(oldExists = true, oldValid = true, safetyValid = true),
                    null,
                ),
                "$phase, null source, old and safety valid",
            )
            assertEquals(
                RestoreRecoveryAction.PROMOTE_SAFETY,
                RestoreRecoveryPlanner.decide(phase, facts(safetyValid = true), null),
                "$phase, null source, safety valid",
            )
            assertEquals(
                RestoreRecoveryAction.EMERGENCY,
                RestoreRecoveryPlanner.decide(phase, facts(), null),
                "$phase, null source, nothing valid",
            )
        }
    }

    /**
     * On a device this row is invisible: the stateless caller re-decides old versus safety
     * itself and ignores the planner's answer (noted in the BUG LIST). Only this JVM row guards it.
     */
    @Test fun statelessRecoveryPrefersOldOverSafety() {
        assertEquals(
            StatelessRecoveryAction.PROMOTE_OLD,
            StatelessRecoveryPlanner.decide(1, liveValid = false, oldValid = true, safetyValid = true),
        )
        assertEquals(
            StatelessRecoveryAction.PROMOTE_SAFETY,
            StatelessRecoveryPlanner.decide(1, liveValid = false, oldValid = false, safetyValid = true),
        )
        assertEquals(
            StatelessRecoveryAction.KEEP_VALID_LIVE,
            StatelessRecoveryPlanner.decide(1, liveValid = true, oldValid = true, safetyValid = true),
        )
        assertEquals(
            StatelessRecoveryAction.EMERGENCY,
            StatelessRecoveryPlanner.decide(2, liveValid = true, oldValid = true, safetyValid = true),
        )
    }

    private fun facts(
        liveExists: Boolean = false,
        liveValid: Boolean = false,
        oldExists: Boolean = false,
        oldValid: Boolean = false,
        safetyValid: Boolean = false,
    ) = RestoreArtifactFacts(
        liveExists = liveExists,
        liveValid = liveValid,
        liveIsCandidate = false,
        oldExists = oldExists,
        oldValid = oldValid,
        safetyValid = safetyValid,
        candidateExists = false,
    )
}

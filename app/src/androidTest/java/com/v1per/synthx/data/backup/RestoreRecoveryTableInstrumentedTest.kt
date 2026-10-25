package com.v1per.synthx.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device mirrors of decision-table rows with real files: an invalid live after a recorded
 * rollback, and a parked `old` next to a freshly created live. Both must end with the original
 * data, not with the broken or empty live.
 */
@RunWith(AndroidJUnit4::class)
class RestoreRecoveryTableInstrumentedTest : RestoreScenarioTestBase() {

    @Test fun rolledBackWithInvalidLivePromotesOldInsteadOfEmergency() {
        val scenario = scenario(
            RestoreTransaction.Phase.ROLLED_BACK,
            old = "original",
            outcome = RestoreTransaction.Outcome.ROLLED_BACK,
            rollbackSource = RestoreTransaction.RollbackSource.OLD,
        )
        liveFile().writeText("broken live")

        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))

        assertEquals("original", readMarker(liveFile()))
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
        assertFalse(File(databaseDir, scenario.state.oldName).exists())
    }

    @Test fun rolledBackWithValidLiveKeepsItAndCompletes() {
        scenario(
            RestoreTransaction.Phase.ROLLED_BACK,
            live = "rolled-back-live",
            old = "original",
            outcome = RestoreTransaction.Outcome.ROLLED_BACK,
            rollbackSource = RestoreTransaction.RollbackSource.OLD,
        )

        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))

        assertEquals("rolled-back-live", readMarker(liveFile()))
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
    }

    @Test fun preparedWithParkedOldPromotesOldOverAFreshLive() {
        scenario(RestoreTransaction.Phase.PREPARED, live = "fresh-empty", old = "original")

        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))

        assertEquals("original", readMarker(liveFile()))
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
        assertEquals(RestoreTransaction.Outcome.ROLLED_BACK, readState().outcome)
    }

    @Test fun liveMoveIntentWithParkedOldPromotesOldOverAFreshLive() {
        scenario(RestoreTransaction.Phase.LIVE_MOVE_INTENT, live = "fresh-empty", old = "original")

        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))

        assertEquals("original", readMarker(liveFile()))
        assertEquals(RestoreTransaction.Outcome.ROLLED_BACK, readState().outcome)
    }

    /**
     * Stateless recovery with both copies valid picks `old` through the planner's PROMOTE_OLD
     * (see RestoreRecoveryPlannerRowsTest.statelessRecoveryPrefersOldOverSafety for the table itself).
     */
    @Test fun statelessWithBothCopiesValidPrefersOld() {
        val names = RestoreArtifactNames(newOp())
        createDatabaseFile(File(databaseDir, names.old), "old")
        createDatabaseFile(File(databaseDir, names.safety), "safety")

        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))

        assertEquals("old", readMarker(liveFile()))
    }

    /** The caller promotes the copy the planner names, even when both copies are valid. */
    @Test fun statelessRecoveryPromotesSafetyWhenThePlannerSaysSafety() {
        val names = RestoreArtifactNames(newOp())
        createDatabaseFile(File(databaseDir, names.old), "old")
        createDatabaseFile(File(databaseDir, names.safety), "safety")

        val result = RestoreTransaction.recoverIfNeeded(
            context,
            databaseDir,
            statelessPlanner = { _, _, _, _ -> StatelessRecoveryAction.PROMOTE_SAFETY },
        )

        assertEquals(RestoreRecovery.NORMAL, result)
        assertEquals("safety", readMarker(liveFile()))
        assertEquals(RestoreTransaction.RollbackSource.SAFETY, readState().rollbackSource)
    }

    /** The mirror of the test above: a forced PROMOTE_OLD promotes `old`. */
    @Test fun statelessRecoveryPromotesOldWhenThePlannerSaysOld() {
        val names = RestoreArtifactNames(newOp())
        createDatabaseFile(File(databaseDir, names.old), "old")
        createDatabaseFile(File(databaseDir, names.safety), "safety")

        val result = RestoreTransaction.recoverIfNeeded(
            context,
            databaseDir,
            statelessPlanner = { _, _, _, _ -> StatelessRecoveryAction.PROMOTE_OLD },
        )

        assertEquals(RestoreRecovery.NORMAL, result)
        assertEquals("old", readMarker(liveFile()))
        assertEquals(RestoreTransaction.RollbackSource.OLD, readState().rollbackSource)
    }
}

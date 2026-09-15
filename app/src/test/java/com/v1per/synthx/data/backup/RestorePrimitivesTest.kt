package com.v1per.synthx.data.backup

import com.v1per.synthx.data.db.SynthXDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestorePrimitivesTest {
    @Test fun operationFilenamesAreSameDirectorySafeAndRoundTrip() {
        val names = RestoreArtifactNames(OP)
        assertEquals("synthx.restore-$OP.candidate.db", names.candidate)
        assertEquals("synthx.restore-$OP.old.db", names.old)
        assertEquals("synthx.restore-$OP.safety.db", names.safety)
        assertEquals(OP, RestoreArtifactNames.parseOperationId(names.failed))
        assertNull(RestoreArtifactNames.parseOperationId("some-other.db"))
        assertFailsWith<IllegalArgumentException> { RestoreArtifactNames("../escape") }
    }

    @Test fun freeSpaceFormulaIsConservativeAndExact() {
        assertEquals(
            20L + 14L + RestoreStorage.RESERVE_BYTES,
            RestoreStorage.requiredBytes(liveSize = 10, candidateSize = 7),
        )
    }

    @Test fun freeSpaceFormulaRejectsNegativeAndOverflow() {
        assertFailsWith<IllegalArgumentException> { RestoreStorage.requiredBytes(-1, 1) }
        assertFailsWith<ArithmeticException> { RestoreStorage.requiredBytes(Long.MAX_VALUE, 1) }
    }

    @Test fun walFullCheckpointIsAccepted() {
        assertTrue(WalCheckpointResult(0, 23, 23).provesStandaloneMain())
        assertTrue(WalCheckpointResult(0, 0, 0).provesStandaloneMain())
    }

    @Test fun documentedNonWalSentinelIsAccepted() {
        assertTrue(WalCheckpointResult(0, -1, -1).provesStandaloneMain())
    }

    @Test fun busyCheckpointIsRejected() {
        assertFalse(WalCheckpointResult(1, 23, 23).provesStandaloneMain())
    }

    @Test fun partialOrMalformedFrameAccountingIsRejected() {
        assertFalse(WalCheckpointResult(0, 23, 22).provesStandaloneMain())
        assertFalse(WalCheckpointResult(0, -1, 0).provesStandaloneMain())
        assertFalse(WalCheckpointResult(0, 0, -1).provesStandaloneMain())
    }

    @Test fun everyRestoreStatePhaseSerializesAndRoundTrips() {
        RestoreTransaction.Phase.entries.forEach { phase ->
            val state = state(phase)
            assertEquals(state, RestoreTransaction.decodeStateOrNull(RestoreTransaction.encodeState(state)))
        }
    }

    @Test fun malformedAndTruncatedStateAreRejected() {
        assertNull(RestoreTransaction.decodeStateOrNull("{"))
        assertNull(RestoreTransaction.decodeStateOrNull("not json"))
    }

    @Test fun inconsistentStateFilenamesAreRejected() {
        val encoded = RestoreTransaction.encodeState(state(RestoreTransaction.Phase.PREPARED))
            .replace("synthx.restore-$OP.old.db", "foreign.db")
        assertNull(RestoreTransaction.decodeStateOrNull(encoded))
    }

    @Test fun trustedCurrentMetadataIsAccepted() {
        assertEquals(
            RestoreEligibility.TRUSTED_CURRENT,
            RestoreEligibility.assess(metadata(SynthXDatabase.VERSION), FILE),
        )
    }

    @Test fun trustedOlderSupportedMetadataIsAccepted() {
        assertEquals(
            RestoreEligibility.TRUSTED_OLDER_SUPPORTED,
            RestoreEligibility.assess(metadata(7), FILE),
        )
    }

    @Test fun newerMetadataIsRejected() {
        assertEquals(
            RestoreEligibility.NEWER_SCHEMA,
            RestoreEligibility.assess(metadata(SynthXDatabase.VERSION + 1), FILE),
        )
    }

    @Test fun legacyAndMalformedMetadataAreRejected() {
        assertEquals(RestoreEligibility.LEGACY_OR_MALFORMED, RestoreEligibility.assess(null, FILE))
        assertEquals(
            RestoreEligibility.LEGACY_OR_MALFORMED,
            RestoreEligibility.assess(metadata(8).copy(sha256 = "bad"), FILE),
        )
        assertEquals(
            RestoreEligibility.LEGACY_OR_MALFORMED,
            RestoreEligibility.assess(metadata(8), "different.db"),
        )
    }

    @Test fun preparedAndUncompletedMoveIntentLeaveValidLiveNormal() {
        listOf(
            RestoreTransaction.Phase.PREPARED,
            RestoreTransaction.Phase.LIVE_MOVE_INTENT,
        ).forEach { phase ->
            assertEquals(
                RestoreRecoveryAction.CLEAN_PRE_CLOSE_AND_NORMAL,
                decide(phase, facts(liveExists = true, liveValid = true)),
            )
        }
    }

    @Test fun liveMovedAlwaysPrefersKnownValidOld() {
        assertEquals(
            RestoreRecoveryAction.PROMOTE_OLD,
            decide(
                RestoreTransaction.Phase.LIVE_MOVED,
                facts(oldExists = true, oldValid = true, safetyValid = true),
            ),
        )
    }

    @Test fun installIntentPreInstallRecoversOld() {
        assertEquals(
            RestoreRecoveryAction.PROMOTE_OLD,
            decide(
                RestoreTransaction.Phase.INSTALL_INTENT,
                facts(oldExists = true, oldValid = true, candidateExists = true),
            ),
        )
    }

    @Test fun installIntentPostInstallRequiresExactCandidateIdentity() {
        assertEquals(
            RestoreRecoveryAction.ACCEPT_INSTALLED_AND_RESTART,
            decide(
                RestoreTransaction.Phase.INSTALL_INTENT,
                facts(liveExists = true, liveValid = true, liveIsCandidate = true, oldExists = true, oldValid = true),
            ),
        )
        assertEquals(
            RestoreRecoveryAction.PROMOTE_OLD,
            decide(
                RestoreTransaction.Phase.INSTALL_INTENT,
                facts(liveExists = true, liveValid = true, oldExists = true, oldValid = true),
            ),
        )
    }

    @Test fun candidateInstalledUsesExactIdentityOrRollsBack() {
        assertEquals(
            RestoreRecoveryAction.ACCEPT_INSTALLED_AND_RESTART,
            decide(
                RestoreTransaction.Phase.CANDIDATE_INSTALLED,
                facts(liveExists = true, liveValid = true, liveIsCandidate = true),
            ),
        )
        assertEquals(
            RestoreRecoveryAction.PROMOTE_SAFETY,
            decide(
                RestoreTransaction.Phase.CANDIDATE_INSTALLED,
                facts(liveExists = true, liveValid = false, safetyValid = true),
            ),
        )
    }

    @Test fun postValidatedCompletesOnlyExactInstalledCandidate() {
        assertEquals(
            RestoreRecoveryAction.COMPLETE_INSTALLED_AND_NORMAL,
            decide(
                RestoreTransaction.Phase.POST_VALIDATED,
                facts(liveExists = true, liveValid = true, liveIsCandidate = true),
            ),
        )
    }

    @Test fun rollbackIntentContinuesOldThenSafetyThenExistingRecoveredLive() {
        assertEquals(
            RestoreRecoveryAction.PROMOTE_OLD,
            decide(
                RestoreTransaction.Phase.ROLLBACK_INTENT,
                facts(oldExists = true, oldValid = true, safetyValid = true),
                RestoreTransaction.Outcome.ROLLED_BACK,
                RestoreTransaction.RollbackSource.OLD,
            ),
        )
        assertEquals(
            RestoreRecoveryAction.PROMOTE_SAFETY,
            decide(
                RestoreTransaction.Phase.ROLLBACK_INTENT,
                facts(safetyValid = true),
                RestoreTransaction.Outcome.ROLLED_BACK,
                RestoreTransaction.RollbackSource.SAFETY,
            ),
        )
        assertEquals(
            RestoreRecoveryAction.COMPLETE_ROLLBACK_AND_NORMAL,
            decide(
                RestoreTransaction.Phase.ROLLBACK_INTENT,
                facts(liveExists = true, liveValid = true, liveMatchesOld = true),
                RestoreTransaction.Outcome.ROLLED_BACK,
                RestoreTransaction.RollbackSource.OLD,
            ),
        )
    }

    @Test fun selectedSafetyAlreadyInLiveIgnoresPresentInvalidOld() {
        assertEquals(
            RestoreRecoveryAction.COMPLETE_ROLLBACK_AND_NORMAL,
            decide(
                RestoreTransaction.Phase.ROLLBACK_INTENT,
                facts(
                    liveExists = true,
                    liveValid = true,
                    oldExists = true,
                    oldValid = false,
                    safetyValid = false,
                    liveMatchesSafety = true,
                ),
                RestoreTransaction.Outcome.ROLLED_BACK,
                RestoreTransaction.RollbackSource.SAFETY,
            ),
        )
    }

    @Test fun statelessRecoveryRecognizesSelectedSourceAlreadyInLive() {
        assertEquals(
            RestoreRecoveryAction.COMPLETE_ROLLBACK_AND_NORMAL,
            decide(
                RestoreTransaction.Phase.STATELESS_RECOVERY_INTENT,
                facts(liveExists = true, liveValid = true, liveMatchesOld = true),
                RestoreTransaction.Outcome.ROLLED_BACK,
                RestoreTransaction.RollbackSource.OLD,
            ),
        )
    }

    @Test fun statelessPlannerKeepsValidLiveOnlyForUnambiguousOwnedDebris() {
        assertEquals(
            StatelessRecoveryAction.KEEP_VALID_LIVE,
            StatelessRecoveryPlanner.decide(1, liveValid = true, oldValid = false, safetyValid = false),
        )
        assertEquals(
            StatelessRecoveryAction.EMERGENCY,
            StatelessRecoveryPlanner.decide(2, liveValid = true, oldValid = true, safetyValid = true),
        )
    }

    @Test fun rolledBackOutcomeValidatesItsActualLiveIdentityBeforeComplete() {
        assertEquals(
            RestoreRecoveryAction.COMPLETE_ROLLBACK_AND_NORMAL,
            decide(
                RestoreTransaction.Phase.ROLLED_BACK,
                facts(liveExists = true, liveValid = true),
                RestoreTransaction.Outcome.ROLLED_BACK,
            ),
        )
    }

    @Test fun completeAcceptsValidCurrentLiveWithoutHistoricalCandidateIdentity() {
        assertEquals(
            RestoreRecoveryAction.ACCEPT_COMPLETE_HISTORY_AND_NORMAL,
            decide(
                RestoreTransaction.Phase.COMPLETE,
                facts(liveExists = true, liveValid = true, liveIsCandidate = false),
            ),
        )
        assertEquals(
            RestoreRecoveryAction.ACCEPT_COMPLETE_HISTORY_AND_NORMAL,
            decide(
                RestoreTransaction.Phase.COMPLETE,
                facts(liveExists = true, liveValid = true),
                RestoreTransaction.Outcome.ROLLED_BACK,
            ),
        )
    }

    @Test fun completeNeverReopensRollbackWhenCurrentLiveIsInvalid() {
        assertEquals(
            RestoreRecoveryAction.EMERGENCY,
            decide(
                RestoreTransaction.Phase.COMPLETE,
                facts(oldExists = true, oldValid = true, safetyValid = true),
            ),
        )
    }

    @Test fun preCompleteCandidatePhasesStillRequireExactIdentity() {
        listOf(
            RestoreTransaction.Phase.CANDIDATE_INSTALLED,
            RestoreTransaction.Phase.POST_VALIDATED,
        ).forEach { phase ->
            assertEquals(
                RestoreRecoveryAction.PROMOTE_OLD,
                decide(
                    phase,
                    facts(liveExists = true, liveValid = true, oldExists = true, oldValid = true),
                ),
            )
            assertEquals(
                RestoreRecoveryAction.EMERGENCY,
                decide(phase, facts(liveExists = true, liveValid = true)),
            )
        }
    }

    @Test fun oldPrecedenceAndSafetyFallbackAreDeterministic() {
        val both = facts(oldExists = true, oldValid = true, safetyValid = true)
        assertEquals(RestoreRecoveryAction.PROMOTE_OLD, decide(RestoreTransaction.Phase.LIVE_MOVED, both))
        assertEquals(
            RestoreRecoveryAction.PROMOTE_SAFETY,
            decide(RestoreTransaction.Phase.LIVE_MOVED, both.copy(oldValid = false)),
        )
    }

    @Test fun candidateFilenameAloneIsNeverIdentityProofAndNoCopyIsEmergency() {
        assertEquals(
            RestoreRecoveryAction.EMERGENCY,
            decide(
                RestoreTransaction.Phase.INSTALL_INTENT,
                facts(candidateExists = true, liveExists = true, liveValid = true),
            ),
        )
        assertEquals(
            RestoreRecoveryAction.EMERGENCY,
            decide(RestoreTransaction.Phase.LIVE_MOVED, facts()),
        )
    }

    @Test fun everyRequiredFaultPointHasANarrowInjectionHook() {
        val expected = setOf(
            RestoreFaultPoint.SOURCE_STREAM_READ,
            RestoreFaultPoint.SOURCE_SHA_VERIFY,
            RestoreFaultPoint.MIGRATION,
            RestoreFaultPoint.FREE_SPACE_PREFLIGHT,
            RestoreFaultPoint.SAFETY_SNAPSHOT,
            RestoreFaultPoint.WAL_CHECKPOINT,
            RestoreFaultPoint.STATE_PERSISTENCE,
            RestoreFaultPoint.LIVE_TO_OLD_RENAME,
            RestoreFaultPoint.CANDIDATE_TO_LIVE_RENAME,
            RestoreFaultPoint.INSTALLED_VALIDATION,
            RestoreFaultPoint.OLD_ROLLBACK_RENAME,
            RestoreFaultPoint.SAFETY_FALLBACK_PROMOTION,
            RestoreFaultPoint.LIVE_TO_OLD_DIRECTORY_SYNC,
            RestoreFaultPoint.CANDIDATE_TO_LIVE_DIRECTORY_SYNC,
            RestoreFaultPoint.PRE_CLOSE_FAILURE_CLEANUP_DIRECTORY_SYNC,
            RestoreFaultPoint.STATELESS_FALLBACK_DIRECTORY_SYNC,
            RestoreFaultPoint.STARTUP_ROLLBACK_DIRECTORY_SYNC,
            RestoreFaultPoint.CURRENT_ROLLBACK_DIRECTORY_SYNC,
            RestoreFaultPoint.PARK_LIVE_DIRECTORY_SYNC,
            RestoreFaultPoint.STATE_JOURNAL_DIRECTORY_SYNC,
            RestoreFaultPoint.PRE_CLOSE_CLEANUP_DIRECTORY_SYNC,
            RestoreFaultPoint.POST_COMPLETE_CLEANUP_DIRECTORY_SYNC,
            RestoreFaultPoint.CORRUPT_STATE_ARCHIVE_DIRECTORY_SYNC,
            RestoreFaultPoint.INCIDENT_DIRECTORY_SYNC,
            RestoreFaultPoint.INCIDENT_ID_OPEN,
            RestoreFaultPoint.INCIDENT_ID_WRITE,
            RestoreFaultPoint.INCIDENT_ID_FILE_SYNC,
            RestoreFaultPoint.INCIDENT_ID_ARCHIVE_RENAME,
            RestoreFaultPoint.INCIDENT_ID_PROMOTE_RENAME,
            RestoreFaultPoint.INCIDENT_REPORT_OPEN,
            RestoreFaultPoint.INCIDENT_REPORT_WRITE,
            RestoreFaultPoint.INCIDENT_REPORT_FILE_SYNC,
            RestoreFaultPoint.INCIDENT_REPORT_PROMOTE_RENAME,
            RestoreFaultPoint.INCIDENT_REPORT_DIRECTORY_SYNC,
            RestoreFaultPoint.RECOVERED_ARTIFACT_ARCHIVE_DIRECTORY_SYNC,
            RestoreFaultPoint.SAFETY_PROMOTION_STAGE_DIRECTORY_SYNC,
            RestoreFaultPoint.SAFETY_PROMOTION_CLEANUP_DIRECTORY_SYNC,
        )
        assertEquals(expected, RestoreFaultPoint.entries.toSet())
        var injected: RestoreFaultPoint? = null
        RestoreFaultInjector { injected = it }.hit(RestoreFaultPoint.MIGRATION)
        assertNotNull(injected)
    }

    private fun decide(
        phase: RestoreTransaction.Phase,
        facts: RestoreArtifactFacts,
        outcome: RestoreTransaction.Outcome = RestoreTransaction.Outcome.INSTALLED,
        rollbackSource: RestoreTransaction.RollbackSource? = null,
    ) = RestoreRecoveryPlanner.decide(phase, outcome, facts, rollbackSource)

    private fun facts(
        liveExists: Boolean = false,
        liveValid: Boolean = false,
        liveIsCandidate: Boolean = false,
        oldExists: Boolean = false,
        oldValid: Boolean = false,
        safetyValid: Boolean = false,
        candidateExists: Boolean = false,
        liveMatchesOld: Boolean = false,
        liveMatchesSafety: Boolean = false,
    ) = RestoreArtifactFacts(
        liveExists,
        liveValid,
        liveIsCandidate,
        oldExists,
        oldValid,
        safetyValid,
        candidateExists,
        liveMatchesOld,
        liveMatchesSafety,
    )

    private fun state(phase: RestoreTransaction.Phase): RestoreTransaction.State {
        val names = RestoreArtifactNames(OP)
        return RestoreTransaction.State(
            operationId = OP,
            sourceName = FILE,
            sourceUri = "content://test/$FILE",
            candidateName = names.candidate,
            oldName = names.old,
            safetyName = names.safety,
            failedName = names.failed,
            candidateSha256 = "a".repeat(64),
            candidateSize = 4096,
            candidateVersion = SynthXDatabase.VERSION,
            safetySha256 = "b".repeat(64),
            safetySize = 4096,
            phase = phase,
            createdAt = 1,
            oldSha256 = "c".repeat(64),
            oldSize = 4096,
            oldVersion = SynthXDatabase.VERSION,
            rollbackSource = if (
                phase == RestoreTransaction.Phase.ROLLBACK_INTENT ||
                phase == RestoreTransaction.Phase.ROLLED_BACK ||
                phase == RestoreTransaction.Phase.STATELESS_RECOVERY_INTENT ||
                phase == RestoreTransaction.Phase.STATELESS_RECOVERED
            ) RestoreTransaction.RollbackSource.OLD else null,
        )
    }

    private fun metadata(version: Int) = BackupMetadata(
        timestamp = 1,
        tier = BackupTier.Manual.name,
        dbVersion = version,
        file = FILE,
        sizeBytes = 4096,
        sha256 = "d".repeat(64),
    )

    private companion object {
        const val OP = "0123456789abcdef0123456789abcdef"
        const val FILE = "synthx_backup_2026-08-26_120000_000_0123abcd.db"
    }
}

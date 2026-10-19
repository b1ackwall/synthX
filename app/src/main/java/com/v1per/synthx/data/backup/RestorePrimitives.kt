package com.v1per.synthx.data.backup

import android.content.Context
import androidx.core.net.toUri
import java.io.InputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class RestoreArtifactNames(
    val operationId: String,
    val candidate: String = "synthx.restore-$operationId.candidate.db",
    val old: String = "synthx.restore-$operationId.old.db",
    val safety: String = "synthx.restore-$operationId.safety.db",
    val failed: String = "synthx.restore-$operationId.failed.db",
    val safetyPromotion: String = "synthx.restore-$operationId.safety-promotion.db",
) {
    init {
        require(OPERATION_ID.matches(operationId)) { "Invalid restore operation ID" }
    }

    companion object {
        private val OPERATION_ID = Regex("[0-9a-f]{32}")
        private val ARTIFACT = Regex(
            "^synthx\\.restore-([0-9a-f]{32})\\.(candidate|old|safety|failed(?:-[0-9]+)?|safety-promotion)\\.db(?:\\.corrupt)?$",
        )

        fun parseOperationId(fileName: String): String? = ARTIFACT.matchEntire(fileName)?.groupValues?.get(1)
    }
}

internal object RestoreStorage {
    const val RESERVE_BYTES: Long = 64L * 1024L * 1024L

    /** live + safety + old + candidate staging/migration + fixed reserve. */
    fun requiredBytes(liveSize: Long, candidateSize: Long): Long {
        require(liveSize >= 0 && candidateSize >= 0)
        return Math.addExact(
            Math.addExact(Math.multiplyExact(liveSize, 2L), Math.multiplyExact(candidateSize, 2L)),
            RESERVE_BYTES,
        )
    }
}

internal enum class RestoreEligibility {
    TRUSTED_CURRENT,
    TRUSTED_OLDER_SUPPORTED,
    LEGACY_OR_MALFORMED,
    NEWER_SCHEMA,
    UNSUPPORTED_SCHEMA,
    ;

    companion object {
        fun assess(metadata: BackupMetadata?, selectedFileName: String): RestoreEligibility {
            if (metadata == null) return LEGACY_OR_MALFORMED
            val valid = runCatching {
                metadata.requireCompleteForPublication()
                require(metadata.file == selectedFileName)
            }.isSuccess
            if (!valid) return LEGACY_OR_MALFORMED
            if (metadata.dbVersion > com.v1per.synthx.data.db.SynthXDatabase.VERSION) {
                return NEWER_SCHEMA
            }
            val contract = BackupSchemaContracts.forVersion(metadata.dbVersion)
                ?: return UNSUPPORTED_SCHEMA
            if (!contract.hasMigrationPathToCurrent) return UNSUPPORTED_SCHEMA
            return if (metadata.dbVersion == com.v1per.synthx.data.db.SynthXDatabase.VERSION) {
                TRUSTED_CURRENT
            } else {
                TRUSTED_OLDER_SUPPORTED
            }
        }
    }
}

internal data class WalCheckpointResult(val busy: Int, val logFrames: Int, val checkpointedFrames: Int) {
    /** SQLite reports (-1,-1) when the database is not in WAL mode. */
    fun provesStandaloneMain(): Boolean = busy == 0 && when {
        logFrames == -1 && checkpointedFrames == -1 -> true
        logFrames >= 0 && checkpointedFrames >= 0 -> logFrames == checkpointedFrames
        else -> false
    }
}

internal enum class RestoreFaultPoint {
    SOURCE_STREAM_READ,
    SOURCE_SHA_VERIFY,
    MIGRATION,
    FREE_SPACE_PREFLIGHT,
    SAFETY_SNAPSHOT,
    WAL_CHECKPOINT,
    STATE_PERSISTENCE,
    LIVE_TO_OLD_RENAME,
    CANDIDATE_TO_LIVE_RENAME,
    INSTALLED_VALIDATION,
    OLD_ROLLBACK_RENAME,
    SAFETY_FALLBACK_PROMOTION,
    LIVE_TO_OLD_DIRECTORY_SYNC,
    CANDIDATE_TO_LIVE_DIRECTORY_SYNC,
    PRE_CLOSE_FAILURE_CLEANUP_DIRECTORY_SYNC,
    STATELESS_FALLBACK_DIRECTORY_SYNC,
    STARTUP_ROLLBACK_DIRECTORY_SYNC,
    CURRENT_ROLLBACK_DIRECTORY_SYNC,
    PARK_LIVE_DIRECTORY_SYNC,
    STATE_JOURNAL_DIRECTORY_SYNC,
    PRE_CLOSE_CLEANUP_DIRECTORY_SYNC,
    POST_COMPLETE_CLEANUP_DIRECTORY_SYNC,
    CORRUPT_STATE_ARCHIVE_DIRECTORY_SYNC,
    INCIDENT_DIRECTORY_SYNC,
    INCIDENT_ID_OPEN,
    INCIDENT_ID_WRITE,
    INCIDENT_ID_FILE_SYNC,
    INCIDENT_ID_ARCHIVE_RENAME,
    INCIDENT_ID_PROMOTE_RENAME,
    INCIDENT_REPORT_OPEN,
    INCIDENT_REPORT_WRITE,
    INCIDENT_REPORT_FILE_SYNC,
    INCIDENT_REPORT_PROMOTE_RENAME,
    INCIDENT_REPORT_DIRECTORY_SYNC,
    RECOVERED_ARTIFACT_ARCHIVE_DIRECTORY_SYNC,
    SAFETY_PROMOTION_STAGE_DIRECTORY_SYNC,
    SAFETY_PROMOTION_CLEANUP_DIRECTORY_SYNC,
}

internal fun interface RestoreFaultInjector {
    fun hit(point: RestoreFaultPoint)

    companion object {
        val NONE = RestoreFaultInjector { }
    }
}

internal fun interface RestoreSourceOpener {
    fun open(context: Context, uri: String): InputStream?

    companion object {
        val CONTENT_RESOLVER = RestoreSourceOpener { context, uri ->
            context.contentResolver.openInputStream(uri.toUri())
        }
    }
}

/** Prevents two direct callers from interpreting or mutating one restore namespace at once. */
internal object RestoreExecutionCoordinator {
    private val mutex = Mutex()
    suspend fun <T> serialize(block: suspend () -> T): T = mutex.withLock { block() }
}

internal data class RestoreArtifactFacts(
    val liveExists: Boolean,
    val liveValid: Boolean,
    val liveIsCandidate: Boolean,
    val oldExists: Boolean,
    val oldValid: Boolean,
    val safetyValid: Boolean,
    val candidateExists: Boolean,
    val liveMatchesOld: Boolean = false,
    val liveMatchesSafety: Boolean = false,
)

internal enum class RestoreRecoveryAction {
    CLEAN_PRE_CLOSE_AND_NORMAL,
    ACCEPT_INSTALLED_AND_RESTART,
    COMPLETE_INSTALLED_AND_NORMAL,
    ACCEPT_COMPLETE_HISTORY_AND_NORMAL,
    PROMOTE_OLD,
    PROMOTE_SAFETY,
    COMPLETE_ROLLBACK_AND_NORMAL,
    EMERGENCY,
}

/** Pure decision table; filesystem mutations happen only after this result is chosen. */
internal object RestoreRecoveryPlanner {
    fun decide(
        phase: RestoreTransaction.Phase,
        facts: RestoreArtifactFacts,
        rollbackSource: RestoreTransaction.RollbackSource? = null,
    ): RestoreRecoveryAction = when (phase) {
        RestoreTransaction.Phase.PREPARED,
        RestoreTransaction.Phase.LIVE_MOVE_INTENT,
        -> {
            if (facts.liveValid && !facts.oldExists) {
                RestoreRecoveryAction.CLEAN_PRE_CLOSE_AND_NORMAL
            } else {
                fallback(facts)
            }
        }

        RestoreTransaction.Phase.LIVE_MOVED -> fallback(facts)

        RestoreTransaction.Phase.INSTALL_INTENT,
        RestoreTransaction.Phase.CANDIDATE_INSTALLED,
        -> {
            if (facts.liveIsCandidate) {
                RestoreRecoveryAction.ACCEPT_INSTALLED_AND_RESTART
            } else {
                fallback(facts)
            }
        }

        RestoreTransaction.Phase.POST_VALIDATED -> {
            if (facts.liveIsCandidate) {
                RestoreRecoveryAction.COMPLETE_INSTALLED_AND_NORMAL
            } else {
                fallback(facts)
            }
        }

        RestoreTransaction.Phase.ROLLBACK_INTENT,
        RestoreTransaction.Phase.STATELESS_RECOVERY_INTENT,
        -> rollback(rollbackSource, facts)

        RestoreTransaction.Phase.ROLLED_BACK,
        RestoreTransaction.Phase.STATELESS_RECOVERED,
        -> {
            if (facts.liveValid) {
                RestoreRecoveryAction.COMPLETE_ROLLBACK_AND_NORMAL
            } else {
                fallback(facts)
            }
        }

        // COMPLETE is durable proof that a prior cold start already confirmed the
        // transaction. The live database may evolve after that point, so historical
        // candidate/old/safety byte identities and cleanup artifacts are no longer
        // recovery authority. A bad current database still fails closed, but it must
        // not reopen rollback from an already completed restore.
        RestoreTransaction.Phase.COMPLETE -> {
            if (facts.liveValid) {
                RestoreRecoveryAction.ACCEPT_COMPLETE_HISTORY_AND_NORMAL
            } else {
                RestoreRecoveryAction.EMERGENCY
            }
        }
    }

    private fun fallback(facts: RestoreArtifactFacts): RestoreRecoveryAction = when {
        facts.oldValid -> RestoreRecoveryAction.PROMOTE_OLD
        facts.safetyValid -> RestoreRecoveryAction.PROMOTE_SAFETY
        else -> RestoreRecoveryAction.EMERGENCY
    }

    private fun rollback(
        source: RestoreTransaction.RollbackSource?,
        facts: RestoreArtifactFacts,
    ): RestoreRecoveryAction = when (source) {
        RestoreTransaction.RollbackSource.OLD -> when {
            facts.oldValid -> RestoreRecoveryAction.PROMOTE_OLD
            facts.liveMatchesOld -> RestoreRecoveryAction.COMPLETE_ROLLBACK_AND_NORMAL
            else -> RestoreRecoveryAction.EMERGENCY
        }

        RestoreTransaction.RollbackSource.SAFETY -> when {
            facts.safetyValid -> RestoreRecoveryAction.PROMOTE_SAFETY
            facts.liveMatchesSafety -> RestoreRecoveryAction.COMPLETE_ROLLBACK_AND_NORMAL
            else -> RestoreRecoveryAction.EMERGENCY
        }

        null -> fallback(facts)
    }
}

internal enum class StatelessRecoveryAction {
    KEEP_VALID_LIVE,
    PROMOTE_OLD,
    PROMOTE_SAFETY,
    EMERGENCY,
}

/** Decision table for recovery when no trustworthy state journal exists. */
internal object StatelessRecoveryPlanner {
    fun decide(
        operationCount: Int,
        liveValid: Boolean,
        oldValid: Boolean,
        safetyValid: Boolean,
    ): StatelessRecoveryAction {
        if (operationCount != 1) return StatelessRecoveryAction.EMERGENCY
        return when {
            liveValid -> StatelessRecoveryAction.KEEP_VALID_LIVE
            oldValid -> StatelessRecoveryAction.PROMOTE_OLD
            safetyValid -> StatelessRecoveryAction.PROMOTE_SAFETY
            else -> StatelessRecoveryAction.EMERGENCY
        }
    }
}

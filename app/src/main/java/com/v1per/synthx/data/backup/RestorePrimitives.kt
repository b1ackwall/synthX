package com.v1per.synthx.data.backup

import android.content.Context
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
            "^synthx\\.restore-([0-9a-f]{32})\\.(candidate|old|safety|failed(?:-[0-9]+)?|safety-promotion)\\.db(?:\\.corrupt)?$"
        )

        fun parseOperationId(fileName: String): String? =
            ARTIFACT.matchEntire(fileName)?.groupValues?.get(1)
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

internal data class WalCheckpointResult(
    val busy: Int,
    val logFrames: Int,
    val checkpointedFrames: Int,
) {
    /** SQLite reports (-1,-1) when the database is not in WAL mode. */
    fun provesStandaloneMain(): Boolean =
        busy == 0 && when {
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
            context.contentResolver.openInputStream(android.net.Uri.parse(uri))
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
)

internal enum class RestoreRecoveryAction {
    CLEAN_PRE_CLOSE_AND_NORMAL,
    ACCEPT_INSTALLED_AND_RESTART,
    COMPLETE_INSTALLED_AND_NORMAL,
    PROMOTE_OLD,
    PROMOTE_SAFETY,
    COMPLETE_ROLLBACK_AND_NORMAL,
    EMERGENCY,
}

/** Pure decision table; filesystem mutations happen only after this result is chosen. */
internal object RestoreRecoveryPlanner {
    fun decide(
        phase: RestoreTransaction.Phase,
        outcome: RestoreTransaction.Outcome,
        facts: RestoreArtifactFacts,
    ): RestoreRecoveryAction = when (phase) {
        RestoreTransaction.Phase.PREPARED,
        RestoreTransaction.Phase.LIVE_MOVE_INTENT -> {
            if (facts.liveValid && !facts.oldExists) {
                RestoreRecoveryAction.CLEAN_PRE_CLOSE_AND_NORMAL
            } else fallback(facts)
        }

        RestoreTransaction.Phase.LIVE_MOVED -> fallback(facts)

        RestoreTransaction.Phase.INSTALL_INTENT,
        RestoreTransaction.Phase.CANDIDATE_INSTALLED -> {
            if (facts.liveIsCandidate) {
                RestoreRecoveryAction.ACCEPT_INSTALLED_AND_RESTART
            } else fallback(facts)
        }

        RestoreTransaction.Phase.POST_VALIDATED -> {
            if (facts.liveIsCandidate) {
                RestoreRecoveryAction.COMPLETE_INSTALLED_AND_NORMAL
            } else fallback(facts)
        }

        RestoreTransaction.Phase.ROLLBACK_INTENT -> when {
            facts.oldValid -> RestoreRecoveryAction.PROMOTE_OLD
            facts.liveValid && !facts.oldExists -> RestoreRecoveryAction.COMPLETE_ROLLBACK_AND_NORMAL
            facts.safetyValid -> RestoreRecoveryAction.PROMOTE_SAFETY
            else -> RestoreRecoveryAction.EMERGENCY
        }

        RestoreTransaction.Phase.ROLLED_BACK -> {
            if (facts.liveValid) RestoreRecoveryAction.COMPLETE_ROLLBACK_AND_NORMAL
            else fallback(facts)
        }

        RestoreTransaction.Phase.COMPLETE -> when (outcome) {
            RestoreTransaction.Outcome.INSTALLED -> {
                if (facts.liveIsCandidate) RestoreRecoveryAction.COMPLETE_INSTALLED_AND_NORMAL
                else fallback(facts)
            }
            RestoreTransaction.Outcome.ROLLED_BACK -> {
                if (facts.liveValid) RestoreRecoveryAction.COMPLETE_ROLLBACK_AND_NORMAL
                else fallback(facts)
            }
        }
    }

    private fun fallback(facts: RestoreArtifactFacts): RestoreRecoveryAction = when {
        facts.oldValid -> RestoreRecoveryAction.PROMOTE_OLD
        facts.safetyValid -> RestoreRecoveryAction.PROMOTE_SAFETY
        else -> RestoreRecoveryAction.EMERGENCY
    }
}

package com.v1per.synthx.data.backup

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.content.FileProvider
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class RestoreIncidentReport(
    val formatVersion: Int = 1,
    val incidentId: String,
    val createdAt: Long,
    val packageName: String,
    val appVersion: String,
    val databaseSchemaVersion: Int,
    val restoreOperationId: String?,
    val workRequestId: String?,
    val journalPhase: String?,
    val journalOutcome: String?,
    val rollbackSource: String?,
    val triggerCategory: String,
    val triggeringCheck: String?,
    val exceptions: List<RestoreIncidentException>,
    val artifacts: List<RestoreIncidentArtifact>,
    val plannerFacts: String?,
    val selectedAction: String?,
    val recoveryResult: String,
    val sourceBackupName: String?,
    val sourceMetadata: String?,
    val remediation: List<String>,
)

@Serializable
internal data class RestoreIncidentException(
    val relation: String,
    val type: String,
    val message: String?,
    val stackTrace: String,
)

@Serializable
internal data class RestoreIncidentArtifact(
    val name: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val validation: String,
)

internal object RestoreIncidentReports {
    private const val TAG = "RestoreIncident"
    private const val DIRECTORY = "restore_incidents"
    private val json = Json { prettyPrint = true; explicitNulls = true }

    fun build(
        context: Context,
        incidentId: String,
        state: RestoreTransaction.State?,
        failure: Throwable?,
        artifacts: List<RestoreIncidentArtifact>,
        plannerFacts: String?,
        selectedAction: String?,
        triggerCategory: String,
    ): RestoreIncidentReport {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("unknown")
        return RestoreIncidentReport(
            incidentId = incidentId,
            createdAt = System.currentTimeMillis(),
            packageName = context.packageName,
            appVersion = version,
            databaseSchemaVersion = SynthXDatabase.VERSION,
            restoreOperationId = state?.operationId,
            workRequestId = state?.requestId,
            journalPhase = state?.phase?.name,
            journalOutcome = state?.outcome?.name,
            rollbackSource = state?.rollbackSource?.name,
            triggerCategory = triggerCategory,
            triggeringCheck = failure?.message,
            exceptions = throwableDetails(failure),
            artifacts = artifacts,
            plannerFacts = plannerFacts,
            selectedAction = selectedAction,
            recoveryResult = RestoreRecovery.EMERGENCY.name,
            sourceBackupName = state?.sourceName,
            sourceMetadata = state?.let {
                "size=${it.candidateSize}, sha256=${it.candidateSha256}, schema=${it.candidateVersion}"
            },
            remediation = listOf(
                "Keep synthX closed except when exporting diagnostics or selecting another backup.",
                "Export this report before attempting manual recovery.",
                "Select a different verified backup if automatic recovery cannot continue.",
            ),
        )
    }

    fun writeBestEffort(
        context: Context,
        report: RestoreIncidentReport,
        faults: RestoreFaultInjector,
    ) {
        runCatching {
            val directory = File(context.filesDir, DIRECTORY)
            check(directory.isDirectory || directory.mkdirs()) { "Could not create incident report directory" }
            val target = reportFile(context, report.incidentId)
            val temp = File(directory, ".${target.name}.tmp")
            faults.hit(RestoreFaultPoint.INCIDENT_REPORT_OPEN)
            FileOutputStream(temp).use { output ->
                faults.hit(RestoreFaultPoint.INCIDENT_REPORT_WRITE)
                output.write(json.encodeToString(report).encodeToByteArray())
                faults.hit(RestoreFaultPoint.INCIDENT_REPORT_FILE_SYNC)
                output.fd.sync()
            }
            faults.hit(RestoreFaultPoint.INCIDENT_REPORT_PROMOTE_RENAME)
            Os.rename(temp.path, target.path)
            faults.hit(RestoreFaultPoint.INCIDENT_REPORT_DIRECTORY_SYNC)
            val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }.onFailure { Log.e(TAG, "Could not persist restore incident report ${report.incidentId}", it) }
    }

    fun reportFile(context: Context, incidentId: String): File =
        File(File(context.filesDir, DIRECTORY), "restore-incident-$incidentId.json")

    fun shareUri(context: Context, incidentId: String) = reportFile(context, incidentId)
        .takeIf(File::isFile)
        ?.let { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }

    internal fun decode(file: File): RestoreIncidentReport =
        json.decodeFromString(RestoreIncidentReport.serializer(), file.readText())

    private fun throwableDetails(failure: Throwable?): List<RestoreIncidentException> {
        if (failure == null) return emptyList()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val result = mutableListOf<RestoreIncidentException>()
        fun visit(error: Throwable, relation: String) {
            if (!seen.add(error)) return
            result += RestoreIncidentException(
                relation = relation,
                type = error.javaClass.name,
                message = error.message,
                stackTrace = error.stackTraceToString(),
            )
            error.suppressed.forEachIndexed { index, suppressed -> visit(suppressed, "suppressed[$index]") }
            error.cause?.let { visit(it, "cause") }
        }
        visit(failure, "thrown")
        return result
    }
}

internal object RestoreFailureFormatter {
    fun describe(error: Throwable, incidentId: String?): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val parts = mutableListOf<String>()
        fun visit(value: Throwable, label: String) {
            if (!seen.add(value)) return
            parts += "$label ${value.javaClass.simpleName}: ${value.message ?: "(no message)"}"
            value.suppressed.forEach { visit(it, "suppressed") }
            value.cause?.let { visit(it, "caused by") }
        }
        visit(error, "restore failed:")
        incidentId?.let { parts += "diagnostic incident: $it" }
        return parts.joinToString("\n").take(8_000)
    }
}

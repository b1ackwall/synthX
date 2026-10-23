package com.v1per.synthx.data.backup

import android.content.Context
import android.util.Log
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

// RestoreTransaction's restore incidents: stable incident ID, incident report and diagnostic operation ID.

private const val TAG = "RestoreTransaction"

private const val INCIDENT_FILE = "synthx.restore-incident.id"

internal data class RecoveryDecisionSnapshot(val facts: String, val action: String)

internal fun RestoreTransaction.stableIncidentId(dir: File, preferred: String?, faults: RestoreFaultInjector): String {
    val fallback = preferred?.takeIf { it.isNotBlank() }
        ?: "recovery-" + UUID.randomUUID().toString().replace("-", "").take(16)
    val file = File(dir, INCIDENT_FILE)
    val existing = runCatching { file.readText().trim() }.getOrNull()
        ?.takeIf { it.matches(Regex("(?:recovery-[0-9a-f]{16}|[0-9a-f]{32})")) }
    val selected = preferred?.takeIf { it.isNotBlank() } ?: existing ?: fallback
    runCatching {
        val temp = File(dir, "$INCIDENT_FILE.tmp")
        faults.hit(RestoreFaultPoint.INCIDENT_ID_OPEN)
        FileOutputStream(temp).use { output ->
            faults.hit(RestoreFaultPoint.INCIDENT_ID_WRITE)
            output.write(selected.encodeToByteArray())
            faults.hit(RestoreFaultPoint.INCIDENT_ID_FILE_SYNC)
            output.fd.sync()
        }
        if (file.exists() && existing == null) {
            var archive = File(dir, "$INCIDENT_FILE.invalid")
            var index = 1
            while (archive.exists()) {
                archive = File(dir, "$INCIDENT_FILE.invalid-$index")
                index++
            }
            faults.hit(RestoreFaultPoint.INCIDENT_ID_ARCHIVE_RENAME)
            rawRename(file, archive)
        }
        faults.hit(RestoreFaultPoint.INCIDENT_ID_PROMOTE_RENAME)
        rawRename(temp, file)
        syncDirectory(dir, RestoreFaultPoint.INCIDENT_DIRECTORY_SYNC, faults)
    }.onFailure { error ->
        Log.e(TAG, "Could not persist restore incident ID $selected", error)
    }
    return selected
}

internal fun RestoreTransaction.publishEmergencyIncident(
    context: Context,
    dir: File,
    preferred: String?,
    failure: Throwable?,
    faults: RestoreFaultInjector,
    triggerCategory: String,
): String {
    val incidentId = stableIncidentId(dir, preferred, faults)
    lastDiagnosticOperationId = incidentId
    runCatching {
        val state = File(dir, STATE_FILE).takeIf(File::isFile)
            ?.let { runCatching { decodeAndValidateState(it.readText()) }.getOrNull() }
        val report = RestoreIncidentReports.build(
            context = context,
            incidentId = incidentId,
            state = state,
            failure = failure,
            artifacts = incidentArtifacts(context, dir),
            plannerFacts = lastRecoveryDecision?.facts,
            selectedAction = lastRecoveryDecision?.action,
            triggerCategory = triggerCategory,
        )
        RestoreIncidentReports.writeBestEffort(context, report, faults)
    }.onFailure { Log.e(TAG, "Could not construct restore incident report $incidentId", it) }
    return incidentId
}

internal fun RestoreTransaction.publishUnexpectedStartupIncident(context: Context, failure: Throwable): String {
    val dir = context.getDatabasePath(SynthXDatabase.DB_NAME).parentFile
    if (dir == null) {
        val fallback = "recovery-" + UUID.randomUUID().toString().replace("-", "").take(16)
        lastDiagnosticOperationId = fallback
        return fallback
    }
    return publishEmergencyIncident(
        context,
        dir,
        lastDiagnosticOperationId,
        failure,
        RestoreFaultInjector.NONE,
        "STARTUP_BOUNDARY_EXCEPTION",
    )
}

private fun RestoreTransaction.incidentArtifacts(context: Context, dir: File): List<RestoreIncidentArtifact> {
    val live = context.getDatabasePath(SynthXDatabase.DB_NAME)
    val files = (
        dir.listFiles().orEmpty().filter {
            RestoreArtifactNames.parseOperationId(it.name) != null ||
                it.name == STATE_FILE || it.name.startsWith(INCIDENT_FILE)
        } + live
        ).distinctBy { it.absolutePath }
    return files.map { file ->
        val validation = if (!file.isFile || !file.name.endsWith(".db")) {
            "not_applicable"
        } else {
            runCatching { if (validateStandalone(file, currentOnly = false)) "valid" else "invalid" }
                .getOrElse { "error:${it.javaClass.simpleName}:${it.message.orEmpty()}" }
        }
        RestoreIncidentArtifact(file.name, file.exists(), file.length(), validation)
    }
}

internal fun RestoreTransaction.clearIncident(dir: File) {
    File(dir, INCIDENT_FILE).delete()
    File(dir, "$INCIDENT_FILE.tmp").delete()
    lastDiagnosticOperationId = null
}

internal fun RestoreTransaction.diagnosticOperationId(): String? = lastDiagnosticOperationId

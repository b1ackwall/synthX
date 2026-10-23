package com.v1per.synthx.data.backup

import android.system.ErrnoException
import android.system.Os
import com.v1per.synthx.data.backup.RestoreTransaction.Phase
import com.v1per.synthx.data.backup.RestoreTransaction.State
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.Json

// RestoreTransaction's restore-state journal: encode, decode, validate and durably write it.

private val json = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
}

internal fun RestoreTransaction.writeStateForTest(dir: File, state: State) = writeState(dir, state)

internal fun RestoreTransaction.writeState(
    dir: File,
    state: State,
    faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
) {
    validateState(state)
    faults.hit(RestoreFaultPoint.STATE_PERSISTENCE)
    val temp = File(dir, STATE_TEMP_FILE)
    val target = File(dir, STATE_FILE)
    FileOutputStream(temp).use { output ->
        output.write(encodeState(state).encodeToByteArray())
        output.fd.sync()
    }
    try {
        Os.rename(temp.path, target.path)
    } catch (error: ErrnoException) {
        throw IllegalStateException("Could not atomically promote restore state", error)
    }
    syncDirectory(dir, RestoreFaultPoint.STATE_JOURNAL_DIRECTORY_SYNC, faults)
}

internal fun RestoreTransaction.encodeState(state: State): String {
    validateState(state)
    return json.encodeToString(State.serializer(), state)
}

internal fun RestoreTransaction.decodeStateOrNullForTest(encoded: String): State? =
    runCatching { decodeAndValidateState(encoded) }.getOrNull()

internal fun RestoreTransaction.decodeAndValidateState(encoded: String): State =
    json.decodeFromString(State.serializer(), encoded).also(::validateState)

private fun validateState(state: State) {
    validateStateStructure(state)
    if (state.phase != Phase.COMPLETE) validateActiveStateVersion(state)
}

private fun validateStateStructure(state: State) {
    val names = RestoreArtifactNames(state.operationId)
    require(state.candidateName == names.candidate)
    require(state.oldName == names.old)
    require(state.safetyName == names.safety)
    require(state.failedName == names.failed)
    require(state.candidateSha256.matches(Regex("[0-9a-f]{64}")))
    require(state.safetySha256.matches(Regex("[0-9a-f]{64}")))
    require(state.candidateSize > 0 && state.safetySize > 0)
    require(state.candidateVersion > 0)
    require(state.oldSha256 == null || state.oldSha256.matches(Regex("[0-9a-f]{64}")))
    require(state.oldSize == null || state.oldSize > 0)
    require(state.oldVersion == null || state.oldVersion > 0)
    state.requestId?.let { require(RestoreTransaction.normalizeRequestId(it) == it) }
    require(state.completedRequestIds.size <= RestoreTransaction.COMPLETED_REQUEST_LIMIT)
    require(state.completedRequestIds.distinct().size == state.completedRequestIds.size)
    state.completedRequestIds.forEach { require(RestoreTransaction.normalizeRequestId(it) == it) }
}

internal fun RestoreTransaction.readCompletedRequestIds(dir: File): List<String> {
    val state = File(dir, STATE_FILE).takeIf(File::isFile)
        ?.let { runCatching { decodeAndValidateState(it.readText()) }.getOrNull() }
        ?: return emptyList()
    return state.completedRequestIds
}

private fun validateActiveStateVersion(state: State) {
    require(state.candidateVersion == SynthXDatabase.VERSION)
    require(state.oldVersion == null || state.oldVersion == SynthXDatabase.VERSION)
}

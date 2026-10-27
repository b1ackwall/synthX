package com.v1per.synthx.data.backup

import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RestoreTransaction members that are internal only so its RestoreTransaction*.kt files can share them
 * are used by no other source file, except the test files in ALLOWED_TEST_FILES. A newly widened member has to be added to one of the lists by hand.
 */
class RestoreTransactionBoundaryTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun widenedMembersAreUsedOnlyInsideRestoreTransactionFiles() {
        val roots = listOf("src/main", "src/test", "src/androidTest", "../benchmark/src").map(::File)
        roots.forEach { assertTrue(it.isDirectory, "source root $it not found from ${File(".").absolutePath}") }
        val sources = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .associateWith { stripCommentsAndStrings(it.readText()) }

        val declaringText = sources.filterKeys(::isMainRestoreTransactionFile)
            .values.joinToString("\n")
        val undeclared = (UNIQUE_NAMES + SHARED_NAMES).filterNot { name ->
            Regex("""\b(?:fun|val|var|class|object)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?$name\b""")
                .containsMatchIn(declaringText)
        }
        assertEquals(emptyList(), undeclared, "listed names that no main RestoreTransaction*.kt file declares")

        // Shared names are also declared by other objects or the SDK, so only the qualified form counts.
        val patterns = UNIQUE_NAMES.associateWith { Regex("""\b$it\b""") } +
            SHARED_NAMES.associateWith { Regex("""\bRestoreTransaction\s*(?:\.|::)\s*$it\b""") }
        fun usedNames(text: String) = patterns.filterValues { it.containsMatchIn(text) }.keys
        val exempt = { file: File -> isMainRestoreTransactionFile(file) || file.name in ALLOWED_TEST_FILES }
        val uses = sources.filterKeys { !exempt(it) }.flatMap { (file, text) ->
            usedNames(text).map { "${file.path}: $it" }
        }
        assertEquals(emptyList(), uses.sorted(), "widened RestoreTransaction members used outside its files")

        val staleAllowed = ALLOWED_TEST_FILES.filterNot { name ->
            sources.any { (file, text) ->
                file.name == name && !isMainRestoreTransactionFile(file) &&
                    usedNames(text).isNotEmpty()
            }
        }
        assertEquals(emptyList(), staleAllowed, "ALLOWED_TEST_FILES entries that are missing or use no listed name")
    }

    private fun isMainRestoreTransactionFile(file: File): Boolean =
        file.name.startsWith("RestoreTransaction") && file.invariantSeparatorsPath.startsWith("src/main/")

    private fun stripCommentsAndStrings(text: String): String = Regex(
        """""${'"'}[\s\S]*?""${'"'}|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])+'|/\*[\s\S]*?\*/|//[^\n]*""",
    ).replace(text, " ")

    private companion object {
        // Test files named RestoreTransaction* that may use the members; every other test file may not.
        val ALLOWED_TEST_FILES = listOf(
            // Pins COMPLETED_REQUEST_LIMIT and calls withCompletedRequest directly.
            "RestoreTransactionCompletedRequestLimitTest.kt",
        )

        // Private before #300, internal after it; no other file declares these names.
        val UNIQUE_NAMES = listOf(
            "STATE_FILE",
            "STATE_TEMP_FILE",
            "COMPLETED_REQUEST_LIMIT",
            "lastDiagnosticOperationId",
            "lastRecoveryDecision",
            "RecoveryDecisionSnapshot",
            "proveCandidateStandalone",
            "readCheckpointResult",
            "migrateCandidate",
            "validateInstalled",
            "validateOld",
            "validateStandalone",
            "validateSafety",
            "parkLiveIfPresent",
            "quarantineSidecars",
            "writeState",
            "decodeAndValidateState",
            "withCompletedRequest",
            "readCompletedRequestIds",
            "normalizeRequestId",
            "cleanupPreClose",
            "cleanupAfterColdValidation",
            "archiveOperationArtifacts",
            "archiveRecoveredArtifacts",
            "archiveCorruptState",
            "stableIncidentId",
            "publishEmergencyIncident",
            "clearIncident",
        )

        // Private before #300, internal after it; the same name is declared elsewhere too.
        val SHARED_NAMES = listOf(
            "force",
            "rename",
            "rawRename",
            "detachSidecars",
            "deleteFileSet",
            "syncDirectory",
        )
    }
}

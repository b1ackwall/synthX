package com.v1per.synthx.data.backup

import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val V241Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
}

/**
 * Journals written by shipped builds still decode with today's code. The bytes come from fixed
 * golden files (README.md next to them), never from today's serializer, so a renamed field or
 * enum value fails here.
 */
class RestoreJournalCompatibilityTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun v24CompleteGoldenEqualsTheV241SerializerOutput() {
        val historical = V241Json.encodeToString(V241State.serializer(), V241_STATE)
        assertEquals(historical, RestoreJournalGoldens.read(RestoreJournalGoldens.V24_COMPLETE))
    }

    @Test fun v24CompleteJournalStillDecodes() {
        val decoded = decode(RestoreJournalGoldens.V24_COMPLETE)
        assertEquals(RestoreTransaction.Phase.COMPLETE, decoded.phase)
        assertEquals(RestoreJournalGoldens.OPERATION_ID, decoded.operationId)
        assertEquals("a".repeat(64), decoded.candidateSha256)
        assertEquals(14, decoded.candidateVersion)
        assertEquals("b".repeat(64), decoded.safetySha256)
        assertEquals("c".repeat(64), decoded.oldSha256)
        assertEquals(14, decoded.oldVersion)
        assertEquals(1758000005000, decoded.updatedAt)
        assertEquals(RestoreTransaction.Outcome.INSTALLED, decoded.outcome)
        assertNull(decoded.rollbackSource)
        assertNull(decoded.requestId)
        assertEquals(emptyList(), decoded.completedRequestIds)
    }

    @Test fun v25CompleteRolledBackJournalStillDecodesAndReencodesByteForByte() {
        val golden = RestoreJournalGoldens.read(RestoreJournalGoldens.V25_COMPLETE_ROLLED_BACK)
        val decoded = decode(RestoreJournalGoldens.V25_COMPLETE_ROLLED_BACK)
        assertEquals(RestoreTransaction.Phase.COMPLETE, decoded.phase)
        assertEquals(16, decoded.candidateVersion)
        assertEquals(RestoreTransaction.Outcome.ROLLED_BACK, decoded.outcome)
        assertEquals(RestoreTransaction.RollbackSource.SAFETY, decoded.rollbackSource)
        assertEquals(RestoreJournalGoldens.OPERATION_ID, decoded.requestId)
        assertEquals(
            listOf(RestoreJournalGoldens.OTHER_REQUEST_ID, RestoreJournalGoldens.OPERATION_ID),
            decoded.completedRequestIds,
        )
        assertEquals(golden, RestoreTransaction.encodeState(decoded))
    }

    @Test fun activePostValidatedJournalDecodesAndReencodesByteForByte() {
        val golden = RestoreJournalGoldens.read(RestoreJournalGoldens.ACTIVE_POST_VALIDATED)
        val decoded = decode(RestoreJournalGoldens.ACTIVE_POST_VALIDATED)
        assertEquals(RestoreTransaction.Phase.POST_VALIDATED, decoded.phase)
        assertEquals("a".repeat(64), decoded.candidateSha256)
        assertEquals(4096, decoded.candidateSize)
        assertEquals(RestoreJournalGoldens.SOURCE_NAME, decoded.sourceName)
        assertEquals(RestoreJournalGoldens.OPERATION_ID, decoded.requestId)
        assertEquals(listOf(RestoreJournalGoldens.OTHER_REQUEST_ID), decoded.completedRequestIds)
        assertEquals(golden, RestoreTransaction.encodeState(decoded))
    }

    @Test fun activeGoldenFollowsTheCurrentDatabaseVersion() {
        val decoded = RestoreTransaction.decodeStateOrNullForTest(
            RestoreJournalGoldens.read(RestoreJournalGoldens.ACTIVE_POST_VALIDATED),
        )
        assertNotNull(
            decoded,
            "The active golden journal no longer decodes. If SynthXDatabase.VERSION was bumped, " +
                "update active-post-validated.json in both golden folders as " +
                "app/src/test/resources/restore-journals/README.md describes.",
        )
        assertEquals(SynthXDatabase.VERSION, decoded.candidateVersion)
    }

    @Test fun activeJournalFromAnOlderDatabaseVersionIsRefused() {
        val older = (SynthXDatabase.VERSION - 1).toString()
        val stale = RestoreJournalGoldens.read(RestoreJournalGoldens.ACTIVE_POST_VALIDATED)
            .replace("\"candidateVersion\":${SynthXDatabase.VERSION}", "\"candidateVersion\":$older")
            .replace("\"oldVersion\":${SynthXDatabase.VERSION}", "\"oldVersion\":$older")
        assertTrue(stale.contains("\"candidateVersion\":$older"))
        assertNull(RestoreTransaction.decodeStateOrNullForTest(stale))
    }

    @Test fun journalWithAnUnknownKeyIsRefused() {
        val golden = RestoreJournalGoldens.read(RestoreJournalGoldens.ACTIVE_POST_VALIDATED)
        assertNotNull(RestoreTransaction.decodeStateOrNullForTest(golden), "control: the unmodified golden must decode")
        val withUnknownKey = golden.replace("{\"operationId\"", "{\"unknownFutureField\":\"x\",\"operationId\"")
        assertTrue(withUnknownKey.contains("unknownFutureField"))
        assertNull(RestoreTransaction.decodeStateOrNullForTest(withUnknownKey))
    }

    /**
     * explicitNulls only changes behaviour for a nullable property without a null default, and the
     * journal never writes defaults. Every nullable State field must stay optional and default to null.
     */
    @Test fun everyNullableStateFieldIsOptionalAndDefaultsToNull() {
        val descriptor = RestoreTransaction.State.serializer().descriptor
        val nullable = (0 until descriptor.elementsCount).filter { descriptor.getElementDescriptor(it).isNullable }
        assertTrue(nullable.isNotEmpty(), "State has no nullable fields; the guard no longer checks anything")
        val required = nullable.filterNot(descriptor::isElementOptional).map(descriptor::getElementName)
        assertEquals(emptyList(), required, "nullable State fields without a default value")

        val names = nullable.map(descriptor::getElementName).toSet()
        val golden = Json.parseToJsonElement(RestoreJournalGoldens.read(RestoreJournalGoldens.ACTIVE_POST_VALIDATED))
        val stripped = JsonObject(golden.jsonObject.filterKeys { it !in names }).toString()
        val decoded =
            assertNotNull(RestoreTransaction.decodeStateOrNullForTest(stripped), "stripped golden must decode")
        val notNull = names.filter { name ->
            RestoreTransaction.State::class.java.getMethod("get" + name.replaceFirstChar(Char::uppercase))
                .invoke(decoded) != null
        }
        assertEquals(emptyList(), notNull, "nullable State fields whose default is not null")
    }

    @Test fun jvmAndDeviceGoldenFoldersAreByteIdentical() {
        val jvmFiles = RestoreJournalGoldens.jvmFolder.listFiles().orEmpty().associateBy { it.name }
        val deviceFiles = RestoreJournalGoldens.deviceFolder.listFiles().orEmpty().associateBy { it.name }
        assertTrue(jvmFiles.isNotEmpty(), "no golden files under ${RestoreJournalGoldens.jvmFolder.absolutePath}")
        assertEquals(jvmFiles.keys, deviceFiles.keys)
        jvmFiles.forEach { (name, file) ->
            assertContentEquals(file.readBytes(), deviceFiles.getValue(name).readBytes(), name)
        }
    }

    private fun decode(name: String): RestoreTransaction.State = assertNotNull(
        RestoreTransaction.decodeStateOrNullForTest(RestoreJournalGoldens.read(name)),
        "$name no longer decodes",
    )

    /** Test-only copy of RestoreTransaction.State, Phase and Outcome as shipped in v2.2.0 to v2.4.x. */
    @Serializable
    enum class V241Phase {
        PREPARED,
        LIVE_MOVE_INTENT,
        LIVE_MOVED,
        INSTALL_INTENT,
        CANDIDATE_INSTALLED,
        POST_VALIDATED,
        ROLLBACK_INTENT,
        ROLLED_BACK,
        COMPLETE,
    }

    @Serializable
    enum class V241Outcome { INSTALLED, ROLLED_BACK }

    @Serializable
    data class V241State(
        val operationId: String,
        val sourceName: String,
        val sourceUri: String,
        val candidateName: String,
        val oldName: String,
        val safetyName: String,
        val failedName: String,
        val candidateSha256: String,
        val candidateSize: Long,
        val candidateVersion: Int,
        val safetySha256: String,
        val safetySize: Long,
        val phase: V241Phase,
        val createdAt: Long,
        val updatedAt: Long = createdAt,
        val oldSha256: String? = null,
        val oldSize: Long? = null,
        val oldVersion: Int? = null,
        val outcome: V241Outcome = V241Outcome.INSTALLED,
    )

    private companion object {
        const val OP = RestoreJournalGoldens.OPERATION_ID
        const val SRC = RestoreJournalGoldens.SOURCE_NAME
        val V241_STATE = V241State(
            operationId = OP, sourceName = SRC, sourceUri = "content://test/$SRC",
            candidateName = "synthx.restore-$OP.candidate.db", oldName = "synthx.restore-$OP.old.db",
            safetyName = "synthx.restore-$OP.safety.db", failedName = "synthx.restore-$OP.failed.db",
            candidateSha256 = "a".repeat(64), candidateSize = 4096, candidateVersion = 14,
            safetySha256 = "b".repeat(64), safetySize = 4096, phase = V241Phase.COMPLETE,
            createdAt = 1758000000000, updatedAt = 1758000005000,
            oldSha256 = "c".repeat(64), oldSize = 4096, oldVersion = 14,
        )
    }
}

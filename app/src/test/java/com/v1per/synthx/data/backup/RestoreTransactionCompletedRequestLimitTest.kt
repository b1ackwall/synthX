package com.v1per.synthx.data.backup

import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RestoreTransactionCompletedRequestLimitTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun limitIsSixtyFour() {
        assertEquals(64, RestoreTransaction.COMPLETED_REQUEST_LIMIT)
    }

    @Test fun journalsWithZeroSixtyThreeAndSixtyFourCompletedIdsRoundTrip() {
        listOf(0, 63, 64).forEach { count ->
            val state = state(ids(count))
            val decoded = RestoreTransaction.decodeStateOrNullForTest(RestoreTransaction.encodeState(state))
            assertEquals(state, decoded, "journal with $count completed ids must round-trip")
        }
    }

    @Test fun encodingSixtyFiveCompletedIdsIsRejected() {
        assertFailsWith<IllegalArgumentException> { RestoreTransaction.encodeState(state(ids(65))) }
    }

    @Test fun handBuiltJournalWithSixtyFiveCompletedIdsDecodesToNull() {
        val encoded = RestoreTransaction.encodeState(state(ids(64)))
        assertNotNull(RestoreTransaction.decodeStateOrNullForTest(encoded), "control: 64 ids must decode")
        val oldest = "\"${id(0)}\""
        assertEquals(1, Regex(Regex.escape(oldest)).findAll(encoded).count(), "splice anchor must be unique")
        val spliced = encoded.replace(oldest, "$oldest,\"${id(64)}\"")
        assertNull(RestoreTransaction.decodeStateOrNullForTest(spliced))
    }

    @Test fun completingOnAFullListDropsTheOldestAndAppendsTheNewId() {
        val result = complete(state(ids(64), requestId = id(100)))
        assertEquals(ids(64).drop(1) + id(100), result.completedRequestIds)
    }

    @Test fun completingOnSixtyThreeIdsKeepsAllAndReachesTheLimit() {
        val result = complete(state(ids(63), requestId = id(100)))
        assertEquals(ids(63) + id(100), result.completedRequestIds)
        assertEquals(RestoreTransaction.COMPLETED_REQUEST_LIMIT, result.completedRequestIds.size)
    }

    @Test fun completingAnAlreadyRecordedIdMovesItToTheEndWithoutGrowing() {
        val result = complete(state(ids(64), requestId = id(10)))
        assertEquals(ids(64).filterNot { it == id(10) } + id(10), result.completedRequestIds)
    }

    @Test fun completionFallsBackToTheOperationIdWhenThereIsNoRequestId() {
        val result = complete(state(ids(2), requestId = null))
        assertEquals(ids(2) + OP, result.completedRequestIds)
    }

    @Test fun aFullListProducedByCompletionStillEncodes() {
        val result = complete(state(ids(64), requestId = id(100)))
        val decoded = RestoreTransaction.decodeStateOrNullForTest(RestoreTransaction.encodeState(result))
        assertEquals(result, decoded)
    }

    private fun complete(state: RestoreTransaction.State): RestoreTransaction.State =
        with(RestoreTransaction) { state.withCompletedRequest() }

    private fun id(index: Int): String = "%032x".format(index + 1)

    private fun ids(count: Int): List<String> = List(count, ::id)

    private fun state(completed: List<String>, requestId: String? = null): RestoreTransaction.State {
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
            phase = RestoreTransaction.Phase.COMPLETE,
            createdAt = 1,
            oldSha256 = "c".repeat(64),
            oldSize = 4096,
            oldVersion = SynthXDatabase.VERSION,
            requestId = requestId,
            completedRequestIds = completed,
        )
    }

    private companion object {
        const val OP = "0123456789abcdef0123456789abcdef"
        const val FILE = "synthx_backup_2026-08-26_120000_000_0123abcd.db"
    }
}

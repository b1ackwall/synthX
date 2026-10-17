package com.v1per.synthx.data.db

import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteBatchingTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test
    fun largerLogicalInputIsDeduplicatedSplitAndReassembledInOrder() = runTest {
        val calls = mutableListOf<List<Int>>()
        val ids = (0..SQLITE_IN_BATCH_SIZE).toList() + listOf(0, SQLITE_IN_BATCH_SIZE)

        val result = ids.queryInBatches(query = { batch ->
            calls += batch
            batch.map { "row-$it" }
        })

        assertEquals(listOf(SQLITE_IN_BATCH_SIZE, 1), calls.map { it.size })
        assertEquals((0..SQLITE_IN_BATCH_SIZE).map { "row-$it" }, result)
    }
}

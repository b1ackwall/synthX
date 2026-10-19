package com.v1per.synthx.ui.common

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The readiness covers lift for anything past the first load: success, empty and error alike. */
class UiStateTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun onlyLoadingIsNotReady() {
        assertFalse(UiState.Loading.isReady())
        assertTrue(UiState.Empty.isReady())
        assertTrue(UiState.Error("failed").isReady())
        assertTrue(UiState.Success(1).isReady())
    }
}

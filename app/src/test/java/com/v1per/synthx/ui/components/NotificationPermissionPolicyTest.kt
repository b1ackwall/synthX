package com.v1per.synthx.ui.components

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure decision behind [NotificationPermissionGate]. */
class NotificationPermissionPolicyTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val android13 = 33

    @Test fun asksOnlyWhenDeniedNotYetAskedAndRuntimePermissionExists() {
        assertTrue(NotificationPermissionPolicy.shouldAsk(android13, granted = false, alreadyAsked = false))
    }

    @Test fun neverAsksTwice() {
        assertFalse(NotificationPermissionPolicy.shouldAsk(android13, granted = false, alreadyAsked = true))
    }

    @Test fun neverAsksWhenAlreadyGranted() {
        assertFalse(NotificationPermissionPolicy.shouldAsk(android13, granted = true, alreadyAsked = false))
    }

    @Test fun neverAsksBelowAndroid13() {
        assertFalse(NotificationPermissionPolicy.shouldAsk(32, granted = false, alreadyAsked = false))
    }
}

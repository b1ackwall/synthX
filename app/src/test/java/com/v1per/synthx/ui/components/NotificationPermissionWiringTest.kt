package com.v1per.synthx.ui.components

import com.v1per.synthx.testing.ProductionSource
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Notification-permission wiring guards, pinned by reading the production source (some checks
 * strip comments first, the rest read the raw text). The
 * behaviour behind them (rationale, single prompt, the "asked" flag, never waiting on the
 * prompt) is covered by NotificationPermissionGateInstrumentedTest; the shell's second chance
 * is also exercised on a device by NotificationSecondChanceInstrumentedTest.
 */
class NotificationPermissionWiringTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private fun source(relative: String): String {
        val file = listOf(File("src/main/java", relative), File("app/src/main/java", relative))
            .firstOrNull(File::isFile) ?: error("Could not locate production source $relative")
        return file.readText()
    }

    private val permissionTokens = listOf(
        "POST_NOTIFICATIONS",
        "areNotificationsEnabled",
        "NotificationPermission",
        "notificationPermissionAsked",
        "checkSelfPermission",
        "launchPermissionRequest",
    )

    // Comment-free code, so a comment that names one of these is not a false failure.
    @Test fun mainActivityNoLongerRequestsNotificationsAtShellComposition() {
        val main = ProductionSource.code("com/v1per/synthx/MainActivity.kt")
        listOf(
            "NotificationPermissionEffect",
            "rememberPermissionState",
            "launchPermissionRequest",
            "POST_NOTIFICATIONS",
            "rememberNotificationPermissionGate",
        ).forEach { assertFalse(main.contains(it), "MainActivity must not reference $it") }
    }

    @Test fun onboardingDoneRoutesTheFirstScanThroughTheGateBeforeStartingIt() {
        val onboarding = source("com/v1per/synthx/ui/screens/onboarding/OnboardingScreen.kt")
        val gate = onboarding.indexOf("notificationGate.runAfterRationale {")
        val start = onboarding.indexOf("libraryManagementViewModel.startFirstScan()")
        assertTrue(gate >= 0, "Done must go through the notification gate")
        assertEquals(
            1,
            Regex("startFirstScan\\(\\)").findAll(onboarding.substringAfter("OnboardingStep.FolderSetup ->")).count(),
        )
        assertTrue(start > gate, "startFirstScan() must run inside the gate's deferred action")
    }

    @Test fun restoreConfirmRoutesTheEnqueueThroughTheGateBeforeStartingIt() {
        val restore = source("com/v1per/synthx/ui/screens/restore/RestoreScreen.kt")
        val gate = restore.indexOf("notificationGate.runAfterRationale {")
        val enqueue = restore.indexOf("viewModel.restore(")
        assertTrue(gate >= 0, "confirming a restore must go through the notification gate")
        assertEquals(1, Regex("viewModel\\.restore\\(").findAll(restore).count())
        assertTrue(enqueue > gate, "viewModel.restore(...) must run inside the gate's deferred action")
    }

    /** Consistency: nothing that runs or reports scan/restore work reads the permission. */
    @Test fun scanAndRestoreWorkAndTheirInAppStatusNeverConsultTheNotificationPermission() {
        listOf(
            "com/v1per/synthx/data/backup/RestoreWorker.kt",
            "com/v1per/synthx/data/scanner/LibraryScanWorker.kt",
            "com/v1per/synthx/ui/screens/restore/RestoreStatusViewModel.kt",
            "com/v1per/synthx/ui/screens/restore/RestoreViewModel.kt",
            "com/v1per/synthx/ui/screens/librarymanagement/LibraryManagementViewModel.kt",
            "com/v1per/synthx/ui/screens/librarymanagement/LibraryManagementViewModelFolders.kt",
            "com/v1per/synthx/ui/screens/librarymanagement/LibraryManagementViewModelFirstScan.kt",
            "com/v1per/synthx/ui/screens/librarymanagement/ScanUiState.kt",
            "com/v1per/synthx/ui/screens/onboarding/OnboardingViewModel.kt",
        ).forEach { path ->
            val text = source(path)
            permissionTokens.forEach { token ->
                assertFalse(text.contains(token), "$path must not depend on notification permission ($token)")
            }
        }
    }

    @Test fun gateNeverWaitsOnThePromptResult() {
        val gate = source("com/v1per/synthx/ui/components/NotificationPermissionGate.kt")
        val confirm = gate.substringAfter("fun confirm()").substringBefore("\n    }\n")
        assertTrue(confirm.indexOf("launchRequest()") in 0 until confirm.indexOf("proceed()"))
        assertFalse(gate.contains("onPermissionResult"), "the gate must not gate work on the prompt's answer")
    }
}

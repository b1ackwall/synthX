package com.v1per.synthx.ui.screens.issues

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The issue rows and the scan status line render their counts with the right noun number. */
@RunWith(AndroidJUnit4::class)
class IssueCountTextInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent { SynthXTheme { content() } }
    }

    @Test
    fun destructiveActionsRowReadsSingularForOneChange() {
        show { DestructiveActionsRow(count = 1, onClick = {}) }
        composeRule.onNodeWithText("1 change since your last backup").assertExists()
    }

    @Test
    fun destructiveActionsRowReadsPluralForManyChanges() {
        show { DestructiveActionsRow(count = 12, onClick = {}) }
        composeRule.onNodeWithText("12 changes since your last backup").assertExists()
    }

    @Test
    fun reconciledPendingCleanupsRowReadsSingularForOneChange() {
        show { ReconciledPendingCleanupsRow(count = 1, timestamp = System.currentTimeMillis()) }
        composeRule.onNodeWithText("1 interrupted change cleaned up on launch").assertExists()
    }

    @Test
    fun reconciledPendingCleanupsRowReadsPluralForManyChanges() {
        show { ReconciledPendingCleanupsRow(count = 3, timestamp = System.currentTimeMillis()) }
        composeRule.onNodeWithText("3 interrupted changes cleaned up on launch").assertExists()
    }

    @Test
    fun missingArtRowReadsSingularForOneSong() {
        show { MissingArtRow(songIds = listOf("a"), onFix = {}) }
        composeRule.onNodeWithText("1 song missing cover art").assertExists()
    }

    @Test
    fun missingArtRowReadsPluralForManySongs() {
        show { MissingArtRow(songIds = listOf("a", "b", "c"), onFix = {}) }
        composeRule.onNodeWithText("3 songs missing cover art").assertExists()
    }

    @Test
    fun scanStatusLineReadsSingularForOneIssue() {
        show { ScanStatusBar(status = IssuesScanStatus.Idle(System.currentTimeMillis(), issueCount = 1), onScan = {}) }
        composeRule.onNodeWithText("— 1 issue found", substring = true).assertExists()
    }

    @Test
    fun scanStatusLineReadsPluralForManyIssues() {
        show { ScanStatusBar(status = IssuesScanStatus.Idle(System.currentTimeMillis(), issueCount = 7), onScan = {}) }
        composeRule.onNodeWithText("— 7 issues found", substring = true).assertExists()
    }

    @Test
    fun scanStatusLineReadsPluralForZeroIssues() {
        show { ScanStatusBar(status = IssuesScanStatus.Idle(System.currentTimeMillis(), issueCount = 0), onScan = {}) }
        composeRule.onNodeWithText("— 0 issues found", substring = true).assertExists()
    }
}

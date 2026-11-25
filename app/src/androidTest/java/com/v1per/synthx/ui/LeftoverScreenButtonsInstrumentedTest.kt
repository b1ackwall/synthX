package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.ui.components.SynthXButtonRole.Primary
import com.v1per.synthx.ui.components.SynthXButtonRole.Secondary
import com.v1per.synthx.ui.components.SynthXButtonRole.Tertiary
import com.v1per.synthx.ui.screens.issues.IssuesScanStatus
import com.v1per.synthx.ui.screens.issues.ScanStatusBar
import com.v1per.synthx.ui.screens.librarymanagement.ScanSection
import com.v1per.synthx.ui.screens.playlistlist.PlaylistListScreen
import com.v1per.synthx.ui.screens.playlistlist.PlaylistListViewModel
import com.v1per.synthx.ui.screens.replacesong.ReplaceSongPreviewFailure
import com.v1per.synthx.ui.screens.restore.AllIneligibleBanner
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.assertDialogButton
import com.v1per.synthx.ui.testing.dialogButton
import com.v1per.synthx.ui.testing.stubSongRepository
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen buttons outside onboarding and the detail headers, on a 360dp screen at 100% font scale: each
 * one's role, fixed width, 48dp height and centring, and the stacked Playlists header pair.
 */
@RunWith(AndroidJUnit4::class)
class LeftoverScreenButtonsInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(id: Int) = context.getString(id)

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent {
            SynthXTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1f)) {
                    Box(
                        Modifier
                            .wrapContentWidth(Alignment.Start, unbounded = true)
                            .width(SCREEN)
                            .fillMaxHeight()
                            .background(Background)
                            .testTag(SCREEN_TAG),
                    ) { content() }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(label: String): DpRect = composeRule.dialogButton(label).getUnclippedBoundsInRoot()

    private fun screen(): DpRect = composeRule.onNodeWithTag(SCREEN_TAG).getUnclippedBoundsInRoot()

    private fun assertNear(message: String, expected: Dp, actual: Dp) =
        assertEquals(message, expected.value, actual.value, TOLERANCE_DP)

    private fun assertSizeAndCentred(label: String, width: Dp) {
        val button = bounds(label)
        val screen = screen()
        assertNear("$label width", width, button.right - button.left)
        assertNear("$label height", 48.dp, button.bottom - button.top)
        assertNear("$label centred", button.left - screen.left, screen.right - button.right)
    }

    @Composable
    private fun Playlists() {
        val vm = viewModel<PlaylistListViewModel>(
            factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlaylistListViewModel(stubSongRepository(), context) as T
            },
        )
        PlaylistListScreen(
            topBar = {},
            onGroupClick = { _, _ -> },
            onPlaylistClick = { _, _ -> },
            onCreatePlaylistClick = {},
            viewModel = vm,
        )
    }

    @Test fun thePlaylistsHeaderStacksTwoSecondaryStandardButtonsNewPlaylistOnTop() {
        show { Playlists() }
        val newPlaylist = string(R.string.action_new_playlist)
        val newGroup = string(R.string.action_new_group)
        listOf(newPlaylist, newGroup).forEach {
            assertSizeAndCentred(it, 168.dp)
            composeRule.assertDialogButton(it, Secondary, backdrop = Background)
        }
        val top = bounds(newPlaylist)
        val bottom = bounds(newGroup)
        assertTrue("New Playlist sits above New Group", top.bottom < bottom.top)
        assertNear("12dp between the stacked buttons", 12.dp, bottom.top - top.bottom)
    }

    @Test fun theNewGroupCardSitsCancelAndCreateSideBySideAsCompactButtons() {
        show { Playlists() }
        composeRule.dialogButton(string(R.string.action_new_group)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Road trips")
        composeRule.waitForIdle()
        val create = bounds(string(R.string.action_create))
        val cancel = bounds(string(R.string.action_cancel))
        assertNear("same row", cancel.top, create.top)
        assertNear("Cancel first, Create 12dp after it", 12.dp, create.left - cancel.right)
        assertNear("Create width", 112.dp, create.right - create.left)
        assertNear("Cancel width", 112.dp, cancel.right - cancel.left)
        assertNear("Create height", 48.dp, create.bottom - create.top)
        composeRule.assertDialogButton(string(R.string.action_create), Primary, backdrop = Surface)
        composeRule.assertDialogButton(string(R.string.action_cancel), Secondary, backdrop = Surface)
    }

    @Test fun theIssuesScanIsATertiaryCompactButton48dpTall() {
        show { ScanStatusBar(status = IssuesScanStatus.Idle(lastScanTimestamp = 0L, issueCount = 0), onScan = {}) }
        val scan = bounds(string(R.string.issues_scan))
        assertNear("Scan width", 112.dp, scan.right - scan.left)
        assertNear("Scan height", 48.dp, scan.bottom - scan.top)
        composeRule.assertDialogButton(string(R.string.issues_scan), Tertiary, backdrop = Background)
    }

    @Test fun whileAScanRunsTheIssuesScanIsDisabled() {
        show { ScanStatusBar(status = IssuesScanStatus.Scanning, onScan = {}) }
        composeRule.assertDialogButton(string(R.string.issues_scan), Tertiary, enabled = false, backdrop = Background)
    }

    @Test fun theScanCancelIsACentredSecondaryCompactButton() {
        show {
            Column {
                ScanSection(
                    status = null,
                    statusNeedsAttention = false,
                    isScanning = true,
                    progress = null,
                    onRescan = {},
                    onCancelScan = {},
                )
            }
        }
        assertSizeAndCentred(string(R.string.action_cancel), 112.dp)
        composeRule.assertDialogButton(string(R.string.action_cancel), Secondary, backdrop = Background)
    }

    @Test fun theAllIneligibleGoBackIsACentredPrimaryCompactButton() {
        show { AllIneligibleBanner(onBack = {}) }
        assertSizeAndCentred(string(R.string.action_go_back), 112.dp)
        composeRule.assertDialogButton(string(R.string.action_go_back), Primary, backdrop = Background)
    }

    @Test fun theReplaceSongPreviewRetryIsACentredPrimaryCompactButton() {
        show { ReplaceSongPreviewFailure(message = "unreadable", onRetry = {}) }
        assertSizeAndCentred(string(R.string.action_retry), 112.dp)
        composeRule.assertDialogButton(string(R.string.action_retry), Primary, backdrop = Background)
    }

    private companion object {
        const val SCREEN_TAG = "screen"
        const val TOLERANCE_DP = 0.5f
        val SCREEN = 360.dp
    }
}

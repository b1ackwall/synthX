package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.RestoreTerminalContent
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.ui.components.DeleteSongDisclosure
import com.v1per.synthx.ui.components.DeleteSongPreviewState
import com.v1per.synthx.ui.screens.restore.BackupRow
import com.v1per.synthx.ui.screens.restore.RestoreAppStatus
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Text that sits outside the five accent contexts must paint a neutral token, never Accent.
 * Buttons left on the Material default would take the theme's primary role, which is Accent.
 */
@RunWith(AndroidJUnit4::class)
class AccentContextColorInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Unmerged: a clickable row merges its texts, and the merged node answers with the first one's layout.
    private fun textColorOf(text: String): Color {
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().layoutInput.style.color
    }

    private fun assertNeutral(text: String) {
        val color = textColorOf(text)
        assertNotEquals("'$text' paints Accent", Accent, color)
        assertEquals("'$text' paints the wrong colour", TextPrimary, color)
    }

    @Test
    fun mostRecentLabelIsNotAccent() {
        val backup = BackupInfo(
            databaseUri = "content://x/recent.db".toUri(),
            metadataUri = null,
            fileName = "recent.db",
            tier = BackupTier.Manual,
            timestamp = System.currentTimeMillis(),
            reason = null,
            sizeBytes = 1_000L,
            restoreEligible = true,
        )
        composeRule.setContent {
            SynthXTheme { BackupRow(backup = backup, isMostRecent = true, onClick = {}) }
        }
        assertNeutral(context.getString(R.string.restore_row_most_recent))
    }

    @Test
    fun restoreOutcomeOkButtonIsNotAccent() {
        composeRule.setContent {
            SynthXTheme {
                RestoreObservedStatusHost(
                    status = RestoreAppStatus.Succeeded(UUID.randomUUID()),
                    routeOwnsRestoreOutcome = false,
                    viewModel = null,
                )
            }
        }
        assertNeutral(context.getString(R.string.action_ok))
    }

    @Test
    fun deleteSongRetryButtonIsNotAccent() {
        composeRule.setContent {
            SynthXTheme {
                DeleteSongDisclosure(
                    previewState = DeleteSongPreviewState.Failed,
                    destructiveActionCount = 0,
                    onRetryPreview = {},
                )
            }
        }
        assertNeutral(context.getString(R.string.action_retry))
    }

    @Test
    fun emergencyScreenButtonsAreNotAccent() {
        composeRule.setContent {
            SynthXTheme { RestoreTerminalContent(RestoreCoordinator.Terminal.EMERGENCY) }
        }
        assertNeutral("Export diagnostics")
        assertNeutral("Choose backup")
        assertNeutral(context.getString(R.string.action_close))
    }
}

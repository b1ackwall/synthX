package com.v1per.synthx.ui.screens.replacesong

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.OldAudioFile
import com.v1per.synthx.data.repository.PlaylistConflictResolution
import com.v1per.synthx.data.repository.ReplaceSongResult
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The review step's move line, its conflict choices as single radio buttons, the busy cover's
 * live region, and the working overlay that takes every touch while a replace runs.
 */
@RunWith(AndroidJUnit4::class)
class ReplaceSongReviewInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val fakes = ReplaceSongTestRepository()

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun showReview(movesToMusic: Boolean, conflicts: Boolean = false) {
        composeRule.setContent {
            SynthXTheme {
                ReplaceSongReviewStep(
                    preview = fakes.preview(conflicts = conflicts, movesToMusic = movesToMusic),
                    conflictResolution = PlaylistConflictResolution.KeepOld,
                    confirmEnabled = true,
                    onConflictResolutionChange = {},
                    onConfirm = {},
                )
            }
        }
    }

    @Test fun theMoveLineShowsWhenTheReplacementMovesIntoMusic() {
        showReview(movesToMusic = true)

        composeRule.onNodeWithTag(ReplaceSongReviewTestTags.WillMoveLine)
            .assertTextEquals(string(R.string.replace_song_review_will_move))
    }

    @Test fun theMoveLineIsAbsentOtherwise() {
        showReview(movesToMusic = false)

        assertEquals(
            0,
            composeRule.onAllNodesWithTag(ReplaceSongReviewTestTags.WillMoveLine).fetchSemanticsNodes().size,
        )
    }

    @Test fun eachConflictChoiceIsOneRadioButton() {
        showReview(movesToMusic = false, conflicts = true)

        PlaylistConflictResolution.entries.forEach { option ->
            val tag = ReplaceSongReviewTestTags.conflictOption(option)
            composeRule.onNodeWithTag(tag)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assert(hasClickAction())
            val innerTargets = composeRule.onAllNodes(
                hasAnyAncestor(hasTestTag(tag)) and hasClickAction(),
                useUnmergedTree = true,
            )
            assertEquals("$option has one target, not a row plus its radio", 0, innerTargets.fetchSemanticsNodes().size)
        }
        composeRule.onNodeWithTag(ReplaceSongReviewTestTags.conflictOption(PlaylistConflictResolution.KeepOld))
            .assertIsSelected()
        composeRule.onNodeWithTag(ReplaceSongReviewTestTags.conflictOption(PlaylistConflictResolution.KeepNew))
            .assertIsNotSelected()
    }

    @Test fun theBusyCoverMessageIsAPoliteLiveRegion() {
        composeRule.setContent { SynthXTheme { ReplaceSongBusyCover(R.string.replace_song_working) } }

        composeRule.onNodeWithTag(ReplaceSongTestTags.BusyCoverMessage)
            .assertTextEquals(string(R.string.replace_song_working))
            .assert(
                SemanticsMatcher("polite live region") {
                    it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
                },
            )
    }

    @Test fun theWorkingOverlayTakesTouchesMeantForTheReviewUnderIt() {
        val repository = ReplaceSongTestRepository(conflicts = true, holdReplace = true)
        val viewModel = repository.viewModel(composeRule.activity)
        composeRule.setContent {
            SynthXTheme { ReplaceSongScreen(onExit = {}, onCompleted = {}, viewModel = viewModel) }
        }
        composeRule.runOnIdle {
            viewModel.selectOldSong(repository.oldSong)
            viewModel.selectNewSong(repository.newSong)
        }
        val keepNew = composeRule.onNodeWithTag(
            ReplaceSongReviewTestTags.conflictOption(PlaylistConflictResolution.KeepNew),
        )
        keepNew.performScrollTo()
        composeRule.onNode(hasSetTextAction()).performTextInput(string(R.string.replace_song_action_replace))
        composeRule.onNodeWithTag(ReplaceSongReviewTestTags.ConfirmButton).performClick()
        composeRule.onNodeWithTag(ReplaceSongTestTags.WorkingOverlay).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.replace_song_working)).assertIsDisplayed()

        keepNew.performClick()
        composeRule.waitForIdle()

        assertEquals(
            "a tap through the overlay must not change the choice",
            PlaylistConflictResolution.KeepOld,
            composeRule.runOnIdle { viewModel.conflictResolution.value },
        )
        composeRule.runOnIdle { repository.finishHeldReplace(ReplaceSongResult.Success(OldAudioFile.Removed)) }
    }
}

package com.v1per.synthx.ui.screens.replacesong

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.OldAudioFile
import com.v1per.synthx.data.repository.ReplaceSongResult
import com.v1per.synthx.ui.components.SynthXSnackbarHost
import com.v1per.synthx.ui.components.showSynthXSnackbar
import com.v1per.synthx.ui.replaceSongCompletion
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #383: a replace that completes without a warning leaves the wizard at once, and the
 * "Replacement complete." confirmation shows on the screen the user lands on, through a snackbar
 * host that outlives the wizard, as MainScaffold's does. The route's own completion
 * ([replaceSongCompletion]) and the real [SynthXSnackbarHost] are used; the repository is a fake,
 * so nothing touches a library.
 */
@RunWith(AndroidJUnit4::class)
class ReplaceSongSuccessInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun showWizardAndConfirm(repository: ReplaceSongTestRepository) {
        val viewModel = repository.viewModel(composeRule.activity)
        composeRule.setContent {
            SynthXTheme {
                val shellHost = remember { SnackbarHostState() }
                val shellScope = rememberCoroutineScope()
                navController = rememberNavController()
                Box(modifier = Modifier.fillMaxSize()) {
                    NavHost(navController = navController, startDestination = LANDING) {
                        composable(LANDING) { Text(LANDING_TEXT) }
                        composable(WIZARD) {
                            ReplaceSongScreen(
                                onExit = { navController.popBackStack() },
                                onCompleted = replaceSongCompletion(navController) { message ->
                                    shellScope.launch { shellHost.showSynthXSnackbar(message) }
                                },
                                viewModel = viewModel,
                            )
                        }
                    }
                    SynthXSnackbarHost(hostState = shellHost, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        composeRule.runOnIdle { navController.navigate(WIZARD) }
        composeRule.runOnIdle {
            viewModel.selectOldSong(repository.oldSong)
            viewModel.selectNewSong(repository.newSong)
        }
        composeRule.onNode(hasSetTextAction()).performTextInput(string(R.string.replace_song_action_replace))
        composeRule.onNodeWithTag(ReplaceSongReviewTestTags.ConfirmButton).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun aCleanReplaceLeavesAtOnceAndConfirmsOnTheScreenBelow() {
        val repository = ReplaceSongTestRepository(replace = { ReplaceSongResult.Success(OldAudioFile.Removed) })

        showWizardAndConfirm(repository)

        // waitForIdle has only drained the frames the completion itself needs: no wait on the
        // snackbar's own duration stands between the success and the exit.
        assertEquals(LANDING, composeRule.runOnIdle { navController.currentDestination?.route })
        assertEquals(
            0,
            composeRule.onAllNodesWithTag(ReplaceSongReviewTestTags.ConfirmButton).fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText(LANDING_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.replace_song_done)).assertIsDisplayed()
        assertEquals(1, repository.replaceCalls)
    }

    @Test
    fun aReplaceWithAWarningStaysOnTheWizardWithItsDialog() {
        val repository = ReplaceSongTestRepository(replace = { ReplaceSongResult.Success(OldAudioFile.StillPresent) })

        showWizardAndConfirm(repository)

        assertEquals(WIZARD, composeRule.runOnIdle { navController.currentDestination?.route })
        composeRule.onNodeWithText(string(R.string.replace_song_done_cleanup_pending)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_retry)).assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText(string(R.string.replace_song_done)).fetchSemanticsNodes().size)

        composeRule.onNodeWithText(string(R.string.action_ok)).performClick()
        composeRule.waitForIdle()

        assertEquals("OK closes the wizard", LANDING, composeRule.runOnIdle { navController.currentDestination?.route })
    }

    private companion object {
        const val LANDING = "landing"
        const val WIZARD = "replace"
        const val LANDING_TEXT = "Library Management"
    }
}

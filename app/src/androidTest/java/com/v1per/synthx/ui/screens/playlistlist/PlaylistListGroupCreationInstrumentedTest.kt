package com.v1per.synthx.ui.screens.playlistlist

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.stubSongRepository
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

/**
 * Through the real [PlaylistListScreen]: the inline "New Group" card must keep
 * its typed name across activity recreation, and a repeat submit — a second tap, or IME Done, while
 * the first write is in flight or while the card is animating out — must create exactly one group.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlaylistListGroupCreationInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val newGroup get() = context.getString(R.string.action_new_group)
    private val create get() = context.getString(R.string.action_create)

    private val createdGroups = mutableListOf<String>()
    private var gate: CompletableDeferred<Unit>? = null

    private val repository: SongRepository = stubSongRepository().let { stub ->
        object : SongRepository by stub {
            override suspend fun createGroup(name: String) {
                createdGroups += name
                gate?.await()
            }
        }
    }

    private var activeRepository: SongRepository = repository

    @Composable
    private fun Screen() {
        val vm = viewModel<PlaylistListViewModel>(
            factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlaylistListViewModel(activeRepository, context) as T
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

    /**
     * The one call site every install shares: rememberSaveable restoration keys off it (see
     * CreatePlaylistRecreationInstrumentedTest).
     */
    private fun install() {
        composeRule.runOnUiThread { composeRule.activity.setContent { Screen() } }
    }

    private fun openCardAndType(name: String) {
        // Before the card opens "New Group" is only the header button; afterwards it is also the
        // card's title, so this has to run first.
        composeRule.onNodeWithText(newGroup).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(name)
    }

    @Test
    fun typedGroupNameSurvivesActivityRecreation() {
        install()
        openCardAndType("Road trips")

        composeRule.activityRule.scenario.recreate()
        install()

        // The open flag is ViewModel-owned and survives recreation; the text has to as well.
        composeRule.onNodeWithText("Road trips").assertIsDisplayed()
    }

    @Test
    fun repeatSubmitWhileTheWriteIsInFlightCreatesOneGroup() {
        gate = CompletableDeferred()
        install()
        openCardAndType("Road trips")

        composeRule.onNodeWithText(create).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        // Busy: the button is a spinner and disabled, so IME Done is the only way to submit
        // again — and it is not gated by the button's state.
        composeRule.onNode(hasSetTextAction()).performImeAction()
        composeRule.waitForIdle()
        assertEquals(listOf("Road trips"), createdGroups)

        gate!!.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(newGroup).assertIsDisplayed() // the header button; the card is gone
        assertEquals(listOf("Road trips"), createdGroups)
    }

    @Test
    fun repeatSubmitDuringTheExitAnimationCreatesOneGroup() {
        install()
        openCardAndType("Road trips")

        // Freeze the clock so the card, once closed, stays composed and tappable mid-exit.
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText(create).performClick()
        composeRule.mainClock.advanceTimeByFrame() // recompose: start the exit animation
        composeRule.mainClock.advanceTimeBy(50)

        // Still composed, button enabled again (name is non-blank, write finished): exactly the
        // window in which a second tap must not insert a duplicate group.
        composeRule.onNodeWithText(create).assertIsEnabled().performClick()
        composeRule.onNode(hasSetTextAction()).performImeAction()
        composeRule.mainClock.advanceTimeBy(500)

        assertEquals(listOf("Road trips"), createdGroups)
    }

    @Test
    fun aFailedCreateKeepsTheCardAndTheTypedName() {
        // createGroup throwing is exercised at the ViewModel level; here the screen must show the
        // draft still in place afterwards, with the button usable again for a retry.
        activeRepository = object : SongRepository by stubSongRepository() {
            override suspend fun createGroup(name: String) {
                createdGroups += name
                throw RuntimeException("disk full")
            }
        }
        install()
        openCardAndType("Road trips")

        composeRule.onNodeWithText(create).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Road trips").assertIsDisplayed()
        composeRule.onNodeWithText(create).assertIsEnabled()
        assertEquals(listOf("Road trips"), createdGroups)
    }
}

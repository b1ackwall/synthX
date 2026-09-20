package com.v1per.synthx.ui.screens.createplaylist

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.ui.testing.stubSongRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

/**
 * P8-T7 / B05: the playlist name and selected group have to survive the activity being recreated
 * (a font-scale, locale or dark-mode change; the activity is portrait-locked, so not rotation).
 * Both used to be plain `remember`, so the ViewModel came back but the form it belonged to was empty.
 *
 * This is the app's first recreation test. The pattern (see [install]): content goes onto the
 * activity with `activity.setContent`, `scenario.recreate()` builds a new activity, and the same
 * `install()` puts the same content on it. Two traps, both found the hard way:
 *  - The rule's own `setContent` refuses a second call, so the new activity is populated directly.
 *  - `rememberSaveable` keys off the composite hash of its position in the composition, and a
 *    `setContent { … }` lambda at a different source line is a different position. Both installs
 *    therefore have to go through the *same* call site, or nothing restores and the test would
 *    pass or fail for the wrong reason.
 * The ViewModelStore is retained across `recreate()`, as in production, so only what
 * `rememberSaveable` owns can bring the form back.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CreatePlaylistRecreationInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val created = mutableListOf<Pair<String, String?>>()

    private val repository: SongRepository = stubSongRepository(
        groups = listOf(PlaylistGroup(id = "g1", name = "Group One"), PlaylistGroup(id = "g2", name = "Group Two")),
    ).let { stub ->
        object : SongRepository by stub {
            override suspend fun createPlaylist(name: String, groupId: String?): String {
                created += name to groupId
                return "new-id"
            }
        }
    }

    @Composable
    private fun Screen() {
        val vm = viewModel<CreatePlaylistViewModel>(
            factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CreatePlaylistViewModel(repository) as T
            },
        )
        CreatePlaylistScreen(topBar = {}, onCreated = {}, viewModel = vm)
    }

    /** The one call site both installs share — see the class comment. */
    private fun install() {
        composeRule.runOnUiThread { composeRule.activity.setContent { Screen() } }
    }

    @Test
    fun nameAndSelectedGroupSurviveActivityRecreation() {
        install()
        composeRule.waitUntilExactlyOneExists(androidx.compose.ui.test.hasText("Group One"))

        composeRule.onNode(hasSetTextAction()).performTextInput("Road Trip")
        composeRule.onNodeWithText("Group Two").performClick()

        composeRule.activityRule.scenario.recreate()
        install()
        composeRule.waitUntilExactlyOneExists(androidx.compose.ui.test.hasText("Group One"))

        composeRule.onNodeWithText("Road Trip").assertIsDisplayed()
        // The selection has no semantics of its own to assert on, so prove it by what gets
        // submitted: the restored name *and* the restored group must reach createPlaylist.
        composeRule.onNodeWithText(context.getString(R.string.action_create_playlist)).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("Road Trip" to "g2"), created)
    }
}

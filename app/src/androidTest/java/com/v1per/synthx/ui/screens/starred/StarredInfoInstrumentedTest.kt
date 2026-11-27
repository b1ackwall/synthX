package com.v1per.synthx.ui.screens.starred

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.ui.MainScaffold
import com.v1per.synthx.ui.ShellTestTags
import com.v1per.synthx.ui.components.SynthXAlertDialog
import com.v1per.synthx.ui.components.SynthXButtonRole
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.assertDialogButton
import com.v1per.synthx.ui.testing.dialogButton
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The info action on the Starred route's top bar and the dialog it opens, in the real shell: the action's label
 * and touch target, the dialog's text and lone Primary Close, the three ways out, the open dialog surviving a
 * recreation, and the action in the empty state. One test draws the dialog's own body and Close at 130% font
 * scale in a short window's dialog height, set inside the dialog because its window takes no font scale from its
 * host.
 */
@RunWith(AndroidJUnit4::class)
class StarredInfoInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val database get() = SynthXDatabase.getInstance(shell.context)

    // Read first: initializing a destination object before Screen itself leaves it null in Screen's own init.
    private val home = Screen.Home.route

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() = shell.tearDown()

    private fun string(id: Int): String = shell.context.getString(id)

    private val title get() = string(R.string.starred_info_dialog_title)
    private val close get() = string(R.string.action_close)
    private val paragraphs get() = listOf(
        string(R.string.starred_info_dialog_message_arrivals),
        shell.context.getString(
            R.string.starred_info_dialog_message_graduate,
            string(R.string.actions_remove_from_starred),
        ),
        string(R.string.starred_info_dialog_message_audience),
    )

    private fun infoAction(): SemanticsNodeInteraction =
        composeRule.onNode(hasContentDescription(string(R.string.cd_starred_info)) and hasClickAction())

    private fun openStarred() {
        check(home.isNotEmpty())
        shell.startShell()
        shell.navigate(Screen.Starred.route)
        shell.awaitText(string(R.string.screen_starred))
    }

    private fun openDialog() {
        openStarred()
        infoAction().performClick()
        shell.awaitText(title)
    }

    private fun awaitDialogGone() = composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.textCount(title) == 0 }

    @Test fun theInfoActionIsLabelledAboutStarredWithAFullTouchTarget() {
        openStarred()
        val touch = infoAction().assertIsDisplayed().fetchSemanticsNode().touchBoundsInRoot
        val minimum = with(composeRule.density) { MIN_TOUCH_TARGET.toPx() }
        assertTrue("touch height ${touch.height}px is at least ${minimum}px", touch.height >= minimum)
        assertTrue("touch width ${touch.width}px is at least ${minimum}px", touch.width >= minimum)
    }

    @Test fun aTapOpensTheDialogWithItsTitleBodyAndALonePrimaryClose() {
        openDialog()
        composeRule.onNodeWithText(title).assertIsDisplayed()
        paragraphs.forEach { composeRule.onNodeWithText(it).assertExists() }
        composeRule.assertDialogButton(close, SynthXButtonRole.Primary)
        assertEquals(Screen.Starred.route, shell.route())
    }

    @Test fun closeDismissesTheDialog() {
        openDialog()
        composeRule.dialogButton(close).performClick()
        awaitDialogGone()
        assertEquals(Screen.Starred.route, shell.route())
        infoAction().assertIsDisplayed()
    }

    @Test fun backDismissesTheDialogAndStaysOnStarred() {
        openDialog()
        shell.pressBackKey()
        awaitDialogGone()
        assertEquals("Back closes the dialog, not the screen", Screen.Starred.route, shell.route())
    }

    @Test fun aTapOutsideTheDialogDismissesIt() {
        openDialog()
        val metrics = shell.context.resources.displayMetrics
        tapScreenAt(x = OUTSIDE_EDGE_PX, y = metrics.heightPixels / 2f)
        awaitDialogGone()
        assertEquals(Screen.Starred.route, shell.route())
    }

    @Test fun anOpenDialogSurvivesRecreation() {
        check(home.isNotEmpty())
        shell.installPlayback(FakePlaybackEngine())
        val restoration = StateRestorationTester(composeRule)
        lateinit var navController: NavHostController
        restoration.setContent {
            SynthXTheme {
                navController = rememberNavController()
                MainScaffold(navController, startDestination = Screen.Home.route)
            }
        }
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.tagCount(ShellTestTags.NavHost) > 0 }
        composeRule.runOnUiThread { navController.navigate(Screen.Starred.route) }
        shell.awaitText(string(R.string.screen_starred))
        infoAction().performClick()
        shell.awaitText(title)

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        shell.awaitText(title)
        assertEquals(Screen.Starred.route, composeRule.runOnIdle { navController.currentDestination?.route })
        paragraphs.forEach { composeRule.onNodeWithText(it).assertExists() }
        composeRule.dialogButton(close).performClick()
        awaitDialogGone()
    }

    @Test fun atA130PercentFontScaleInAShortWindowTheBodyScrollsAndCloseStaysReachable() {
        var closes = 0
        composeRule.setContent {
            SynthXTheme {
                SynthXAlertDialog(
                    onDismissRequest = {},
                    modifier = Modifier.heightIn(max = SHORT_WINDOW_DIALOG_HEIGHT),
                    title = { Scaled { Text(title) } },
                    text = { Scaled { StarredInfoBody() } },
                    confirmButton = { Scaled { StarredInfoCloseButton(onClick = { closes++ }) } },
                )
            }
        }
        composeRule.waitForIdle()

        val scrollRange = SemanticsProperties.VerticalScrollAxisRange
        val body = composeRule.onNode(SemanticsMatcher.keyIsDefined(scrollRange)).fetchSemanticsNode()
        assertTrue("the body overflows, so it has to scroll", body.config[scrollRange].maxValue() > 0f)
        composeRule.dialogButton(close).assertIsDisplayed()

        composeRule.onNodeWithText(paragraphs.last()).performScrollTo().assertIsDisplayed()
        composeRule.dialogButton(close).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals("Close is reachable and taps", 1, closes) }
    }

    @Test fun theInfoActionShowsInTheEmptyStateToo() = withNoStarredSongs {
        openStarred()
        shell.awaitText(string(R.string.empty_no_starred))
        infoAction().assertIsDisplayed().performClick()
        shell.awaitText(title)
    }

    /** Draws [content] at 130%, set inside the dialog because its window takes no font scale from its host. */
    @Composable
    private fun Scaled(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, FONT_SCALE), content = content)
    }

    /**
     * Runs [block] with an empty Starred list. Any song the device's library has starred is un-starred first and
     * starred again afterwards, so the library is left as it was found.
     */
    private fun withNoStarredSongs(block: () -> Unit) {
        val db = database.openHelper.writableDatabase
        val starred = db.query("SELECT id FROM songs WHERE in_inbox = 1").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        db.execSQL("UPDATE songs SET in_inbox = 0 WHERE in_inbox = 1")
        try {
            block()
        } finally {
            starred.chunked(SQL_BATCH).forEach { ids ->
                db.execSQL(
                    "UPDATE songs SET in_inbox = 1 WHERE id IN (${ids.joinToString { "?" }})",
                    ids.toTypedArray(),
                )
            }
        }
    }

    /** A real touchscreen tap through the input system, so it reaches whichever window is on top. */
    private fun tapScreenAt(x: Float, y: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + TAP_MS).forEach { (action, time) ->
            val event = MotionEvent.obtain(down, time, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("injected $action", automation.injectInputEvent(event, true))
            event.recycle()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        val MIN_TOUCH_TARGET = 48.dp

        // A short window, such as half of a split screen. MainActivity is locked to portrait, so on a phone this,
        // not landscape, is what leaves the dialog this little height.
        val SHORT_WINDOW_DIALOG_HEIGHT = 360.dp
        const val FONT_SCALE = 1.3f
        const val OUTSIDE_EDGE_PX = 4f
        const val TAP_MS = 50L
        const val SQL_BATCH = 500
    }
}

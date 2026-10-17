package com.v1per.synthx.ui.screens.home

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home has one content state and reads no data: its screen tag and the synthX wordmark. */
@RunWith(AndroidJUnit4::class)
class HomeScreenInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    @Test fun homeShowsTheWordmark() {
        composeRule.setContent { SynthXTheme { HomeScreen() } }
        composeRule.onNodeWithTag(PerfTestTags.HomeScreen).assertExists()
        composeRule.onNodeWithText("synthX").assertExists()
    }
}

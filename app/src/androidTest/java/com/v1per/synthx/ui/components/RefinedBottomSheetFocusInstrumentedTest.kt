package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opening any [RefinedBottomSheet] must take focus away from a focused text field, so the IME
 * doesn't stay up over the sheet (Search field + song 3-dot sheet; playlist rename field +
 * group picker). IME visibility itself isn't reliably assertable in Compose tests, so this
 * asserts focus state, which is what keeps the keyboard from staying up or coming back.
 */
@RunWith(AndroidJUnit4::class)
class RefinedBottomSheetFocusInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Composable
    private fun Host(showSheet: Boolean, onDismiss: () -> Unit) {
        val sheetState = rememberRefinedBottomSheetState()
        Box {
            Column {
                TextField(value = "q", onValueChange = {}, modifier = Modifier.testTag("field"))
            }
            if (showSheet) {
                RefinedBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
                    Text("sheet")
                }
            }
        }
    }

    @Test fun openingSheetClearsFocusFromTextField() {
        var show by mutableStateOf(false)
        composeRule.setContent { Host(showSheet = show, onDismiss = { show = false }) }

        composeRule.onNodeWithTag("field").performClick()
        composeRule.onNodeWithTag("field").assertIsFocused()

        show = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("field").assertIsNotFocused()
    }

    @Test fun focusDoesNotReturnToFieldAfterSheetCloses() {
        var show by mutableStateOf(false)
        composeRule.setContent { Host(showSheet = show, onDismiss = { show = false }) }

        composeRule.onNodeWithTag("field").performClick()
        show = true
        composeRule.waitForIdle()
        show = false
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("field").assertIsNotFocused()
    }

    @Test fun openingSheetWithNothingFocusedIsHarmless() {
        var show by mutableStateOf(false)
        composeRule.setContent { Host(showSheet = show, onDismiss = { show = false }) }

        show = true
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("field").assertIsNotFocused()
    }
}

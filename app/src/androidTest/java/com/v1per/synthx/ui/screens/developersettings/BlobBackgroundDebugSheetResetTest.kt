package com.v1per.synthx.ui.screens.developersettings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.ui.components.BlobBackgroundDebugState
import com.v1per.synthx.ui.components.DefaultInitialBlobs
import com.v1per.synthx.ui.components.HomeBlobDebugStateHolder
import com.v1per.synthx.ui.components.rememberRefinedBottomSheetState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P5-T6 acceptance criteria: Developer Settings' blob-tuning holder is a process-lifetime
 * singleton with no reset control (R5-F11(b)) — a previously-disabled blob (`enabled = false`)
 * in particular is never restored on its own. This proves the sheet's new
 * "Reset" button genuinely restores every field, including that stuck-disabled blob.
 */
@RunWith(AndroidJUnit4::class)
class BlobBackgroundDebugSheetResetTest {
    @get:Rule val composeRule = createComposeRule()

    @After
    fun tearDown() {
        HomeBlobDebugStateHolder.state.value = BlobBackgroundDebugState()
    }

    @Test fun resetButtonRestoresDefaultsIncludingADisabledBlob() {
        // Simulate prior developer tuning: a disabled blob plus a changed base hue.
        HomeBlobDebugStateHolder.state.value = BlobBackgroundDebugState(
            blobs = DefaultInitialBlobs.mapIndexed { index, blob ->
                blob.copy(enabled = index != 0, hue = 300f)
            },
            baseSurfaceHue = 40f,
            blurRadiusDp = 500f,
        )

        composeRule.setContent {
            BlobBackgroundDebugSheet(
                sheetState = rememberRefinedBottomSheetState(),
                onDismissRequest = {},
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Reset").performClick()
        composeRule.waitForIdle()

        assertEquals(BlobBackgroundDebugState(), HomeBlobDebugStateHolder.state.value)
    }
}

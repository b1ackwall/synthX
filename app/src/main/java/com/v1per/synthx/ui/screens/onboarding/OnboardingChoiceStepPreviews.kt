package com.v1per.synthx.ui.screens.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.v1per.synthx.ui.theme.SynthXTheme

@Preview(name = "Onboarding welcome", showBackground = true)
@Composable
private fun ChoiceStepPreview() {
    SynthXTheme { ChoiceStep(onFreshInstall = {}, onRecoverFromBackup = {}) }
}

// 840dp is past the 600dp pair breakpoint and the 480dp content cap: buttons side by side, column centred.
@Preview(name = "Onboarding welcome, tablet width", showBackground = true, widthDp = 840, heightDp = 1280)
@Composable
private fun ChoiceStepTabletPreview() {
    SynthXTheme { ChoiceStep(onFreshInstall = {}, onRecoverFromBackup = {}) }
}

package com.v1per.synthx.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.ui.screens.onboarding.OnboardingScreen
import java.util.UUID

/**
 * First-run wizard route — the [SynthXNavHost] start destination when
 * [com.v1per.synthx.shouldShowOnboarding] is true (onboarding not complete, or a first-scan
 * request still pending), Home otherwise. Resolved once in MainActivity, see [SynthXNavHost]'s
 * startDestination parameter. Onboarding is complete when the first scan starts, so a process
 * death mid-scan resumes the wizard until the scan is accepted or abandoned. The wizard ends in
 * two places: the scanning step, once the first scan finishes, and the recovery branch, once a
 * restore succeeds. Both navigate to Home and pop the wizard off the back stack. Not wrapped in
 * [RouteContent]/chromeBottomPadding like the other route groups — this route is never in
 * MainScaffold's bottomBarVisibleRoutes set, so it never reserves that space in the first place.
 */
fun NavGraphBuilder.onboardingRoutes(navController: NavHostController, onRestoreTerminalShown: (UUID) -> Unit = {}) {
    composable(Screen.Onboarding.route) {
        OnboardingScreen(
            onOnboardingComplete = {
                // Pops the whole wizard off the back stack so pressing back from Home can
                // never return to it.
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Onboarding.route) { inclusive = true }
                }
            },
            onRestoreTerminalShown = onRestoreTerminalShown,
        )
    }
}

package com.v1per.synthx.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.ui.screens.onboarding.OnboardingScreen
import java.util.UUID

/**
 * First-run wizard route — the [SynthXNavHost] start destination whenever
 * [com.v1per.synthx.preferences.PreferencesManager.musicFolderUri] is null (resolved once in
 * MainActivity, see [SynthXNavHost]'s startDestination parameter). Not wrapped in
 * [RouteContent]/chromeBottomPadding like the other route groups — this route is never in
 * MainScaffold's bottomBarRoutes set, so it never reserves that space in the first place.
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

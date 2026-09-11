package com.v1per.synthx.ui

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.v1per.synthx.R
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.ui.components.DetailTopBar
import com.v1per.synthx.ui.screens.issues.IssuesScreen
import com.v1per.synthx.ui.screens.issues.IssuesViewModel
import com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementScreen
import com.v1per.synthx.ui.screens.replacesong.ReplaceSongScreen

fun NavGraphBuilder.libraryManagementRoutes(
    navController: NavHostController,
    chromeBottomPadding: Dp,
) {
    composable(Screen.LibraryManagement.route) {
        RouteContent(chromeBottomPadding) {
            LibraryManagementScreen(
                topBar = {
                    DetailTopBar(
                        title = stringResource(R.string.screen_library_management),
                        onBack = { navController.popBackStack() },
                    )
                },
                onIssuesClick = {
                    navController.navigate(Screen.Issues.route)
                },
                onReplaceSongClick = {
                    navController.navigate(Screen.ReplaceSong.route)
                },
            )
        }
    }
    composable(Screen.ReplaceSong.route) {
        RouteContent(chromeBottomPadding) {
            // No DetailTopBar supplied from here, unlike every other route in this file: the
            // wizard's back button has to mean "previous step" on two of its three steps, so the
            // screen owns its own bar. See ReplaceSongScreen's kdoc.
            ReplaceSongScreen(
                onExit = { navController.popBackStack() },
            )
        }
    }
    composable(Screen.Issues.route) {
        RouteContent(chromeBottomPadding) {
            val issuesViewModel = viewModel<IssuesViewModel>(
                factory = IssuesViewModel.Factory(LocalContext.current),
            )
            IssuesScreen(
                topBar = {
                    DetailTopBar(
                        title = stringResource(R.string.screen_issues),
                        onBack = { navController.popBackStack() },
                    )
                },
                onNavigateToBackups = {
                    navController.navigate(Screen.BackupsAndRestore.route)
                },
                viewModel = issuesViewModel,
            )
        }
    }
}

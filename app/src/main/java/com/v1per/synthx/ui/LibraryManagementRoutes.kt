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
                viewModel = issuesViewModel,
            )
        }
    }
}

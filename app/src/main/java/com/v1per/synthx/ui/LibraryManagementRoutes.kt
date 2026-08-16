package com.v1per.synthx.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.v1per.synthx.R
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.ui.common.isReady
import com.v1per.synthx.ui.components.DetailTopBar
import com.v1per.synthx.ui.screens.issues.IssuesScreen
import com.v1per.synthx.ui.screens.issues.IssuesViewModel
import com.v1per.synthx.ui.screens.librarymanagement.LibraryManagementScreen
import com.v1per.synthx.ui.screens.restore.RestoreScreen
import com.v1per.synthx.ui.screens.restore.RestoreViewModel

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
                onRestoreClick = {
                    navController.navigate(Screen.Restore.route)
                },
            )
        }
    }
    composable(Screen.Restore.route) {
        RouteContent(chromeBottomPadding) {
            val restoreViewModel = viewModel<RestoreViewModel>(
                factory = RestoreViewModel.Factory(LocalContext.current),
            )
            val uiState by restoreViewModel.uiState.collectAsState()
            // Disabled 2026-08-13: opaque backgrounds fix the double-composition issue this was
            // also masking; re-enable if cold-load flash becomes a problem.
            // ReadinessCoverOverlay(isReady = uiState.isReady()) {
                RestoreScreen(
                    topBar = {
                        DetailTopBar(
                            title = stringResource(R.string.screen_restore),
                            onBack = { navController.popBackStack() },
                        )
                    },
                    viewModel = restoreViewModel,
                )
            // }
        }
    }
    composable(Screen.Issues.route) {
        RouteContent(chromeBottomPadding) {
            val issuesViewModel = viewModel<IssuesViewModel>(
                factory = IssuesViewModel.Factory(LocalContext.current),
            )
            val uiState by issuesViewModel.uiState.collectAsState()
            // Disabled 2026-08-13: opaque backgrounds fix the double-composition issue this was
            // also masking; re-enable if cold-load flash becomes a problem.
            // ReadinessCoverOverlay(isReady = uiState.isReady()) {
                IssuesScreen(
                    topBar = {
                        DetailTopBar(
                            title = stringResource(R.string.screen_issues),
                            onBack = { navController.popBackStack() },
                        )
                    },
                    viewModel = issuesViewModel,
                )
            // }
        }
    }
}

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
import com.v1per.synthx.ui.screens.backupsandrestore.BackupsAndRestoreScreen
import com.v1per.synthx.ui.screens.restore.RestoreScreen
import com.v1per.synthx.ui.screens.restore.RestoreViewModel
import java.util.UUID

fun NavGraphBuilder.backupsAndRestoreRoutes(
    navController: NavHostController,
    chromeBottomPadding: Dp,
    onRestoreTerminalShown: (UUID) -> Unit = {},
) {
    composable(Screen.BackupsAndRestore.route) {
        RouteContent(chromeBottomPadding) {
            BackupsAndRestoreScreen(
                topBar = {
                    DetailTopBar(
                        title = stringResource(R.string.screen_backups_and_restore),
                        onBack = { navController.popBackStack() },
                    )
                },
                onRestoreClick = {
                    navController.navigate(Screen.Restore.route)
                },
                onManageLibraryClick = {
                    navController.navigate(Screen.LibraryManagement.route)
                },
            )
        }
    }
    composable(Screen.Restore.route) {
        RouteContent(chromeBottomPadding) {
            val restoreViewModel = viewModel<RestoreViewModel>(
                factory = RestoreViewModel.Factory(LocalContext.current),
            )
            RestoreScreen(
                topBar = {
                    DetailTopBar(
                        title = stringResource(R.string.screen_restore),
                        onBack = { navController.popBackStack() },
                    )
                },
                viewModel = restoreViewModel,
                onTerminalOutcomeShown = onRestoreTerminalShown,
            )
        }
    }
}

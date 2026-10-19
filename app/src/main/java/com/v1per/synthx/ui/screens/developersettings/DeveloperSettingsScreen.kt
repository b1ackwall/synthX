package com.v1per.synthx.ui.screens.developersettings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.ui.components.SettingsDetailScaffold

@Composable
fun DeveloperSettingsScreen(
    topBar: @Composable () -> Unit,
    viewModel: DeveloperSettingsViewModel = viewModel(
        factory = DeveloperSettingsViewModel.Factory(LocalContext.current),
    ),
) {
    val rootOrganizationCheckStatus by viewModel.rootOrganizationCheckStatus.collectAsState()
    val isCheckingRootOrganization by viewModel.isCheckingRootOrganization.collectAsState()
    val rootOrganizationCheckResult by viewModel.rootOrganizationCheckResult.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            SettingsDetailScaffold(topBar = topBar) {
                VerifyRootOrganizationSection(
                    checkStatus = rootOrganizationCheckStatus,
                    isChecking = isCheckingRootOrganization,
                    result = rootOrganizationCheckResult,
                    onCheckRootLibraryOrganization = viewModel::checkRootLibraryOrganization,
                    onDismissResult = viewModel::dismissRootOrganizationCheckResult,
                )
            }
        }
    }
}

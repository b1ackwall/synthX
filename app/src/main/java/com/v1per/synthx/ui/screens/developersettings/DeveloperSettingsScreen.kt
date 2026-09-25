package com.v1per.synthx.ui.screens.developersettings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.ui.components.SettingsDetailScaffold
import com.v1per.synthx.ui.components.hiddenFromAccessibilityWhileSheetShown
import com.v1per.synthx.ui.components.rememberRefinedBottomSheetState
import com.v1per.synthx.ui.screens.settings.SettingsRow
import kotlinx.coroutines.launch

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
    var showBlobDebugSheet by remember { mutableStateOf(false) }
    val blobDebugSheetState = rememberRefinedBottomSheetState()
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hiddenFromAccessibilityWhileSheetShown(showBlobDebugSheet),
        ) {
            SettingsDetailScaffold(topBar = topBar) {
                SettingsRow(
                    title = "Tune blob background",
                    onClick = { showBlobDebugSheet = true },
                )
                VerifyRootOrganizationSection(
                    checkStatus = rootOrganizationCheckStatus,
                    isChecking = isCheckingRootOrganization,
                    result = rootOrganizationCheckResult,
                    onCheckRootLibraryOrganization = viewModel::checkRootLibraryOrganization,
                    onDismissResult = viewModel::dismissRootOrganizationCheckResult,
                )
            }
        }

        if (showBlobDebugSheet) {
            BlobBackgroundDebugSheet(
                sheetState = blobDebugSheetState,
                onDismissRequest = {
                    scope.launch {
                        blobDebugSheetState.hide()
                        showBlobDebugSheet = false
                    }
                },
            )
        }
    }
}

package com.v1per.synthx.ui.screens.librarymanagement

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R

/** Badge appears only when there is something to report; the row itself is always present. */
@Composable
internal fun IssuesRow(count: Int, onClick: () -> Unit) {
    ManagementRow(
        title = stringResource(R.string.settings_issues),
        trailingText = if (count > 0) count.toString() else null,
        onClick = onClick,
    )
}

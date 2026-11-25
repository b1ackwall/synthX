package com.v1per.synthx.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R

/** Confirmation for deleting a group, matching [DeletePlaylistDialog]'s pattern. */
@Composable
fun DeleteGroupDialog(groupName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SynthXAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_group_dialog_title)) },
        text = { Text(stringResource(R.string.delete_group_dialog_message, groupName)) },
        confirmButton = {
            SynthXDestructiveButton(
                onClick = onConfirm,
                width = SynthXButtonWidth.Dialog,
            ) {
                Text(text = stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            SynthXSecondaryButton(
                onClick = onDismiss,
                width = SynthXButtonWidth.Dialog,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

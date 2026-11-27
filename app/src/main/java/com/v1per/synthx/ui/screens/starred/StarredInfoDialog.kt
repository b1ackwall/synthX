package com.v1per.synthx.ui.screens.starred

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.ui.components.SynthXAlertDialog
import com.v1per.synthx.ui.components.SynthXButtonWidth
import com.v1per.synthx.ui.components.SynthXPrimaryButton
import com.v1per.synthx.ui.theme.Spacing

/**
 * Explains the Starred list and how a song leaves it, opened from the info action on the Starred
 * route's top bar. One lone Primary Close; back and a tap outside the dialog dismiss it as well.
 */
@Composable
internal fun StarredInfoDialog(onDismiss: () -> Unit) {
    SynthXAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.starred_info_dialog_title)) },
        text = { StarredInfoBody() },
        confirmButton = { StarredInfoCloseButton(onClick = onDismiss) },
    )
}

/**
 * The three paragraphs, in a column that scrolls once it no longer fits (a large font scale, a
 * short window such as split screen). Separate from [StarredInfoDialog], like
 * [StarredInfoCloseButton], so a test can draw both at a scaled font inside the dialog window,
 * which takes no font scale from its host.
 *
 * The middle paragraph quotes the menu row through a format argument, so it always reads the
 * row's real label.
 */
@Composable
internal fun StarredInfoBody() {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.starred_info_dialog_message_arrivals))
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            stringResource(
                R.string.starred_info_dialog_message_graduate,
                stringResource(R.string.actions_remove_from_starred),
            ),
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(stringResource(R.string.starred_info_dialog_message_audience))
    }
}

/** The dialog's only button: a Dialog-width Primary Close, as in GraduationProgressDialog. */
@Composable
internal fun StarredInfoCloseButton(onClick: () -> Unit) {
    SynthXPrimaryButton(onClick = onClick, width = SynthXButtonWidth.Dialog) {
        Text(stringResource(R.string.action_close))
    }
}

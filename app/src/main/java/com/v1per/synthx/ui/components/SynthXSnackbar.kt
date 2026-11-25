package com.v1per.synthx.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.v1per.synthx.ui.theme.ButtonBorder
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Strokes
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary

/** The app's snackbar host: Material's SnackbarHost showing every snackbar as a [SynthXSnackbar]. */
@Composable
fun SynthXSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data -> SynthXSnackbar(data) }
}

/**
 * One snackbar: a SurfaceVariant box with the Secondary button's 1dp border, 2dp corners and a TextPrimary
 * message, kept 12dp from the host's edges as Material's default snackbar is. The optional action is a
 * Tertiary Dialog-width button; a tap on it reports [SnackbarData.performAction], which is what a caller
 * waiting on showSnackbar receives as ActionPerformed. There is no dismiss action.
 */
@Composable
fun SynthXSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.small
    val actionLabel = data.visuals.actionLabel
    Snackbar(
        modifier = modifier
            .padding(Spacing.ItemSpacing)
            .border(Strokes.ButtonBorder, ButtonBorder, shape),
        action = actionLabel?.let { label ->
            {
                SynthXTextButton(onClick = { data.performAction() }, width = SynthXButtonWidth.Dialog) {
                    Text(text = label)
                }
            }
        },
        shape = shape,
        containerColor = SurfaceVariant,
        contentColor = TextPrimary,
        actionContentColor = TextPrimary,
    ) {
        Text(text = data.visuals.message)
    }
}

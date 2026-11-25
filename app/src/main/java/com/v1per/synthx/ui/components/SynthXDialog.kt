package com.v1per.synthx.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Every dialog in the app: Material's AlertDialog on the app Surface, with a TextPrimary title, TextSecondary
 * body and SynthX buttons. The parameters mirror AlertDialog's.
 *
 * AlertDialog itself always receives one child in its confirm slot and no dismiss slot, so its own button flow
 * (an 8dp row that wraps with the confirm button 12dp above the dismiss one, each at its own width) never runs.
 * With [dismissButton] set, the child is a [SynthXButtonPair]: [confirmButton] is the emphasised button, at the
 * end side by side and on top when stacked, and stacked buttons share one width and are drawn 8dp apart.
 * Without it, [confirmButton] is the child as it is: a lone button or a [SynthXDialogButtonStack]. Either way the
 * dialog's button row aligns it to the end.
 *
 * [confirmButton] and [dismissButton] each emit exactly one Dialog-width SynthX button (or, for [confirmButton]
 * alone, one stack). Choose between a pair, a stack and a lone button where the dialog is built, never inside
 * a slot, because a slot that emits no button or two fails as soon as the dialog shows.
 */
@Composable
fun SynthXAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            if (dismissButton == null) {
                confirmButton()
            } else {
                SynthXButtonPair(primary = confirmButton, secondary = dismissButton)
            }
        },
        modifier = modifier,
        dismissButton = null,
        title = title,
        text = text,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
    )
}

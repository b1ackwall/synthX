package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary
import androidx.compose.ui.res.stringResource

/**
 * Whether a typed confirmation matches the word it is gating on.
 *
 * Case-**sensitive** on purpose: the whole point of the gate is that the word cannot be produced
 * by muscle memory, and accepting "delete" for "Delete" gives most of that back. Surrounding
 * whitespace is tolerated because a soft keyboard's auto-space after a word is not the user
 * failing to confirm.
 *
 * A pure function, separate from [TypedConfirmationField] so callers can drive a button's `enabled`
 * from it without duplicating the rule — and so the rule itself is unit-testable.
 */
fun typedConfirmationMatches(typed: String, requiredWord: String): Boolean = typed.trim() == requiredWord

/**
 * The text field half of a typed confirmation: the user must type [requiredWord] exactly before
 * the destructive action it guards becomes available.
 *
 * [requiredWord] is always the confirming button's own label, passed in by the caller from the
 * same string resource that labels it, so the two can never drift apart (and so a future
 * translation changes both together).
 *
 * Stateless — the caller owns [value] because it also owns the button whose `enabled` depends on
 * it; see [typedConfirmationMatches].
 */
@Composable
fun TypedConfirmationField(
    requiredWord: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = {
            Text(
                text = stringResource(R.string.typed_confirmation_label, requiredWord),
                color = TextSecondary,
            )
        },
        placeholder = { Text(text = requiredWord, color = TextTertiary) },
        singleLine = true,
        // No autocorrect/autocapitalize: both actively fight an exact-match gate, and a keyboard
        // "helpfully" capitalising the first letter would otherwise make the word un-typeable
        // lowercase (and vice versa) depending on the required word.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TextSecondary,
            cursorColor = TextPrimary,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
        ),
        shape = MaterialTheme.shapes.small,
    )
}

package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.TextTertiary

/**
 * Shell for the "3-dot menu -> optional confirm dialog" pattern repeated at every row that
 * offers a destructive action behind a dropdown (playlist rows, missing-song rows). Only the
 * shell — trigger button, dropdown wiring, confirm-dialog show/hide state — is shared here;
 * each call site supplies its own menu items and its own confirm dialog content, so the
 * specific actions each row offers stay local to that row.
 *
 * @param menuItems receives `closeMenu` (dismiss without confirming, for direct actions like
 *   rename) and `requestConfirm` (dismiss the menu and open the confirm dialog, for the
 *   destructive action).
 * @param confirmDialog receives `onConfirm`/`onDismiss` that close the confirm dialog; wrap
 *   `onConfirm` with the caller's own destructive action, e.g. `{ onConfirm(); onDelete() }`.
 *
 * There was also an `extraMenuContent` escape hatch here, for a second dropdown anchored at the
 * same button — PlaylistRow's nested "move to group" submenu was its only user, and that is now
 * a bottom sheet ([GroupPickerBottomSheet]), so the hatch is gone with it.
 */
@Composable
internal fun ThreeDotMenuWithConfirm(
    modifier: Modifier = Modifier,
    tint: Color = TextTertiary,
    menuItems: @Composable (closeMenu: () -> Unit, requestConfirm: () -> Unit) -> Unit,
    confirmDialog: @Composable (onConfirm: () -> Unit, onDismiss: () -> Unit) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { showMenu = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.cd_more_options),
                tint = tint,
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            menuItems(
                { showMenu = false },
                {
                    showMenu = false
                    showConfirm = true
                },
            )
        }
    }

    if (showConfirm) {
        confirmDialog(
            { showConfirm = false },
            { showConfirm = false },
        )
    }
}

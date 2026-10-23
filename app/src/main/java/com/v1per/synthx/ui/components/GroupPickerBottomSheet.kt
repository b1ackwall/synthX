package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.PlaylistGroup
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.IconSizes
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Strokes
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextTertiary
// Aliased: the Material3 `Surface` composable owns the simple name in Compose files.
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

/**
 * Single-select list of playlist groups, hosted in a [RefinedBottomSheet] rather than a second
 * [androidx.compose.material3.DropdownMenu] anchored inside PlaylistRow's 3-dot menu, which
 * would have no scroll container of its own, no name truncation and no indication of where the
 * playlist already is.
 *
 * Deliberately a sibling of SongActionsBottomSheet's private `SubPage` rather than an extension
 * of it. `SubPage` is a *page* swapped inside an already-open multi-page sheet (see the
 * SheetPage enum's kdoc) whose whole contract is "every row is tappable and picking one acts
 * immediately"; this picker owns a sheet of its own and has to render one row as checked and
 * inert. Threading a nullable selected-id plus a disabled state through `SubPage` would make the
 * shared composable's behaviour conditional for the two callers (add-to-playlist, go-to-artist)
 * that will never use it, so the row recipe — caption, hairline, `Spacing.xxl` rows, `bodyLarge`
 * ellipsized names — is mirrored here instead and the two stay independently readable.
 *
 * Height is bounded by [RefinedBottomSheet]'s own cap, so a long group list scrolls inside the
 * [LazyColumn] rather than growing under the status bar.
 *
 * @param currentGroupId the group the playlist is already in, or null when it is ungrouped. Also
 *   selects the title: "Move to group" versus "Add to group", matching the menu item that opened
 *   this sheet.
 */
@Composable
internal fun GroupPickerBottomSheet(
    groups: List<PlaylistGroup>,
    currentGroupId: String?,
    sheetState: RefinedBottomSheetState,
    onGroupSelected: (groupId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    RefinedBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceColor,
        shape = MaterialTheme.shapes.small,
        showDragHandle = false,
    ) {
        Column {
            Text(
                text = if (currentGroupId == null) {
                    stringResource(R.string.action_add_to_group)
                } else {
                    stringResource(R.string.action_move_to_group)
                },
                style = MaterialTheme.typography.labelLarge,
                color = TextTertiary,
                modifier = Modifier.padding(
                    horizontal = Spacing.HorizontalPadding,
                    vertical = Spacing.sm,
                ),
            )
            HorizontalDivider(thickness = Strokes.Hairline, color = Hairline)

            if (groups.isEmpty()) {
                // Not reachable from either call site today — both gate the menu item on there
                // being at least one group — but the sheet shouldn't render as a bare caption if
                // the last group is deleted from under an open picker.
                Text(
                    text = stringResource(R.string.empty_no_groups),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextTertiary,
                    modifier = Modifier.padding(
                        horizontal = Spacing.HorizontalPadding,
                        vertical = Spacing.md,
                    ),
                )
            } else {
                LazyColumn {
                    items(groups, key = { it.id }) { group ->
                        GroupPickerRow(
                            name = group.name,
                            isCurrent = group.id == currentGroupId,
                            onClick = { onGroupSelected(group.id) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(Spacing.md))
        }
    }
}

/**
 * The group the playlist is already in gets no `clickable` at all — not a disabled one — so it
 * takes no ripple and no tap. It renders at full opacity on a [SurfaceVariant] background (same
 * treatment as SongActionsBottomSheet's already-a-member playlist row) so it stands out as the
 * current answer rather than receding like a disabled row. Picking it would be a
 * no-op write, so it is shown as the answer to "where is this playlist now" rather than as a
 * choice that silently does nothing.
 */
@Composable
private fun GroupPickerRow(name: String, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.xxl)
            .then(
                if (isCurrent) {
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(SurfaceVariant)
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .padding(horizontal = Spacing.HorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isCurrent) {
            Spacer(modifier = Modifier.width(Spacing.md))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.cd_current_group),
                tint = TextPrimary,
                modifier = Modifier.size(IconSizes.Standard),
            )
        }
    }
}

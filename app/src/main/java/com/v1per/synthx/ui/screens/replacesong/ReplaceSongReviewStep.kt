package com.v1per.synthx.ui.screens.replacesong

import androidx.annotation.StringRes
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.PlaylistConflictResolution
import com.v1per.synthx.data.repository.ReplaceSongPreview
import com.v1per.synthx.ui.components.SongIdentity
import com.v1per.synthx.ui.components.TypedConfirmationField
import com.v1per.synthx.ui.components.typedConfirmationMatches
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Strokes
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.ui.components.SynthXButtonWidth
import com.v1per.synthx.ui.components.SynthXDestructiveButton
import com.v1per.synthx.ui.components.SynthXPrimaryButton

/**
 * The dry-run review and destructive confirm.
 *
 * The confirm button is gated on typing the word "Replace", the same mechanism (and the same
 * [TypedConfirmationField]) the delete dialog uses, with the required word again read from the
 * button's own label resource so the two cannot drift.
 */
@Composable
internal fun ReplaceSongReviewStep(
    preview: ReplaceSongPreview,
    conflictResolution: PlaylistConflictResolution,
    confirmEnabled: Boolean,
    onConflictResolutionChange: (PlaylistConflictResolution) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requiredWord = stringResource(R.string.replace_song_action_replace)
    val destructiveActionCount by DestructiveActionTracker.count.collectAsState()
    var typedConfirmation by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel(R.string.replace_song_section_old)
            SongIdentity(song = preview.oldSong, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(Spacing.md))

            SectionLabel(R.string.replace_song_section_new)
            SongIdentity(song = preview.newSong, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(Spacing.md))

            HorizontalDivider(thickness = Strokes.Hairline, color = Hairline)

            PlaylistSection(
                labelRes = R.string.replace_song_section_transferring,
                names = preview.transferringPlaylists,
            )
            if (preview.hasConflicts) {
                PlaylistSection(
                    labelRes = R.string.replace_song_section_conflicts,
                    names = preview.conflictingPlaylists,
                )
                ConflictResolutionPicker(
                    selected = conflictResolution,
                    onSelect = onConflictResolutionChange,
                )
            }
            if (preview.newSongOnlyPlaylists.isNotEmpty()) {
                PlaylistSection(
                    labelRes = R.string.replace_song_section_new_only,
                    names = preview.newSongOnlyPlaylists,
                )
            }

            HorizontalDivider(thickness = Strokes.Hairline, color = Hairline)
            if (preview.replacementMovesToMusic) {
                Text(
                    text = stringResource(R.string.replace_song_review_will_move),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier
                        .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md)
                        .testTag(ReplaceSongReviewTestTags.WillMoveLine),
                )
            }
            Text(
                text = stringResource(R.string.replace_song_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = Error,
                modifier = Modifier.padding(
                    horizontal = Spacing.HorizontalPadding,
                    vertical = Spacing.md,
                ),
            )
            if (destructiveActionCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.destructive_action_warning_count,
                        destructiveActionCount,
                        destructiveActionCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Error,
                    modifier = Modifier.padding(horizontal = Spacing.HorizontalPadding),
                )
            }
            TypedConfirmationField(
                requiredWord = requiredWord,
                value = typedConfirmation,
                onValueChange = { typedConfirmation = it },
                enabled = confirmEnabled,
                modifier = Modifier.padding(horizontal = Spacing.HorizontalPadding),
            )
            Spacer(modifier = Modifier.height(Spacing.md))
        }

        SynthXDestructiveButton(
            onClick = onConfirm,
            width = SynthXButtonWidth.Standard,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(Spacing.HorizontalPadding)
                .testTag(ReplaceSongReviewTestTags.ConfirmButton),
            enabled = confirmEnabled && typedConfirmationMatches(typedConfirmation, requiredWord),
        ) {
            Text(text = requiredWord)
        }
    }
}

/** Same label treatment Library Management's SectionHeader and Search's section headers use. */
@Composable
private fun SectionLabel(@StringRes labelRes: Int, vararg formatArgs: Any) {
    Text(
        text = stringResource(labelRes, *formatArgs),
        style = MaterialTheme.typography.labelLarge,
        color = TextSecondary,
        modifier = Modifier.padding(
            horizontal = Spacing.HorizontalPadding,
            vertical = Spacing.sm,
        ),
    )
}

@Composable
private fun PlaylistSection(@StringRes labelRes: Int, names: List<String>) {
    SectionLabel(labelRes, names.size)
    if (names.isEmpty()) {
        Text(
            text = stringResource(R.string.replace_song_no_playlists),
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
            modifier = Modifier.padding(
                horizontal = Spacing.HorizontalPadding,
                vertical = Spacing.xs,
            ),
        )
        return
    }
    names.forEach { name ->
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.padding(
                horizontal = Spacing.HorizontalPadding,
                vertical = Spacing.xs,
            ),
        )
    }
}

/**
 * Rendered only when there is an actual conflict — with no playlist in common there is nothing for
 * the choice to apply to, and offering it anyway would imply the transfer itself was optional.
 */
@Composable
private fun ConflictResolutionPicker(
    selected: PlaylistConflictResolution,
    onSelect: (PlaylistConflictResolution) -> Unit,
) {
    Text(
        text = stringResource(R.string.replace_song_conflict_prompt),
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
        modifier = Modifier.padding(
            horizontal = Spacing.HorizontalPadding,
            vertical = Spacing.sm,
        ),
    )
    ConflictOption(
        option = PlaylistConflictResolution.KeepOld,
        selected = selected,
        labelRes = R.string.replace_song_conflict_keep_old,
        detailRes = R.string.replace_song_conflict_keep_old_detail,
        onSelect = onSelect,
    )
    ConflictOption(
        option = PlaylistConflictResolution.KeepNew,
        selected = selected,
        labelRes = R.string.replace_song_conflict_keep_new,
        detailRes = R.string.replace_song_conflict_keep_new_detail,
        onSelect = onSelect,
    )
    ConflictOption(
        option = PlaylistConflictResolution.Merge,
        selected = selected,
        labelRes = R.string.replace_song_conflict_merge,
        detailRes = R.string.replace_song_conflict_merge_detail,
        onSelect = onSelect,
    )
}

/**
 * One conflict choice. The whole row is the single radio-button target (`Role.RadioButton`), and
 * the [RadioButton] inside it only draws the state, so TalkBack reads one control per option.
 */
@Composable
private fun ConflictOption(
    option: PlaylistConflictResolution,
    selected: PlaylistConflictResolution,
    @StringRes labelRes: Int,
    @StringRes detailRes: Int,
    onSelect: (PlaylistConflictResolution) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = option == selected,
                interactionSource = interactionSource,
                indication = ripple(color = Accent),
                role = Role.RadioButton,
                onClick = { onSelect(option) },
            )
            .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.sm)
            .testTag(ReplaceSongReviewTestTags.conflictOption(option)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Off the neon accent, same reasoning as DeleteSongsRow's switch: the design system
        // reserves it for six specific contexts and this is not one of them.
        RadioButton(
            selected = option == selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = TextPrimary,
                unselectedColor = TextTertiary,
            ),
        )
        Column(modifier = Modifier.padding(start = Spacing.sm)) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            Text(
                text = stringResource(detailRes),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

/**
 * Full-bleed spinner and message, matching RestoreScreen's own cover: shown while the preview is
 * computed, and inside the working overlay while a confirmed replace runs. The message is a
 * polite live region, so TalkBack reads it as it appears and as it changes.
 */
@Composable
internal fun ReplaceSongBusyCover(@StringRes messageRes: Int) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = TextSecondary, strokeWidth = Strokes.ProgressRing)
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                // Polite: it must not cut off other speech, the same as onboarding's scan status.
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(ReplaceSongTestTags.BusyCoverMessage),
            )
        }
    }
}

/** Preview computation failed; [onRetry] is absent when repeating cannot repair the cause. */
@Composable
internal fun ReplaceSongPreviewFailure(message: String, onRetry: (() -> Unit)?) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Error,
                modifier = Modifier.padding(horizontal = Spacing.HorizontalPadding),
            )
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(Spacing.md))
                SynthXPrimaryButton(onClick = onRetry, width = SynthXButtonWidth.Compact) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

/** Test tags for the review step. */
internal object ReplaceSongReviewTestTags {
    const val WillMoveLine = "replace_song_review_will_move"
    const val ConfirmButton = "replace_song_confirm"

    fun conflictOption(option: PlaylistConflictResolution): String = "replace_song_conflict_${option.name}"
}

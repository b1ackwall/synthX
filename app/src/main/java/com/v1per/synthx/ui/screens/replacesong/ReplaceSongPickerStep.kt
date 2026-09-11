package com.v1per.synthx.ui.screens.replacesong

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.components.SongIdentity
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary
import kotlinx.collections.immutable.ImmutableList

/**
 * The song-picking half of the wizard, shared verbatim by both selection steps — they differ only
 * in their subtitle and in what the ViewModel already filtered out of [songs].
 *
 * Selecting a row is what locks that step in, so there is no separate confirm button here: a step
 * cannot be advanced past without a selection because making one *is* the advance.
 */
@Composable
internal fun ReplaceSongPickerStep(
    @StringRes subtitleRes: Int,
    query: String,
    songs: ImmutableList<Song>,
    onQueryChange: (String) -> Unit,
    onSelect: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(subtitleRes),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(
                horizontal = Spacing.HorizontalPadding,
                vertical = Spacing.md,
            ),
        )

        // Same field shape/colors SearchScreen uses — replicated rather than extracted from
        // behind that screen, which owns it inline alongside its filter chips and four result
        // sections and exposes nothing reusable.
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(text = stringResource(R.string.search_placeholder), color = TextTertiary)
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = TextSecondary,
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.cd_clear),
                            tint = TextSecondary,
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.sm),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TextSecondary,
                cursorColor = TextPrimary,
            ),
            shape = MaterialTheme.shapes.small,
        )

        if (songs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.replace_song_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary,
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(songs, key = { it.id }, contentType = { "song" }) { song ->
                ReplaceSongPickerRow(song = song, onClick = { onSelect(song) })
            }
        }
    }
}

/**
 * A selectable song row.
 *
 * Deliberately not [com.v1per.synthx.ui.components.SongRow]: that row taps to *play* and carries
 * an overflow button wired to the shared actions sheet, which inside a picker would offer the very
 * delete action this screen is a safer alternative to. It also drops its clickable entirely for a
 * missing song, which would make the song-to-replace step unable to select exactly the songs most
 * worth replacing. This reuses [SongIdentity] — art, title, artist, already used standalone by the
 * actions sheet — under the same ripple/divider treatment, so it still reads as the same list row.
 */
@Composable
private fun ReplaceSongPickerRow(song: Song, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Column {
        SongIdentity(
            song = song,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = Accent),
                    onClick = onClick,
                )
                .padding(vertical = Spacing.sm)
                // Dimmed the way SongRow dims a missing song, but still selectable — see this
                // composable's kdoc.
                .alpha(if (song.isMissing) Alpha.Disabled else 1f),
        )
        HorizontalDivider(thickness = 1.dp, color = Hairline)
    }
}

package com.v1per.synthx.ui.screens.addsongs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.v1per.synthx.R
import com.v1per.synthx.ui.components.GlitchPlaceholder
import com.v1per.synthx.ui.components.LoadingIndicator
import com.v1per.synthx.ui.components.crossfadeModel
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

@Composable
fun AddSongsScreen(
    topBar: @Composable () -> Unit,
    playlistId: String,
    onSubmitted: () -> Unit,
) {
    val viewModel: AddSongsViewModel = viewModel(
        key = playlistId,
        factory = AddSongsViewModel.factory(LocalContext.current, playlistId),
    )
    val songs by viewModel.songs.collectAsState()
    val submitted by viewModel.submitted.collectAsState()
    val error by viewModel.error.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isSubmitting by viewModel.isSubmitting.collectAsState()

    LaunchedEffect(submitted) {
        if (submitted) onSubmitted()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        topBar()

        // The search bar here is deliberately deferred (no filtering built yet) — rather than
        // showing a fully-interactive-looking OutlinedTextField that silently swallows taps,
        // it's simply not shown until search actually works. Re-add it alongside real filtering.

        Box(modifier = Modifier.weight(1f)) {
            when {
                isLoading -> LoadingIndicator()
                songs.isEmpty() -> Text(
                    text = stringResource(R.string.empty_no_songs),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = Spacing.HorizontalPadding),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(songs, key = { it.song.id }) { item ->
                        AddSongRow(
                            songItem = item,
                            onToggle = { viewModel.toggleSong(item.song.id) },
                        )
                    }
                }
            }
        }

        error?.let {
            Text(
                text = stringResource(errorStringRes(it)),
                style = MaterialTheme.typography.bodyMedium,
                color = Error,
                modifier = Modifier.padding(
                    horizontal = Spacing.HorizontalPadding,
                    vertical = Spacing.sm,
                ),
            )
        }

        Button(
            onClick = viewModel::submit,
            enabled = !isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.HorizontalPadding)
                .height(Spacing.xxl),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant),
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = TextPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = stringResource(R.string.action_done),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                )
            }
        }
    }
}

private fun errorStringRes(error: AddSongsViewModel.AddSongsError): Int = when (error) {
    AddSongsViewModel.AddSongsError.LoadSongsFailed -> R.string.error_load_songs
    AddSongsViewModel.AddSongsError.LoadPlaylistFailed -> R.string.error_load_playlist
    AddSongsViewModel.AddSongsError.SaveFailed -> R.string.error_save_playlist
}

@Composable
private fun AddSongRow(
    songItem: AddSongsViewModel.SongItem,
    onToggle: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.RowHeight)
                .clickable(onClick = onToggle)
                .padding(horizontal = Spacing.HorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = songItem.isChecked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = TextPrimary,
                    uncheckedColor = TextSecondary,
                    checkmarkColor = Background,
                ),
            )
            Spacer(modifier = Modifier.width(Spacing.md))
            if (songItem.song.albumArtUri != null) {
                AsyncImage(
                    model = crossfadeModel(songItem.song.albumArtUri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(MaterialTheme.shapes.small),
                )
            } else {
                GlitchPlaceholder(
                    songId = songItem.song.id,
                    isPlaying = false,
                    size = 44.dp,
                )
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = songItem.song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = songItem.song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}

package com.v1per.synthx.ui.screens.issues

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.MissingSongInfo
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SynthXTheme
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary

@Composable
fun IssuesScreen(
    topBar: @Composable () -> Unit,
    viewModel: IssuesViewModel = viewModel(
        factory = IssuesViewModel.Factory(LocalContext.current),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        topBar()

        IssuesContent(uiState = uiState)
    }
}

@Composable
private fun IssuesContent(uiState: UiState<List<MissingSongInfo>>) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is UiState.Loading -> Unit
            is UiState.Empty -> Text(
                text = stringResource(R.string.empty_no_issues),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
            is UiState.Error -> Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
            is UiState.Success -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                items(uiState.data, key = { it.id }) { song ->
                    MissingSongRow(song = song)
                }
            }
        }
    }
}

/** Informational only — a missing song has no file to act on, so the row is inert. */
@Composable
private fun MissingSongRow(song: MissingSongInfo) {
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding, vertical = Spacing.md),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.issues_missing_since, relativeTime(song.missingSince)),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Hairline,
        )
    }
}

/**
 * "3 days ago" / "5 min. ago". MINUTE_IN_MILLIS resolution means anything under a
 * minute reads as "0 min. ago" rather than a bare "now", which is fine here — a song
 * that vanished seconds ago is not a case worth special-casing.
 */
private fun relativeTime(timestamp: Long): CharSequence =
    DateUtils.getRelativeTimeSpanString(
        timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    )

@Preview(name = "Issues — Success", showBackground = true)
@Composable
private fun IssuesPreview() {
    val now = System.currentTimeMillis()
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            IssuesContent(
                uiState = UiState.Success(
                    data = listOf(
                        MissingSongInfo(
                            id = "1",
                            title = "Severed Link",
                            artist = "KNTRL",
                            missingSince = now - 2 * DateUtils.HOUR_IN_MILLIS,
                        ),
                        MissingSongInfo(
                            id = "2",
                            title = "Null Signal",
                            artist = "v1per",
                            missingSince = now - 3 * DateUtils.DAY_IN_MILLIS,
                        ),
                        MissingSongInfo(
                            id = "3",
                            title = "Ghost Partition",
                            artist = "Unknown Artist",
                            missingSince = now - 21 * DateUtils.DAY_IN_MILLIS,
                        ),
                    ),
                ),
            )
        }
    }
}

@Preview(name = "Issues — Empty", showBackground = true)
@Composable
private fun IssuesEmptyPreview() {
    SynthXTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
        ) {
            IssuesContent(uiState = UiState.Empty)
        }
    }
}

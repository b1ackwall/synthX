package com.v1per.synthx.ui.screens.createplaylist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v1per.synthx.R
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.LoadingIndicator
import com.v1per.synthx.ui.components.SettingsDetailScaffold
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary
import com.v1per.synthx.ui.theme.TextTertiary

@Composable
fun CreatePlaylistScreen(
    topBar: @Composable () -> Unit,
    onCreated: () -> Unit,
    viewModel: CreatePlaylistViewModel = viewModel(
        factory = CreatePlaylistViewModel.factory(LocalContext.current),
    ),
) {
    val uiState by viewModel.uiState.collectAsState()
    val created by viewModel.created.collectAsState()
    val error by viewModel.error.collectAsState()
    val isCreating by viewModel.isCreating.collectAsState()

    // rememberSaveable, not remember: the ViewModel outlives a configuration change but these
    // two fields did not, so a font-size/locale/dark-mode change (or process death) wiped a
    // half-filled form.
    var name by rememberSaveable { mutableStateOf("") }
    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(created) {
        if (created) onCreated()
    }

    SettingsDetailScaffold(
        topBar = topBar,
        // Lifts the pinned Create button above the keyboard instead of leaving it covered.
        modifier = Modifier.imePadding(),
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.HorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Error,
                    )
                }
                CreateButton(
                    // Disabled on a blank name rather than silently no-oping on tap
                    // — and disabled again mid-write so a
                    // slow create or a double-tap can't fire it twice.
                    enabled = name.isNotBlank() && !isCreating,
                    isCreating = isCreating,
                    onClick = { viewModel.createPlaylist(name, selectedGroupId) },
                )
                Spacer(modifier = Modifier.height(Spacing.lg))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.HorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Spacer(modifier = Modifier.height(Spacing.xl))

            Text(
                text = stringResource(R.string.label_playlist_name),
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = {
                    Text(text = stringResource(R.string.hint_playlist_name), color = TextTertiary)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TextSecondary,
                    cursorColor = TextPrimary,
                ),
            )

            Text(
                text = stringResource(R.string.label_add_to_group_optional),
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
            )
            when (val state = uiState) {
                is UiState.Loading -> LoadingIndicator()
                is UiState.Error -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Error,
                )
                is UiState.Empty -> Text(
                    text = stringResource(R.string.empty_no_groups),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary,
                )
                is UiState.Success -> LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(state.data, key = { it.id }) { group ->
                        val selected = group.id == selectedGroupId
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(Spacing.RowHeight)
                                    .clickable {
                                        selectedGroupId = if (selected) null else group.id
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = group.name,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    ),
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = TextPrimary,
                                    )
                                }
                            }
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = Hairline,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))
        }
    }
}

@Composable
private fun CreateButton(
    enabled: Boolean,
    isCreating: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.xxl),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceVariant,
            contentColor = TextPrimary,
        ),
    ) {
        if (isCreating) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = TextPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = stringResource(R.string.action_create_playlist),
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
            )
        }
    }
}

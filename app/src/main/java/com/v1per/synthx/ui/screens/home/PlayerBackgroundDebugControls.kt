package com.v1per.synthx.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.v1per.synthx.ui.components.RefinedBottomSheet
import com.v1per.synthx.ui.components.RefinedBottomSheetState
import com.v1per.synthx.ui.components.rememberRefinedBottomSheetState
import com.v1per.synthx.ui.player.AltStageBlurEdgeMode
import com.v1per.synthx.ui.player.PlayerBackgroundBlurMaxDp
import com.v1per.synthx.ui.player.PlayerBackgroundBlurMinDp
import com.v1per.synthx.ui.player.PlayerBackgroundDebugStateHolder
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Surface as SurfaceColor
import com.v1per.synthx.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Debug-only: a settings button over Home and the bottom sheet it opens. Compose only in debug builds. */
@Composable
internal fun PlayerBackgroundDebugControls() {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberRefinedBottomSheetState()
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        IconButton(
            onClick = { showSheet = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Player background debug",
                tint = TextSecondary,
            )
        }

        if (showSheet) {
            PlayerBackgroundDebugSheet(
                sheetState = sheetState,
                onDismissRequest = {
                    scope.launch {
                        sheetState.hide()
                        showSheet = false
                    }
                },
            )
        }
    }
}

// Translucent surface, no handle, no drag-dismiss.
@Composable
private fun PlayerBackgroundDebugSheet(sheetState: RefinedBottomSheetState, onDismissRequest: () -> Unit) {
    val debugState by PlayerBackgroundDebugStateHolder.state

    RefinedBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = SurfaceColor.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.small,
        showDragHandle = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Player background", modifier = Modifier.weight(1f))
                TextButton(onClick = PlayerBackgroundDebugStateHolder::reset) { Text("Reset") }
                TextButton(onClick = onDismissRequest) { Text("Close") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = "Blur radius: ${debugState.blurRadiusDp.roundToInt()} dp",
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = debugState.blurRadiusDp,
                    onValueChange = PlayerBackgroundDebugStateHolder::setBlurRadiusDp,
                    valueRange = PlayerBackgroundBlurMinDp..PlayerBackgroundBlurMaxDp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Twist", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = debugState.twistEnabled,
                    onCheckedChange = PlayerBackgroundDebugStateHolder::setTwistEnabled,
                )
            }
            Text(
                text = "Edge mode: ${debugState.blurEdgeMode.name}",
                style = MaterialTheme.typography.labelSmall,
            )
            // The selected mode is bold.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AltStageBlurEdgeMode.entries.forEach { mode ->
                    TextButton(onClick = { PlayerBackgroundDebugStateHolder.setBlurEdgeMode(mode) }) {
                        Text(
                            text = mode.name,
                            fontWeight = if (mode == debugState.blurEdgeMode) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

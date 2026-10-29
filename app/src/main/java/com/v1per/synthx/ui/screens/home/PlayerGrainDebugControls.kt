package com.v1per.synthx.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Grain
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.v1per.synthx.ui.components.RefinedBottomSheet
import com.v1per.synthx.ui.components.RefinedBottomSheetState
import com.v1per.synthx.ui.components.rememberRefinedBottomSheetState
import com.v1per.synthx.ui.player.GrainBlend
import com.v1per.synthx.ui.player.GrainPatternMax
import com.v1per.synthx.ui.player.GrainPatternMin
import com.v1per.synthx.ui.player.GrainSizeMaxDp
import com.v1per.synthx.ui.player.GrainSizeMinDp
import com.v1per.synthx.ui.player.GrainStrengthMax
import com.v1per.synthx.ui.player.PlayerGrainDebugStateHolder
import com.v1per.synthx.ui.player.PlayerGrainSettings
import com.v1per.synthx.ui.player.playerGrainReadout
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Surface as SurfaceColor
import com.v1per.synthx.ui.theme.TextSecondary
import kotlinx.coroutines.launch

internal const val HOME_GRAIN_DEBUG_BUTTON_TAG = "home_grain_debug_button"

/** Debug-only: a grain tuning button at Home's top start and the sheet it opens. Compose only in debug builds. */
@Composable
internal fun BoxScope.PlayerGrainDebugControls() {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberRefinedBottomSheetState()
    val scope = rememberCoroutineScope()

    IconButton(
        onClick = { showSheet = true },
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(Spacing.sm)
            .testTag(HOME_GRAIN_DEBUG_BUTTON_TAG),
    ) {
        Icon(
            imageVector = Icons.Filled.Grain,
            contentDescription = "Grain tuning",
            tint = TextSecondary,
        )
    }

    if (showSheet) {
        PlayerGrainDebugSheet(
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

// Translucent surface, no handle, no drag-dismiss.
@Composable
private fun PlayerGrainDebugSheet(sheetState: RefinedBottomSheetState, onDismissRequest: () -> Unit) {
    val grain by PlayerGrainDebugStateHolder.state
    val update = PlayerGrainDebugStateHolder::update

    RefinedBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = SurfaceColor.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Grain", modifier = Modifier.weight(1f))
                TextButton(onClick = PlayerGrainDebugStateHolder::reset) { Text("Reset") }
                TextButton(onClick = onDismissRequest) { Text("Close") }
            }
            Text(
                text = "Grain shows in the expanded player.",
                style = MaterialTheme.typography.labelSmall,
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Grain on", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(checked = grain.enabled, onCheckedChange = { on -> update { it.copy(enabled = on) } })
            }
            GrainSlider("Strength", "%.2f".format(grain.strength), grain.strength, 0f..GrainStrengthMax) { v ->
                update { it.copy(strength = v) }
            }
            GrainSlider("Size", "%.1f dp".format(grain.sizeDp), grain.sizeDp, GrainSizeMinDp..GrainSizeMaxDp) { v ->
                update { it.copy(sizeDp = v) }
            }
            GrainSlider("Contrast", "%.2f".format(grain.contrast), grain.contrast, 0f..1f) { v ->
                update { it.copy(contrast = v) }
            }
            GrainSlider(
                label = "Pattern",
                value = "${grain.pattern}",
                current = grain.pattern.toFloat(),
                range = GrainPatternMin.toFloat()..GrainPatternMax.toFloat(),
                steps = GrainPatternMax - GrainPatternMin - 1,
            ) { v -> update { it.copy(pattern = v.toInt()) } }
            Text("Blend", style = MaterialTheme.typography.labelSmall)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GrainBlend.entries.forEach { blend ->
                    SelectableChoice(blend.name, blend == grain.blend) { update { it.copy(blend = blend) } }
                }
            }
            Text("Colour", style = MaterialTheme.typography.labelSmall)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SelectableChoice("Grey", !grain.colored) { update { it.copy(colored = false) } }
                SelectableChoice("Colour", grain.colored) { update { it.copy(colored = true) } }
            }
            GrainReadout(grain)
        }
    }
}

@Composable
private fun GrainSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("$label: $value", style = MaterialTheme.typography.labelSmall)
        Slider(
            value = current,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// The selected choice is bold.
@Composable
private fun SelectableChoice(name: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text = name, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun GrainReadout(grain: PlayerGrainSettings) {
    Box(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        SelectionContainer {
            Text(
                text = playerGrainReadout(grain),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

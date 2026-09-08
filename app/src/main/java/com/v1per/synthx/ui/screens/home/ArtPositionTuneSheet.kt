package com.v1per.synthx.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.components.ArtPositionTuneStateHolder
import com.v1per.synthx.ui.components.RefinedBottomSheet
import com.v1per.synthx.ui.components.RefinedBottomSheetState
import com.v1per.synthx.ui.theme.Surface as SurfaceColor

@Composable
internal fun ArtPositionTuneSheet(
    sheetState: RefinedBottomSheetState,
    onDismissRequest: () -> Unit,
) {
    var settings by ArtPositionTuneStateHolder.state

    RefinedBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = SurfaceColor.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.small,
        showDragHandle = false,
        enableDragToDismiss = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tune art position", modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissRequest) { Text("Close") }
            }
            Text(
                text = "Art position balance: ${"%.2f".format(settings.aboveFraction)}",
                style = MaterialTheme.typography.labelSmall,
            )
            Slider(
                value = settings.aboveFraction,
                onValueChange = { value -> settings = settings.copy(aboveFraction = value) },
                valueRange = 0f..1f,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Art position balance" },
            )
            Text(
                text = "Lower: art closer to the top. Higher: art closer to the title.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Title tightness: ${"%.2f".format(settings.titleTightnessFraction)}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            Slider(
                value = settings.titleTightnessFraction,
                onValueChange = { value -> settings = settings.copy(titleTightnessFraction = value) },
                valueRange = 0f..1f,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Title tightness" },
            )
            Text(
                text = "0.00: title against art. 1.00: original gap. Art stays fixed.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

package com.v1per.synthx.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Outfit Type Scale", showBackground = true, widthDp = 380, heightDp = 900)
@Composable
private fun TypeScalePreview() {
    SynthXTheme {
        Column(
            modifier = Modifier
                .background(Background)
                .padding(horizontal = Spacing.md, vertical = Spacing.md)
                .fillMaxWidth(),
        ) {
            TypeRow("DisplayLarge · Bold 57sp", MaterialTheme.typography.displayLarge, "synthX")
            TypeRow("DisplayMedium · SemiBold 45sp", MaterialTheme.typography.displayMedium, "synthX")
            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(color = Hairline)
            Spacer(Modifier.height(Spacing.sm))
            TypeRow("TitleLarge · SemiBold 22sp", MaterialTheme.typography.titleLarge, "Now Playing")
            TypeRow("TitleMedium · Medium 16sp", MaterialTheme.typography.titleMedium, "Blinding Lights")
            TypeRow("TitleSmall · Medium 14sp", MaterialTheme.typography.titleSmall, "The Weeknd")
            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(color = Hairline)
            Spacer(Modifier.height(Spacing.sm))
            TypeRow("BodyLarge · Regular 16sp", MaterialTheme.typography.bodyLarge, "After Hours · 2020")
            TypeRow("BodyMedium · Regular 14sp", MaterialTheme.typography.bodyMedium, "3 minutes 20 seconds")
            TypeRow("BodySmall · Light 12sp", MaterialTheme.typography.bodySmall, "/music/afterhours.mp3")
            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(color = Hairline)
            Spacer(Modifier.height(Spacing.sm))
            TypeRow("LabelLarge · Medium 14sp", MaterialTheme.typography.labelLarge, "LIBRARY")
            TypeRow("LabelSmall · Regular 11sp", MaterialTheme.typography.labelSmall, "42 songs")
        }
    }
}

@Composable
private fun TypeRow(label: String, style: androidx.compose.ui.text.TextStyle, sample: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Accent,
        modifier = Modifier.padding(top = Spacing.xs),
    )
    Text(
        text = sample,
        style = style,
        color = TextPrimary,
    )
    Spacer(Modifier.height(Spacing.xs))
}

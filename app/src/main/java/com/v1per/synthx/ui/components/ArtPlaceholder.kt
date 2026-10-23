package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import com.v1per.synthx.ui.theme.ArtSizes
import com.v1per.synthx.ui.theme.SurfaceVariant

/** Static album-art placeholder shown wherever a song/album/playlist has no artwork. */
@Composable
fun ArtPlaceholder(modifier: Modifier = Modifier, size: Dp = ArtSizes.Song) {
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
            .background(SurfaceVariant),
    )
}

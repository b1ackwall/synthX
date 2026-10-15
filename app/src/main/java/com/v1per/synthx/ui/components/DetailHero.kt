package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.v1per.synthx.ui.theme.SurfaceVariant

/**
 * A detail screen's hero cover art at [DetailHeroSize]. While loading, with a null [coverUri], or
 * when the file behind it cannot be read, it draws a flat SurfaceVariant square — the same pixels
 * as [ArtPlaceholder] — never an empty hole.
 */
@Composable
fun DetailHeroArt(
    coverUri: String?,
    modifier: Modifier = Modifier,
) {
    val placeholder = remember { ColorPainter(SurfaceVariant) }
    AsyncImage(
        model = crossfadeModel(coverUri),
        contentDescription = null,
        placeholder = placeholder,
        error = placeholder,
        fallback = placeholder,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(DetailHeroSize)
            .clip(MaterialTheme.shapes.small),
    )
}

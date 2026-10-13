package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.player.LocalPlayerContentColors
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Artwork + title + artist, the shared visual identity of a song.
 *
 * Extracted from SongRow so the actions sheet header shows exactly what the row shows.
 * Deliberately owns neither the missing-song dimming nor any layout weight — callers
 * apply `.alpha()` and `.weight()` themselves, since those belong to the containing row.
 */
@Composable
fun SongIdentity(
    song: Song,
    modifier: Modifier = Modifier,
) {
    // Non-null only inside the player sheet (the queue rows); everywhere else this stays null
    // and the text keeps its usual tokens.
    val playerColors = LocalPlayerContentColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(Spacing.md))
        if (song.albumArtUri != null) {
            AsyncImage(
                model = crossfadeModel(song.albumArtUri),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small),
            )
        } else {
            ArtPlaceholder()
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.md),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = playerColors?.content ?: TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = playerColors?.content ?: TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

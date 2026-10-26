package com.v1per.synthx.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import com.v1per.synthx.R

private const val WORDMARK_WIDTH = 65f
private const val WORDMARK_HEIGHT = 17.14f

/**
 * The synthX wordmark, drawn with its baked-in colours (no content-colour tint). Its height is the
 * line height of [heightStyle], the text style the wordmark used to be set in; the width follows
 * the drawable's aspect ratio.
 */
@Composable
fun Wordmark(heightStyle: TextStyle, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val height = with(density) { heightStyle.lineHeight.toDp() }
    Image(
        painter = painterResource(R.drawable.ic_wordmark),
        contentDescription = stringResource(R.string.cd_wordmark),
        modifier = modifier.height(height).aspectRatio(WORDMARK_WIDTH / WORDMARK_HEIGHT),
    )
}

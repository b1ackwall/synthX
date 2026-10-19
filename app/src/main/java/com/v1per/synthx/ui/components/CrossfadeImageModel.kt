package com.v1per.synthx.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import coil.size.Size

/**
 * Wraps [uri] in a crossfading [ImageRequest] for [coil.compose.AsyncImage]. Without this,
 * art pops in abruptly the instant Coil finishes decoding. One shared helper instead of repeating the
 * `ImageRequest.Builder(LocalContext.current).data(uri).crossfade(true).build()` boilerplate.
 *
 * @param original when true, decodes at [Size.ORIGINAL] instead of the view's measured size —
 *   the expanded player opts into this so Coil doesn't upscale a small cached thumbnail.
 */
@Composable
fun crossfadeModel(uri: Any?, original: Boolean = false): Any = ImageRequest.Builder(LocalContext.current)
    .data(uri)
    .crossfade(true)
    .apply { if (original) size(Size.ORIGINAL) }
    .build()

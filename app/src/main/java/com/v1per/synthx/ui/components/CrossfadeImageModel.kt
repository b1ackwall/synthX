package com.v1per.synthx.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest

/**
 * Wraps [uri] in a crossfading [ImageRequest] for [coil.compose.AsyncImage]. Without this,
 * art pops in abruptly the instant Coil finishes decoding — every album-art `AsyncImage` call
 * site in the app used to pass a raw URI with no `placeholder`/`crossfade`, which is the same
 * gap the audit flagged once per screen. One shared helper instead of repeating the
 * `ImageRequest.Builder(LocalContext.current).data(uri).crossfade(true).build()` boilerplate.
 */
@Composable
fun crossfadeModel(uri: Any?): Any =
    ImageRequest.Builder(LocalContext.current)
        .data(uri)
        .crossfade(true)
        .build()

package com.v1per.synthx.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavBackStackEntry

/** Shared per-route wrapper: full-size Box reserving [chromeBottomPadding] below content. */
@Composable
fun RouteContent(chromeBottomPadding: Dp, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = chromeBottomPadding),
        content = content,
    )
}

/** String nav arg lookup, defaulting to "" when absent — matches every route's prior inline pattern. */
fun NavBackStackEntry.requireStringArg(key: String): String = arguments?.getString(key) ?: ""

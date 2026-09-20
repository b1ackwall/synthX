package com.v1per.synthx.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavBackStackEntry

/**
 * Shared per-route wrapper: full-size Box reserving [chromeBottomPadding] below content.
 *
 * The reserved strip is also declared consumed as a window inset. The chrome (bottom nav and
 * mini player) sits at the very bottom of the screen and is covered by the keyboard when it
 * opens, so a screen using `imePadding()` only needs to clear the keyboard height *beyond* that
 * strip; without this it is counted twice and a pinned button floats a whole nav-bar height above
 * the keyboard.
 */
@Composable
fun RouteContent(chromeBottomPadding: Dp, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = chromeBottomPadding)
            .consumeWindowInsets(PaddingValues(bottom = chromeBottomPadding)),
        content = content,
    )
}

/** String nav arg lookup, defaulting to "" when absent — matches every route's prior inline pattern. */
fun NavBackStackEntry.requireStringArg(key: String): String = arguments?.getString(key) ?: ""

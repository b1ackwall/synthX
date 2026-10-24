package com.v1per.synthx.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TabSelectionTest {
    private val tabs = setOf("home", "library", "search")

    private fun lit(current: String?, vararg stack: String?) = selectedTabRoute(current, stack.toList(), tabs)

    @Test fun aTabRootLightsItself() {
        assertEquals("library", lit("library", "graph", "home", "library"))
    }

    @Test fun aTabRootLightsItselfEvenWithAnEmptyStack() {
        assertEquals("search", lit("search"))
    }

    @Test fun aSubScreenLightsTheNearestTabBelowIt() {
        assertEquals("library", lit("album", "graph", "home", "library", "albumList", "album"))
        assertEquals("search", lit("album", "graph", "home", "search", "album"))
        assertEquals("home", lit("album", "graph", "home", "album"))
    }

    @Test fun theNearestTabWinsOverAnOlderOne() {
        assertEquals("search", lit("playlist", "home", "library", "search", "playlist"))
    }

    @Test fun entriesAboveTheVisibleOneAreIgnored() {
        assertEquals("library", lit("songs", "home", "library", "songs", "search"))
    }

    @Test fun extraEntriesBelowDoNotChangeTheResult() {
        assertEquals("search", lit("album", "home", "library", "home", "library", "home", "search", "album"))
    }

    @Test fun aStackThatLacksTheVisibleRouteStillFindsTheNearestTab() {
        assertEquals("library", lit("songs", "home", "library"))
    }

    @Test fun nothingIsLitWithoutATabRootOnTheStack() {
        assertNull(lit("settings", "graph", "settings"))
        assertNull(lit("settings"))
    }

    @Test fun nothingIsLitWithoutAVisibleRoute() {
        assertNull(lit(null, "home", "library"))
    }

    @Test fun entriesWithoutARouteAreSkipped() {
        assertEquals("home", lit("album", null, "home", null, "album"))
    }
}

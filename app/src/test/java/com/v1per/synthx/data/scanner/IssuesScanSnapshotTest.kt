package com.v1per.synthx.data.scanner

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The status line's "N issue(s) found" summary and the cached missing-art ordering — the two
 * pieces of the issues-scan cache that are pure enough to pin down without WorkManager or
 * SharedPreferences in the way.
 */
class IssuesScanSnapshotTest {

    private fun snapshot(
        missingArtSongIds: List<String> = emptyList(),
        missingSongCount: Int = 0,
        grantLost: Boolean = false,
        backupUnhealthy: Boolean = false,
        exportUnhealthy: Boolean = false,
        verificationFailed: Boolean = false,
        destructiveActionCount: Int = 0,
        // DEAD CODE: the Issues MissingPalette item is retired. Delete in a cleanup pass. Ask  before deleting.
        // missingPaletteSongIds: List<String> = emptyList(),
    ) = IssuesScanSnapshot(
        missingArtSongIds = missingArtSongIds,
        missingSongCount = missingSongCount,
        grantLost = grantLost,
        backupUnhealthy = backupUnhealthy,
        exportUnhealthy = exportUnhealthy,
        verificationFailed = verificationFailed,
        destructiveActionCount = destructiveActionCount,
        // DEAD CODE: the Issues MissingPalette item is retired. Delete in a cleanup pass. Ask  before deleting.
        // missingPaletteSongIds = missingPaletteSongIds,
    )

    @Test fun healthyLibraryCountsZeroIssues() {
        assertEquals(0, snapshot().issueCount)
    }

    @Test fun eachMissingSongCountsAsItsOwnIssue() {
        assertEquals(3, snapshot(missingSongCount = 3).issueCount)
    }

    /** The screen renders ONE aggregate MissingArt row however many songs it covers. */
    @Test fun missingArtCountsAsOneIssueRegardlessOfSongCount() {
        assertEquals(1, snapshot(missingArtSongIds = listOf("a", "b", "c", "d")).issueCount)
    }

    @Test fun emptyMissingArtListCountsAsNoIssue() {
        assertEquals(0, snapshot(missingArtSongIds = emptyList()).issueCount)
    }

    /** Likewise a plain count: many destructive actions are still one row on the screen. */
    @Test fun destructiveActionsCountAsOneIssueRegardlessOfCount() {
        assertEquals(1, snapshot(destructiveActionCount = 12).issueCount)
    }

    @Test fun zeroDestructiveActionsCountAsNoIssue() {
        assertEquals(0, snapshot(destructiveActionCount = 0).issueCount)
    }

    @Test fun eachSafetyNetSignalCountsSeparately() {
        assertEquals(
            4,
            snapshot(
                grantLost = true,
                backupUnhealthy = true,
                exportUnhealthy = true,
                verificationFailed = true,
            ).issueCount,
        )
    }

    // DEAD CODE: the Issues MissingPalette item is retired. Delete in a cleanup pass. Ask  before deleting.
    // @Test fun missingPaletteCountsAsOneIssueRegardlessOfSongCount() {
    //     assertEquals(1, snapshot(missingPaletteSongIds = List(2000) { "$it" }).issueCount)
    //     assertEquals(0, snapshot(missingPaletteSongIds = emptyList()).issueCount)
    // }
    //
    // @Test fun artAndPaletteAreSeparateIssuesEvenForTheSameSong() {
    //     assertEquals(2, snapshot(missingArtSongIds = listOf("a"), missingPaletteSongIds = listOf("a")).issueCount)
    // }

    // DEAD CODE: the Issues MissingPalette item is retired. Delete in a cleanup pass. Ask  before deleting.
    // @Test fun allEightCategoriesTogether() {
    //     assertEquals(
    //         // 5 missing songs + grant-lost + backup + export + verification + destructive + art + palette
    //         12,
    //         snapshot(
    //             missingArtSongIds = listOf("a", "b"),
    //             missingPaletteSongIds = listOf("c", "d"),
    //             missingSongCount = 5,
    //             grantLost = true,
    //             backupUnhealthy = true,
    //             exportUnhealthy = true,
    //             verificationFailed = true,
    //             destructiveActionCount = 1,
    //         ).issueCount,
    //     )
    // }

    @Test fun allSevenCategoriesTogether() {
        assertEquals(
            // 5 missing songs + grant-lost + backup + export + verification + destructive + art
            11,
            snapshot(
                missingArtSongIds = listOf("a", "b"),
                missingSongCount = 5,
                grantLost = true,
                backupUnhealthy = true,
                exportUnhealthy = true,
                verificationFailed = true,
                destructiveActionCount = 1,
            ).issueCount,
        )
    }

    @Test fun stableOrderIsIndependentOfSetIterationOrder() {
        val ids = listOf("zeta", "alpha", "mid", "beta")
        assertEquals(
            IssuesScanSnapshot.stableOrder(ids.toSet()),
            IssuesScanSnapshot.stableOrder(ids.reversed().toSet()),
        )
    }

    @Test fun stableOrderSortsAscending() {
        assertEquals(
            listOf("alpha", "beta", "mid", "zeta"),
            IssuesScanSnapshot.stableOrder(setOf("zeta", "alpha", "mid", "beta")),
        )
    }

    @Test fun stableOrderOfEmptyCacheIsEmpty() {
        assertEquals(emptyList(), IssuesScanSnapshot.stableOrder(emptySet()))
    }
}

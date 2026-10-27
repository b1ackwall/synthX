package com.v1per.synthx.data.scanner

/**
 * One issues-scan result, as plain data — no Android, no WorkManager, no SharedPreferences.
 *
 * Separate from [IssuesScanWorker] so the two things that are easy to get quietly wrong are
 * testable on their own: what the status line's issue count is composed of, and what order the
 * cached missing-art ids come back in. The worker's job is then only to gather the inputs and
 * hand the result to [com.v1per.synthx.preferences.PreferencesManager].
 *
 * [issueCount] must stay in step with IssuesViewModel.buildIssues, which is what actually
 * renders these rows — one row per missing song, one per unhealthy safety-net signal, and one
 * aggregate row for missing art, no matter how many songs it covers.
 */
internal data class IssuesScanSnapshot(
    val missingArtSongIds: List<String>,
    val missingSongCount: Int,
    val grantLost: Boolean,
    val backupUnhealthy: Boolean,
    val exportUnhealthy: Boolean,
    val verificationFailed: Boolean,
    val destructiveActionCount: Int,
) {
    val issueCount: Int
        get() = missingSongCount + listOf(
            grantLost,
            backupUnhealthy,
            exportUnhealthy,
            verificationFailed,
            destructiveActionCount > 0,
            missingArtSongIds.isNotEmpty(),
        ).count { it }

    companion object {
        /**
         * The cached id list, in a stable order.
         *
         * The cache is persisted with `putStringSet`, which round-trips through an unordered
         * collection — so without this, the Issues screen's MissingArt row could rebuild with a
         * different `songIds` list on every read of identical data, churning the LazyColumn item
         * and the Fix action's argument for no reason. The order itself carries no meaning
         * (the row is an aggregate); only its stability does.
         */
        fun stableOrder(songIds: Set<String>): List<String> = songIds.sorted()
    }
}

package com.v1per.synthx.data.scanner

data class ScanResult(
    val added: Int,
    val updated: Int,
    val missing: Int,
    val unchanged: Int,
    /**
     * Whether the missing-detection pass actually ran. `false` means `is_missing` was left
     * entirely untouched this scan — either it was not requested, or a configured root could
     * not be enumerated and reconciling against a partial path set would have wrongly flagged
     * songs. Distinguishes that from a pass that ran and legitimately found [missing] == 0.
     */
    val missingReconciled: Boolean = true,
    /** Rows hard-deleted this scan — path-collision occupant eviction only (Site A). */
    val deleted: Int = 0,
    /**
     * Rows flagged missing this scan because their path was reused by different content
     * (Site B, hash-mismatch) — counted separately from [missing] (Site C's mass
     * reconciliation flag) since they come from different triggers.
     */
    val hashMismatchFlagged: Int = 0,
    /**
     * Files skipped this scan because no identity hash could be computed for them —
     * the head read failed, or the tail read failed even after [HashUtils]'s internal
     * retry. Distinct from [missing]: these files were seen on disk but couldn't be
     * read, so they are left untouched in the library rather than flagged missing.
     */
    val errors: Int = 0,
    /**
     * Rows this scan deliberately left alone because another file operation (graduation,
     * delete, replace) held the song, or mutated it after the scan started — see
     * [com.v1per.synthx.data.ScanRowClaims]. Not errors: the next scan picks them up.
     */
    val deferred: Int = 0,
    /** Root-scoped uncertainty, independent of whether missing reconciliation was requested. */
    val degradations: Set<ScanDegradation> = emptySet(),
) {
    /**
     * Combined count of everything this scan changed destructively or flagged away:
     * hard deletes plus both missing-flag mechanisms. Used to gauge whether a single
     * scan pass affected an unusually large slice of the library.
     */
    val totalImpact: Int get() = deleted + hashMismatchFlagged + missing
}

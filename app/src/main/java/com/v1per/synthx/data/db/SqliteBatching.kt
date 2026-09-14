package com.v1per.synthx.data.db

/**
 * Conservative ceiling for every Room-expanded `IN (...)` list.
 *
 * The one-time real-device diagnostic lives in HostParameterLimitDiagnosticTest. Measured on a
 * Xiaomi Redmi Note 7 Pro, Android 13/API 33, SQLite 3.32.2: 32,766 effective variables.
 * 900 remains deliberately below SQLite's legacy 999-variable default; correctness does not
 * depend on a newer platform's larger ceiling.
 */
internal const val SQLITE_IN_BATCH_SIZE = 900

/** Runs an explicit IN-list query in stable, de-duplicated batches. */
internal suspend fun <K, V> Iterable<K>.queryInBatches(
    query: suspend (List<K>) -> List<V>,
    batchSize: Int = SQLITE_IN_BATCH_SIZE,
): List<V> {
    require(batchSize > 0) { "batchSize must be positive" }
    return distinct().chunked(batchSize).flatMap { query(it) }
}

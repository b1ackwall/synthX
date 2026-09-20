package com.v1per.synthx.data.scanner

import androidx.work.Operation
import java.util.UUID

/**
 * What a scan worker's `enqueue` hands back: the request's client-side [id] (assigned before
 * enqueue, so it is known even if WorkManager later discards the request) and the enqueue
 * [operation].
 *
 * Awaiting [operation] and then asking WorkManager for [id] is the exact way to tell whether an
 * [androidx.work.ExistingWorkPolicy.KEEP] enqueue was honoured: a request KEEP discards is never
 * inserted, so its id resolves to no WorkInfo at all once the operation has completed.
 */
class ScanEnqueueResult(val id: UUID, val operation: Operation)

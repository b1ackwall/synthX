package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlotRecord

/**
 * The engine's [BaselineOrderWriter]: [QueueOrderStore] hands it every changed baseline, and it holds
 * the latest one until the engine saves the live order, so for an edit that changes both orders the
 * live commit is always enqueued first and the baseline commit second ([flushAfter]). With that
 * order, a process death between the two leaves a database the restore repair turns into the queue
 * after the edit (docs/queue-persistence.md lists the one accepted exception).
 *
 * Main-thread-confined, like the order store it serves.
 */
internal class DeferredBaselineWriter : BaselineOrderWriter {
    private var pending: List<QueueSlotRecord>? = null

    override fun writeBaselineOrder(records: List<QueueSlotRecord>) {
        pending = records
    }

    /** Runs [writeLive], then hands the latest held baseline, if any, to [writeBaseline]. */
    fun flushAfter(writeLive: () -> Unit, writeBaseline: (List<QueueSlotRecord>) -> Unit) {
        writeLive()
        pending?.let {
            pending = null
            writeBaseline(it)
        }
    }

    /** Drops the held baseline, for a write that saves the baseline itself. */
    fun discard() {
        pending = null
    }
}

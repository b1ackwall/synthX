package com.v1per.synthx.ui.screens.librarymanagement

import androidx.work.WorkInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The picker-callback rules (P9-T4 / F09) that used to live inline in nine composable lambdas:
 * a refused persistable grant, a cancelled picker, and the mandatory-folder Done gate. The URI
 * type is a plain String here — the helpers are generic precisely so `android.net.Uri` (a stub on
 * the JVM) is not needed.
 */
class FolderPickTest {

    private class Recorder {
        val events = mutableListOf<String>()
        fun take(uri: String) { events += "take:$uri" }
        fun refuse(uri: String) { events += "take:$uri"; throw SecurityException("no persistable grant") }
    }

    // --- classifyPickResult ----------------------------------------------------------------

    @Test
    fun `a refused persistable grant is classified as refused`() {
        val recorder = Recorder()
        assertEquals(FolderPickResult.GrantRefused, classifyPickResult("content://cloud/tree", recorder::refuse))
    }

    @Test
    fun `a granted pick carries its uri`() {
        assertEquals(FolderPickResult.Granted("content://ok"), classifyPickResult("content://ok") {})
    }

    @Test
    fun `a cancelled picker is classified as cancelled without touching permissions`() {
        val recorder = Recorder()
        assertEquals(FolderPickResult.Cancelled, classifyPickResult(null, recorder::take))
        assertTrue(recorder.events.isEmpty(), "no grant may be attempted for a null result")
    }

    @Test
    fun `only a SecurityException counts as a refusal`() {
        assertFailsWith<IllegalStateException> {
            classifyPickResult("content://x") { throw IllegalStateException("not a permission problem") }
        }
    }

    // --- handleFolderPick ------------------------------------------------------------------

    private class Routed {
        val picked = mutableListOf<String>()
        var refused = 0
    }

    @Test
    fun `a refused grant reaches the refused handler and never the save`() {
        val routed = Routed()
        handleFolderPick(
            picked = "content://cloud/tree",
            takePermission = { throw SecurityException("no persistable grant") },
            onPicked = { routed.picked += it },
            onGrantRefused = { routed.refused++ },
        )
        assertEquals(1, routed.refused)
        assertTrue(routed.picked.isEmpty())
    }

    /** Standard rows stay silent: a cancel must produce no message and no save. */
    @Test
    fun `a cancelled picker triggers neither the save nor any message`() {
        val routed = Routed()
        handleFolderPick(
            picked = null as String?,
            takePermission = { error("must not be called") },
            onPicked = { routed.picked += it },
            onGrantRefused = { routed.refused++ },
        )
        assertEquals(0, routed.refused)
        assertTrue(routed.picked.isEmpty())
    }

    @Test
    fun `a granted pick is saved after its grant is taken`() {
        val recorder = Recorder()
        handleFolderPick(
            picked = "content://ok",
            takePermission = recorder::take,
            onPicked = { recorder.events += "save:$it" },
            onGrantRefused = { recorder.events += "refused" },
        )
        assertEquals(listOf("take:content://ok", "save:content://ok"), recorder.events)
    }

    /**
     * The save used to sit inside the same try/catch as the grant, so any SecurityException it
     * threw was reported as a refused grant. It no longer is.
     */
    @Test
    fun `a SecurityException from the save is not misreported as a refused grant`() {
        val routed = Routed()
        assertFailsWith<SecurityException> {
            handleFolderPick(
                picked = "content://ok",
                takePermission = {},
                onPicked = { throw SecurityException("from the save") },
                onGrantRefused = { routed.refused++ },
            )
        }
        assertEquals(0, routed.refused)
    }

    // --- mandatory music-folder step -------------------------------------------------------

    @Test
    fun `done stays disabled and the hint shows until a music folder is set`() {
        assertEquals(MusicFolderStepGate(doneEnabled = false, showRequiredHint = true), musicFolderStepGate(false))
    }

    @Test
    fun `done enables and the hint goes away once a music folder is set`() {
        assertEquals(MusicFolderStepGate(doneEnabled = true, showRequiredHint = false), musicFolderStepGate(true))
    }

    @Test
    fun `the hint and the disabled button can never disagree`() {
        for (selected in listOf(true, false)) {
            val gate = musicFolderStepGate(selected)
            assertFalse(gate.doneEnabled == gate.showRequiredHint, "selected=$selected")
        }
    }

    // --- deferred-scan pending states (F18) ------------------------------------------------

    @Test
    fun `a deferred scan is pending only while it is waiting to start`() {
        val pending = WorkInfo.State.values().filter(::isDeferredScanPending)
        assertEquals(setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED), pending.toSet())
    }
}

package com.v1per.synthx.data.scanner

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GraduationSourcePreflightTest {
    private val source = "content://provider/tree/source/document/song"
    private val music = "content://provider/tree/music"
    private val newArrivals = "content://provider/tree/new-arrivals"

    @Test fun deleteRefusalUnderMusicFailsBeforeCopyWithMusicNamed() = runTest {
        val probe = FakeProbe(ownerRoot = music, supportsDelete = false)

        val result = subject(probe).check(source)

        val failed = assertIs<GraduationSourcePreflightResult.Failed>(result)
        assertTrue(failed.reason.contains("Music folder"))
        assertTrue(failed.reason.contains("Nothing was copied"))
        assertEquals(1, probe.deleteCapabilityChecks)
    }

    @Test fun deleteRefusalUnderNewArrivalsNamesNewArrivals() = runTest {
        val result = subject(FakeProbe(ownerRoot = newArrivals, supportsDelete = false)).check(source)

        val failed = assertIs<GraduationSourcePreflightResult.Failed>(result)
        assertTrue(failed.reason.contains("New Arrivals folder"))
        assertTrue(failed.reason.contains("Nothing was copied"))
    }

    @Test fun missingMusicGrantNamesTheResolvedMusicRoot() = runTest {
        val result = subject(
            FakeProbe(ownerRoot = music, rootWriteState = RootWriteState.GrantNotWritable),
        ).check(source)

        val failed = assertIs<GraduationSourcePreflightResult.Failed>(result)
        assertTrue(failed.reason.contains("your Music folder"))
        assertTrue(failed.reason.contains("Reselect it"))
    }

    @Test fun missingNewArrivalsGrantNamesTheResolvedNewArrivalsRoot() = runTest {
        val result = subject(
            FakeProbe(ownerRoot = newArrivals, rootWriteState = RootWriteState.GrantNotWritable),
        ).check(source)

        val failed = assertIs<GraduationSourcePreflightResult.Failed>(result)
        assertTrue(failed.reason.contains("your New Arrivals folder"))
        assertTrue(failed.reason.contains("Reselect it"))
    }

    private fun subject(probe: FakeProbe) = GraduationSourcePreflight(
        probe = probe,
        roots = ConfiguredAudioRoots(music = music, newArrivals = newArrivals),
    )

    private class FakeProbe(
        private val ownerRoot: String,
        private val rootWriteState: RootWriteState = RootWriteState.Writable,
        private val supportsDelete: Boolean? = true,
    ) : AudioDocumentPreflightProbe {
        var deleteCapabilityChecks = 0

        override fun resolve(filePath: String): SourceDocumentResolution =
            SourceDocumentResolution.Present(source)

        override suspend fun isUnderRoot(documentUri: String, rootUri: String): Boolean =
            rootUri == ownerRoot

        override fun rootWriteState(rootUri: String): RootWriteState = rootWriteState
        override fun canWrite(documentUri: String): Boolean? = true
        override fun supportsDelete(documentUri: String): Boolean? {
            deleteCapabilityChecks++
            return supportsDelete
        }
        override fun computeSongId(documentUri: String): String? = "song"

        private companion object {
            const val source = "content://provider/tree/source/document/song"
        }
    }
}

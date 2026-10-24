package com.v1per.synthx.data.scanner

import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private const val MUSIC = "content://provider/tree/music"
private const val SOURCE = "content://provider/tree/source/document/song"

class GraduationSourcePreflightCopyTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun aSourceOutsideEveryConfiguredRootIsExplainedWithATypographicApostrophe() = runTest {
        val result = subject(FakeProbe(ownerRoot = "content://provider/tree/elsewhere")).check(SOURCE)

        assertEquals(
            "This song’s file is outside your configured Music and New Arrivals folders.",
            failureReason(result),
        )
    }

    @Test fun anUnwritableGrantIsExplainedWithATypographicApostrophe() = runTest {
        val result = subject(FakeProbe(rootWriteState = RootWriteState.GrantNotWritable)).check(SOURCE)

        assertEquals(
            "synthX doesn’t have write access to your Music folder. Reselect it in " +
                "Settings > Library Management. Nothing was copied.",
            failureReason(result),
        )
    }

    @Test fun anUnverifiableGrantIsExplainedWithATypographicApostrophe() = runTest {
        val result = subject(FakeProbe(rootWriteState = RootWriteState.Ambiguous)).check(SOURCE)

        assertEquals(
            "synthX couldn’t verify write access to your Music folder. Nothing was copied.",
            failureReason(result),
        )
    }

    @Test fun aProviderThatCannotDeleteIsExplainedWithATypographicApostrophe() = runTest {
        val result = subject(FakeProbe(supportsDelete = false)).check(SOURCE)

        assertEquals(
            "The file in your Music folder can’t be deleted by its storage provider. Nothing was copied.",
            failureReason(result),
        )
    }

    @Test fun anUnknownDeleteCapabilityIsExplainedWithATypographicApostrophe() = runTest {
        val result = subject(FakeProbe(supportsDelete = null)).check(SOURCE)

        assertEquals(
            "synthX couldn’t confirm that the file in your Music folder can be deleted. Nothing was copied.",
            failureReason(result),
        )
    }

    private fun failureReason(result: GraduationSourcePreflightResult): String =
        assertIs<GraduationSourcePreflightResult.Failed>(result).reason

    private fun subject(probe: FakeProbe) = GraduationSourcePreflight(
        probe = probe,
        roots = ConfiguredAudioRoots(music = MUSIC, newArrivals = null),
    )

    private class FakeProbe(
        private val ownerRoot: String = MUSIC,
        private val rootWriteState: RootWriteState = RootWriteState.Writable,
        private val supportsDelete: Boolean? = true,
    ) : AudioDocumentPreflightProbe {
        override fun resolve(filePath: String): SourceDocumentResolution = SourceDocumentResolution.Present(SOURCE)

        override suspend fun isUnderRoot(documentUri: String, rootUri: String): Boolean = rootUri == ownerRoot

        override fun rootWriteState(rootUri: String): RootWriteState = rootWriteState
        override fun canWrite(documentUri: String): Boolean? = true
        override fun supportsDelete(documentUri: String): Boolean? = supportsDelete
        override fun computeSongId(documentUri: String): String? = "song"
    }
}

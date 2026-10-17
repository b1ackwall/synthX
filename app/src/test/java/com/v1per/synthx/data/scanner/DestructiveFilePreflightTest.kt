package com.v1per.synthx.data.scanner

import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class DestructiveFilePreflightTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    private val uri = "content://provider/tree/music/document/song"

    @Test fun passedWhenDocumentRootPermissionCapabilityAndIdentityMatch() = runTest {
        assertEquals(DestructiveFilePreflightResult.Passed, subject().checkDestructive("path", "song"))
    }

    @Test fun sourceGoneIsNonBlocking() = runTest {
        assertEquals(
            DestructiveFilePreflightResult.SourceGone,
            subject(FakeProbe(resolution = SourceDocumentResolution.Gone))
                .checkDestructive("path", "song"),
        )
    }

    @Test fun inconclusiveResolutionIsAmbiguous() = runTest {
        assertEquals(
            DestructiveFilePreflightResult.Ambiguous,
            subject(FakeProbe(resolution = SourceDocumentResolution.Ambiguous))
                .checkDestructive("path", "song"),
        )
    }

    @Test fun readOnlyMusicGrantNamesMusicRoot() = runTest {
        assertEquals(
            DestructiveFilePreflightResult.GrantNotWritable(KnownAudioRoot.Music),
            subject(FakeProbe(rootWriteState = RootWriteState.GrantNotWritable))
                .checkDestructive("path", "song"),
        )
    }

    @Test fun readOnlyNewArrivalsGrantNamesNewArrivalsRoot() = runTest {
        assertEquals(
            DestructiveFilePreflightResult.GrantNotWritable(KnownAudioRoot.NewArrivals),
            subject(
                probe = FakeProbe(rootWriteState = RootWriteState.GrantNotWritable),
                roots = ConfiguredAudioRoots(music = null, newArrivals = uri),
            ).checkDestructive("path", "song"),
        )
    }

    @Test fun providerDeleteRefusalIsDocumentNotWritable() = runTest {
        assertEquals(
            DestructiveFilePreflightResult.DocumentNotWritable,
            subject(FakeProbe(canWrite = false)).checkDestructive("path", "song"),
        )
    }

    @Test fun differentSampledHashIsIdentityMismatch() = runTest {
        assertEquals(
            DestructiveFilePreflightResult.IdentityMismatch,
            subject(FakeProbe(songId = "replacement")).checkDestructive("path", "song"),
        )
    }

    @Test fun documentOutsideBothConfiguredRootsIsRootUnknown() = runTest {
        assertEquals(
            DestructiveFilePreflightResult.RootUnknown,
            subject(FakeProbe(underRoot = false)).checkDestructive("path", "song"),
        )
    }

    @Test fun failedHashIsAmbiguousRatherThanBlocking() = runTest {
        assertEquals(
            DestructiveFilePreflightResult.Ambiguous,
            subject(FakeProbe(songId = null)).checkDestructive("path", "song"),
        )
    }

    @Test fun replacementGoneAndMismatchAreDefiniteFailuresButHashFailureIsAmbiguous() = runTest {
        assertEquals(
            ReplacementFilePreflightResult.SourceGone,
            subject(FakeProbe(resolution = SourceDocumentResolution.Gone))
                .checkReplacement("path", "song"),
        )
        assertEquals(
            ReplacementFilePreflightResult.IdentityMismatch,
            subject(FakeProbe(songId = "replacement")).checkReplacement("path", "song"),
        )
        assertEquals(
            ReplacementFilePreflightResult.Ambiguous,
            subject(FakeProbe(songId = null)).checkReplacement("path", "song"),
        )
    }

    private fun subject(
        probe: FakeProbe = FakeProbe(),
        roots: ConfiguredAudioRoots = ConfiguredAudioRoots(music = uri, newArrivals = null),
    ) = DestructiveFilePreflight(probe) { roots }

    private inner class FakeProbe(
        private val resolution: SourceDocumentResolution = SourceDocumentResolution.Present(uri),
        private val underRoot: Boolean = true,
        private val rootWriteState: RootWriteState = RootWriteState.Writable,
        private val canWrite: Boolean? = true,
        private val songId: String? = "song",
    ) : AudioDocumentPreflightProbe {
        override fun resolve(filePath: String): SourceDocumentResolution = resolution

        override suspend fun isUnderRoot(documentUri: String, rootUri: String): Boolean = underRoot

        override fun rootWriteState(rootUri: String): RootWriteState = rootWriteState

        override fun canWrite(documentUri: String): Boolean? = canWrite

        override fun supportsDelete(documentUri: String): Boolean? = true

        override fun computeSongId(documentUri: String): String? = songId
    }
}

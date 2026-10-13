package com.v1per.synthx.data.scanner

/** Result of the source-side grant and delete-capability check that precedes every copy. */
internal sealed interface GraduationSourcePreflightResult {
    data object Passed : GraduationSourcePreflightResult
    data class Failed(val reason: String) : GraduationSourcePreflightResult
}

/**
 * Graduation-specific destructive preflight. Source reachability is checked by the caller first,
 * so a missing/read-revoked source keeps its distinct message; this check then resolves the
 * configured owner, validates that root's write grant, and asks for delete support specifically.
 */
internal class GraduationSourcePreflight(
    private val probe: AudioDocumentPreflightProbe,
    private val roots: ConfiguredAudioRoots,
) {
    suspend fun check(documentUri: String): GraduationSourcePreflightResult {
        val owner = resolveOwningAudioRoot(probe, documentUri, roots)
            ?: return GraduationSourcePreflightResult.Failed(
                "This song's file is outside your configured Music and New Arrivals folders.",
            )
        val rootName = when (owner.role) {
            KnownAudioRoot.Music -> "Music"
            KnownAudioRoot.NewArrivals -> "New Arrivals"
        }
        when (probe.rootWriteState(owner.uri)) {
            RootWriteState.GrantNotWritable -> return GraduationSourcePreflightResult.Failed(
                "synthX doesn't have write access to your $rootName folder. Reselect it in " +
                    "Settings > Library Management. Nothing was copied.",
            )
            RootWriteState.Ambiguous -> return GraduationSourcePreflightResult.Failed(
                "synthX couldn't verify write access to your $rootName folder. Nothing was copied.",
            )
            RootWriteState.Writable -> Unit
        }
        return when (probe.supportsDelete(documentUri)) {
            true -> GraduationSourcePreflightResult.Passed
            false -> GraduationSourcePreflightResult.Failed(
                "The file in your $rootName folder can't be deleted by its storage provider. " +
                    "Nothing was copied.",
            )
            null -> GraduationSourcePreflightResult.Failed(
                "synthX couldn't confirm that the file in your $rootName folder can be deleted. " +
                    "Nothing was copied.",
            )
        }
    }
}

package com.v1per.synthx.data.backup

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [classifyIneligibility] must distinguish missing sidecar, size mismatch,
 * duplicate display name, and unsupported schema instead of collapsing them into one
 * "Legacy backup" literal. Pure and Android-free, so no SAF/DocumentFile fixture is needed —
 * see [BackupListResultInstrumentedTest] for the same causes wired through real [BackupManager]
 * listing.
 */
class BackupIneligibilityReasonTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    private val validMetadata = BackupMetadata(
        timestamp = 1_000L,
        tier = BackupTier.Manual.name,
        dbVersion = 8,
        file = "synthx_backup_2026-08-27_120000_000_00000001.db",
        sizeBytes = 42L,
        sha256 = "ab".repeat(32),
    )

    @Test
    fun `missing metadata when no sidecar was readable`() {
        val reason = classifyIneligibility(
            metadata = null,
            actualSizeBytes = 42L,
            isDuplicateName = false,
            schemaSupported = true,
        )
        assertEquals(BackupIneligibilityReason.MissingMetadata, reason)
    }

    @Test
    fun `size mismatch when metadata size disagrees with the actual file`() {
        val reason = classifyIneligibility(
            metadata = validMetadata,
            actualSizeBytes = 999L,
            isDuplicateName = false,
            schemaSupported = true,
        )
        assertEquals(BackupIneligibilityReason.SizeMismatch(expectedBytes = 42L, actualBytes = 999L), reason)
    }

    @Test
    fun `duplicate display name wins over an otherwise-valid metadata and schema`() {
        val reason = classifyIneligibility(
            metadata = validMetadata,
            actualSizeBytes = 42L,
            isDuplicateName = true,
            schemaSupported = true,
        )
        assertEquals(BackupIneligibilityReason.DuplicateDisplayName, reason)
    }

    @Test
    fun `unsupported schema when nothing else is wrong but the schema can't migrate`() {
        val reason = classifyIneligibility(
            metadata = validMetadata,
            actualSizeBytes = 42L,
            isDuplicateName = false,
            schemaSupported = false,
        )
        assertEquals(BackupIneligibilityReason.UnsupportedSchema(dbVersion = 8), reason)
    }

    @Test
    fun `null when every check passes`() {
        val reason = classifyIneligibility(
            metadata = validMetadata,
            actualSizeBytes = 42L,
            isDuplicateName = false,
            schemaSupported = true,
        )
        assertNull(reason)
    }

    @Test
    fun `size mismatch is not reported when metadata omits size entirely`() {
        val reason = classifyIneligibility(
            metadata = validMetadata.copy(sizeBytes = null),
            actualSizeBytes = 999L,
            isDuplicateName = false,
            schemaSupported = true,
        )
        assertNull(reason)
    }
}

package com.v1per.synthx.data.backup

import com.v1per.synthx.data.db.SynthXDatabase
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class BackupPrimitivesTest {

    @Test
    fun `SQLite versions are parsed and compared numerically`() {
        assertTrue(SQLiteVersion.parse("3.9.0")!! < SQLiteVersion.VACUUM_INTO_MINIMUM)
        assertEquals(SQLiteVersion.VACUUM_INTO_MINIMUM, SQLiteVersion.parse("3.27.0"))
        assertTrue(SQLiteVersion.parse("3.32.0")!! > SQLiteVersion.VACUUM_INTO_MINIMUM)
        assertTrue(SQLiteVersion.parse("3.45.2")!! > SQLiteVersion.VACUUM_INTO_MINIMUM)
    }

    @Test
    fun `malformed SQLite versions are rejected`() {
        listOf("", "3.27", "3.27.0.1", "3.x.0", "3.27.0 suffix", "-3.27.0")
            .forEach { value -> assertNull(SQLiteVersion.parse(value), value) }
    }

    @Test
    fun `new backup names have milliseconds and collision resistant suffixes`() {
        val instant = Instant.parse("2026-08-26T17:18:19.123Z")
        val first = BackupFileNames.create(instant, ZoneOffset.UTC, "0123abcd")
        val second = BackupFileNames.create(instant, ZoneOffset.UTC, "89abcdef")

        assertEquals("synthx_backup_2026-08-26_171819_123_0123abcd.db", first.databaseFileName)
        assertEquals("synthx_backup_2026-08-26_171819_123_0123abcd.json", first.metadataFileName)
        assertEquals("synthx_backup_2026-08-26_171819_123_0123abcd.db.partial", first.temporaryDatabaseFileName)
        assertNotEquals(first.stem, second.stem)
        assertTrue(BackupFileNames.isCompletedDatabaseName(first.databaseFileName))
        assertFalse(BackupFileNames.isCompletedDatabaseName(first.temporaryDatabaseFileName))
        assertFalse(BackupFileNames.isCompletedDatabaseName(first.temporaryMetadataFileName))
        assertEquals(100, List(100) { BackupFileNames.create(instant).stem }.toSet().size)
    }

    @Test
    fun `legacy completed names remain parseable while partial files do not`() {
        val oldName = "synthx_backup_2025-01-02_030405.db"
        assertTrue(BackupFileNames.isCompletedDatabaseName(oldName))
        assertNotNull(BackupFileNames.parseTimestampMillis(oldName, ZoneOffset.UTC))
        assertFalse(BackupFileNames.isCompletedDatabaseName("$oldName.partial"))
        assertFalse(BackupFileNames.isCompletedDatabaseName("unrelated.db"))
    }

    @Test
    fun `metadata round trip preserves actual snapshot facts`() {
        val metadata = completeMetadata(dbVersion = 7)
        val decoded = assertNotNull(BackupMetadataCodec.decodeOrNull(BackupMetadataCodec.encode(metadata)))

        assertEquals(7, decoded.dbVersion)
        assertEquals(42L, decoded.sizeBytes)
        assertEquals("ab".repeat(32), decoded.sha256)
        assertEquals(BackupTier.Auto.name, decoded.tier)
        assertEquals(BackupReason.MIGRATION, decoded.reason)
    }

    @Test
    fun `legacy metadata without hash and size remains readable`() {
        val legacy = """
            {
              "timestamp": 1735787045000,
              "tier": "Manual",
              "reason": null,
              "dbVersion": 7,
              "file": "synthx_backup_2025-01-02_030405.db"
            }
        """.trimIndent().encodeToByteArray()

        val decoded = assertNotNull(BackupMetadataCodec.decodeOrNull(legacy))
        assertEquals(7, decoded.dbVersion)
        assertNull(decoded.sizeBytes)
        assertNull(decoded.sha256)
        assertNull(BackupMetadataCodec.decodeOrNull(legacy.decodeToString().replace("7", "\"bad\"").encodeToByteArray()))
    }

    @Test
    fun `incomplete or malformed publication metadata is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            completeMetadata().copy(dbVersion = 0).requireCompleteForPublication()
        }
        assertFailsWith<IllegalArgumentException> {
            completeMetadata().copy(sha256 = "not-a-sha").requireCompleteForPublication()
        }
        assertFailsWith<IllegalArgumentException> {
            completeMetadata().copy(sizeBytes = 0).requireCompleteForPublication()
        }
    }

    @Test
    fun `schema contracts resolve current and known migration versions`() {
        val current = assertNotNull(BackupSchemaContracts.forVersion(SynthXDatabase.VERSION))
        assertEquals(current, BackupSchemaContracts.current)
        // Pinned literal, not sourced from BackupSchemaContracts.current itself — the whole
        // point of this assertion is to catch an accidental hash edit/typo in
        // BackupSchemaContract.kt, which a self-referential comparison could never catch.
        // Update this to the new version's exported app/schemas/.../<N>.json identityHash on
        // every SynthXDatabase.VERSION bump (see CLAUDE.md's migration checklist) — this
        // literal going stale (previously pinned to v9's hash while VERSION had moved to 11)
        // is exactly the bug this comment exists to prevent from recurring silently.
        assertEquals("0c6e349e42c3acb1c0d2fb907a1706df", current.roomIdentityHash)
        assertTrue("songs" in current.expectedTables)
        assertTrue("queue_items" in current.expectedTables)
        assertTrue("queue_baseline_items" in current.expectedTables)
        assertTrue("playback_cursor" in current.expectedTables)

        // A trusted v8 backup is still a valid *source* to migrate forward from — the
        // contract map must keep describing it, not just the current version.
        val previous = assertNotNull(BackupSchemaContracts.forVersion(8))
        assertEquals("5c340ed398e096db822b28b067600167", previous.roomIdentityHash)
        assertTrue(previous.hasMigrationPathToCurrent)

        assertEquals(7, BackupSchemaContracts.forVersion(7)?.userVersion)
        assertTrue(BackupSchemaContracts.forVersion(7)?.hasMigrationPathToCurrent == true)
        val known = 1..SynthXDatabase.VERSION
        assertEquals(known.toSet(), known.mapNotNull(BackupSchemaContracts::forVersion).map { it.userVersion }.toSet())
        assertNull(BackupSchemaContracts.forVersion(99))
    }

    @Test
    fun `process wide backup coordinator serializes callers`() = runTest {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)

        suspend fun operation() = BackupCreationCoordinator.serialize {
            val now = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, now) }
            delay(25)
            active.decrementAndGet()
        }

        val first = async { operation() }
        val second = async { operation() }
        first.await()
        second.await()

        assertEquals(1, maximum.get())
    }

    private fun completeMetadata(dbVersion: Int = 8) = BackupMetadata(
        timestamp = 1_777_777_777_777L,
        tier = BackupTier.Auto.name,
        reason = BackupReason.MIGRATION,
        dbVersion = dbVersion,
        file = "synthx_backup_2026-08-26_171819_123_0123abcd.db",
        sizeBytes = 42,
        sha256 = "ab".repeat(32),
    )
}

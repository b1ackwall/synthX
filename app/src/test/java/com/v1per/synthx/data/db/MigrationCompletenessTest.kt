package com.v1per.synthx.data.db

import com.v1per.synthx.data.backup.BackupSchemaContracts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards against the exact gap N7 found: MIGRATION_9_10 and MIGRATION_10_11 shipped with zero
 * test coverage while Migration8To9Test.kt sat as the only migration test in the repo. Two
 * independent checks, deliberately not merged into one:
 *
 *  1. [allMigrationsFormAContiguousChainWithMatchingSchemaContracts] — ALL_MIGRATIONS and
 *     BackupSchemaContracts must form a gap-free chain. This catches a missing/duplicated
 *     *migration definition or schema contract*.
 *  2. [everyMigrationStepHasADedicatedInstrumentedTest] — a dedicated androidTest file that
 *     actually exercises MigrationTestHelper must exist for every single-version step from
 *     [PER_STEP_TEST_COVERAGE_BASELINE_VERSION] onward. This is the check that would have caught
 *     the *real* historical gap: when 9->10 and 10->11 shipped untested, check (1) was already
 *     fully satisfied — Phase 5 correctly added both migrations to ALL_MIGRATIONS and both
 *     contracts to BackupSchemaContracts. A chain/contract-only completeness check would have
 *     passed silently right through the missing-test gap; only a check that inspects the test
 *     tree itself catches it.
 *
 *     The baseline is deliberately 8, not the oldest supported version (1): per N7's own
 *     discovery, Migration8To9Test.kt was already the *only* per-step migration test file in the
 *     repo before this fix — versions 1->2 through 7->8 never had one. Retroactively demanding
 *     six legacy test files for migrations untouched by this sweep is a separate, larger task;
 *     this check instead holds the line at the precedent Migration8To9Test.kt already set, so no
 *     *future* migration can ship the same way 9->10/10->11 did.
 */
class MigrationCompletenessTest {
    companion object {
        private const val PER_STEP_TEST_COVERAGE_BASELINE_VERSION = 8
    }

    @Test
    fun allMigrationsFormAContiguousChainWithMatchingSchemaContracts() {
        val migrations = SynthXDatabase.ALL_MIGRATIONS
        assertTrue(migrations.isNotEmpty(), "ALL_MIGRATIONS must not be empty")

        migrations.forEach { migration ->
            assertEquals(
                migration.startVersion + 1,
                migration.endVersion,
                "Migration ${migration.startVersion}->${migration.endVersion} is not a " +
                    "single-version step",
            )
        }

        val oldestSupportedVersion = migrations.minOf { it.startVersion }
        val actualStarts = migrations.map { it.startVersion }.sorted()
        val expectedStarts = (oldestSupportedVersion until SynthXDatabase.VERSION).toList()
        assertEquals(
            expectedStarts,
            actualStarts,
            "ALL_MIGRATIONS must contain exactly one step per version from " +
                "$oldestSupportedVersion to ${SynthXDatabase.VERSION}, with no gaps or duplicates",
        )

        (oldestSupportedVersion..SynthXDatabase.VERSION).forEach { version ->
            val schemaPath = "schemas/com.v1per.synthx.data.db.SynthXDatabase/$version.json"
            assertTrue(
                listOf(File(schemaPath), File("app/$schemaPath")).any { it.isFile },
                "Missing exported Room schema for version $version",
            )
            assertTrue(
                BackupSchemaContracts.forVersion(version) != null,
                "BackupSchemaContracts has no entry for version $version",
            )
        }
    }

    @Test
    fun everyMigrationStepHasADedicatedInstrumentedTest() {
        val directory = androidTestDirectory("com/v1per/synthx/data/db")

        (PER_STEP_TEST_COVERAGE_BASELINE_VERSION until SynthXDatabase.VERSION).forEach { start ->
            val end = start + 1
            val fileName = "Migration${start}To${end}Test.kt"
            val file = File(directory, fileName)
            assertTrue(
                file.isFile,
                "No dedicated migration test found for $start->$end — expected $fileName under " +
                    directory.path,
            )
            val source = file.readText()
            assertTrue(
                source.contains("MigrationTestHelper") && source.contains("runMigrationsAndValidate"),
                "$fileName must exercise MigrationTestHelper.runMigrationsAndValidate, not just " +
                    "assert the migration object exists",
            )
            assertTrue(
                source.contains("MIGRATION_${start}_$end"),
                "$fileName must reference MIGRATION_${start}_$end",
            )
        }
    }

    private fun androidTestDirectory(relative: String): File {
        val candidates = listOf(
            File("src/androidTest/java", relative),
            File("app/src/androidTest/java", relative),
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("Could not locate androidTest directory $relative")
    }
}

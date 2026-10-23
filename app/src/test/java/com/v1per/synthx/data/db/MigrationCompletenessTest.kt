package com.v1per.synthx.data.db

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards against a migration shipping without its own test. Two independent checks,
 * deliberately not merged into one:
 *
 *  1. [allMigrationsFormAContiguousChainWithExportedSchemas] — ALL_MIGRATIONS must form
 *     a gap-free chain. This catches a missing/duplicated *migration definition*; the schema
 *     contracts are checked by BackupSchemaContractExportTest.
 *  2. [everyMigrationStepHasADedicatedInstrumentedTest] — a dedicated androidTest file that
 *     actually exercises MigrationTestHelper must exist for every single-version step from
 *     [PER_STEP_TEST_COVERAGE_BASELINE_VERSION] onward. Check (1) is fully satisfied by a
 *     migration that is in ALL_MIGRATIONS with its contract in BackupSchemaContracts but has no
 *     test at all, so a chain/contract-only check passes silently through a missing-test gap;
 *     only a check that inspects the test tree itself catches it.
 *
 *     The baseline is deliberately 8, not the oldest supported version (1): versions 1->2
 *     through 7->8 have no per-step test file, and Migration8To9Test.kt is the first. Demanding
 *     six legacy test files for those migrations is a separate, larger task; this check holds
 *     the line from Migration8To9Test.kt on, so no *future* migration can ship untested.
 */
class MigrationCompletenessTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    companion object {
        private const val PER_STEP_TEST_COVERAGE_BASELINE_VERSION = 8
    }

    @Test
    fun allMigrationsFormAContiguousChainWithExportedSchemas() {
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

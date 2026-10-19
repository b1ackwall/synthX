package com.v1per.synthx.data.backup

import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every backup schema contract matches the schema Room exported for that version: the same
 * identity hash and the same table set. A typo in a hash or a dropped table would otherwise
 * only show when a real backup of that version is restored.
 */
class BackupSchemaContractExportTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun everySchemaContractMatchesItsExportedRoomSchema() {
        val schemas = File("schemas/com.v1per.synthx.data.db.SynthXDatabase")
        assertTrue(schemas.isDirectory, "exported schemas not found from ${File(".").absolutePath}")
        (1..SynthXDatabase.VERSION).forEach { version ->
            val database = Json.parseToJsonElement(File(schemas, "$version.json").readText())
                .jsonObject.getValue("database").jsonObject
            val contract = assertNotNull(BackupSchemaContracts.forVersion(version), "no contract for v$version")
            assertEquals(
                database.getValue("identityHash").jsonPrimitive.content,
                contract.roomIdentityHash,
                "v$version identity hash",
            )
            assertEquals(
                database.getValue("entities").jsonArray
                    .map { it.jsonObject.getValue("tableName").jsonPrimitive.content }
                    .toSet(),
                contract.expectedTables,
                "v$version tables",
            )
        }
    }
}

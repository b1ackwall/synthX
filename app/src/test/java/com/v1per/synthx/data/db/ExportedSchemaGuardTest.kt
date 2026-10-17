package com.v1per.synthx.data.db

import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reads the exported Room schemas for details the identity hash cannot see. Room's hash
 * ignores column collation, so v15 and v16 share one hash and differ only in albums.title
 * being COLLATE NOCASE.
 */
class ExportedSchemaGuardTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test fun onlyTheKnownVersionPairsShareAnIdentityHash() {
        val hashes = (1..SynthXDatabase.VERSION).associateWith { version ->
            database(version).getValue("identityHash").jsonPrimitive.content
        }
        val sharing = hashes.keys.flatMap { a ->
            hashes.keys.filter { b -> b > a && hashes[a] == hashes[b] }.map { b -> a to b }
        }.toSet()
        assertEquals(
            KNOWN_SAME_HASH_PAIRS,
            sharing,
            "Version pairs sharing an identity hash changed. A new pair needs a schema detail " +
                "the hash ignores (collation, for example) checked here, and a known-pair entry.",
        )
    }

    @Test fun v15AndV16DifferOnlyInTheCaseBlindAlbumTitle() {
        val v15 = createSqlByTable(15)
        val v16 = createSqlByTable(16)
        assertEquals(v15.keys, v16.keys, "v15 and v16 table sets")
        assertEquals(setOf("albums"), v15.keys.filter { v15[it] != v16[it] }.toSet(), "tables that differ")
        assertTrue(
            v16.getValue("albums").contains("`title` TEXT NOT NULL COLLATE NOCASE"),
            "16.json albums.title must be COLLATE NOCASE: ${v16["albums"]}",
        )
        assertFalse(v15.getValue("albums").contains("NOCASE"), "15.json albums.title must not be NOCASE")
    }

    @Test fun currentSchemaKeepsTheCaseBlindIndexedAlbumTitle() {
        val albums = entity(SynthXDatabase.VERSION, "albums")
        assertTrue(
            albums.getValue("createSql").jsonPrimitive.content.contains("`title` TEXT NOT NULL COLLATE NOCASE"),
            "Current exported schema lost COLLATE NOCASE on albums.title",
        )
        val titleIndexes = albums.getValue("indices").jsonArray.map { it.jsonObject }.filter { index ->
            index.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content } == listOf("title")
        }
        assertEquals(listOf("index_albums_title"), titleIndexes.map { it.getValue("name").jsonPrimitive.content })
    }

    private fun database(version: Int): JsonObject {
        val schemas = File("schemas/com.v1per.synthx.data.db.SynthXDatabase")
        assertTrue(schemas.isDirectory, "exported schemas not found from ${File(".").absolutePath}")
        return Json.parseToJsonElement(File(schemas, "$version.json").readText())
            .jsonObject.getValue("database").jsonObject
    }

    private fun entities(version: Int) = database(version).getValue("entities").jsonArray.map { it.jsonObject }

    private fun entity(version: Int, table: String) =
        entities(version).single { it.getValue("tableName").jsonPrimitive.content == table }

    private fun createSqlByTable(version: Int) = entities(version).associate {
        it.getValue("tableName").jsonPrimitive.content to it.getValue("createSql").jsonPrimitive.content
    }

    private companion object {
        /** 3->4 only deletes rows; 15->16 only changes albums.title collation in the schema. */
        val KNOWN_SAME_HASH_PAIRS = setOf(3 to 4, 15 to 16)
    }
}

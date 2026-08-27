package com.v1per.synthx.data.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupRetentionTest {
    @Test
    fun `only coherent complete final pairs enter retention`() {
        val valid = listOf(pair("00000001", 100), pair("00000002", 200), pair("00000003", 300))
        val orphanDb = db("00000004")
        val orphanMetadata = json("00000005")
        val malformedDb = db("00000006")
        val malformedMetadata = json("00000006")
        val partialDb = artifact(name("00000007") + ".partial", 42)
        val partialMetadata = artifact(name("00000008").removeSuffix(".db") + ".json.partial", 5)
        val mismatchedDb = db("00000009")
        val mismatchedMetadata = json("00000009")
        val artifacts = valid.flatMap { it.artifacts } + listOf(
            orphanDb, orphanMetadata, malformedDb, malformedMetadata,
            partialDb, partialMetadata, mismatchedDb, mismatchedMetadata,
        )
        val metadata = buildMap<String, BackupMetadata?> {
            valid.forEach { put(it.metadataDocument.document, it.metadata) }
        }.toMutableMap()
        metadata[malformedMetadata.document] = null
        metadata[mismatchedMetadata.document] = completeMetadata(
            file = name("ffffffff"),
            timestamp = 900,
        )

        val completed = enumerate(artifacts, metadata)

        assertEquals(valid.map { it.database.name }.toSet(), completed.map { it.databaseName }.toSet())
    }

    @Test
    fun `orphan database cannot evict a valid backup`() {
        val valid = listOf(pair("00000001", 100), pair("00000002", 200), pair("00000003", 300))
        val completed = enumerate(valid.flatMap { it.artifacts } + db("00000004"), metadataOf(valid))
        val deleted = mutableListOf<String>()

        BackupRetention.prune(completed, 3, name("00000003")) { deleted += it.databaseName }

        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `clock skew cannot prune current publication and limit remains enforced`() {
        val future = listOf(pair("00000001", 4_000), pair("00000002", 5_000), pair("00000003", 6_000))
        val current = pair("00000004", 100)
        val all = future + current
        val completed = enumerate(all.flatMap { it.artifacts }, metadataOf(all))
        val deleted = mutableListOf<String>()

        BackupRetention.prune(completed, 3, current.database.name) { deleted += it.databaseName }

        assertEquals(listOf(future[0].database.name), deleted)
        assertFalse(current.database.name in deleted)
        assertEquals(3, completed.size - deleted.size)
    }

    @Test
    fun `deletion failure is contained and current publication survives`() {
        val old = pair("00000001", 1)
        val current = pair("00000002", 0)
        val completed = enumerate((old.artifacts + current.artifacts), metadataOf(listOf(old, current)))
        var currentDeleteAttempted = false

        val result = runCatching {
            BackupRetention.prune(completed, 1, current.database.name) { victim ->
                currentDeleteAttempted = victim.databaseName == current.database.name
                error("provider refused deletion")
            }
        }

        assertTrue(result.isSuccess)
        assertFalse(currentDeleteAttempted)
    }

    @Test
    fun `partial pair deletion leaves an orphan that does not count next pass`() {
        val first = pair("00000001", 1)
        val second = pair("00000002", 2)
        val artifacts = (first.artifacts + second.artifacts).toMutableList()
        val metadata = metadataOf(listOf(first, second))
        val completed = enumerate(artifacts, metadata)

        BackupRetention.prune(completed, 1, second.database.name) { victim ->
            artifacts.removeAll { it.document == victim.database.document }
            // Simulate metadata deletion failure.
        }

        val next = enumerate(artifacts, metadata)
        assertEquals(listOf(second.database.name), next.map { it.databaseName })
    }

    @Test
    fun `tiers are isolated by metadata identity`() {
        val manual = pair("00000001", 1, BackupTier.Manual)
        val daily = pair("00000002", 2, BackupTier.Daily)
        val artifacts = manual.artifacts + daily.artifacts
        val metadata = metadataOf(listOf(manual, daily))

        assertEquals(listOf(manual.database.name), enumerate(artifacts, metadata, BackupTier.Manual).map { it.databaseName })
        assertEquals(listOf(daily.database.name), enumerate(artifacts, metadata, BackupTier.Daily).map { it.databaseName })
    }

    @Test
    fun `equal timestamps choose a deterministic stable victim`() {
        val pairs = listOf(pair("0000000c", 10), pair("0000000a", 10), pair("0000000b", 10))
        val completed = enumerate(pairs.flatMap { it.artifacts }, metadataOf(pairs))
        val victims = mutableListOf<String>()

        BackupRetention.prune(completed.shuffled(), 2, pairs[0].database.name) { victims += it.databaseName }

        assertEquals(listOf(name("0000000a")), victims)
    }

    @Test
    fun `orphan malformed and partial artifacts are never trusted restore pairs`() {
        val orphan = db("00000001")
        val malformedDb = db("00000002")
        val malformedJson = json("00000002")
        val partial = artifact(name("00000003") + ".partial", 42)
        val metadata = mapOf<String, BackupMetadata?>(malformedJson.document to null)

        assertTrue(enumerate(listOf(orphan, malformedDb, malformedJson, partial), metadata).isEmpty())
    }

    private data class PairFixture(
        val database: BackupArtifact<String>,
        val metadataDocument: BackupArtifact<String>,
        val metadata: BackupMetadata,
    ) {
        val artifacts get() = listOf(database, metadataDocument)
    }

    private fun pair(
        id: String,
        timestamp: Long,
        tier: BackupTier = BackupTier.Manual,
    ): PairFixture {
        val database = db(id)
        val metadataDocument = json(id)
        return PairFixture(
            database,
            metadataDocument,
            completeMetadata(database.name, timestamp, tier),
        )
    }

    private fun enumerate(
        artifacts: List<BackupArtifact<String>>,
        metadata: Map<String, BackupMetadata?>,
        tier: BackupTier = BackupTier.Manual,
    ) = CompletedBackupCatalog.enumerate(tier, artifacts) { metadata[it] }

    private fun metadataOf(pairs: List<PairFixture>): Map<String, BackupMetadata> =
        pairs.associate { it.metadataDocument.document to it.metadata }

    private fun db(id: String) = artifact(name(id), 42)
    private fun json(id: String) = artifact(name(id).removeSuffix(".db") + ".json", 200)
    private fun artifact(fileName: String, size: Long) = BackupArtifact(
        document = fileName,
        name = fileName,
        isFile = true,
        sizeBytes = size,
    )

    private fun name(id: String) = "synthx_backup_2026-08-27_120000_000_$id.db"

    private fun completeMetadata(
        file: String,
        timestamp: Long,
        tier: BackupTier = BackupTier.Manual,
    ) = BackupMetadata(
        timestamp = timestamp.coerceAtLeast(1),
        tier = tier.name,
        dbVersion = 8,
        file = file,
        sizeBytes = 42,
        sha256 = "ab".repeat(32),
    )
}

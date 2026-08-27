package com.v1per.synthx.data.backup

import com.v1per.synthx.data.db.SynthXDatabase

/**
 * Minimal backup-validation facts copied from Room's exported schemas in app/schemas.
 *
 * This deliberately does not reproduce columns, indices, or foreign keys. Room's exported schema
 * and migrations remain the schema source of truth; this map only identifies which exported schema
 * a standalone snapshot claims to be and which managed tables must be present.
 */
internal data class BackupSchemaContract(
    val userVersion: Int,
    val roomIdentityHash: String,
    val expectedTables: Set<String>,
    val hasMigrationPathToCurrent: Boolean,
)

internal object BackupSchemaContracts {
    private val coreTables = setOf(
        "songs",
        "artists",
        "albums",
        "song_artists",
        "playlist_groups",
        "playlists",
        "playlist_songs",
    )

    private val contracts = listOf(
        BackupSchemaContract(
            userVersion = 1,
            roomIdentityHash = "125da6b603c6298a972408a3c7561382",
            expectedTables = coreTables + "listen_history",
            hasMigrationPathToCurrent = true,
        ),
        BackupSchemaContract(
            userVersion = 2,
            roomIdentityHash = "447de7d24582f27ee5bf5716f35b2777",
            expectedTables = coreTables + "listen_history",
            hasMigrationPathToCurrent = true,
        ),
        BackupSchemaContract(
            userVersion = 3,
            roomIdentityHash = "9eb7746b1677789626c64e6befbddb1f",
            expectedTables = coreTables + "listen_history",
            hasMigrationPathToCurrent = true,
        ),
        BackupSchemaContract(
            userVersion = 4,
            roomIdentityHash = "9eb7746b1677789626c64e6befbddb1f",
            expectedTables = coreTables + "listen_history",
            hasMigrationPathToCurrent = true,
        ),
        BackupSchemaContract(
            userVersion = 5,
            roomIdentityHash = "1237ac598986d9b545ce676959b1b3eb",
            expectedTables = coreTables + setOf("listen_history", "queue_items"),
            hasMigrationPathToCurrent = true,
        ),
        BackupSchemaContract(
            userVersion = 6,
            roomIdentityHash = "84fe42e20bd697d7e1cb2552f7703f3b",
            expectedTables = coreTables +
                setOf("listen_history", "queue_items", "queue_baseline_items"),
            hasMigrationPathToCurrent = true,
        ),
        BackupSchemaContract(
            userVersion = 7,
            roomIdentityHash = "4f31bd97f28e57843848298fd62f2976",
            expectedTables = coreTables + setOf("queue_items", "queue_baseline_items"),
            hasMigrationPathToCurrent = true,
        ),
        BackupSchemaContract(
            userVersion = 8,
            roomIdentityHash = "5c340ed398e096db822b28b067600167",
            expectedTables = coreTables + setOf("queue_items", "queue_baseline_items"),
            hasMigrationPathToCurrent = true,
        ),
    ).associateBy { it.userVersion }

    val current: BackupSchemaContract
        get() = checkNotNull(contracts[SynthXDatabase.VERSION]) {
            "No backup schema contract for current database version ${SynthXDatabase.VERSION}"
        }

    fun forVersion(userVersion: Int): BackupSchemaContract? = contracts[userVersion]
}

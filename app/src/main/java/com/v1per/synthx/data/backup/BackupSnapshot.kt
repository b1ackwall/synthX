package com.v1per.synthx.data.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.RandomAccessFile

internal class BackupSnapshotException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal data class ValidatedBackupSnapshot(
    val file: File,
    val schema: BackupSchemaContract,
)

internal object BackupSnapshotter {
    fun createFromRoom(source: SupportSQLiteDatabase, destination: File) {
        if (source.inTransaction()) {
            throw BackupSnapshotException("Cannot create a backup from inside a database transaction")
        }
        checkDestination(destination)
        requireVacuumIntoSupport(querySQLiteVersion(source))
        try {
            source.execSQL("VACUUM INTO ?", arrayOf(destination.absolutePath))
        } catch (error: Exception) {
            throw BackupSnapshotException("Local VACUUM INTO snapshot failed", error)
        }
    }

    fun createForMigration(sourceFile: File, destination: File) {
        checkDestination(destination)
        SQLiteDatabase.openDatabase(
            sourceFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { source ->
            if (source.inTransaction()) {
                throw BackupSnapshotException(
                    "Cannot create a migration backup from inside a database transaction"
                )
            }
            requireVacuumIntoSupport(querySQLiteVersion(source))
            try {
                source.execSQL("VACUUM INTO ?", arrayOf(destination.absolutePath))
            } catch (error: Exception) {
                throw BackupSnapshotException("Local migration VACUUM INTO snapshot failed", error)
            }
        }
    }

    fun forceToStorage(snapshot: File) {
        if (!snapshot.isFile || snapshot.length() <= 0L) {
            throw BackupSnapshotException("Local snapshot file is missing or empty")
        }
        try {
            RandomAccessFile(snapshot, "rw").channel.use { channel -> channel.force(true) }
        } catch (error: Exception) {
            throw BackupSnapshotException("Could not force local snapshot to storage", error)
        }
    }

    private fun checkDestination(destination: File) {
        val parent = destination.parentFile
            ?: throw BackupSnapshotException("Local snapshot has no staging directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw BackupSnapshotException("Could not create local backup staging directory")
        }
        if (!parent.isDirectory) {
            throw BackupSnapshotException("Local backup staging path is not a directory")
        }
        if (destination.exists()) {
            throw BackupSnapshotException("Local snapshot destination already exists")
        }
    }

    private fun querySQLiteVersion(source: SupportSQLiteDatabase): String =
        source.query("SELECT sqlite_version()").use(::readSingleString)

    private fun querySQLiteVersion(source: SQLiteDatabase): String =
        source.rawQuery("SELECT sqlite_version()", null).use(::readSingleString)

    private fun readSingleString(cursor: Cursor): String {
        if (!cursor.moveToFirst() || cursor.columnCount < 1) {
            throw BackupSnapshotException("SQLite engine did not report its version")
        }
        return cursor.getString(0)
            ?: throw BackupSnapshotException("SQLite engine reported an empty version")
    }

    private fun requireVacuumIntoSupport(rawVersion: String) {
        val version = SQLiteVersion.parse(rawVersion)
            ?: throw BackupSnapshotException("Unsupported malformed SQLite version: $rawVersion")
        if (version < SQLiteVersion.VACUUM_INTO_MINIMUM) {
            throw BackupSnapshotException(
                "SQLite $rawVersion is unsupported; backups require SQLite 3.27.0 or newer"
            )
        }
    }
}

internal object BackupSnapshotValidator {
    fun validate(snapshot: File, migrationSnapshot: Boolean): ValidatedBackupSnapshot {
        if (!snapshot.isFile || snapshot.length() <= 0L) {
            throw BackupSnapshotException("Local snapshot file is missing or empty")
        }

        try {
            SQLiteDatabase.openDatabase(
                snapshot.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                validateIntegrity(database)
                validateForeignKeys(database)

                val userVersion = database.singleInt("PRAGMA user_version")
                val schema = BackupSchemaContracts.forVersion(userVersion)
                    ?: throw BackupSnapshotException(
                        "Snapshot uses unknown synthX database version $userVersion"
                    )

                if (migrationSnapshot) {
                    if (!schema.hasMigrationPathToCurrent) {
                        throw BackupSnapshotException(
                            "No supported migration path exists from snapshot version $userVersion"
                        )
                    }
                } else if (userVersion != BackupSchemaContracts.current.userVersion) {
                    throw BackupSnapshotException(
                        "Current backup has database version $userVersion; expected " +
                            BackupSchemaContracts.current.userVersion
                    )
                }

                validateRoomIdentity(database, schema)
                validateExpectedTables(database, schema)
                return ValidatedBackupSnapshot(snapshot, schema)
            }
        } catch (error: BackupSnapshotException) {
            throw error
        } catch (error: Exception) {
            throw BackupSnapshotException("Local snapshot validation failed", error)
        }
    }

    private fun validateIntegrity(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            val valid = cursor.moveToFirst() &&
                cursor.columnCount > 0 &&
                cursor.getString(0) == "ok" &&
                !cursor.moveToNext()
            if (!valid) throw BackupSnapshotException("Snapshot failed SQLite integrity_check")
        }
    }

    private fun validateForeignKeys(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            if (cursor.moveToFirst()) {
                throw BackupSnapshotException("Snapshot failed SQLite foreign_key_check")
            }
        }
    }

    private fun validateRoomIdentity(
        database: SQLiteDatabase,
        schema: BackupSchemaContract,
    ) {
        val tables = database.tableNames()
        if (ROOM_MASTER_TABLE !in tables) {
            throw BackupSnapshotException("Snapshot is missing Room identity table")
        }

        val identity = database.rawQuery(
            "SELECT identity_hash FROM room_master_table WHERE id = 42 LIMIT 1",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        if (identity != schema.roomIdentityHash) {
            throw BackupSnapshotException(
                "Snapshot Room identity does not match exported schema version ${schema.userVersion}"
            )
        }
    }

    private fun validateExpectedTables(
        database: SQLiteDatabase,
        schema: BackupSchemaContract,
    ) {
        val actual = database.tableNames()
        val missing = schema.expectedTables - actual
        if (missing.isNotEmpty()) {
            throw BackupSnapshotException(
                "Snapshot is missing expected Room tables: ${missing.sorted().joinToString()}"
            )
        }
    }

    private fun SQLiteDatabase.singleInt(sql: String): Int =
        rawQuery(sql, null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.columnCount < 1) {
                throw BackupSnapshotException("Snapshot did not return a value for $sql")
            }
            cursor.getInt(0)
        }

    private fun SQLiteDatabase.tableNames(): Set<String> =
        rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private const val ROOM_MASTER_TABLE = "room_master_table"
}

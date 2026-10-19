package com.v1per.synthx.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The state observed directly from the live SQLite database, before Room is constructed. */
internal sealed interface LiveDatabaseVersion {
    data object Missing : LiveDatabaseVersion

    /**
     * The file exists and SQLite opens it, but it reports `user_version = 0` AND
     * `sqlite_master` has zero tables. This is the shape left behind by a first-ever database
     * creation interrupted before Room's schema-creation transaction ever committed (the file
     * was created, but nothing was written into it) — never bumped from a real, previously
     * migrated database. It is deliberately distinct from [Present] with `userVersion == 0`:
     * a file with real tables that merely reports version 0 (e.g. a pre-Room or corrupted file)
     * must still fail closed through the ordinary migration/snapshot path below, not be treated
     * as fresh.
     */
    data object EmptyFile : LiveDatabaseVersion
    data class Present(val userVersion: Int) : LiveDatabaseVersion
}

/** The startup action derived exclusively from the live database, never from preferences. */
internal sealed interface DatabaseVersionDecision {
    data object FreshDatabase : DatabaseVersionDecision
    data object CurrentDatabase : DatabaseVersionDecision
    data class BackupBeforeMigration(val fromVersion: Int) : DatabaseVersionDecision
    data class UnsupportedNewerDatabase(val actualVersion: Int) : DatabaseVersionDecision
}

internal object DatabaseVersionPolicy {
    fun decide(live: LiveDatabaseVersion, currentVersion: Int): DatabaseVersionDecision = when (live) {
        LiveDatabaseVersion.Missing,
        LiveDatabaseVersion.EmptyFile,
        -> DatabaseVersionDecision.FreshDatabase

        is LiveDatabaseVersion.Present -> when {
            live.userVersion == currentVersion -> DatabaseVersionDecision.CurrentDatabase

            live.userVersion < currentVersion ->
                DatabaseVersionDecision.BackupBeforeMigration(live.userVersion)

            else -> DatabaseVersionDecision.UnsupportedNewerDatabase(live.userVersion)
        }
    }
}

internal class UnsupportedLiveDatabaseVersionException(actualVersion: Int, currentVersion: Int) :
    IllegalStateException(
        "The live database uses schema version $actualVersion, but this synthX build supports " +
            "version $currentVersion. Downgrade is disabled.",
    )

/**
 * Reads SQLite's live [android.database.sqlite.SQLiteDatabase] view of `user_version`.
 *
 * A read-only framework connection does not invoke Room or any Room migration. Opening the
 * database through SQLite (instead of parsing page one as a plain file) is important because
 * SQLite then observes a committed page-one value that is still resident in a normal WAL.
 */
internal object LiveDatabaseVersionReader {
    fun read(databaseFile: File): LiveDatabaseVersion {
        if (!databaseFile.exists()) return LiveDatabaseVersion.Missing
        check(databaseFile.isFile) { "Live database path is not a regular file" }

        return SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            val version = database.rawQuery("PRAGMA user_version", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.columnCount >= 1) {
                    "SQLite did not report the live database user_version"
                }
                cursor.getInt(0)
            }
            check(version >= 0) { "SQLite reported an invalid live database user_version" }

            // Version 0 is ambiguous on its own — it is both SQLite's default for a
            // file that never had a schema committed to it (interrupted first-ever creation)
            // AND a value a real, populated file could report (pre-Room or corrupted). Only
            // the absence of any table in sqlite_master proves the former; a populated file
            // must still fall through to Present(0) so it fails closed via the normal
            // migration/snapshot path.
            if (version == 0 &&
                BackupSnapshotValidator.tableNames { sql -> database.rawQuery(sql, null) }.isEmpty()
            ) {
                LiveDatabaseVersion.EmptyFile
            } else {
                LiveDatabaseVersion.Present(version)
            }
        }
    }
}

/**
 * Testable single-flight state machine behind the process singleton.
 *
 * Every caller present during one attempt awaits the same deferred result. A successful attempt
 * permanently opens the process gate. A failed attempt leaves it closed and clears only the
 * failed flight, so a later call can make a new attempt against the still-unmigrated live file.
 */
internal class DatabaseStartupController(
    private val currentVersion: Int,
    private val awaitRestoreNormal: suspend () -> Unit,
    private val inspectLiveVersion: suspend () -> LiveDatabaseVersion,
    private val performMigration: suspend (fromVersion: Int) -> Unit,
    /** True while a previous migration's [MigrationInvariantCheck] failure hasn't been recovered. */
    private val hasPendingMigrationRecovery: suspend () -> Boolean = { false },
    /**
     * Consumes every leftover `pending_cleanups` record before the gate opens, so no
     * repository/ViewModel code can ever observe a half-reconciled destructive operation. Runs
     * after any pending migration (a fresh/current database has nothing to reconcile either way)
     * and before [onReady].
     */
    private val reconcilePendingCleanups: suspend () -> Unit = {},
    /** Fired once per process, the first time this attempt reaches readiness. */
    private val onReady: suspend () -> Unit = {},
) {
    private val lock = Any()

    @Volatile private var ready = false
    private var running: CompletableDeferred<Result<Unit>>? = null

    suspend fun awaitReady() {
        if (ready) return

        val (flight, ownsFlight) = synchronized(lock) {
            if (ready) return
            val active = running
            if (active != null) {
                active to false
            } else {
                CompletableDeferred<Result<Unit>>().also { running = it } to true
            }
        }

        if (ownsFlight) {
            // Startup safety belongs to the process, not to whichever Activity/Worker happened
            // to arrive first. Caller cancellation must not abandon the shared backup mid-flight.
            val outcome = withContext(NonCancellable) {
                runCatching {
                    awaitRestoreNormal()
                    // Checked before re-deriving anything from the live file's own user_version:
                    // a migration that failed its post-migration check already left the file
                    // reading as "current" — decide() alone would wrongly call that fine.
                    if (hasPendingMigrationRecovery()) {
                        throw MigrationInvariantFailedException(
                            "A previous migration failed its post-migration safety check and " +
                                "has not been recovered yet",
                        )
                    }
                    when (
                        val decision = DatabaseVersionPolicy.decide(
                            inspectLiveVersion(),
                            currentVersion,
                        )
                    ) {
                        DatabaseVersionDecision.FreshDatabase,
                        DatabaseVersionDecision.CurrentDatabase,
                        -> Unit

                        is DatabaseVersionDecision.BackupBeforeMigration ->
                            performMigration(decision.fromVersion)

                        is DatabaseVersionDecision.UnsupportedNewerDatabase ->
                            throw UnsupportedLiveDatabaseVersionException(
                                decision.actualVersion,
                                currentVersion,
                            )
                    }
                    reconcilePendingCleanups()
                    onReady()
                }
            }

            synchronized(lock) {
                if (outcome.isSuccess) ready = true
                flight.complete(outcome)
                check(running === flight) { "Database startup flight changed unexpectedly" }
                running = null
            }
        }

        flight.await().getOrThrow()
    }

    fun requireReady() {
        check(ready) {
            "Room cannot open until restore recovery and pre-migration maintenance complete"
        }
    }

    fun isReady(): Boolean = ready

    internal fun markReadyForTest() {
        synchronized(lock) {
            check(running == null) { "Cannot force readiness during a startup attempt" }
            ready = true
        }
    }
}

/** One authoritative process-wide permission boundary for opening the live Room database. */
internal object DatabaseStartupGate {
    private const val TAG = "DatabaseStartupGate"
    private val lock = Any()

    @Volatile private var controller: DatabaseStartupController? = null

    /** Called from [android.app.Application.onCreate]; it deliberately performs no database I/O. */
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (controller != null) return
        synchronized(lock) {
            if (controller == null) controller = productionController(appContext)
        }
    }

    suspend fun awaitReady() = requireController().awaitReady()

    /** Synchronous Room factories fail closed; potentially long startup work is never blocked here. */
    fun requireReady() = requireController().requireReady()

    fun isReady(): Boolean = controller?.isReady() == true

    private fun requireController(): DatabaseStartupController = checkNotNull(controller) {
        "Database startup gate was not initialized by SynthXApplication"
    }

    private fun productionController(context: Context) = DatabaseStartupController(
        currentVersion = SynthXDatabase.VERSION,
        awaitRestoreNormal = {
            withContext(Dispatchers.IO) { RestoreStartup.requireNormal() }
        },
        inspectLiveVersion = {
            withContext(Dispatchers.IO) {
                LiveDatabaseVersionReader.read(context.getDatabasePath(SynthXDatabase.DB_NAME))
            }
        },
        hasPendingMigrationRecovery = {
            withContext(Dispatchers.IO) {
                MigrationRecoveryGate.isRecoveryStillRequired(context)
            }
        },
        performMigration = { fromVersion ->
            withContext(Dispatchers.IO) { performProductionMigration(context, fromVersion) }
        },
        reconcilePendingCleanups = {
            withContext(Dispatchers.IO) {
                val database = SynthXDatabase.getInstanceForMigrationGate(context)
                PendingCleanupReconciliation.reconcile(context, database)
            }
        },
        onReady = {
            withContext(Dispatchers.IO) { MigrationSnapshotStore.recordSuccessfulLaunch(context) }
        },
    )

    /**
     * The gate's single production migration path:
     *  1. An unconditional internal snapshot — required. Its failure is a genuine storage
     *     problem, not a SAF misconfiguration, and correctly fails the gate closed; there is no
     *     safety net to protect the migration otherwise.
     *  2. A best-effort SAF backup — an extra copy in the user's normal backup folder if one
     *     happens to be configured and reachable. Its failure must never block startup: the
     *     internal snapshot above is what actually protects the migration, so a missing or
     *     broken SAF grant cannot lock the app out.
     *  3. The migration itself, forced to run now (inside the gate, before any repository or
     *     ViewModel can touch Room) and immediately validated. A failure here means recovery is
     *     offered from the internal snapshot, never a silent continue or an uncaught crash later.
     */
    private suspend fun performProductionMigration(context: Context, fromVersion: Int) {
        val toVersion = SynthXDatabase.VERSION
        MigrationSnapshotStore.createSnapshot(context, fromVersion, toVersion)

        runCatching { BackupManager.create(context).createPreMigrationBackup().getOrThrow() }
            .onFailure { Log.w(TAG, "Best-effort SAF pre-migration backup failed", it) }

        // Durably recorded BEFORE Room's forced open below commits the
        // migration. A death after that commit but before `verify` runs leaves this marker
        // behind; MigrationRecoveryGate treats its mere presence as proof validation never
        // provably completed, rather than trusting the live file's now-current user_version.
        MigrationValidationMarker.write(context, fromVersion, toVersion)
        try {
            val database = SynthXDatabase.getInstanceForMigrationGate(context)
            database.openHelper.writableDatabase // forces Room's migration to run now
            MigrationInvariantCheck.verify(database, toVersion)
            MigrationValidationMarker.clear(context)
        } catch (error: Exception) {
            val committed = PreferencesManager(context).setMigrationInvariantFailurePendingDurably(true)
            if (!committed) {
                Log.e(TAG, "Could not durably persist migrationInvariantFailurePending=true")
            }
            // Marker is deliberately left in place on failure — see MigrationRecoveryGate,
            // which treats its presence alone as sufficient even if the commit() above also
            // somehow failed to land.
            throw MigrationInvariantFailedException(
                "Migration to version $toVersion failed its post-migration safety check",
                error,
            )
        }
    }

    /** Instrumentation isolation only; production never replaces the process gate. */
    internal fun installForTest(value: DatabaseStartupController) {
        synchronized(lock) { controller = value }
    }

    /** Lets restore instrumentation model a process whose DatabaseStartupGate is ready. */
    internal fun markReadyForTest() {
        val forced = DatabaseStartupController(
            currentVersion = SynthXDatabase.VERSION,
            awaitRestoreNormal = {},
            inspectLiveVersion = { LiveDatabaseVersion.Missing },
            performMigration = {},
        ).also(DatabaseStartupController::markReadyForTest)
        installForTest(forced)
    }

    /** Restores lazy production behavior after an instrumentation-specific injected gate. */
    internal fun resetForTest(context: Context) {
        synchronized(lock) { controller = productionController(context.applicationContext) }
    }
}

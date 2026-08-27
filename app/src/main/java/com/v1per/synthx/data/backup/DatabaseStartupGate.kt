package com.v1per.synthx.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The state observed directly from the live SQLite database, before Room is constructed. */
internal sealed interface LiveDatabaseVersion {
    data object Missing : LiveDatabaseVersion
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
    fun decide(
        live: LiveDatabaseVersion,
        currentVersion: Int,
    ): DatabaseVersionDecision = when (live) {
        LiveDatabaseVersion.Missing -> DatabaseVersionDecision.FreshDatabase
        is LiveDatabaseVersion.Present -> when {
            live.userVersion == currentVersion -> DatabaseVersionDecision.CurrentDatabase
            live.userVersion < currentVersion ->
                DatabaseVersionDecision.BackupBeforeMigration(live.userVersion)
            else -> DatabaseVersionDecision.UnsupportedNewerDatabase(live.userVersion)
        }
    }
}

internal class UnsupportedLiveDatabaseVersionException(
    actualVersion: Int,
    currentVersion: Int,
) : IllegalStateException(
    "The live database uses schema version $actualVersion, but this synthX build supports " +
        "version $currentVersion. Downgrade is disabled."
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

        val version = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.columnCount >= 1) {
                    "SQLite did not report the live database user_version"
                }
                cursor.getInt(0)
            }
        }
        check(version >= 0) { "SQLite reported an invalid live database user_version" }
        return LiveDatabaseVersion.Present(version)
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
    private val createPreMigrationBackup: suspend (fromVersion: Int) -> Unit,
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
                    when (val decision = DatabaseVersionPolicy.decide(
                        inspectLiveVersion(),
                        currentVersion,
                    )) {
                        DatabaseVersionDecision.FreshDatabase,
                        DatabaseVersionDecision.CurrentDatabase -> Unit
                        is DatabaseVersionDecision.BackupBeforeMigration ->
                            createPreMigrationBackup(decision.fromVersion)
                        is DatabaseVersionDecision.UnsupportedNewerDatabase ->
                            throw UnsupportedLiveDatabaseVersionException(
                                decision.actualVersion,
                                currentVersion,
                            )
                    }
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
        createPreMigrationBackup = { _ ->
            BackupManager.create(context).createPreMigrationBackup().getOrThrow()
        },
    )

    /** Instrumentation isolation only; production never replaces the process gate. */
    internal fun installForTest(value: DatabaseStartupController) {
        synchronized(lock) { controller = value }
    }

    /** Existing restore instrumentation may explicitly model a process whose C1 gate is ready. */
    internal fun markReadyForTest() {
        val forced = DatabaseStartupController(
            currentVersion = SynthXDatabase.VERSION,
            awaitRestoreNormal = {},
            inspectLiveVersion = { LiveDatabaseVersion.Missing },
            createPreMigrationBackup = {},
        ).also(DatabaseStartupController::markReadyForTest)
        installForTest(forced)
    }

    /** Restores lazy production behavior after an instrumentation-specific injected gate. */
    internal fun resetForTest(context: Context) {
        synchronized(lock) { controller = productionController(context.applicationContext) }
    }
}

package com.v1per.synthx.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.db.PlaylistGroupEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import java.util.UUID
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule

/**
 * Shared fixture for the Phase B restore instrumented suites.
 *
 * Deliberately abstract and deliberately NOT annotated with `@RunWith` so that
 * AndroidJUnitRunner does not collect it as a test class. Subclasses carry the `@RunWith`
 * annotation and the `@Test` methods; the `@Rule`, `@Before`/`@After` and cross-cutting helpers
 * below are inherited.
 *
 * Visibility matters here: JUnit4 discovers `@Rule` members through the superclass chain but
 * requires them to be public, so [migrationHelper] must stay public. Everything a subclass calls
 * is `protected`.
 */
abstract class PhaseBRestoreTestBase {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected val databaseDir: File get() = requireNotNull(context.getDatabasePath(SynthXDatabase.DB_NAME).parentFile)
    protected val registeredDatabaseNames = mutableSetOf<String>()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SynthXDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Before
    fun setUp() {
        RestoreStartup.await()
        TestWorkManager.ensureInitialized(context)
        SynthXDatabase.closeInstance()
        SongRepositoryImpl.resetForTest()
        RestoreStartup.resetForTest()
        RestoreCoordinator.resetForTest()
        DatabaseStartupGate.markReadyForTest()
        cleanOwnedFiles()
        File(context.filesDir, "restore_incidents").listFiles().orEmpty().forEach(File::delete)
    }

    @After
    fun tearDown() {
        SynthXDatabase.closeInstance()
        SongRepositoryImpl.resetForTest()
        registeredDatabaseNames.forEach(context::deleteDatabase)
        cleanOwnedFiles()
        File(context.filesDir, "restore_incidents").listFiles().orEmpty().forEach(File::delete)
        RestorePermitLeakCheck.assertDrainedThenReset()
    }

    // `internal`, not `protected`: BackupMetadata / RestoreFaultInjector / RestoreTransaction are
    // internal in the main source set, and Kotlin forbids a protected member exposing them.
    // androidTest is a single compilation unit, so every subclass below still sees these.
    internal suspend fun execute(
        source: File,
        metadata: BackupMetadata,
        faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
    ): Result<Unit> = RestoreTransaction.execute(
        context,
        backupInfo(source),
        metadata,
        UUID.randomUUID().toString(),
        faults,
    )

    protected fun createLive(marker: String) = createDatabaseFile(liveFile(), marker)

    protected fun newTrustedSource(marker: String): File =
        ownedFile(validBackupName()).also { createDatabaseFile(it, marker) }

    protected fun createDatabaseFile(destination: File, marker: String?) {
        deleteFileSet(destination)
        val builderName = "phaseb-builder-${UUID.randomUUID()}.db"
        registeredDatabaseNames += builderName
        val database = SynthXDatabase.createStandalone(context, builderName)
        try {
            database.openHelper.writableDatabase
            if (marker != null) runBlocking { database.playlistDao().insertGroup(group(marker)) }
            BackupSnapshotter.createFromRoom(database.openHelper.writableDatabase, destination)
            BackupSnapshotter.forceToStorage(destination)
        } finally {
            database.close()
            context.deleteDatabase(builderName)
        }
        BackupSnapshotValidator.validate(destination, false)
    }

    protected fun group(marker: String, id: String = "marker", position: Int = 0) = PlaylistGroupEntity(
        id = id,
        name = marker,
        position = position,
        createdAt = 1,
    )

    protected fun readMarker(file: File): String =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT name FROM playlist_groups WHERE id='marker'", null).use { cursor ->
                assertTrue("Missing marker in ${file.name}", cursor.moveToFirst())
                cursor.getString(0)
            }
        }

    internal fun metadata(source: File): BackupMetadata {
        val digest = BackupHashing.hash(source)
        return BackupMetadata(
            timestamp = System.currentTimeMillis(),
            tier = BackupTier.Manual.name,
            dbVersion = SynthXDatabase.VERSION,
            file = source.name,
            sizeBytes = digest.sizeBytes,
            sha256 = digest.sha256,
        )
    }

    protected fun backupInfo(source: File) = BackupInfo(
        databaseUri = Uri.fromFile(source),
        metadataUri = null,
        fileName = source.name,
        tier = BackupTier.Manual,
        timestamp = System.currentTimeMillis(),
        reason = null,
        sizeBytes = source.length(),
        restoreEligible = true,
    )

    internal fun readState(): RestoreTransaction.State {
        val state = RestoreTransaction.decodeStateOrNullForTest(stateFile().readText())
        assertNotNull(state)
        return requireNotNull(state)
    }

    protected fun validBackupName(): String = BackupFileNames.create(
        randomId = UUID.randomUUID().toString().replace("-", "").take(8),
    ).databaseFileName

    protected fun liveFile(): File = context.getDatabasePath(SynthXDatabase.DB_NAME)
    protected fun stateFile(): File = File(databaseDir, "synthx.restore-state.json")
    protected fun ownedFile(name: String): File = File(databaseDir, name)

    protected fun <T : Throwable> expectFailure(type: KClass<T>, messageContains: String, block: () -> Unit): T =
        expectFailureOf(type, messageContains, block)

    protected suspend fun <T : Throwable> expectSuspendFailure(
        type: KClass<T>,
        messageContains: String,
        block: suspend () -> Unit,
    ): T = expectSuspendFailureOf(type, messageContains, block)

    protected fun cleanOwnedFiles() {
        databaseDir.listFiles().orEmpty().filter { file ->
            file.name == SynthXDatabase.DB_NAME ||
                file.name.startsWith("${SynthXDatabase.DB_NAME}-") ||
                file.name.startsWith("synthx.restore-") ||
                file.name.startsWith("phaseb-") ||
                file.name.startsWith("synthx_backup_")
        }.forEach(::deleteFileSet)
    }

    protected fun deleteFileSet(file: File) {
        listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))
            .forEach { it.delete() }
    }
}

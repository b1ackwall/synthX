package com.v1per.synthx.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.RestorePermitLeakCheck
import com.v1per.synthx.data.db.PlaylistGroupEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryDbFlowInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: SynthXDatabase
    private lateinit var repository: RoomGroupRepository
    private val ownedScopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        RestoreCoordinator.resetForTest()
        database = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomGroupRepository(database)
    }

    @After
    fun cleanUp() {
        ownedScopes.forEach(CoroutineScope::cancel)
        database.close()
        if (RestoreCoordinator.currentMode() == RestoreCoordinator.Mode.RESTORE_PREPARING) {
            RestoreCoordinator.markNormal()
        }
        RestorePermitLeakCheck.assertDrainedThenReset()
    }

    @Test
    fun externallyCollectedColdRoomFlowIsQuiescedBeforeDrain() = runBlocking {
        insertGroup("a")
        val scope = ownedScope()
        val observed = AtomicInteger()
        val collector = scope.launch {
            repository.groups().collect { observed.incrementAndGet() }
        }
        awaitReactiveCount(1)
        withTimeout(2_000) { while (observed.get() == 0) kotlinx.coroutines.yield() }

        RestoreCoordinator.exclusive {
            RepositoryDbFlow.quiesceForRestore()
            assertTrue(collector.isCancelled)
            RestoreCoordinator.awaitDrained()
        }

        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    /**
     * A cached upstream where a reconnect is really attempted: a WhileSubscribed cache
     * restarts its upstream when a new subscriber arrives. During restore preparation that new
     * Room upstream must wait for admission, then connect once the mode is back to normal.
     */
    @Test
    fun aCachedUpstreamReconnectDuringRestorePreparationWaitsUntilNormal() = runBlocking {
        insertGroup("a")
        val attempts = AtomicInteger()
        val cached = flow {
            attempts.incrementAndGet()
            emitAll(repository.groups())
        }.stateIn(ownedScope(), SharingStarted.WhileSubscribed(), emptyList())
        val firstSubscriber = ownedScope().launch { cached.collect() }
        awaitReactiveCount(1)
        withTimeout(2_000) { cached.first { it.size == 1 } }

        RestoreCoordinator.exclusive {
            RepositoryDbFlow.quiesceForRestore()
            RestoreCoordinator.awaitDrained()
            firstSubscriber.cancel()
            firstSubscriber.join()
            insertGroup("b")

            ownedScope().launch { cached.collect() }
            withTimeout(2_000) { while (attempts.get() < 2) kotlinx.coroutines.yield() }
            // Admission is decided synchronously on subscribe; give a wrong admit time to show.
            kotlinx.coroutines.delay(200)
            assertEquals(
                "no Room upstream may connect during preparation",
                0,
                RestoreCoordinator.activeReactiveOperationCountForTest(),
            )
            assertEquals(1, cached.value.size)
        }

        RestoreCoordinator.markNormal()
        awaitReactiveCount(1)
        withTimeout(2_000) { cached.first { it.size == 2 } }
        Unit
    }

    @Test
    fun normalModeRoomFlowRemainsReactiveToDatabaseChanges() = runBlocking {
        val values = mutableListOf<List<String>>()
        val scope = ownedScope()
        val collector = scope.launch {
            repository.groups().map { groups -> groups.map { it.name } }.collect { names ->
                values += names
            }
        }
        awaitReactiveCount(1)
        insertGroup("first")
        insertGroup("second")
        withTimeout(2_000) { while (values.none { it == listOf("first", "second") }) kotlinx.coroutines.yield() }

        collector.cancel()
        collector.join()
        assertTrue(values.any { it == listOf("first", "second") })
        assertEquals(0, RestoreCoordinator.activeReactiveOperationCountForTest())
    }

    private suspend fun insertGroup(id: String) = withContext(Dispatchers.IO) {
        database.playlistDao().insertGroup(
            PlaylistGroupEntity(id, id, position = 0, createdAt = id.length.toLong()),
        )
    }

    private fun ownedScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also(ownedScopes::add)

    private suspend fun awaitReactiveCount(expected: Int) = withTimeout(2_000) {
        while (RestoreCoordinator.activeReactiveOperationCountForTest() != expected) {
            kotlinx.coroutines.yield()
        }
    }

    private class RoomGroupRepository(database: SynthXDatabase) {
        private val dao = database.playlistDao()
        fun groups(): Flow<List<PlaylistGroupEntity>> = RepositoryDbFlow.observe { dao.getAllGroups() }
    }
}

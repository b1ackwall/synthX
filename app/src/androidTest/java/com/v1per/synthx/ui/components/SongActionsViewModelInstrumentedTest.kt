package com.v1per.synthx.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.data.scanner.GraduationStage
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.repository.SongDeletionOutcome
import com.v1per.synthx.data.repository.SongRepository
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.preferences.PreferencesManager
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import com.v1per.synthx.data.repository.Playlist
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented rather than a plain JVM unit test (unlike ReplaceSongViewModelTest): constructing
 * [SongActionsViewModel] requires a real [PreferencesManager], which eagerly calls
 * `Context.getSharedPreferences` in its constructor — that throws under the plain unit-test
 * Android stub jar this project uses (no Robolectric configured), so a real device/emulator
 * [Context] via [ApplicationProvider] is required, same reasoning as
 * [com.v1per.synthx.ui.screens.issues.IssuesViewModelInstrumentedTest].
 */
@RunWith(AndroidJUnit4::class)
class SongActionsViewModelInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var viewModel: SongActionsViewModel? = null

    @Before
    fun setUp() {
        RestoreCoordinator.resetForTest()
        DestructiveActionTracker.resetForTest(context)
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        viewModel = null
        RestoreCoordinator.resetForTest()
        DestructiveActionTracker.resetForTest(context)
    }

    @Test
    fun rapidDoubleTapInvokesRepositoryDeleteExactlyOnce() = runBlocking {
        val callCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val subject = subject(
            DeferredDeleteRepository(context, callCount, gate, fakeRepository()),
        )

        // First tap: starts the delete, which suspends on `gate` before completing — i.e.
        // genuinely still in flight, not merely "launched but not yet dispatched".
        subject.deleteSong("song", "content://song")
        // Second tap, while the first is still suspended inside repository.deleteSong: must be
        // a no-op — see SongActionsViewModel.deleteInProgress.
        subject.deleteSong("song", "content://song")

        gate.complete(Unit)
        withTimeout(2_000) { subject.deleteInProgress.first { !it } }

        assertEquals(1, callCount.get())
        assertEquals(1, DestructiveActionTracker.count.value)
    }

    /**
     * Closing the progress dialog mid-graduation leaves the song in Starred, so the sheet
     * can call removeFromStarred again while the first graduation is still suspended inside the
     * repository. The second call must not start a second graduation.
     */
    @Test
    fun rapidDoubleRemoveFromStarredStartsGraduationExactlyOnce() = runBlocking {
        val callCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val preferencesContext = IsolatedPreferencesContext(context)
        try {
            val preferences = PreferencesManager(preferencesContext).apply {
                musicFolderUri = "content://com.v1per.synthx.test/tree/music"
            }
            val subject = SongActionsViewModel(
                DeferredGraduateRepository(callCount, gate, fakeRepository()),
                preferences,
            ).also { viewModel = it }

            subject.removeFromStarred("song")
            withTimeout(2_000) { subject.graduationInProgress.first { it } }
            // What the dialog's Close button does mid-flight — the in-flight guard must not
            // depend on the dialog still being up.
            subject.dismissGraduationDialog()
            subject.removeFromStarred("song")

            gate.complete(Unit)
            withTimeout(2_000) { subject.graduationInProgress.first { !it } }
            assertEquals(1, callCount.get())

            // Guard released: a later, deliberate graduation is accepted again.
            subject.removeFromStarred("song")
            withTimeout(2_000) { subject.graduationInProgress.first { !it } }
            assertEquals(2, callCount.get())
        } finally {
            preferencesContext.cleanUp()
        }
    }

    @Test
    fun failedGraduationRemainsVisibleUntilAcknowledged() = runBlocking {
        val preferencesContext = IsolatedPreferencesContext(context)
        try {
            val preferences = PreferencesManager(preferencesContext).apply {
                musicFolderUri = "content://com.v1per.synthx.test/tree/music"
                newArrivalsFolderUri = "content://com.v1per.synthx.test/tree/new-arrivals"
            }
            var dismissDelayCalls = 0
            val repository = TerminalGraduateRepository(
                GraduationStage.Failed("recovery guidance"),
                fakeRepository(),
            )
            val subject = SongActionsViewModel(
                repository,
                preferences,
                graduationDismissDelay = { dismissDelayCalls++ },
            ).also { viewModel = it }

            subject.removeFromStarred("song")
            withTimeout(2_000) { subject.graduationInProgress.first { !it } }

            assertEquals(GraduationStage.Failed("recovery guidance"), subject.graduationStage.value)
            assertEquals(0, dismissDelayCalls)
            assertEquals(
                "content://com.v1per.synthx.test/tree/new-arrivals".toUri(),
                repository.receivedNewArrivalsRoot,
            )
            subject.dismissGraduationDialog()
            assertEquals(null, subject.graduationStage.value)
        } finally {
            preferencesContext.cleanUp()
        }
    }

    @Test
    fun successfulGraduationStillUsesTransientAutoDismiss() = runBlocking {
        val preferencesContext = IsolatedPreferencesContext(context)
        try {
            val preferences = PreferencesManager(preferencesContext).apply {
                musicFolderUri = "content://com.v1per.synthx.test/tree/music"
            }
            var dismissDelayCalls = 0
            val subject = SongActionsViewModel(
                TerminalGraduateRepository(GraduationStage.Done, fakeRepository()),
                preferences,
                graduationDismissDelay = { dismissDelayCalls++ },
            ).also { viewModel = it }

            subject.removeFromStarred("song")
            withTimeout(2_000) { subject.graduationInProgress.first { !it } }

            assertEquals(1, dismissDelayCalls)
            assertEquals(null, subject.graduationStage.value)
        } finally {
            preferencesContext.cleanUp()
        }
    }

    @Test
    fun missingMusicRootShowsPersistentSetupGuidanceInsteadOfGenericFailure() {
        val preferencesContext = IsolatedPreferencesContext(context)
        try {
            val subject = SongActionsViewModel(
                fakeRepository(),
                PreferencesManager(preferencesContext),
                graduationDismissDelay = { error("missing-root guidance must not auto-dismiss") },
            ).also { viewModel = it }

            subject.removeFromStarred("song")

            val failed = subject.graduationStage.value as GraduationStage.Failed
            assertEquals(GRADUATION_MUSIC_FOLDER_REQUIRED_REASON, failed.reason)
            assertTrue(failed.reason.contains("Choose a Music folder"))
        } finally {
            preferencesContext.cleanUp()
        }
    }

    @Test
    fun removeFromPlaylistCallsRepositoryThenEmitsRemovedEventWithPlaylistName() = runBlocking {
        val repo = RecordingRemoveRepository(fakeRepository())
        val subject = subject(repo)
        // playlists is WhileSubscribed: subscribe so the name lookup has data.
        withTimeout(2_000) { subject.playlists.first { it.isNotEmpty() } }
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { subject.events.first() }
        }

        subject.removeFromPlaylist("pl-1", "song-1")

        assertEquals(SongActionEvent.RemovedFromPlaylist("Road Trip"), event.await())
        assertEquals(listOf("pl-1" to "song-1"), repo.removals)
    }

    @Test
    fun removeFromPlaylistFailureEmitsActionFailedAndNeverRemovedEvent() = runBlocking {
        val repo = RecordingRemoveRepository(fakeRepository(), failWith = IllegalStateException("boom"))
        val subject = subject(repo)
        withTimeout(2_000) { subject.playlists.first { it.isNotEmpty() } }
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { subject.events.first() }
        }

        subject.removeFromPlaylist("pl-1", "song-1")

        assertEquals(SongActionEvent.ActionFailed, event.await())
    }

    private fun subject(repository: SongRepository): SongActionsViewModel =
        SongActionsViewModel(repository, PreferencesManager(context)).also { viewModel = it }

    private class RecordingRemoveRepository(
        backing: SongRepository,
        private val failWith: Throwable? = null,
    ) : SongRepository by backing {
        val removals = mutableListOf<Pair<String, String>>()

        override fun getAllPlaylists(): Flow<List<Playlist>> =
            flowOf(listOf(Playlist(id = "pl-1", name = "Road Trip", groupId = null, albumArtUri = null)))

        override suspend fun removeSongFromPlaylist(playlistId: String, songId: String) {
            failWith?.let { throw it }
            removals += playlistId to songId
        }
    }

    /** Same shape as [DeferredDeleteRepository], for graduateSong's Flow. */
    private class DeferredGraduateRepository(
        private val callCount: AtomicInteger,
        private val gate: CompletableDeferred<Unit>,
        backing: SongRepository,
    ) : SongRepository by backing {
        override fun graduateSong(
            songId: String,
            rootMusicFolderUri: Uri,
            rootNewArrivalsFolderUri: Uri?,
        ): Flow<GraduationStage> = flow {
            callCount.incrementAndGet()
            emit(GraduationStage.Copying)
            gate.await()
            emit(GraduationStage.Done)
        }
    }

    private class TerminalGraduateRepository(
        private val terminal: GraduationStage,
        backing: SongRepository,
    ) : SongRepository by backing {
        var receivedNewArrivalsRoot: Uri? = null

        override fun graduateSong(
            songId: String,
            rootMusicFolderUri: Uri,
            rootNewArrivalsFolderUri: Uri?,
        ): Flow<GraduationStage> {
            receivedNewArrivalsRoot = rootNewArrivalsFolderUri
            return flowOf(terminal)
        }
    }

    /** Keeps the test's musicFolderUri out of the installed dev build's real preferences. */
    private class IsolatedPreferencesContext(base: Context) : ContextWrapper(base) {
        private val prefix = "song-actions-test-${UUID.randomUUID()}"
        private val names = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$prefix-$name"
            names += isolated
            return super.getSharedPreferences(isolated, mode)
        }
        fun cleanUp() = names.forEach { baseContext.deleteSharedPreferences(it) }
    }

    private fun fakeRepository(): SongRepository = Proxy.newProxyInstance(
        SongRepository::class.java.classLoader,
        arrayOf(SongRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getAllPlaylists" -> flowOf(emptyList<Any>())
            "getSongs" -> MutableStateFlow(emptyList<Any>())
            else -> error("Unexpected repository call: ${method.name}")
        }
    } as SongRepository

    /**
     * Wraps a fake [SongRepository] to make `deleteSong` genuinely suspend on [gate] and count
     * invocations — needed to prove [SongActionsViewModel.deleteInProgress] blocks a second,
     * concurrent call rather than merely a call made after the first has already finished. Also
     * records a real [DestructiveActionTracker] entry per invocation, mirroring
     * SongRepositoryImpl.deleteSong's own coupling, so the same test proves both halves of the
     * guard: repository.deleteSong called once, and the destructive-action count bumped once.
     */
    private class DeferredDeleteRepository(
        private val context: Context,
        private val callCount: AtomicInteger,
        private val gate: CompletableDeferred<Unit>,
        backing: SongRepository,
    ) : SongRepository by backing {
        override suspend fun deleteSong(id: String): SongDeletionOutcome {
            callCount.incrementAndGet()
            gate.await()
            DestructiveActionTracker.recordDestructiveAction(context)
            return SongDeletionOutcome.Deleted
        }
    }
}

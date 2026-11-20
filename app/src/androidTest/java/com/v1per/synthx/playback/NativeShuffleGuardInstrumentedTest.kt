package com.v1per.synthx.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The service-side safety net around a real ExoPlayer: [SystemNavigationPlayer] keeps ExoPlayer's
 * own shuffle off whenever it sees it on (when the wrapper is created, in a listener on the raw
 * player, and when an engine attaches to the bridge), and session listeners never hear ExoPlayer's
 * own shuffle flag.
 */
@RunWith(AndroidJUnit4::class)
class NativeShuffleGuardInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var exo: ExoPlayer
    private val wrappers = mutableListOf<SystemNavigationPlayer>()
    private val attached = mutableListOf<SystemNavigationHandler>()

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private class Heard : Player.Listener {
        val shuffle = CopyOnWriteArrayList<Boolean>()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            shuffle += shuffleModeEnabled
        }
    }

    private fun wrap(): SystemNavigationPlayer = SystemNavigationPlayer(exo).also { wrappers += it }

    @Before fun setUp() {
        exo = onMain { ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build() }
    }

    @After fun tearDown() {
        onMain {
            wrappers.forEach { it.detach() }
            attached.forEach(SystemNavigationBridge::detach)
            exo.release()
        }
    }

    @Test fun creatingTheWrapperTurnsANativeShuffleOff() {
        onMain { exo.shuffleModeEnabled = true }

        onMain { wrap() }

        assertFalse(onMain { exo.shuffleModeEnabled })
    }

    @Test fun aNativeShuffleSeenOnIsTurnedOffAndSessionListenersNeverHearIt() {
        val heard = Heard()
        onMain { wrap().addListener(heard) }

        onMain { exo.shuffleModeEnabled = true }

        assertFalse("the raw player is set back off", onMain { exo.shuffleModeEnabled })
        assertTrue("a session listener never hears native shuffle on, heard=${heard.shuffle}", true !in heard.shuffle)
    }

    @Test fun anEngineAttachingToTheBridgeTurnsANativeShuffleOff() {
        onMain { wrap() }
        onMain { exo.shuffleModeEnabled = true }
        // No session listener is registered, so only the attach can catch it.
        val handler = ReadyHandler()
        attached += handler
        onMain { SystemNavigationBridge.attach(handler) }

        assertFalse(onMain { exo.shuffleModeEnabled })
    }

    private class ReadyHandler : SystemNavigationHandler {
        override val isReady = true
        override val shuffleEnabled = false
        override fun hasNext() = true
        override fun hasPrevious() = true
        override fun seekToNext() = Unit
        override fun seekToPrevious() = Unit
        override fun setShuffleFromSystem(enabled: Boolean) = Unit
    }
}

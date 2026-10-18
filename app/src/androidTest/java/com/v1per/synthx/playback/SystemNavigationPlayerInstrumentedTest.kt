package com.v1per.synthx.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The command-set half of [SystemNavigationPlayer]: it needs a real [Player.Commands], whose
 * backing SparseBooleanArray is only usable on-device (the routing half is a plain JVM test,
 * SystemNavigationPlayerTest). The wrapped ExoPlayer is a recording proxy — this covers the
 * wrapper's own logic, not the service.
 */
@RunWith(AndroidJUnit4::class)
class SystemNavigationPlayerInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private var nativeCommands: Player.Commands = Player.Commands.EMPTY
    private val registered = mutableListOf<Player.Listener>()

    private val delegate: Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, args ->
        when (method.name) {
            "addListener" -> registered += args[0] as Player.Listener
            "removeListener" -> registered -= args[0] as Player.Listener
        }
        when {
            method.name == "getAvailableCommands" -> nativeCommands
            method.returnType == Boolean::class.javaPrimitiveType -> false
            method.returnType == Int::class.javaPrimitiveType -> 0
            method.returnType == Long::class.javaPrimitiveType -> 0L
            method.returnType == Float::class.javaPrimitiveType -> 0f
            else -> null
        }
    } as Player

    private class Handler(
        var next: Boolean = true,
        var previous: Boolean = true,
    ) : SystemNavigationHandler {
        override val isReady = true
        override fun hasNext() = next
        override fun hasPrevious() = previous
        override fun seekToNext() = Unit
        override fun seekToPrevious() = Unit
    }

    private class Capturing : Player.Listener {
        val published = mutableListOf<Player.Commands>()
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            published += availableCommands
        }
    }

    private lateinit var wrapper: SystemNavigationPlayer

    // Only what this test attached is detached afterwards: these tests share the process with the
    // real service (whose own wrapper and any live engine must keep working), so they must not
    // reset the bridge wholesale.
    private val attached = mutableListOf<SystemNavigationHandler>()
    private fun attach(handler: Handler): Handler = handler.also {
        attached += it
        onMain { SystemNavigationBridge.attach(it) }
    }

    // SystemNavigationBridge is "main-thread only" (see its KDoc): notifyChanged() broadcasts
    // synchronously to every registered listener, including the real service's SystemNavigationPlayer
    // wrapping a live ExoPlayer whenever another instrumented test in this process has started it.
    // That real listener asserts it's called on the main thread, so every direct bridge call from
    // this test must go through here rather than run on the JUnit test thread.
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @OptIn(markerClass = [UnstableApi::class])
    private fun commandsOf(vararg ids: Int) =
        Player.Commands.Builder().apply { ids.forEach { add(it) } }.build()

    @Before fun setUp() {
        wrapper = SystemNavigationPlayer(delegate)
    }

    @After fun tearDown() {
        wrapper.detach()
        onMain { attached.forEach(SystemNavigationBridge::detach) }
    }

    /** Repeat One on the final item: native availability has no next; the app wraps, so offer it. */
    @Test fun nextBecomesAvailableWhereNativeHidesIt() {
        nativeCommands = commandsOf(Player.COMMAND_PLAY_PAUSE)
        assertFalse(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))

        attach(Handler(next = true))

        assertTrue(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(wrapper.availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(wrapper.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
    }

    @Test fun nextIsHiddenWhenTheEngineHasNoneEvenIfNativeOffersIt() {
        nativeCommands = commandsOf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT)
        attach(Handler(next = false))
        assertFalse(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertFalse(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
    }

    @Test fun previousMediaItemIsOfferedAtTheWindowsRearEdge() {
        nativeCommands = commandsOf(Player.COMMAND_SEEK_TO_PREVIOUS)
        attach(Handler(previous = true))
        assertTrue(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    }

    @Test fun nativeAvailabilityIsUntouchedWithoutAnEngine() {
        nativeCommands = commandsOf(Player.COMMAND_SEEK_TO_NEXT)
        assertTrue(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertFalse(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    }

    /** ExoPlayer's own command events would otherwise overwrite the rewritten set on the session. */
    @Test fun nativeAvailabilityEventsAreRewrittenBeforeTheSessionSeesThem() {
        attach(Handler(next = true, previous = true))
        val sessionListener = Capturing()
        wrapper.addListener(sessionListener)
        val registeredWithExo = registered.single()

        registeredWithExo.onAvailableCommandsChanged(Player.Commands.EMPTY)

        val seen = sessionListener.published.single()
        assertTrue(seen.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(seen.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    }

    @Test fun anEngineSideChangeIsPublishedOnceAndNotRepeatedWhileUnchanged() {
        val handler = attach(Handler(next = false))
        val sessionListener = Capturing()
        wrapper.addListener(sessionListener)

        // Nothing moved since attach published this state: no event.
        onMain { SystemNavigationBridge.notifyChanged() }
        onMain { SystemNavigationBridge.notifyChanged() }
        assertTrue(sessionListener.published.isEmpty())

        handler.next = true
        onMain { SystemNavigationBridge.notifyChanged() }
        assertEquals(1, sessionListener.published.size)
        assertTrue(sessionListener.published.single().contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))

        onMain { SystemNavigationBridge.notifyChanged() }
        assertEquals(1, sessionListener.published.size)

        handler.next = false
        onMain { SystemNavigationBridge.notifyChanged() }
        assertEquals(2, sessionListener.published.size)
        assertFalse(sessionListener.published.last().contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    }

    @Test fun aRemovedListenerHearsNothingFurther() {
        attach(Handler(next = true))
        val sessionListener = Capturing()
        wrapper.addListener(sessionListener)
        wrapper.removeListener(sessionListener)
        sessionListener.published.clear()

        onMain { SystemNavigationBridge.notifyChanged() }

        assertTrue(sessionListener.published.isEmpty())
        assertTrue(registered.isEmpty())
    }
}

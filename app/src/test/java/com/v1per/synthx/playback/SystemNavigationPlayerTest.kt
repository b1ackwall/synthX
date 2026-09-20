package com.v1per.synthx.playback

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SystemNavigationPlayer] against a recording proxy in place of the wrapped ExoPlayer — no
 * service, no Android runtime. Covers the routing (engine when ready, native otherwise) and that the listener wrapper forwards
 * everything. The command-set rewriting needs a real `Player.Commands`, so it is covered on-device
 * in SystemNavigationPlayerInstrumentedTest.
 */
class SystemNavigationPlayerTest {

    /** A [Player] whose every method is recorded and answers a type-appropriate default. */
    private class RecordingPlayer(var nativeCommands: Player.Commands = Player.Commands.EMPTY) {
        val calls = mutableListOf<String>()
        val listeners = mutableListOf<Player.Listener>()
        var nativeHasNext = false

        fun seekCalls() = calls.filter { it.startsWith("seekTo") }

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            calls += method.name
            when (method.name) {
                "addListener" -> listeners += args[0] as Player.Listener
                "removeListener" -> listeners -= args[0] as Player.Listener
            }
            when {
                method.name == "getAvailableCommands" -> nativeCommands
                method.name == "hasNextMediaItem" -> nativeHasNext
                method.returnType == Boolean::class.javaPrimitiveType -> false
                method.returnType == Int::class.javaPrimitiveType -> 0
                method.returnType == Long::class.javaPrimitiveType -> 0L
                method.returnType == Float::class.javaPrimitiveType -> 0f
                else -> null
            }
        } as Player
    }

    private class FakeHandler(
        override var isReady: Boolean = true,
        var next: Boolean = true,
        var previous: Boolean = true,
    ) : SystemNavigationHandler {
        val calls = mutableListOf<String>()
        override fun hasNext() = next
        override fun hasPrevious() = previous
        override fun seekToNext() { calls += "next" }
        override fun seekToPrevious() { calls += "previous" }
    }

    private val delegate = RecordingPlayer()
    private lateinit var wrapper: SystemNavigationPlayer

    @BeforeTest
    fun setUp() {
        SystemNavigationBridge.resetForTest()
        wrapper = SystemNavigationPlayer(delegate.player)
    }

    @AfterTest
    fun tearDown() {
        wrapper.detach()
        SystemNavigationBridge.resetForTest()
    }

    // --- routing -----------------------------------------------------------------------------

    @Test
    fun `all four skip commands go to the engine when one is ready`() {
        val handler = FakeHandler().also(SystemNavigationBridge::attach)

        wrapper.seekToNext()
        wrapper.seekToNextMediaItem()
        wrapper.seekToPrevious()
        wrapper.seekToPreviousMediaItem()

        assertEquals(listOf("next", "next", "previous", "previous"), handler.calls)
        assertTrue(
            delegate.seekCalls().isEmpty(),
            "the raw player must not be touched: ${delegate.calls}",
        )
    }

    @Test
    fun `falls back to the native player when no engine is attached`() {
        wrapper.seekToNext()
        wrapper.seekToNextMediaItem()
        wrapper.seekToPrevious()
        wrapper.seekToPreviousMediaItem()

        assertEquals(
            listOf("seekToNext", "seekToNextMediaItem", "seekToPrevious", "seekToPreviousMediaItem"),
            delegate.seekCalls(),
        )
    }

    @Test
    fun `falls back to native when the attached engine is not ready`() {
        val handler = FakeHandler(isReady = false).also(SystemNavigationBridge::attach)

        wrapper.seekToNextMediaItem()
        wrapper.seekToPreviousMediaItem()

        assertTrue(handler.calls.isEmpty())
        assertEquals(listOf("seekToNextMediaItem", "seekToPreviousMediaItem"), delegate.seekCalls())
    }

    @Test
    fun `falls back to native again after the engine detaches`() {
        val handler = FakeHandler().also(SystemNavigationBridge::attach)
        SystemNavigationBridge.detach(handler)

        wrapper.seekToNextMediaItem()

        assertTrue(handler.calls.isEmpty())
        assertEquals(listOf("seekToNextMediaItem"), delegate.seekCalls())
    }

    @Test
    fun `hasNextMediaItem follows the engine, not the window`() {
        delegate.nativeHasNext = false
        SystemNavigationBridge.attach(FakeHandler(next = true))
        assertTrue(wrapper.hasNextMediaItem())
        SystemNavigationBridge.resetForTest()
        SystemNavigationBridge.attach(FakeHandler(next = false))
        delegate.nativeHasNext = true
        assertFalse(wrapper.hasNextMediaItem())
    }

    @Test
    fun `hasNextMediaItem is native without an engine`() {
        delegate.nativeHasNext = true
        assertTrue(wrapper.hasNextMediaItem())
        delegate.nativeHasNext = false
        assertFalse(wrapper.hasNextMediaItem())
    }

    // --- listener plumbing (the rewritten command payloads are covered on-device) --------------

    @Test
    fun `a removed listener is removed from the wrapped player`() {
        val listener = object : Player.Listener {}
        wrapper.addListener(listener)
        assertEquals(1, delegate.listeners.size)
        wrapper.removeListener(listener)
        assertTrue(delegate.listeners.isEmpty())
    }

    @Test
    fun `other listener events still reach the session listener`() {
        val seen = mutableListOf<Boolean>()
        wrapper.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { seen += isPlaying }
        })

        delegate.listeners.single().onIsPlayingChanged(true)

        assertEquals(listOf(true), seen)
    }
}

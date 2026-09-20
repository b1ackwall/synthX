package com.v1per.synthx.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * The player [SynthXPlaybackService]'s session is built around (P9-T3). Notification buttons,
 * headset/Bluetooth media keys and lock-screen controls all reach the player through the session,
 * and the raw ExoPlayer only knows the ~[WINDOW_SIZE]-slot window it holds — not the queue. Its
 * native Next/Previous therefore diverge from the app's own buttons at the window's edges, and its
 * availability (which media3's notification uses to show or hide Next) treats Repeat One as
 * Repeat Off. This wrapper answers those questions from the engine's full-order rules instead,
 * through [SystemNavigationBridge], and falls back to the native behaviour whenever no engine is
 * attached or ready — never crashes or blocks in that case.
 *
 * Only the four skip commands and their availability are intercepted. Everything else, including
 * seek-to-queue-item and shuffle, is deliberately still the raw player (see the P9-T3 report).
 *
 * The engine's own seeks go through `seekTo(index)` / `seekTo(position)`, which are not overridden
 * here, so the engine calling back into the session cannot re-enter this class's interception.
 * Main thread only, like every session callback.
 */
internal class SystemNavigationPlayer(player: Player) : ForwardingPlayer(player) {

    private val wrappers = LinkedHashMap<Player.Listener, RewritingListener>()
    private var lastPublished: Player.Commands? = null

    private val changeListener: () -> Unit = { refreshAvailableCommands() }

    init {
        SystemNavigationBridge.addChangeListener(changeListener)
    }

    /** Stops listening to the engine; call when the service releases the player. */
    fun detach() {
        SystemNavigationBridge.removeChangeListener(changeListener)
        wrappers.clear()
    }

    override fun seekToNext() {
        val handler = SystemNavigationBridge.readyHandler()
        if (handler != null) handler.seekToNext() else super.seekToNext()
    }

    override fun seekToNextMediaItem() {
        val handler = SystemNavigationBridge.readyHandler()
        if (handler != null) handler.seekToNext() else super.seekToNextMediaItem()
    }

    // Both Previous entry points share the app's restart-after-threshold rule. The notification
    // uses seekToPreviousMediaItem, which natively never restarts; headset/legacy use
    // seekToPrevious, which does.
    override fun seekToPrevious() {
        val handler = SystemNavigationBridge.readyHandler()
        if (handler != null) handler.seekToPrevious() else super.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        val handler = SystemNavigationBridge.readyHandler()
        if (handler != null) handler.seekToPrevious() else super.seekToPreviousMediaItem()
    }

    override fun hasNextMediaItem(): Boolean =
        SystemNavigationBridge.readyHandler()?.hasNext() ?: super.hasNextMediaItem()

    override fun getAvailableCommands(): Player.Commands = rewrite(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean = getAvailableCommands().contains(command)

    // ForwardingPlayer forwards the wrapped player's own onAvailableCommandsChanged payload, which
    // would silently replace our rewritten set the next time ExoPlayer's own availability moves.
    override fun addListener(listener: Player.Listener) {
        val wrapper = wrappers.getOrPut(listener) {
            RewritingListener.create(listener, ::rewrite) { lastPublished = it }
        }
        super.addListener(wrapper.proxy)
    }

    override fun removeListener(listener: Player.Listener) {
        wrappers.remove(listener)?.let { super.removeListener(it.proxy) }
    }

    /**
     * Re-publishes availability to the session when the engine's answers may have moved without
     * ExoPlayer's own moving (Repeat One on the final item, a queue edit outside the window).
     * A no-op when nothing changed, since the engine calls this on every navigation refresh.
     */
    private fun refreshAvailableCommands() {
        val current = getAvailableCommands()
        if (current == lastPublished) return
        lastPublished = current
        wrappers.values.toList().forEach { it.delegate.onAvailableCommandsChanged(current) }
    }

    private fun rewrite(base: Player.Commands): Player.Commands {
        val handler = SystemNavigationBridge.readyHandler() ?: return base
        return navigationCommandsFor(base, handler.hasNext(), handler.hasPrevious())
    }

    /**
     * Forwards every [Player.Listener] callback to [delegate] unchanged except
     * `onAvailableCommandsChanged`, whose payload is rewritten. A reflective proxy rather than
     * Kotlin `by`-delegation (which does not delegate Java default methods — every callback would
     * silently vanish) or a hand-written override per callback (which would silently miss any
     * callback a later media3 adds).
     */
    private class RewritingListener(val delegate: Player.Listener, val proxy: Player.Listener) {
        companion object {
            fun create(
                delegate: Player.Listener,
                rewrite: (Player.Commands) -> Player.Commands,
                onPublished: (Player.Commands) -> Unit,
            ): RewritingListener {
                val proxy = Proxy.newProxyInstance(
                    Player.Listener::class.java.classLoader,
                    arrayOf(Player.Listener::class.java),
                ) { self, method, args ->
                    val callArgs = args ?: emptyArray()
                    when {
                        method.name == "equals" && callArgs.size == 1 -> self === callArgs[0]
                        method.name == "hashCode" && callArgs.isEmpty() -> System.identityHashCode(self)
                        method.name == "toString" && callArgs.isEmpty() ->
                            "RewritingListener($delegate)"
                        method.name == "onAvailableCommandsChanged" -> {
                            val rewritten = rewrite(callArgs[0] as Player.Commands)
                            onPublished(rewritten)
                            delegate.onAvailableCommandsChanged(rewritten)
                            null
                        }
                        else -> try {
                            method.invoke(delegate, *callArgs)
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    }
                } as Player.Listener
                return RewritingListener(delegate, proxy)
            }
        }
    }
}

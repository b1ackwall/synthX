package com.v1per.synthx.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * The player [SynthXPlaybackService]'s session is built around. Notification buttons,
 * headset/Bluetooth media keys and lock-screen controls all reach the player through the session,
 * and the raw ExoPlayer only knows the ~[WINDOW_SIZE]-slot window it holds — not the queue. Its
 * native Next/Previous therefore diverge from the app's own buttons at the window's edges, and its
 * availability (which media3's notification uses to show or hide Next) treats Repeat One as
 * Repeat Off. This wrapper answers those questions from the engine's full-order rules instead,
 * through [SystemNavigationBridge], and falls back to the native behaviour whenever no engine is
 * attached or ready — never crashes or blocks in that case.
 *
 * The four skip commands, their availability, and shuffle are intercepted. Shuffle is the app's
 * alone: a controller's shuffle command goes to the engine ([SystemNavigationHandler.setShuffleFromSystem])
 * and never to ExoPlayer, controllers are shown the app's flag, and every change of that flag (from
 * the app or from a controller, refused or not) is published to the session's listeners as
 * `onShuffleModeEnabledChanged`. The command is offered only while an engine is ready; without one
 * it is withdrawn, and a command that still arrives is ignored. ExoPlayer's own shuffle is kept off:
 * whenever the wrapper sees it on (when it is created, in any listener it holds on the raw player,
 * and when an engine attaches to the bridge) it switches it back off, so ExoPlayer's random order
 * never drives playback. Everything else, including seek-to-queue-item and repeat, is the raw player.
 *
 * The engine's own seeks go through `seekTo(index)` / `seekTo(position)`, which are not overridden
 * here, so the engine calling back into the session cannot re-enter this class's interception.
 * Main thread only, like every session callback.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class SystemNavigationPlayer(player: Player) : ForwardingPlayer(player) {

    private val wrappers = LinkedHashMap<Player.Listener, RewritingListener>()
    private var lastPublished: Player.Commands? = null
    private var lastPublishedShuffle = false

    // True while a controller's shuffle command is handled: the change it makes is published once, at its end.
    private var handlingShuffleCommand = false

    private val changeListener: () -> Unit = {
        keepNativeShuffleOff()
        refreshAvailableCommands()
        if (!handlingShuffleCommand) publishShuffle(force = false)
    }

    init {
        keepNativeShuffleOff()
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

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        val handler = SystemNavigationBridge.readyHandler()
        if (handler != null) {
            handlingShuffleCommand = true
            try {
                handler.setShuffleFromSystem(shuffleModeEnabled)
            } finally {
                handlingShuffleCommand = false
            }
        }
        // Also when nothing changed: the controller may already show its own request.
        publishShuffle(force = true)
    }

    override fun getShuffleModeEnabled(): Boolean = SystemNavigationBridge.readyHandler()?.shuffleEnabled ?: false

    override fun getAvailableCommands(): Player.Commands = rewrite(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean = getAvailableCommands().contains(command)

    // ForwardingPlayer forwards the wrapped player's own onAvailableCommandsChanged payload, which
    // would silently replace our rewritten set the next time ExoPlayer's own availability moves.
    override fun addListener(listener: Player.Listener) {
        val wrapper = wrappers.getOrPut(listener) {
            RewritingListener.create(
                delegate = listener,
                rewrite = ::rewrite,
                onPublished = { lastPublished = it },
                onNativeShuffleChanged = ::keepNativeShuffleOff,
                reportedShuffle = { shuffleModeEnabled },
            )
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

    /** Publishes the app's shuffle flag to the session's listeners, unless [force] is off and it is unchanged. */
    private fun publishShuffle(force: Boolean) {
        val current = shuffleModeEnabled
        if (!force && current == lastPublishedShuffle) return
        lastPublishedShuffle = current
        wrappers.values.toList().forEach { it.delegate.onShuffleModeEnabledChanged(current) }
    }

    /** Switches ExoPlayer's own shuffle back off; a no-op while it is off, so it cannot loop. */
    private fun keepNativeShuffleOff() {
        if (super.getShuffleModeEnabled()) super.setShuffleModeEnabled(false)
    }

    private fun rewrite(base: Player.Commands): Player.Commands {
        val handler = SystemNavigationBridge.readyHandler()
            ?: return base.buildUpon().remove(Player.COMMAND_SET_SHUFFLE_MODE).build()
        return navigationCommandsFor(base, handler.hasNext(), handler.hasPrevious())
            .buildUpon()
            .add(Player.COMMAND_SET_SHUFFLE_MODE)
            .build()
    }

    /**
     * Forwards every [Player.Listener] callback to [delegate] unchanged except
     * `onAvailableCommandsChanged`, whose payload is rewritten, and `onShuffleModeEnabledChanged`,
     * which reports ExoPlayer's own flag: that one switches it back off ([onNativeShuffleChanged])
     * and hands [delegate] the app's flag instead. A reflective proxy rather than
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
                onNativeShuffleChanged: () -> Unit,
                reportedShuffle: () -> Boolean,
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

                        method.name == "onShuffleModeEnabledChanged" -> {
                            onNativeShuffleChanged()
                            delegate.onShuffleModeEnabledChanged(reportedShuffle())
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

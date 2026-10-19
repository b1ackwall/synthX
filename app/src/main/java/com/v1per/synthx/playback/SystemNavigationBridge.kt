package com.v1per.synthx.playback

import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the playback service's player wrapper needs from whoever owns the queue order.
 *
 * The full order, the current slot and the repeat-aware navigation rules live in [ExoPlayerEngine],
 * on the client side of the MediaController — the service only holds a bounded window of it. So
 * system transport commands (notification, headset, lock screen) are answered by the engine rather
 * than by media3's native window-only traversal. All members are main-thread only, like
 * the engine itself and the session callbacks that reach them.
 */
internal interface SystemNavigationHandler {
    /**
     * False while the engine cannot answer authoritatively — no live controller, or a queue it
     * does not hold the current slot of — in which case callers must fall back to native behaviour.
     */
    val isReady: Boolean

    fun hasNext(): Boolean

    fun hasPrevious(): Boolean

    fun seekToNext()

    fun seekToPrevious()
}

/**
 * Process-scoped rendezvous between the engine (registers itself as a [SystemNavigationHandler])
 * and [SystemNavigationPlayer] inside the service (asks for one). Same process, so a static hook
 * is enough; the service stays ignorant of the engine type.
 *
 * A list rather than a single slot because a new engine can be constructed before the previous
 * one's teardown lands (Activity recreation): detaching the old one must not orphan the new.
 */
internal object SystemNavigationBridge {
    private val handlers = CopyOnWriteArrayList<SystemNavigationHandler>()

    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    fun attach(handler: SystemNavigationHandler) {
        handlers += handler
        notifyChanged()
    }

    fun detach(handler: SystemNavigationHandler) {
        if (handlers.remove(handler)) notifyChanged()
    }

    /** The most recently attached handler that can answer right now, or null (use native). */
    fun readyHandler(): SystemNavigationHandler? = handlers.lastOrNull { it.isReady }

    /** Registered by a player wrapper so it can re-publish available commands. */
    fun addChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    fun removeChangeListener(listener: () -> Unit) {
        changeListeners -= listener
    }

    /** The engine calls this whenever its navigation answers (hasNext, current slot) may differ. */
    fun notifyChanged() {
        changeListeners.forEach { it() }
    }

    internal fun resetForTest() {
        handlers.clear()
        changeListeners.clear()
    }
}

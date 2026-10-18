package com.v1per.synthx.playback

import android.annotation.SuppressLint
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** What a Previous press should do — the outcome of [previousAction], applied by the engine. */
internal sealed interface PreviousAction {
    /** Seek the current item back to its start. */
    data object RestartCurrent : PreviousAction

    /** Jump to an item that is already loaded in the player's window. */
    data class SeekLoaded(val loadedIndex: Int) : PreviousAction

    /** Jump to a position in the full order, which may need the window rebuilt around it. */
    data class SeekOrder(val orderIndex: Int) : PreviousAction
}

/**
 * The Previous rule, extracted from `ExoPlayerEngine.previous()` so app buttons and system
 * transport (notification, headset, lock screen) share one tested decision (P9-T3).
 *
 *  1. More than [restartThresholdMs] into the item: restart it, whatever else is true.
 *  2. Otherwise step back within the loaded window while there is a loaded item behind this one.
 *  3. At the window's rear edge, wrap to the end of the queue only when this genuinely is the
 *     queue's first slot under Repeat All. Anywhere else the rear edge is just the edge of the
 *     loaded window (see [WINDOW_BACK]), so restart rather than walk further back — the accepted
 *     limit of keeping only a few songs of history loaded. Native media3 would instead wrap
 *     *within the window* here under Repeat All, jumping roughly [WINDOW_FORWARD] songs forward.
 *
 * @param loadedIndex index of the current item within the loaded window, or [C.INDEX_UNSET].
 * @param currentOrderIndex index of the current slot within the full order, or -1 if unknown.
 */
internal fun previousAction(
    positionMs: Long,
    restartThresholdMs: Long,
    loadedIndex: Int,
    repeatMode: RepeatMode,
    currentOrderIndex: Int,
    orderSize: Int,
): PreviousAction {
    if (positionMs > restartThresholdMs) return PreviousAction.RestartCurrent
    if (loadedIndex != C.INDEX_UNSET && loadedIndex > 0) {
        return PreviousAction.SeekLoaded(loadedIndex - 1)
    }
    if (repeatMode == RepeatMode.All && currentOrderIndex == 0 && orderSize > 1) {
        return PreviousAction.SeekOrder(orderSize - 1)
    }
    return PreviousAction.RestartCurrent
}

/** Which skip commands to force on and off — see [navigationCommandEdit]. */
internal data class NavigationCommandEdit(val add: Set<Int>, val remove: Set<Int>)

/**
 * The four skip commands rewritten to what the app's own navigation can actually do, for the
 * player wrapper's availability gate. Pure over command ids (rather than over [Player.Commands],
 * whose backing `SparseBooleanArray` is not usable in JVM unit tests) so the decision is testable
 * exactly; [navigationCommandsFor] applies it.
 *
 * Both the plain and `_MEDIA_ITEM` variants are set because the default media notification sends
 * `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` / `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM` regardless of which
 * one made its button appear, and the session drops any command the player does not currently
 * report as available *before* it reaches the player. Native availability is derived from the
 * loaded window and treats Repeat One as Repeat Off, so it hides Next on the queue's final item
 * (where the app wraps) and hides Previous at the window's rear edge (where the app restarts).
 */
internal fun navigationCommandEdit(hasNext: Boolean, hasPrevious: Boolean): NavigationCommandEdit {
    val next = setOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    val previous = setOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
    return NavigationCommandEdit(
        add = (if (hasNext) next else emptySet()) + (if (hasPrevious) previous else emptySet()),
        remove = (if (hasNext) emptySet() else next) + (if (hasPrevious) emptySet() else previous),
    )
}

// The sets only contain Player.COMMAND_SEEK_TO_NEXT, _NEXT_MEDIA_ITEM, _PREVIOUS and
// _PREVIOUS_MEDIA_ITEM; Lint cannot track the @Player.Command IntDef through a Set<Int>.
/** [base] with [navigationCommandEdit] applied. */
@SuppressLint("WrongConstant")
@OptIn(markerClass = [UnstableApi::class])
internal fun navigationCommandsFor(
    base: Player.Commands,
    hasNext: Boolean,
    hasPrevious: Boolean,
): Player.Commands {
    val edit = navigationCommandEdit(hasNext, hasPrevious)
    return base.buildUpon()
        .addAll(*edit.add.toIntArray())
        .removeAll(*edit.remove.toIntArray())
        .build()
}

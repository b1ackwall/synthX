package com.v1per.synthx.ui

import com.v1per.synthx.data.backup.RestoreCoordinator

/** The three mutually exclusive shells [MainScaffold] can compose. */
internal enum class RestoreShellSurface {
    /** The ordinary nav graph, playback chrome and every DB-backed screen. */
    Normal,

    /** A self-contained, non-DB-dependent surface shown while admission is closed. */
    Maintenance,

    /** The existing post-Room-close dialog; the process can only be restarted from here. */
    Terminal,
}

/**
 * Maps restore's two independent lifecycle signals onto one shell decision.
 *
 * [RestoreCoordinator.Mode] is runtime database-admission state and
 * [RestoreCoordinator.Terminal] is the post-Room-close process outcome. Neither implies the
 * other, which is the whole reason this exists: a shell gating only on `Terminal` renders the
 * live nav graph for the entire `RESTORE_PREPARING` window, because `Terminal` is not published
 * until [RestoreCoordinator.markClosed] — by which point preparation is already over.
 *
 * Kept as a pure function next to its consumer, the same shape as
 * `DatabaseVersionPolicy.decide`, so every combination is exercisable without a device.
 */
internal object RestoreShellPolicy {
    fun decide(
        mode: RestoreCoordinator.Mode,
        terminal: RestoreCoordinator.Terminal,
    ): RestoreShellSurface = when {
        // Checked ahead of Terminal because it is the earlier lifecycle phase: RESTORE_PREPARING
        // is entered before any Terminal exists, and on an early abort is left again with no
        // Terminal ever published.
        //
        // The `terminal == NORMAL` qualifier is what stops this branch swallowing the terminal
        // states, and it also closes a real gap rather than only a theoretical one: markClosed()
        // writes mode and terminal as two separate StateFlow updates, so a recomposition can
        // observe RESTORE_CLOSED while terminal is still NORMAL. That instant belongs to
        // Maintenance — never to the live nav graph, which is exactly what it would fall
        // through to if this only matched RESTORE_PREPARING.
        mode != RestoreCoordinator.Mode.NORMAL &&
            terminal == RestoreCoordinator.Terminal.NORMAL -> RestoreShellSurface.Maintenance

        // Once published, Terminal owns the shell.
        terminal != RestoreCoordinator.Terminal.NORMAL -> RestoreShellSurface.Terminal

        else -> RestoreShellSurface.Normal
    }
}

/** Stable node identifiers for shell-level instrumentation. Never user visible. */
internal object ShellTestTags {
    const val RestoreMaintenanceSurface = "shell:restore-maintenance"
    const val NavHost = "shell:nav-host"
    const val RestorePending = "shell:restore-pending"
}

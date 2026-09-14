package com.v1per.synthx.playback

/**
 * Pure backoff/attempt-limit arithmetic behind [ExoPlayerEngine]'s connection retry (P4-T3 /
 * R3-F09). Split out purely so the actual policy — not the `MediaController.Builder`/coroutine
 * plumbing around it — is unit-testable.
 */
internal object ReconnectPolicy {
    const val MAX_ATTEMPTS = 3
    private const val BASE_DELAY_MS = 1_000L

    /**
     * The delay before the ([attempt] + 1)th reconnection attempt (0-indexed), or null once
     * [MAX_ATTEMPTS] have already been made — at which point the caller gives up rather than
     * retrying forever against a session that is genuinely, permanently gone.
     */
    fun nextDelayMsOrNull(attempt: Int): Long? {
        if (attempt >= MAX_ATTEMPTS) return null
        return BASE_DELAY_MS shl attempt // 1s, 2s, 4s
    }
}

package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

// Reads through the public PlaybackEngine flows only, so tests survive a split of the engine files.

/** The current value of one of [PlaybackEngine]'s state flows (not `errors`, which holds none). */
internal fun <T> Flow<T>.now(): T = runBlocking { first() }

/** The playing slot, from the public queue and index. Call on the main thread for a consistent pair. */
internal fun PlaybackEngine.currentSlot(): QueueSlot? = queue.now().getOrNull(currentIndex.now())

internal fun List<QueueSlot>.songIds(): List<String> = map { it.song.id }

internal fun List<QueueSlot>.slotIds(): List<String> = map { it.slotId }

package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A playback engine fake that models the queue the view model acts on: an ordered list of slots and
 * a cursor. Play Next inserts a new slot right after the cursor, Add to Queue appends one, next moves
 * the cursor one slot on, and a queue load installs a fresh queue. A load can be held on a gate, the
 * way the real engine suspends while it builds artwork or waits for its session connection; a load
 * cancelled while held installs nothing. A restore can be held the same way. Every command is
 * recorded in [calls].
 */
internal class QueueModelEngine : PlaybackEngine {
    val calls = mutableListOf<String>()

    /** Gates for the next queue loads, one per load in order; a load with no gate left runs at once. */
    val loadGates = ArrayDeque<CompletableDeferred<Unit>>()

    /** False: a load returns without installing, as one refused for lack of a connection does. */
    var loadsInstall = true

    /** Holds [restoreSavedQueue] until completed; null lets it return at once. */
    var restoreGate: CompletableDeferred<Unit>? = null

    /** What [restoreSavedQueue] installs (songs, current index); null restores nothing. */
    var restoreInstalls: Pair<List<Song>, Int>? = null

    var acceptsInserts = true

    private var nextSlot = 0
    private val slots = MutableStateFlow<List<QueueSlot>>(emptyList())
    private val cursor = MutableStateFlow(-1)
    private val songId = MutableStateFlow<String?>(null)
    private val upcoming = MutableStateFlow<List<QueueSlot>>(emptyList())
    val ended = MutableStateFlow(false)
    val playing = MutableStateFlow(false)
    val customized = MutableStateFlow(false)

    /** Installs [songs] with [current] playing, paused, as a restore or a test setup would. */
    fun install(songs: List<Song>, current: Int) {
        slots.value = songs.map { QueueSlot("s${nextSlot++}", it) }
        cursor.value = current
        ended.value = false
        customized.value = false
        publish()
    }

    fun songIds(): List<String> = slots.value.map { it.song.id }

    fun slotIds(): List<String> = slots.value.map { it.slotId }

    fun currentIndex(): Int = cursor.value

    fun currentSlotId(): String? = slots.value.getOrNull(cursor.value)?.slotId

    /** Moves the cursor without a recorded command, as the engine's own track changes do. */
    fun moveTo(index: Int) {
        cursor.value = index
        publish()
    }

    private fun publish() {
        val list = slots.value
        val at = cursor.value
        songId.value = list.getOrNull(at)?.song?.id
        upcoming.value = if (at in list.indices) list.drop(at + 1) else emptyList()
    }

    private fun insert(at: Int, song: Song) {
        slots.value = slots.value.toMutableList().apply { add(at, QueueSlot("s${nextSlot++}", song)) }
        customized.value = true
        publish()
    }

    override val isQueueEnded: Flow<Boolean> = ended
    override val isPlaying: Flow<Boolean> = playing
    override val isBuffering: Flow<Boolean> = emptyFlow()
    override val currentSongId: Flow<String?> = songId
    override val currentSong: Flow<Song?> = MutableStateFlow(null)
    override val currentPosition: Flow<Long> = emptyFlow()
    override val duration: Flow<Long> = emptyFlow()
    override val errors: Flow<PlaybackError> = emptyFlow()
    override val queue: Flow<List<QueueSlot>> = slots
    override val currentIndex: Flow<Int> = cursor
    override val upcomingQueue: Flow<List<QueueSlot>> = upcoming
    override val shuffleEnabled: Flow<Boolean> = emptyFlow()
    override val shuffleReorderedByUser: Flow<Boolean> = emptyFlow()
    override val repeatMode: Flow<RepeatMode> = emptyFlow()
    override val hasNext: Flow<Boolean> = emptyFlow()
    override val hasPrevious: Flow<Boolean> = emptyFlow()
    override val isQueueCustomized: Flow<Boolean> = customized

    override suspend fun playQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean?) {
        calls += "playQueue:${songs.map { it.id }}@$startIndex"
        loadGates.removeFirstOrNull()?.await()
        if (!loadsInstall) return
        install(songs, startIndex)
        playing.value = true
        calls += "installed:${songs.map { it.id }}"
    }

    override suspend fun restoreSavedQueue() {
        restoreGate?.await()
        restoreInstalls?.let { (songs, current) -> install(songs, current) }
    }

    override fun persistPlaybackState() {}

    override fun next() {
        calls += "next"
        if (cursor.value + 1 < slots.value.size) moveTo(cursor.value + 1)
    }

    override fun previous() {}
    override suspend fun setShuffleEnabled(enabled: Boolean) {}
    override fun setRepeatMode(mode: RepeatMode) {}

    override fun playNext(song: Song): Boolean {
        calls += "playNext:${song.id}"
        if (!acceptsInserts) return false
        insert(cursor.value + 1, song)
        return true
    }

    override fun addToQueue(song: Song): Boolean {
        calls += "addToQueue:${song.id}"
        if (!acceptsInserts) return false
        insert(slots.value.size, song)
        return true
    }

    override fun moveQueueItem(slotId: String, toIndex: Int) {}
    override fun removeQueueItem(slotId: String) {}
    override fun seekToQueueItem(slotId: String) {}

    override fun pause() {
        calls += "pause"
        playing.value = false
    }

    override fun resume() {
        calls += "resume"
        playing.value = true
    }

    override fun stop() {}
    override fun seekTo(positionMs: Long) {}
    override fun release() {}
    override suspend fun reconcileWithDatabase() {}

    companion object {
        fun song(id: String) = Song(
            id = id,
            title = id,
            artist = "Artist",
            album = "Album",
            duration = 60_000L,
            filePath = "file:///$id.mp3",
        )
    }
}

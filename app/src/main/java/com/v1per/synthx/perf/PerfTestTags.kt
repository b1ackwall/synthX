package com.v1per.synthx.perf

/**
 * Test tags the benchmark module drives the app with. MainScaffold exposes them to UiAutomator
 * as resource ids (testTagsAsResourceId), so each value must stay a plain, unique identifier.
 * The benchmark module keeps its own copy of these strings; change both together.
 */
internal object PerfTestTags {
    /** The whole player sheet; its bounds tell collapsed from expanded. */
    const val PlayerSheet = "player_sheet"

    /** Mini player row; tapping its center expands the sheet. */
    const val MiniPlayer = "player_mini"
    const val MiniPlayPause = "player_mini_play_pause"

    const val SeekBar = "player_seek_bar"
    const val Previous = "player_previous"
    const val PlayPause = "player_play_pause"
    const val Next = "player_next"
    const val QueueButton = "player_queue_button"

    const val QueueList = "player_queue_list"

    /** Shared by every queue row. */
    const val QueueRow = "player_queue_row"

    const val NavLibrary = "nav_library"
    const val LibraryScreen = "screen_library"
}

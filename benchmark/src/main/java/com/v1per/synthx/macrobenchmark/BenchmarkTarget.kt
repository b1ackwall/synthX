package com.v1per.synthx.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.test.platform.app.InstrumentationRegistry

/** The app under test, plus copies of the app's PerfTestTags and PerfTraceSections strings. */
internal object BenchmarkTarget {
    /** :app's benchmark build type. Never the production package. */
    const val PACKAGE = "com.v1per.synthx.benchmark"
    private const val PRODUCTION_PACKAGE = "com.v1per.synthx"

    init {
        check(PACKAGE != PRODUCTION_PACKAGE) { "Benchmark target must never be production" }
    }
}

/** Mirrors app/src/main/java/com/v1per/synthx/perf/PerfTestTags.kt. */
internal object Tags {
    const val PlayerSheet = "player_sheet"
    const val MiniPlayer = "player_mini"
    const val MiniPlayPause = "player_mini_play_pause"
    const val SeekBar = "player_seek_bar"
    const val Previous = "player_previous"
    const val PlayPause = "player_play_pause"
    const val Next = "player_next"
    const val QueueButton = "player_queue_button"
    const val QueueList = "player_queue_list"
    const val QueueRow = "player_queue_row"
    const val NavLibrary = "nav_library"
    const val LibraryScreen = "screen_library"
    const val NavHome = "nav_home"
    const val HomeScreen = "screen_home"
}

/** Mirrors the app's cd_play and cd_pause strings; the app ships no translations. */
internal object UiText {
    const val Play = "Play"
    const val Pause = "Pause"
}

/** Mirrors app/src/main/java/com/v1per/synthx/perf/PerfTraceSections.kt. */
internal object Sections {
    const val PlayerSheet = "synthX.PlayerSheet"
    const val OnProgressChanged = "synthX.PlayerSheet.onProgressChanged"
    const val Background = "synthX.PlayerBackground"
    const val BackgroundDraw = "synthX.PlayerBackground.draw"
    const val AlbumArtWithGlow = "synthX.PlayerAlbumArtWithGlow"
    const val MiniContent = "synthX.PlayerMiniContent"
    const val TitleRow = "synthX.PlayerTitleRow"
    const val ControlsOverlay = "synthX.PlayerControlsOverlay"
    const val QueueContent = "synthX.PlayerQueueContent"
    const val QueueRow = "synthX.QueueRow"
    const val QueueMaskDraw = "synthX.QueueMaskLayer.draw"
    const val ArtDecode = "synthX.PlayerArtDecode"
    const val MainShell = "synthX.MainShell"
    const val MainShellContent = "synthX.MainShell.content"
    const val NavHost = "synthX.NavHost"
    const val BottomChrome = "synthX.BottomChrome"
    const val BottomNav = "synthX.BottomNav"
    const val LibraryScreen = "synthX.LibraryScreen"
    const val LibraryContent = "synthX.LibraryContent"
    const val HomeScreen = "synthX.HomeScreen"

    /** Every section; each is reported as a sum and a count per iteration. */
    val All = listOf(
        PlayerSheet, OnProgressChanged, Background, BackgroundDraw, AlbumArtWithGlow, MiniContent,
        TitleRow, ControlsOverlay, QueueContent, QueueRow, QueueMaskDraw, ArtDecode, MainShell,
        MainShellContent, NavHost, BottomChrome, BottomNav, LibraryScreen, LibraryContent, HomeScreen,
    )

    /** Sections that also report their longest single run. */
    val WithMax = listOf(PlayerSheet, BackgroundDraw, QueueMaskDraw, ArtDecode, MainShell)
}

/** Run options, read from `am instrument -e` arguments. */
internal object BenchmarkArgs {
    private val args get() = InstrumentationRegistry.getArguments()

    /** `-e synthx.compilation none` measures the uncompiled worst case; default is DEFAULT. */
    fun compilationMode(): CompilationMode =
        when (val mode = args.getString("synthx.compilation") ?: "default") {
            "default" -> CompilationMode.DEFAULT
            "none" -> CompilationMode.None()
            else -> error("synthx.compilation must be 'default' or 'none', got '$mode'")
        }

    /** `-e synthx.iterations N` overrides every scenario's own default count. */
    fun iterations(default: Int = 10): Int =
        args.getString("synthx.iterations")?.toIntOrNull() ?: default

    /** Macrobenchmark's own switch; false skips the compilation reset entirely. */
    fun compilationEnabled(): Boolean =
        args.getString("androidx.benchmark.compilation.enabled")?.toBoolean() ?: true
}

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
    const val NavLibrary = "nav_library"
    const val LibraryScreen = "screen_library"
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

    /** `-e synthx.iterations N`; each iteration is one expand plus one collapse. */
    fun iterations(): Int = args.getString("synthx.iterations")?.toIntOrNull() ?: 10

    /** Macrobenchmark's own switch; false skips the compilation reset entirely. */
    fun compilationEnabled(): Boolean =
        args.getString("androidx.benchmark.compilation.enabled")?.toBoolean() ?: true
}

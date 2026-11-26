package com.v1per.synthx.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.playback.PlayerBackgroundArt
import com.v1per.synthx.playback.PlayerBackgroundArtSidePx
import com.v1per.synthx.playback.backgroundBrightness
import com.v1per.synthx.ui.components.PlayerToggleButton
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.assertImageEventually
import com.v1per.synthx.ui.testing.colorsMatch
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.SynthXTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The art fade as drawn, frame by frame on the paused test clock: the real transition, background
 * (with its effect) and a toggle, then the real shell for taps and drags during a fade. Uses solid
 * grey art, which the blur, twist and grey-preserving saturation leave flat.
 */
@RunWith(AndroidJUnit4::class)
class PlayerArtFadeInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val darkArt = solidArt("dark", DARK_GREY)
    private val brightArt = solidArt("bright", BRIGHT_GREY)

    private var art by mutableStateOf<PlayerBackgroundArt<Bitmap>?>(null)
    private var transition: PlayerArtTransition<ImageBitmap>? = null
    private val probeCompositions = AtomicInteger()

    /** What one frame showed, next to the transition state it was drawn from. */
    private data class Drawn(
        val progress: Float,
        val fading: Boolean,
        val colors: PlayerContentColors,
        val background: Color,
    )

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val powerSave = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertFalse(
            "The fade snaps under reduced motion: turn battery saver off and set the animator duration scale to 1x",
            shouldReduceAmbientMotion(powerSave, scale),
        )
        shell.setUp()
    }

    @After fun tearDown() = shell.tearDown()

    @Test fun theTextFlipsAtTheMidpointFrameAndTheFadeEndsOnTheTarget() {
        showHarness(darkArt)
        val darkBackground = settledBackground(PlayerContentColors.Light)
        val frames = mutableListOf<Drawn>()
        composeRule.mainClock.autoAdvance = false
        try {
            probeCompositions.set(0)
            composeRule.runOnUiThread { art = brightArt }
            var waited = 0
            while (!state().fading && waited++ < START_LIMIT) composeRule.mainClock.advanceTimeByFrame()
            assertTrue("the fade never started", state().fading)
            var settled = 0
            while (settled < SETTLED_FRAMES && frames.size < FRAME_LIMIT) {
                frames += drawnFrame()
                if (!frames.last().fading) settled++
                composeRule.mainClock.advanceTimeByFrame()
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        val brightBackground = settledBackground(PlayerContentColors.Dark)

        val fading = frames.filter { it.fading }
        // 300 ms on the 16 ms test clock.
        assertTrue("the fade drew ${fading.size} frames", fading.size in 18..21)
        assertTrue("no frame before the midpoint", fading.any { it.progress in 0.05f..0.45f })
        assertTrue(
            "the background never showed a mix: $fading",
            fading.any {
                it.background.red > darkBackground.red + MIX_MARGIN &&
                    it.background.red < brightBackground.red - MIX_MARGIN
            },
        )
        for ((previous, next) in frames.zipWithNext()) {
            assertTrue(
                "the background went back from $previous to $next",
                next.background.red >= previous.background.red - NOISE,
            )
        }
        for (frame in frames.filter { !it.fading }) {
            assertTrue(
                "a wrong background after the fade: $frame",
                colorsMatch(brightBackground, frame.background, NOISE),
            )
        }
        assertEquals("the probe recomposed once, at the flip", 1, probeCompositions.get())
    }

    @Test fun tapsAndDragsStillWorkMidFade() {
        val bright = artSong("bright")
        val dark = artSong("dark")
        val engine = FakePlaybackEngine(bright, upcoming = (1..5).map { FakePlaybackEngine.song("next-$it") })
        val viewModel = shell.startShell(engine, loadBackgroundArt = { request -> artFor(request.songId)?.image })
        composeRule.waitUntil(WAIT_MS) { viewModel.playerBackgroundArt.value?.songId == bright.id }
        composeRule.onNodeWithTag(PerfTestTags.MiniPlayer).performClick()
        composeRule.waitForIdle()
        val sheet = sheetBounds()
        assertTrue("the sheet did not open: $sheet", sheet.top < rootHeight() * 0.1f)
        val before = sheetBackground()

        composeRule.mainClock.autoAdvance = false
        try {
            engine.currentSongState.value = dark
            var waited = 0
            while (viewModel.playerBackgroundArt.value?.songId != dark.id && waited++ < START_LIMIT) {
                composeRule.mainClock.advanceTimeByFrame()
                SystemClock.sleep(DECODE_PAUSE_MS)
            }
            assertEquals(dark.id, viewModel.playerBackgroundArt.value?.songId)
            repeat(MID_FADE_FRAMES) { composeRule.mainClock.advanceTimeByFrame() }
            val midFade = sheetBackground()

            // Next, not play: starting playback can raise the notification rationale over the sheet.
            val skips = engine.nextCalls.get()
            composeRule.onNodeWithTag(PerfTestTags.Next).performClick()
            composeRule.runOnIdle {
                assertEquals("the next button missed a mid-fade tap", skips + 1, engine.nextCalls.get())
            }
            repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
            val stillFading = sheetBackground()
            assertTrue("the fade had not started: $before then $midFade", midFade.red < before.red - MIX_MARGIN)
            assertTrue("the fade stopped at the tap: $midFade then $stillFading", stillFading.red < midFade.red - NOISE)

            val travel = rootHeight()
            composeRule.onRoot().performTouchInput { slowDrag(Offset(sheet.center.x, travel * 0.15f), travel * 0.7f) }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        assertTrue("a mid-fade drag down did not close the sheet", sheetBounds().top > rootHeight() * 0.6f)
    }

    @Test fun anIdenticalPictureChangesNothing() {
        showHarness(darkArt)
        val shown = settledBackground(PlayerContentColors.Light)
        composeRule.mainClock.autoAdvance = false
        try {
            probeCompositions.set(0)
            // Another decode of the same picture: a new bitmap with equal pixels.
            composeRule.runOnUiThread { art = solidArt("dark-again", DARK_GREY) }
            repeat(SETTLED_FRAMES) {
                composeRule.mainClock.advanceTimeByFrame()
                assertFalse("an identical picture started a fade", state().fading)
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        assertTrue(colorsMatch(shown, settledBackground(PlayerContentColors.Light), NOISE))
        assertEquals("an identical picture recomposed the content", 0, probeCompositions.get())
    }

    @Test fun losingTheArtSnapsToThePlainFillAndLightText() {
        showHarness(brightArt)
        settledBackground(PlayerContentColors.Dark)
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnUiThread { art = null }
            composeRule.mainClock.advanceTimeByFrame()
            val now = state()
            assertFalse("losing the art started a fade", now.fading)
            assertSame(PlayerContentColors.Light, now.colors)
            repeat(SETTLED_FRAMES) {
                // The snap lands after the frame above, so each check follows a frame.
                composeRule.mainClock.advanceTimeByFrame()
                // The unfiltered fill: the effect's saturation would move it beyond the tolerance.
                composeRule.assertImageEventually("the plain fill", ::capture) { image ->
                    val background = backgroundOf(image)
                    contentMismatch(image, PlayerContentColors.Light)
                        ?: if (colorsMatch(SurfaceVariant, background, FILL_TOLERANCE)) null else "fill $background"
                }
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test fun stoppingTheActivityFinishesARunningFade() {
        showHarness(darkArt)
        settledBackground(PlayerContentColors.Light)
        val scenario = composeRule.activityRule.scenario
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnUiThread { art = brightArt }
            var waited = 0
            while (!state().fading && waited++ < START_LIMIT) composeRule.mainClock.advanceTimeByFrame()
            repeat(MID_FADE_FRAMES) { composeRule.mainClock.advanceTimeByFrame() }
            assertTrue("not mid-fade before the stop", stateNow().fading)
            scenario.moveToState(Lifecycle.State.CREATED)
            val stopped = stateNow()
            assertFalse("the fade kept running after the activity stopped", stopped.fading)
            assertSame(PlayerContentColors.Dark, stopped.colors)
            scenario.moveToState(Lifecycle.State.RESUMED)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        settledBackground(PlayerContentColors.Dark)
    }

    private fun showHarness(initial: PlayerBackgroundArt<Bitmap>) {
        art = initial
        composeRule.setContent {
            SynthXTheme {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val current = rememberPlayerArtTransition(art)
                    SideEffect { transition = current }
                    ProvidePlayerContentColors(current) {
                        PlayerAltStageBackground(
                            art = current,
                            hasSong = true,
                            isPlaying = false,
                            fullHeight = maxHeight,
                            progress = 1f,
                        )
                        ContentProbe(
                            probeCompositions,
                            Modifier.align(Alignment.TopStart).padding(PROBE_INSET).testTag(PROBE_TAG),
                        )
                        PlayerToggleButton(
                            checked = true,
                            onClick = {},
                            icon = Icons.Filled.Shuffle,
                            contentDescription = "fade probe",
                            modifier = Modifier.align(Alignment.Center).testTag(TOGGLE_TAG),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private class TransitionState(val progress: Float, val fading: Boolean, val colors: PlayerContentColors)

    private fun state(): TransitionState {
        lateinit var read: TransitionState
        composeRule.runOnIdle { read = readState() }
        return read
    }

    /** Without waiting for idle, which a stopped activity may never reach. */
    private fun stateNow(): TransitionState {
        lateinit var read: TransitionState
        composeRule.runOnUiThread { read = readState() }
        return read
    }

    private fun readState(): TransitionState {
        val current = checkNotNull(transition)
        return TransitionState(current.progress.value, current.from != null, current.displayedColors)
    }

    /**
     * The frame now on screen. Its colors must be the target's from the midpoint on and the old
     * ones before it, in the probe and in the toggle's fill and icon alike.
     */
    private fun drawnFrame(): Drawn {
        val now = state()
        val expected = when {
            !now.fading -> PlayerContentColors.Dark
            now.progress >= PlayerArtFlipProgress -> PlayerContentColors.Dark
            else -> PlayerContentColors.Light
        }
        var background = Color.Unspecified
        composeRule.assertImageEventually("frame at progress ${now.progress}", ::capture) { image ->
            background = backgroundOf(image)
            contentMismatch(image, expected)
        }
        return Drawn(now.progress, now.fading, now.colors, background)
    }

    /** Null when the probe and the toggle both show [colors], else what they showed. */
    private fun contentMismatch(image: ImageBitmap, colors: PlayerContentColors): String? {
        val pixels = image.toPixelMap()
        val probe = composeRule.onNodeWithTag(PROBE_TAG).fetchSemanticsNode().boundsInRoot
        val probeColor = pixels[probe.center.x.toInt(), probe.center.y.toInt()]
        if (!colorsMatch(colors.content, probeColor)) return "probe $probeColor, wanted ${colors.content}"
        val toggle = composeRule.onNodeWithTag(TOGGLE_TAG).fetchSemanticsNode().boundsInRoot
        // Beside the centred icon, clear of the corners.
        val fill = pixels[toggle.left.toInt() + TOGGLE_FILL_INSET_PX, toggle.center.y.toInt()]
        if (!colorsMatch(colors.content, fill)) return "toggle fill $fill, wanted ${colors.content}"
        var icon = 0
        for (x in toggle.left.toInt() until toggle.right.toInt()) {
            for (y in toggle.top.toInt() until toggle.bottom.toInt()) {
                if (colorsMatch(colors.onContent, pixels[x, y])) icon++
            }
        }
        return if (icon >= MIN_ICON_PIXELS) null else "toggle icon in ${colors.onContent}: $icon pixels"
    }

    private fun settledBackground(colors: PlayerContentColors): Color {
        var background = Color.Unspecified
        composeRule.assertImageEventually("settled on $colors", ::capture) { image ->
            background = backgroundOf(image)
            contentMismatch(image, colors)
        }
        return background
    }

    private fun capture(): ImageBitmap = composeRule.onRoot().captureToImage()

    /** The mean of a patch clear of the probe and the toggle, which evens out the grain. */
    private fun backgroundOf(image: ImageBitmap): Color = patchMean(image, image.width / 4, image.height * 3 / 4)

    private fun patchMean(image: ImageBitmap, centerX: Int, centerY: Int): Color {
        val pixels = image.toPixelMap()
        var red = 0f
        var green = 0f
        var blue = 0f
        var count = 0
        for (x in centerX - PATCH_RADIUS..centerX + PATCH_RADIUS) {
            for (y in centerY - PATCH_RADIUS..centerY + PATCH_RADIUS) {
                val pixel = pixels[x, y]
                red += pixel.red
                green += pixel.green
                blue += pixel.blue
                count++
            }
        }
        return Color(red / count, green / count, blue / count)
    }

    private fun sheetBounds(): Rect =
        composeRule.onNodeWithTag(PerfTestTags.PlayerSheet).fetchSemanticsNode().boundsInRoot

    private fun rootHeight(): Float = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height

    /** Near the sheet's left edge above its bottom, where only the background draws. */
    private fun sheetBackground(): Color {
        val sheet = sheetBounds()
        var color = Color.Unspecified
        composeRule.assertImageEventually("the sheet background", ::capture) { image ->
            color =
                patchMean(image, (sheet.left + sheet.width * 0.03f).toInt(), (sheet.top + sheet.height * 0.88f).toInt())
            null
        }
        return color
    }

    /** A slow drag by [dy], then a still hold, so the release carries no fling velocity. */
    private fun TouchInjectionScope.slowDrag(from: Offset, dy: Float) {
        down(from)
        val steps = 120
        repeat(steps) {
            advanceEventTime(16)
            moveBy(Offset(0f, dy / steps))
        }
        repeat(20) {
            advanceEventTime(16)
            moveBy(Offset.Zero)
        }
        up()
    }

    private fun artFor(songId: String): PlayerBackgroundArt<Bitmap>? = when (songId) {
        "bright" -> brightArt
        "dark" -> darkArt
        else -> null
    }

    private companion object {
        const val DARK_GREY = 0xFF141414.toInt()
        const val BRIGHT_GREY = 0xFFE6E6E6.toInt()
        const val PROBE_TAG = "fade_probe"
        const val TOGGLE_TAG = "fade_toggle"
        val PROBE_INSET = 96.dp
        const val TOGGLE_FILL_INSET_PX = 4
        const val MIN_ICON_PIXELS = 20
        const val PATCH_RADIUS = 6

        /** A mix must sit this far inside both settled backgrounds. */
        const val MIX_MARGIN = 8f / 255f

        /** Grain and rounding noise between two captures of one state. */
        const val NOISE = 3f / 255f

        /** The plain fill under the grain; the saturated fill differs by about 7 levels in red. */
        const val FILL_TOLERANCE = 4f / 255f
        const val START_LIMIT = 120
        const val FRAME_LIMIT = 60
        const val SETTLED_FRAMES = 8
        const val MID_FADE_FRAMES = 6
        const val DECODE_PAUSE_MS = 10L
        const val WAIT_MS = 10_000L

        fun solidArt(id: String, argb: Int): PlayerBackgroundArt<Bitmap> {
            val bitmap = createBitmap(PlayerBackgroundArtSidePx, PlayerBackgroundArtSidePx)
            bitmap.eraseColor(argb)
            return PlayerBackgroundArt(id, bitmap, bitmap.backgroundBrightness(PlayerAltStageStyle.Saturation))
        }

        fun artSong(id: String) = Song(
            id = id,
            title = id,
            artist = "Artist",
            album = "Album",
            duration = 60_000L,
            filePath = "file:///$id.mp3",
            albumArtUri = "test://art/$id",
        )
    }
}

/** A square in the content color; counts its own recompositions. */
@Composable
private fun ContentProbe(compositions: AtomicInteger, modifier: Modifier) {
    val colors = LocalPlayerContentColors.current ?: PlayerContentColors.Light
    SideEffect { compositions.incrementAndGet() }
    Box(modifier.size(64.dp).background(colors.content))
}

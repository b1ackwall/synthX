package com.v1per.synthx.ui.player.v2

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.components.DragResistantIconButton
import com.v1per.synthx.ui.components.MoreOptionsButton
import com.v1per.synthx.ui.components.PlayerAlbumArt
import com.v1per.synthx.ui.components.PlayerSeekBar
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Deliberate breathing room between the top system inset and the album art region — and
 * *only* that. It is not a status-bar allowance: the real inset is read separately from
 * [WindowInsets.safeDrawing] and added to this (see [PlayerV2ExpandedContent]).
 *
 * That separation is the whole point of the constant. The old (detached) player's
 * `ExpandedTopMargin = 32.dp` (PlayerSheetSkeleton.kt:68) was one number silently doing both
 * jobs, which is why the old expanded sheet's top edge sat *under* the status bar on every
 * device — there was no inset term anywhere in that layout. Design doc §8 lists fixing this
 * as in scope for the rebuild rather than something to carry over.
 */
val ExpandedArtTopMargin: Dp = 24.dp

/**
 * Left/right inset of the expanded content column, and the single source of the expanded
 * album art's width.
 *
 * The art is centered at `maxWidth - ExpandedContentHorizontalInset * 2`, so its left edge
 * lands exactly here; [PlayerV2TitleRow] uses the same value as its start padding so the
 * title text's left edge lines up with the art's. Declaring it once is deliberate — the old
 * player's art inset (`maxWidth - Spacing.HorizontalPadding * 4`, QueueArtPositioning.kt:58)
 * and its title row's start padding (`Spacing.HorizontalPadding` + `Spacing.lg` = 40dp,
 * PlayerSkeletonExpandedContent.kt:47,53) were independently written constants that happened
 * to disagree by 8dp, which is exactly the class of drift this stage exists to remove.
 *
 * Numerically identical to the old `Spacing.HorizontalPadding * 4` art width, so the art
 * itself is unchanged in size — only the title now agrees with it.
 *
 * Note this alignment is exact only while the art is width-bound (every normal portrait
 * phone). On a short/landscape screen the art clamps to the available height and, being
 * centered, its edges move inward; the title row stays at this fixed inset.
 */
val ExpandedContentHorizontalInset: Dp = Spacing.HorizontalPadding * 2

/**
 * Stage 2.2 built real expanded content — title/artist + 3-dot menu near the top, seek bar +
 * transport + queue button near the bottom — statically laid out via a Column, not the old
 * (detached) PlayerSkeletonControlsOverlay's
 * progress-driven slide/fade/reveal-clip wrapper; only its *inner* content (the seek bar,
 * transport row, and queue row themselves) is mirrored here.
 *
 * Stage 2.3 adds the envelope reveal: [EnvelopeRevealShape], clipped onto this Column as the
 * single container the design doc (§2) calls for, grows the visible region bottom-up as
 * [progress] climbs — content is drawn once at its final position and revealed, never
 * animated in place. The call site decides whether this composable exists in the tree at all —
 * as of migration Step 5 that is a `Transition.AnimatedVisibility(visible = { it == Expanded })`
 * rather than Stage 2.3's `progress > 0f` boolean, on the same frames in both directions; once
 * present, this clip decides how much of it is visible. The fact that this block renders
 * underneath the mini content
 * whenever both are visible in the same frame is unrelated to the reveal and still expected —
 * z-order layering between the two is out of scope for this stage.
 *
 * Stage 2.3b makes two changes on top of that.
 *
 * First, the container is no longer `fillMaxSize`. It is [requiredHeight] at the sheet's fully
 * expanded height — constant for the screen — and bottom-anchored inside the sheet Box. (Those
 * three modifiers now live on the [PlayerV2Host] call site's AnimatedVisibility node rather than
 * on this Column; see the modifier note below.) Under `fillMaxSize` this Column was re-measured
 * against the live `sheetHeight` on every frame of a drag, which was harmless while the only
 * flexible child was a spacer but would have resized the album art (and re-triggered Coil)
 * once-per-frame. Design doc §1 asks for the expanded layout to be "laid out once, at fixed
 * final positions… not re-derived per animation frame"; this is what actually delivers that.
 * The visible consequence is that the content no longer slides with the sheet's top edge as
 * the sheet grows — it holds still and is revealed, which is the §2 envelope model taken
 * literally.
 *
 * Second, the 320dp placeholder spacer is gone, replaced by a real album art frame sized off
 * Column's own measurement of the title row and controls block. See the inline comments.
 *
 * The art deliberately stays *inside* this clipped Column rather than being split out as an
 * unclipped sibling, which is where design doc §2 will eventually want it. At this stage the
 * art has no independent animation, so there is nothing for the clip to interfere with, and
 * the split only becomes load-bearing at Stage 2.4b — where it arrives for free: a matched
 * `Modifier.sharedElement` renders into the SharedTransitionScope's overlay specifically, per
 * its own API docs, "to avoid being faded in/out along with its parents or clipped by its
 * parent". Outside an active transition the clip is either fully open (settled expanded) or
 * this composable is not in the tree at all (settled collapsed), so drawing in place is never
 * wrong in the meantime.
 *
 * The queue button's [onClick][DragResistantIconButton] is a no-op: queue mode doesn't exist
 * until Stage 4, so there is nothing to wire it to yet, but it's laid out in its final
 * position now so Stage 4 only has to attach behavior, not insert a new element.
 *
 * **Migration Step 5 — the modifier split, and why it is geometry-neutral.** This composable's
 * outer Column used to carry
 * `fillMaxWidth → requiredHeight(expandedHeight) → align(BottomCenter) → offset(y = bottomInset)
 * → clip(revealShape)`, with `expandedHeight` and `bottomInset` as parameters and [BoxScope] as
 * the receiver (for `align`). `AnimatedVisibility`'s content lambda is an
 * `AnimatedVisibilityScope`, not a `BoxScope`, so the three placement/sizing modifiers moved out
 * to the `AnimatedVisibility` call itself and both parameters went with them; only
 * `fillMaxWidth` and `clip` stayed. The receiver went away entirely.
 *
 * The split is size- and position-neutral, traced through the framework rather than assumed:
 * `AnimatedVisibility` emits exactly one `Layout` node with the caller's modifier applied first
 * (`AnimatedVisibility.kt:855-877`), and its measure policy passes the incoming constraints to
 * its child untouched, reports `max(children)` as its own size, and places at `(0, 0)`
 * (:896-916). So `requiredHeight` on that node fixes the constraints the Column is measured
 * with, the Column's `fillMaxWidth` still resolves against the sheet Box's width, the node
 * reports the Column's exact size back up, and `align`/`offset` position that node precisely
 * where they positioned the Column. `EnvelopeRevealShape` therefore still reads the same
 * `size.height` it always did. The one node that could have perturbed this — the
 * `EnterExitTransitionElement` from `createModifier` — is inert here because neither the enter
 * nor the exit carries a `changeSize`, so `shouldAnimateSizeChange` is false and no size or
 * offset animation is created (`EnterExitTransition.kt:956-957`).
 *
 * One genuinely new behavior, harmless today: `AnimatedVisibilityImpl` wraps the caller's
 * modifier in a `layout` that reports `IntSize.Zero` **during lookahead** while the target state
 * is not visible (`AnimatedVisibility.kt:766-776`). This subtree *is* inside a lookahead scope —
 * Step 1 wrapped it in `SharedTransitionLayout` — so on a collapse drag this node's lookahead
 * size is zero while its approach (real) size is unchanged. Nothing reads lookahead geometry
 * here yet, so there is no visible effect; it becomes load-bearing at Stage 2.4b, when the album
 * art's `sharedElement` starts deriving its target bounds from exactly that pass.
 */
@Composable
fun PlayerV2ExpandedContent(
    song: Song,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPauseClick: () -> Unit,
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    progress: State<Float>,
) {
    // Keyed on progress's own identity (stable for this composable's whole lifetime — as of
    // migration Step 4 it's the same animateFloat TransitionAnimationState instance from
    // PlayerV2Host on every recomposition, `remember(this)`-scoped to the transition itself,
    // Transition.kt:2119-2120), so this Shape instance is built once, not once per frame.
    val revealShape = remember(progress) { EnvelopeRevealShape(progress) }

    // Read as a plain Dp rather than applied via Modifier.statusBarsPadding(): this value is
    // a term in the art's height budget below, and a padding modifier would shrink the
    // Column's usable height invisibly from outside the arithmetic. safeDrawing rather than
    // statusBars so a display cutout taller than the status bar is not under-reported —
    // identical on non-cutout devices. Un-consumed by the time it reaches here: the only
    // inset consumed above this point is BottomChromeOverlay's navigationBarsPadding()
    // (SynthXBottomNav.kt:115), and SynthXNavHost's consumeWindowInsets is on a sibling
    // subtree, not an ancestor.
    val topInset = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Top)
        .asPaddingValues()
        .calculateTopPadding()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // All this Column keeps as of Step 5. The `.requiredHeight(expandedHeight)
            // .align(BottomCenter).offset(y = bottomInset)` that used to sit between
            // fillMaxWidth and clip now lives on the AnimatedVisibility node one level up —
            // see this composable's doc for why, and why the geometry is unchanged.
            .clip(revealShape),
    ) {
        Spacer(modifier = Modifier.height(topInset + ExpandedArtTopMargin))
        // The art frame. Sized by Column's own measurement rather than by any budget
        // arithmetic: Column measures its zero-weight children first (the title row and the
        // controls block below, each at whatever height it actually needs at the current font
        // scale) and hands this weighted child exactly what is left
        // (RowColumnMeasurePolicy.kt — "First measure children with zero weight", then
        // `mainAxisMax = childMainAxisSize` for the weighted pass). BoxWithConstraints reads
        // that remainder back as a real Dp.
        //
        // This is the direct replacement for the old player's two hand-written height
        // budgets, which partitioned the same space and disagreed by exactly
        // ExpandedTopMargin — `artMaxHeight` (QueueArtPositioning.kt:59) omitted the top
        // margin that `expandedRegionHeight` (:81) subtracted, so on any screen where height
        // was the binding constraint the art overran its region by 32dp and the controls
        // overlay painted over the bottom of the artist line. There is only one budget now
        // and Compose owns it.
        //
        // It also removes the two sp-as-dp constants that fed those budgets
        // (TitleRowReservedHeight = 52.dp is titleLarge's 28sp + bodyLarge's 24sp line
        // heights written down as dp; ControlsOverlayHeight = 300.dp over-reserved the real
        // ~240dp block by 60dp). Measuring the real composables is correct at every font
        // scale; neither constant was.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            // Square, capped by whichever axis binds. maxHeight here is the genuine leftover
            // space, so the cap needs no separate derivation.
            val side = min(maxWidth - ExpandedContentHorizontalInset * 2, maxHeight)
            PlayerAlbumArt(
                song = song,
                isPlaying = isPlaying,
                size = side,
                // The mini bar's 44dp thumbnail would visibly upscale at this size.
                fullResolution = true,
            )
        }
        PlayerV2TitleRow(song = song)
        Spacer(modifier = Modifier.height(Spacing.lg))
        PlayerV2ControlsBlock(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            onPlayPauseClick = onPlayPauseClick,
            currentPosition = currentPosition,
            duration = duration,
            onSeek = onSeek,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onPreviousClick = onPreviousClick,
            onNextClick = onNextClick,
        )
    }
}

/**
 * Stage 2.3's envelope reveal clip (design doc §2) — a rectangle grown bottom-up as
 * [progress] climbs from 0f (nothing revealed) to 1f (the whole container revealed), so
 * content already drawn at its final position becomes visible edge-first from the bottom,
 * matching the sheet's own bottom-up growth ([PlayerV2Host]'s `sheetHeight` lerp) rather than
 * fading or sliding in place.
 *
 * [progress] is read inside [createOutline] — called from [Shape], not from this class's own
 * constructor or from any `@Composable` body — so the read happens where Compose UI's
 * graphics-layer machinery invokes it: inside `NodeCoordinator.updateLayerParameters()`'s
 * `snapshotObserver.observeReads(...)` block (`GraphicsLayerScope.updateOutline()` calls
 * `shape.createOutline(...)` there — verified against the actual `ui-android:1.12.0` sources,
 * the version this module builds against). That observer is scoped to the layer/coordinator,
 * entirely separate from the composition's own snapshot observer: a change to [progress]
 * re-runs only that layer's parameter update (recomputing the outline and redrawing), the
 * same phase-deferred path `Modifier.graphicsLayer { }` reads are documented to use, since
 * `Modifier.clip(shape)` is defined as `graphicsLayer(shape = shape, clip = true)`
 * (`androidx.compose.ui.draw.Clip.kt`). No `@Composable` function re-executes when progress
 * changes because of this shape — this class holds no snapshot-state read anywhere outside
 * [createOutline].
 */
class EnvelopeRevealShape(private val progress: State<Float>) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val revealedHeight = size.height * progress.value.coerceIn(0f, 1f)
        return Outline.Rectangle(
            Rect(
                left = 0f,
                top = size.height - revealedHeight,
                right = size.width,
                bottom = size.height,
            ),
        )
    }
}

/**
 * Title/artist + 3-dot menu — mirrors PlayerSkeletonExpandedContent.kt's inner Row exactly
 * (same padding, same text styles, same marquee-on-both). [MoreOptionsButton] is reused as-is
 * (it's `internal`, same module) rather than duplicated: it already reads
 * [com.v1per.synthx.ui.components.LocalSongActions], which is live here via
 * [com.v1per.synthx.ui.components.SongActionsHost] wrapping BottomChromeOverlay in
 * MainScaffold, so this needs no new host or wiring.
 */
@Composable
fun PlayerV2TitleRow(song: Song) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Start is [ExpandedContentHorizontalInset] (32dp) so the title's left edge lines
            // up with the album art's — the old pair of stacked paddings
            // (Spacing.HorizontalPadding + Spacing.lg) put it at 40dp against art at 32dp, an
            // 8dp disagreement that was invisible only because there was no art on screen yet.
            // End is unchanged at 28dp (Spacing.HorizontalPadding + 12dp): it trails
            // MoreOptionsButton, not the art, so it has no reason to match.
            .padding(start = ExpandedContentHorizontalInset, end = Spacing.HorizontalPadding + 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
        }
        MoreOptionsButton(song = song, enabled = true)
    }
}

/**
 * Seek bar + transport + queue button — mirrors PlayerSkeletonControlsOverlay.kt's inner
 * content (sizes, icons, [Alpha.Disabled] dimming) with fixed [Spacing] gaps standing in for
 * that file's `weight(1f)` spacers, which only made sense inside its fixed-height,
 * progress-driven outer Column (not present here — see [PlayerV2ExpandedContent]'s doc).
 */
@Composable
fun PlayerV2ControlsBlock(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPauseClick: () -> Unit,
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.HorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlayerSeekBar(
            currentPosition = currentPosition,
            duration = duration,
            onSeek = onSeek,
            enabled = true,
        )
        Spacer(modifier = Modifier.height(Spacing.lg))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(2f))
            DragResistantIconButton(
                onClick = onPreviousClick,
                size = 72.dp,
                enabled = hasPrevious,
            ) {
                Icon(
                    imageVector = Icons.Filled.FastRewind,
                    contentDescription = stringResource(R.string.cd_previous_track),
                    tint = TextPrimary,
                    modifier = Modifier
                        .size(56.dp)
                        .alpha(if (hasPrevious) 1f else Alpha.Disabled),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            DragResistantIconButton(
                onClick = onPlayPauseClick,
                size = 96.dp,
            ) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = TextPrimary,
                        strokeWidth = 3.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) {
                            stringResource(R.string.cd_pause)
                        } else {
                            stringResource(R.string.cd_play)
                        },
                        tint = if (isPlaying) Accent else TextPrimary,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            DragResistantIconButton(
                onClick = onNextClick,
                size = 72.dp,
                enabled = hasNext,
            ) {
                Icon(
                    imageVector = Icons.Filled.FastForward,
                    contentDescription = stringResource(R.string.cd_next_track),
                    tint = TextPrimary,
                    modifier = Modifier
                        .size(56.dp)
                        .alpha(if (hasNext) 1f else Alpha.Disabled),
                )
            }
            Spacer(modifier = Modifier.weight(2f))
        }
        Spacer(modifier = Modifier.height(Spacing.xl))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            // No-op — queue mode doesn't exist until Stage 4. See this composable's own doc.
            DragResistantIconButton(onClick = {}) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = stringResource(R.string.cd_queue),
                    tint = TextSecondary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.md))
    }
}

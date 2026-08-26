package com.v1per.synthx.ui.player.v2

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.components.CollapsedPlayerHeight
import com.v1per.synthx.ui.components.DragResistantIconButton
import com.v1per.synthx.ui.components.PlayerAlbumArt
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Stage 2.1 built real mini-bar content — art, title/artist, play/pause — statically, with no
 * shared-element art morph yet (§3, still a later stage).
 *
 * This stage adds the fade-as-you-drag design doc §4 calls for: the title/artist/play-pause
 * portion fades out as the sheet grows past mini height, using
 * [PlayerSheetSkeleton.kt:210][com.v1per.synthx.ui.player.PlayerSheetSkeleton]'s exact proven
 * curve (`1f - progress * 3f`, coerced 0..1 — fully visible at progress 0, fully gone by
 * progress ≈ 0.333) rather than inventing a new one. Deliberately *not* symmetric with
 * [PlayerV2ExpandedContent]'s reveal, which starts opening from `progress > 0f` — the two
 * overlapping for roughly the first third of the drag is expected, not a bug: design doc §4
 * calls mini content "disposable, not part of the envelope content," with no reveal treatment
 * of its own.
 *
 * The album art is deliberately outside the fade: it is pinned at the sheet's top edge and
 * stays fully visible for the whole drag, exactly as before this stage. That's why the fade
 * modifier lands on a new wrapper `Row` around just the title/artist [Column] and the
 * play/pause button, rather than on this composable's own root `Row` the way the old
 * (detached) PlayerSkeletonMiniContent applies its `.alpha(miniContentAlpha)` — that file's
 * root `Row` never actually contained its real art (a `Spacer` there only reserves the art's
 * footprint; the real art is a separately-positioned overlay drawn elsewhere,
 * PlayerSheetSkeleton.kt:392-399), so a whole-`Row` alpha never touched it. `PlayerAlbumArt`
 * here *is* a direct child of the root `Row`, so fading the whole thing would fade the art too
 * — hence the narrower wrapper.
 *
 * **Migration Step 6 — the receiver, and what did *not* change.** This composable was a
 * [BoxScope] extension solely so its root `Row` could call `.align(Alignment.TopCenter)`. It is
 * now called from inside a `Transition.AnimatedVisibility(visible = { it == Collapsed })`, whose
 * content lambda is an `AnimatedVisibilityScope`, so that `align` moved up onto the
 * `AnimatedVisibility` node and the receiver went away with it. Deliberately *not* replaced by an
 * `AnimatedVisibilityScope` receiver, for the reason Step 5 established on
 * [PlayerV2ExpandedContent]: that interface carries no `@Stable`, so taking it as a parameter
 * would make this composable non-skippable. Stage 2.4b will need the scope for the art's
 * `Modifier.sharedElement` and can take it at that point as a narrower, explicitly-typed
 * parameter on the art alone.
 *
 * Neither of this composable's two progress-driven behaviors is affected by that wrapping. The
 * `graphicsLayer` alpha below is unchanged — the plan is explicit that Step 6 keeps the
 * `1f - progress * 3f` curve rather than substituting a real `fadeOut`, because the
 * `AnimatedVisibility` exit governs *composition lifetime* while this governs *opacity*, and
 * they are answering different questions. `miniContentEnabled` is likewise untouched: it is a
 * `derivedStateOf` over the very same `progress` `State<Float>` reference the host passes in,
 * with no dependency on the transition's visibility machinery, so the play/pause hit-test still
 * flips at exactly progress ≈ 0.1667 in both directions. The only way `AnimatedVisibility` could
 * have moved that threshold is by disposing this subtree while the gate still mattered, and it
 * disposes only once `snapTo(Expanded)` commits — five times past the crossing, with the button
 * long since disabled and at alpha 0.
 *
 * @param progress the same `expansionState` reference [PlayerV2Host] hands to
 *   [PlayerV2ExpandedContent] — not the unwrapped value — so the fade alpha below is read
 *   inside [androidx.compose.ui.graphics.graphicsLayer]'s lambda at draw time, the same
 *   phase-deferred, zero-recomposition-cost pattern [EnvelopeRevealShape] already establishes
 *   in this file. Reading `progress.value` directly in this composable's body instead (e.g. to
 *   share one local val between the alpha and the enabled check below) would subscribe this
 *   whole composable to a 60Hz recomposition for the entire drag, not just the ~33% of it where
 *   the fade is actually animating — a cost this composable doesn't currently pay and shouldn't
 *   newly acquire. See the `miniContentEnabled` local below for why `enabled` can't avoid a
 *   composition-phase read the same way, and how that cost is bounded instead.
 */
@Composable
fun PlayerV2MiniContent(
    song: Song,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPauseClick: () -> Unit,
    progress: State<Float>,
) {
    // `DragResistantIconButton`'s `enabled` is a plain Boolean composable parameter, not a
    // draw-phase-readable modifier — unlike the graphicsLayer alpha below, there is no way to
    // gate it without a composition-phase read of `progress.value` somewhere. Wrapped in
    // derivedStateOf (mirroring this file's own `isContentPresent` pattern above) rather than
    // a bare `val`, so this composable only recomposes when the *boolean*
    // actually flips — twice per drag, at the progress ≈ 0.1667 crossing — instead of on every
    // one of the ~60 progress ticks a full drag produces. A bare `val miniContentEnabled =
    // (1f - progress.value * 3f).coerceIn(0f, 1f) > 0.5f` would read progress.value in the
    // composition body directly, subscribing this composable to every progress change for the
    // *entire* drag (Compose can't know the post-coerce, post-comparison result is unchanged
    // without derivedStateOf's own value-equality check) — exactly the cost this stage
    // otherwise avoids by keeping the alpha itself draw-phase-only.
    val miniContentEnabled by remember(progress) {
        derivedStateOf { (1f - progress.value * 3f).coerceIn(0f, 1f) > 0.5f }
    }

    Row(
        modifier = Modifier
            // An `.align(...)` used to head this chain (TopCenter through Step 6, then
            // BottomCenter + a fixed offset as of Stage 2.4b-2). It lives on the
            // AnimatedVisibility node at the call site, not here — see this composable's
            // doc, and that call site's own comment for why the move is position-neutral.
            .fillMaxWidth()
            .height(CollapsedPlayerHeight)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerAlbumArt(
            song = song,
            isPlaying = isPlaying,
            size = 44.dp,
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        // Wraps exactly the title/artist + play/pause portion — see this composable's doc for
        // why the fade can't land on the root Row the way the old player's does. weight(1f)
        // moves here from the inner Column (below) so this wrapper still claims the same
        // leftover horizontal space in the outer Row that the Column alone used to; the
        // Column's own weight(1f) now applies within this wrapper instead, so the button's
        // size and position, and the Column's width, are unchanged from before this stage.
        Row(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer {
                    // Same curve as `miniContentEnabled` above, deliberately inlined again
                    // rather than sharing a local val — see this composable's `progress` param
                    // doc. This read happens once per draw/layer update, not once per
                    // recomposition, and costs nothing extra by being duplicated.
                    alpha = (1f - progress.value * 3f).coerceIn(0f, 1f)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DragResistantIconButton(onClick = onPlayPauseClick, enabled = miniContentEnabled) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = TextSecondary,
                        strokeWidth = 2.dp,
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
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

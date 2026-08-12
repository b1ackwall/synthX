package com.v1per.synthx.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Surface
import com.v1per.synthx.ui.theme.TextPrimary
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Seek bar, transport controls, and the queue-toggle icon row — the whole bottom overlay,
 * identical in Player mode and Queue mode by construction.
 *
 * [controlsVisible] fades the **entire** overlay, queue button included (Player mode passes
 * true always; Queue mode passes it based on scroll position). Once the queue is scrolled and
 * the overlay is gone, returning to Player mode means swiping the queue back down to reveal
 * the overlay and then tapping the queue button — the same round trip Apple Music makes you
 * do, and the reason the button is inside the fade rather than pinned outside it.
 *
 * [modifier] carries the fixed height from the call site. Everything below negotiates space
 * inside that fixed box only — the `weight(1f)` here is the ~44dp remainder of
 * ControlsOverlayHeight after its fixed children, and cannot be influenced by the mode
 * content on the layer beneath.
 */
@Composable
internal fun PlayerControlsSection(
    controlsVisible: Boolean,
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    enabled: Boolean,
    showQueue: Boolean,
    onQueueClick: () -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    // Fast, not Normal: this is scroll-driven chrome show/hide, not a content reveal —
    // it should track the queue-scroll gesture snappily rather than visibly lag behind it.
    AnimatedVisibility(
        visible = controlsVisible,
        enter = fadeIn(animationSpec = tween(Motion.DurationFast, easing = Motion.EasingStandard)),
        exit = fadeOut(animationSpec = tween(Motion.DurationFast, easing = Motion.EasingStandard)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Surface)
                // Opaque background alone doesn't stop touches from reaching the queue rows
                // beneath — Compose hit-tests every overlapping pointer input handler at a
                // position, not just the topmost drawn one. This no-op clickable (no ripple,
                // no callback) consumes the down event so it never propagates to QueueRow.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(Spacing.md))
            PlayerSeekBar(currentPosition = currentPosition, duration = duration, onSeek = onSeek)
            Spacer(modifier = Modifier.height(Spacing.xl))
            PlayerTransportControls(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                onPlayPauseClick = onPlayPauseClick,
                onPreviousClick = onPreviousClick,
                onNextClick = onNextClick,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                enabled = enabled,
            )

            // The overlay's only flexible gap — the remainder of ControlsOverlayHeight.
            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(
                    onClick = onQueueClick,
                    enabled = enabled,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = stringResource(R.string.cd_queue),
                        tint = if (showQueue) TextPrimary else TextSecondary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            // Fixed bottom edge + the gesture-nav clearance folded in from the call site.
            Spacer(modifier = Modifier.height(Spacing.md + bottomInset))
        }
    }
}

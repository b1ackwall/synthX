package com.v1per.synthx.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Hairline

/** Fixed estimate of QueueHeader + QueuePlaybackModeRow's combined height. */
private val ControlsAreaHeight: Dp = 132.dp

enum class QueueSheetValue { ControlsVisible, ListFullScreen }

/**
 * Up Next / Queue overlay. Its own [AnchoredDraggableState], scoped entirely to this
 * composable and completely independent of [PlayerSheet]'s — the two must never share or
 * couple state, per the recent PlayerSheet refactor's own single-progress-float pattern,
 * which this mirrors rather than reuses.
 *
 * The drag surface is a dedicated grip strip above the controls block, not the whole
 * screen: the queue list below it is a scrollable, reorderable [LazyColumn], and layering
 * a screen-wide vertical drag over that would fight both the list's own scroll and the
 * Reorderable drag-handle's pointer input. A grip strip is the same idiom bottom sheets
 * already use, and it stays clear of both.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueScreen(
    playbackViewModel: PlaybackViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentSong by playbackViewModel.currentSong.collectAsState()
    val song = currentSong ?: return

    val upcomingQueue by playbackViewModel.upcomingQueue.collectAsState()
    val isPlaying by playbackViewModel.isPlaying.collectAsState()
    val shuffleEnabled by playbackViewModel.shuffleEnabled.collectAsState()
    val repeatMode by playbackViewModel.repeatMode.collectAsState()

    BackHandler(onBack = onClose)

    val density = LocalDensity.current
    val decayAnimationSpec = remember { exponentialDecay<Float>(frictionMultiplier = 1.8f) }
    val controlsHeightPx = with(density) { ControlsAreaHeight.toPx() }

    val draggableState = remember {
        AnchoredDraggableState(
            initialValue = QueueSheetValue.ControlsVisible,
            positionalThreshold = { totalDistance -> totalDistance * 0.4f },
            velocityThreshold = { with(density) { 200.dp.toPx() } },
            snapAnimationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            decayAnimationSpec = decayAnimationSpec,
        )
    }

    LaunchedEffect(controlsHeightPx) {
        // newTarget is explicit rather than left to updateAnchors' default: the sheet must
        // always rest at Peek (controls visible) the moment it opens, never Full — spelling
        // the target out here removes any dependency on AnchoredDraggableState's own
        // (correct, but implicit) default-target resolution for this first call.
        draggableState.updateAnchors(
            newAnchors = DraggableAnchors {
                QueueSheetValue.ControlsVisible at 0f
                QueueSheetValue.ListFullScreen at -controlsHeightPx
            },
            newTarget = QueueSheetValue.ControlsVisible,
        )
    }

    // 0f = controls fully shown, 1f = collapsed away and the list owns the screen.
    val progress by remember {
        derivedStateOf {
            val offset = draggableState.offset
            if (offset.isNaN()) 0f else (offset / -controlsHeightPx).coerceIn(0f, 1f)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(Background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Grip — the one part of the screen that always stays draggable, regardless
            // of how far the controls block has collapsed.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .anchoredDraggable(draggableState, Orientation.Vertical),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Hairline),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(lerp(ControlsAreaHeight, 0.dp, progress))
                    .graphicsLayer { alpha = (1f - progress * 1.5f).coerceIn(0f, 1f) }
                    .clipToBounds(),
            ) {
                QueueHeader(song = song, isPlaying = isPlaying, onClose = onClose)
                QueuePlaybackModeRow(
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    onShuffleClick = { playbackViewModel.setShuffleEnabled(!shuffleEnabled) },
                    onRepeatClick = { playbackViewModel.setRepeatMode(repeatMode.next()) },
                )
            }

            QueueList(
                queue = upcomingQueue,
                shuffleEnabled = shuffleEnabled,
                onSongClick = { index -> playbackViewModel.playQueueItem(index) },
                onMove = playbackViewModel::moveQueueItem,
                onRemove = playbackViewModel::removeQueueItem,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

package com.v1per.synthx.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.unit.dp
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.RepeatMode

/** [PlayerSkeletonQueueContent] fully in queue mode on a 640dp sheet, for tests of Up Next's own content. */
@Composable
internal fun QueueContentTestHost(current: Song, upcomingQueue: List<QueueSlot>, repeatMode: RepeatMode) {
    Box(Modifier.fillMaxWidth().height(640.dp)) {
        PlayerSkeletonQueueContent(
            song = current,
            artOffsetYQueue = 0.dp,
            queueListOffsetY = 180.dp,
            headerRegionHeight = 180.dp,
            queueListState = rememberLazyListState(),
            queueScrollConnection = object : NestedScrollConnection {},
            expandedContentAlpha = 1f,
            queueProgressValue = 1f,
            expandedContentEnabled = true,
            queueModeArtSize = 48.dp,
            upcomingQueue = upcomingQueue,
            shuffleEnabled = false,
            repeatMode = repeatMode,
            onShuffleClick = {},
            onRepeatClick = {},
            onQueueSongClick = {},
            onQueueMove = { _, _ -> },
            onQueueRemove = {},
            controlsOverlayHeight = 300.dp,
            queueFadeAlpha = 1f,
            controlsOverlayAlpha = 1f,
            overlayVisibleAlpha = 1f,
            controlsOverlaySlide = 0.dp,
            bottomInset = 0.dp,
            fullHeight = 640.dp,
        )
    }
}

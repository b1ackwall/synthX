package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import kotlin.math.abs

/** The app's snackbar host: Material's SnackbarHost showing every snackbar as a [SynthXSnackbar]. */
@Composable
fun SynthXSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data -> SynthXSnackbar(data) }
}

/**
 * Shows [message] on this host and suspends until it is gone, like [SnackbarHostState.showSnackbar], under the
 * app's replace rule: a snackbar showing without an action is dismissed at once, so the new message takes its
 * place, while one showing with an action (such as Retry) is never replaced and the new message queues behind
 * it in order. The replaced caller's showSnackbar returns [SnackbarResult.Dismissed].
 *
 * Every producer calls this instead of showSnackbar (SharedControlUsageTest enforces it), so the rule holds on
 * every host. The rule is applied when the message arrives: a message already queued behind an action snackbar
 * still shows in turn once that one is gone.
 */
suspend fun SnackbarHostState.showSynthXSnackbar(
    message: String,
    actionLabel: String? = null,
    duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
): SnackbarResult {
    currentSnackbarData?.takeIf { it.visuals.actionLabel == null }?.dismiss()
    return showSnackbar(message = message, actionLabel = actionLabel, duration = duration)
}

/**
 * One snackbar: a light TextPrimary box with a Background message, the Primary button's colours, with 2dp
 * corners and kept 12dp from the host's edges as Material's default snackbar is. The optional action is a
 * Dialog-width text button drawn dark for the light box, its label underlined; a tap on it reports
 * [SnackbarData.performAction], which is what a caller waiting on showSnackbar receives as ActionPerformed.
 *
 * A horizontal swipe in either direction past the default threshold dismisses it ([SnackbarData.dismiss],
 * which the caller receives as Dismissed); the box fades as it moves, so none of it is left at the screen edge
 * while the host fades it out. The host gives each snackbar its own swipe state (it composes each one in its
 * own key) and the accessibility Dismiss action. The 12dp margin is outside the swipe area and passes touches
 * through; the box blocks touches inside its bounds only.
 */
@Composable
fun SynthXSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.small
    val actionLabel = data.visuals.actionLabel
    val swipeState = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {},
        modifier = modifier.padding(Spacing.ItemSpacing),
        onDismiss = { data.dismiss() },
    ) {
        Snackbar(
            modifier = Modifier.graphicsLayer { alpha = 1f - swipeState.swipedFraction(size.width) },
            action = actionLabel?.let { label ->
                {
                    SynthXTextButton(
                        onClick = { data.performAction() },
                        width = SynthXButtonWidth.Dialog,
                        onLightSurface = true,
                    ) {
                        Text(text = label, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)
                    }
                }
            },
            shape = shape,
            containerColor = TextPrimary,
            contentColor = Background,
            actionContentColor = Background,
        ) {
            Text(text = data.visuals.message)
        }
    }
}

/** How far the snackbar has been swiped, as a fraction of its [width]: 0 at rest, 1 fully off its place. */
private fun SwipeToDismissBoxState.swipedFraction(width: Float): Float {
    // requireOffset throws until the first layout has set the anchors.
    val offset = runCatching { requireOffset() }.getOrDefault(0f)
    return if (width > 0f) (abs(offset) / width).coerceIn(0f, 1f) else 0f
}

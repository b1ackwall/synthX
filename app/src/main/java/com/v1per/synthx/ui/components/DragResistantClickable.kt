package com.v1per.synthx.ui.components

import androidx.compose.foundation.Indication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Drop-in replacement for [Modifier.clickable] for tap targets nested inside an
 * ancestor drag detector (e.g. [androidx.compose.foundation.gestures.anchoredDraggable]
 * on PlayerSheet's sheet Box).
 *
 * Stock `clickable` never consumes small position deltas during a press — by design,
 * so it doesn't block a scrolling ancestor — which lets an ancestor drag detector's own
 * independent touch-slop check claim the gesture and silently cancel the child's tap.
 * A `pointerInput` placed *alongside* stock `clickable` can't fix this: `isConsumed` is
 * a single flag shared by every listener on the same [androidx.compose.ui.input.pointer.PointerInputChange],
 * so consumption meant to shield the ancestor is just as invisible to clickable's own
 * cancel-on-consumed check, breaking the tap too. This reimplements press/tap detection
 * from scratch instead: net displacement is tracked locally rather than via `isConsumed`,
 * so consuming here to shield the ancestor has no effect on this modifier's own
 * tap-vs-cancel decision.
 */
fun Modifier.dragResistantClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = Role.Button,
    indication: Indication = ripple(),
    onClick: () -> Unit,
): Modifier = this
    .indication(interactionSource, indication)
    .semantics(mergeDescendants = true) {
        if (role != null) this.role = role
        if (!enabled) disabled()
        onClick(label = onClickLabel) { onClick(); true }
    }
    .pointerInput(interactionSource, enabled, onClick) {
        if (!enabled) return@pointerInput
        val slop = viewConfiguration.touchSlop
        // interactionSource.emit is a suspend call on MutableInteractionSource, but
        // awaitEachGesture's block runs in the @RestrictsSuspension AwaitPointerEventScope,
        // which can only invoke suspend functions defined on itself. Emitting from a
        // `launch` inside an enclosing coroutineScope (as Compose's own clickable/
        // toggleable do internally) sidesteps that restriction without blocking the
        // gesture-await loop on the emit's completion.
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val press = PressInteraction.Press(down.position)
                launch { interactionSource.emit(press) }

                var clicked = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUpIgnoreConsumed()) {
                        change.consume()
                        clicked = true
                        break
                    }
                    val netDistance = (change.position - down.position).getDistance()
                    if (netDistance <= slop) {
                        // Still tap-shaped: shield the ancestor's drag detector from this
                        // movement so a stationary press never gets reinterpreted as a
                        // drag start.
                        change.consume()
                    } else {
                        // Genuine drag-sized movement — stop shielding and let it flow up
                        // un-consumed so the ancestor's own slop check picks up the
                        // gesture from here, exactly as it would today.
                        break
                    }
                }

                if (clicked) {
                    launch { interactionSource.emit(PressInteraction.Release(press)) }
                    onClick()
                } else {
                    launch { interactionSource.emit(PressInteraction.Cancel(press)) }
                }
            }
        }
    }

/**
 * Drop-in replacement for Material3's [androidx.compose.material3.IconButton] built on
 * [dragResistantClickable] instead of `clickable`, for icon buttons nested inside an
 * ancestor drag detector. [size] defaults to IconButton's own default 40dp circular
 * touch target; callers with a larger custom touch target (e.g. PlayerTransportControls'
 * 72dp/96dp buttons) pass it explicitly rather than layering a conflicting
 * `Modifier.size(...)` on top.
 */
@Composable
fun DragResistantIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .dragResistantClickable(
                interactionSource = interactionSource,
                enabled = enabled,
                role = Role.Button,
                indication = ripple(bounded = false),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

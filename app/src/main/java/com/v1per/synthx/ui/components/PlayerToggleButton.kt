package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import com.v1per.synthx.ui.player.LocalPlayerContentColors
import com.v1per.synthx.ui.player.PlayerContentColors
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.ButtonSizes
import com.v1per.synthx.ui.theme.IconSizes

/**
 * An on/off control of the player sheet (shuffle, repeat, the queue button): a [ButtonSizes.PlayerToggle]
 * square with MaterialTheme.shapes.small corners. On, the square is filled in the player's content color and
 * [icon] is drawn in [PlayerContentColors.onContent]; off, there is no fill and [icon] is drawn in the content
 * color at full strength. The state changes instantly, without animation, and the ripple stays inside the
 * square in the icon's color.
 *
 * Without [stateDescription] the toggle is a switch to accessibility services (Role.Switch, on or off from
 * [checked]). With one it is a button that states it, as Repeat's Off, All or One does.
 *
 * [available] is false only when the action cannot be used at all; the whole toggle then draws at the
 * disabled alpha. [interactive] gates the click alone and never changes the drawing, for the player's fades
 * and drags, which turn controls off and on mid-animation. Taps go through [dragResistantClickable], so the
 * sheet's drag detector cannot steal them.
 */
@Composable
internal fun PlayerToggleButton(
    checked: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    stateDescription: String? = null,
    iconSize: Dp = IconSizes.Standard,
    available: Boolean = true,
    interactive: Boolean = true,
) {
    val colors = LocalPlayerContentColors.current ?: PlayerContentColors.Light
    val iconColor = if (checked) colors.onContent else colors.content
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(ButtonSizes.PlayerToggle)
            .alpha(if (available) 1f else Alpha.Disabled)
            .clip(MaterialTheme.shapes.small)
            .background(if (checked) colors.content else Color.Transparent)
            .dragResistantClickable(
                interactionSource = interactionSource,
                enabled = available && interactive,
                role = if (stateDescription == null) Role.Switch else Role.Button,
                indication = ripple(color = iconColor),
                onClick = onClick,
            )
            .semantics {
                if (stateDescription == null) {
                    toggleableState = ToggleableState(checked)
                } else {
                    this.stateDescription = stateDescription
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

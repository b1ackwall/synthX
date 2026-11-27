package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.ButtonBorder
import com.v1per.synthx.ui.theme.ButtonSizes
import com.v1per.synthx.ui.theme.Error
import com.v1per.synthx.ui.theme.Strokes
import com.v1per.synthx.ui.theme.SurfaceVariant
import com.v1per.synthx.ui.theme.TextPrimary

/**
 * The button roles. Each has fixed colours in [synthXButtonColors]; none of them uses Accent.
 * [TertiaryOnLight] is the Tertiary role drawn on a TextPrimary surface (the snackbar), so its label is dark.
 */
enum class SynthXButtonRole { Primary, Secondary, Tertiary, TertiaryOnLight, Destructive }

/**
 * The colours of one button role. [border] is null for a role drawn without one. While a button is
 * disabled, the whole button (container, label and border) is drawn at [disabledAlpha].
 */
@Immutable
data class SynthXButtonColors(val container: Color, val content: Color, val border: Color?, val disabledAlpha: Float)

/** The colours of [role]. Every colour is set here, so none falls back to the theme's Accent primary. */
fun synthXButtonColors(role: SynthXButtonRole): SynthXButtonColors = when (role) {
    SynthXButtonRole.Primary -> SynthXButtonColors(
        container = TextPrimary,
        content = Background,
        border = null,
        disabledAlpha = Alpha.Disabled,
    )

    SynthXButtonRole.Secondary -> SynthXButtonColors(
        container = SurfaceVariant,
        content = TextPrimary,
        border = ButtonBorder,
        disabledAlpha = Alpha.Disabled,
    )

    SynthXButtonRole.Tertiary -> SynthXButtonColors(
        container = Color.Transparent,
        content = TextPrimary,
        border = null,
        disabledAlpha = Alpha.Disabled,
    )

    SynthXButtonRole.TertiaryOnLight -> SynthXButtonColors(
        container = Color.Transparent,
        content = Background,
        border = null,
        disabledAlpha = Alpha.Disabled,
    )

    SynthXButtonRole.Destructive -> SynthXButtonColors(
        container = Error,
        content = Background,
        border = null,
        disabledAlpha = Alpha.Disabled,
    )
}

/**
 * The emphasised action: a TextPrimary container with a Background label.
 *
 * [width] sets the size (see [SynthXButtonWidth]). [content] is the label, usually one Text; it
 * reads the label colour and labelLarge from the button, and wrapped lines are centred.
 */
@Composable
fun SynthXPrimaryButton(
    onClick: () -> Unit,
    width: SynthXButtonWidth,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    SynthXButton(SynthXButtonRole.Primary, onClick, width, enabled, modifier, content)
}

/**
 * The supporting action: a SurfaceVariant container, a TextPrimary label and a 1dp ButtonBorder
 * outline. Sizing and [content] work as in [SynthXPrimaryButton].
 */
@Composable
fun SynthXSecondaryButton(
    onClick: () -> Unit,
    width: SynthXButtonWidth,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    SynthXButton(SynthXButtonRole.Secondary, onClick, width, enabled, modifier, content)
}

/**
 * The Tertiary role: a TextPrimary label with no container and no border. Sizing and [content]
 * work as in [SynthXPrimaryButton]. [onLightSurface] draws it in [SynthXButtonRole.TertiaryOnLight]
 * instead: a Background label and ripple, for a button that sits on a TextPrimary surface.
 */
@Composable
fun SynthXTextButton(
    onClick: () -> Unit,
    width: SynthXButtonWidth,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLightSurface: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val role = if (onLightSurface) SynthXButtonRole.TertiaryOnLight else SynthXButtonRole.Tertiary
    SynthXButton(role, onClick, width, enabled, modifier, content)
}

/**
 * An irreversible action: an Error container with a Background label. Sizing and [content] work
 * as in [SynthXPrimaryButton].
 */
@Composable
fun SynthXDestructiveButton(
    onClick: () -> Unit,
    width: SynthXButtonWidth,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    SynthXButton(SynthXButtonRole.Destructive, onClick, width, enabled, modifier, content)
}

/**
 * The shared body of every role. Corners come from MaterialTheme.shapes.small, the ripple is the
 * role's content colour, and a 48dp tap area is kept even when a Dialog button is drawn 40dp tall.
 * The width token is also attached as the layoutId that [SynthXButtonPair] reads.
 */
@Composable
private fun SynthXButton(
    role: SynthXButtonRole,
    onClick: () -> Unit,
    width: SynthXButtonWidth,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = synthXButtonColors(role)
    val shape = MaterialTheme.shapes.small
    val labelStyle = MaterialTheme.typography.labelLarge.copy(textAlign = TextAlign.Center)
    val interactionSource = remember { MutableInteractionSource() }
    CompositionLocalProvider(LocalContentColor provides colors.content, LocalTextStyle provides labelStyle) {
        Row(
            modifier = modifier
                .layoutId(width)
                .minimumInteractiveComponentSize()
                .buttonSize(width)
                .alpha(if (enabled) 1f else colors.disabledAlpha)
                .clip(shape)
                .background(colors.container)
                .buttonBorder(colors.border, shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = colors.content),
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = ButtonSizes.HorizontalPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

private fun Modifier.buttonBorder(color: Color?, shape: Shape): Modifier =
    if (color == null) this else this.border(Strokes.ButtonBorder, color, shape)

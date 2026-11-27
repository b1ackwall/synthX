package com.v1per.synthx.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.min
import com.v1per.synthx.R
import com.v1per.synthx.ui.components.SynthXButtonPair
import com.v1per.synthx.ui.components.SynthXButtonWidth
import com.v1per.synthx.ui.components.SynthXPrimaryButton
import com.v1per.synthx.ui.components.SynthXSecondaryButton
import com.v1per.synthx.ui.components.Wordmark
import com.v1per.synthx.ui.player.playerGrain
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.Alpha
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.OnboardingSizes
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Strokes
import com.v1per.synthx.ui.theme.TextSecondary

/** The welcome wordmark's width on a screen [availableWidth] wide. */
internal fun onboardingWordmarkWidth(availableWidth: Dp): Dp =
    min(availableWidth * OnboardingSizes.WordmarkWidthFraction, OnboardingSizes.WordmarkMaxWidth)

/**
 * The wizard's first screen: the wordmark over a static Accent glow, the subtitle, a hairline and
 * the two entry buttons, centred, scrolling when the window is short or the font is large. The
 * wordmark carries the welcome title as the screen's heading.
 *
 * No BackHandler here, unlike the steps after it: wizard position is persisted, and nothing has been
 * started yet at Choice, so a back-press that leaves the app loses nothing and relaunching lands
 * right back on this screen.
 */
@Composable
internal fun ChoiceStep(onFreshInstall: () -> Unit, onRecoverFromBackup: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Background)) {
        val wordmarkWidth = onboardingWordmarkWidth(maxWidth)
        // The player's still grain under everything, which also breaks up banding in the glow.
        Spacer(modifier = Modifier.matchParentSize().playerGrain())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.HorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = OnboardingSizes.ContentMaxWidth).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                WelcomeWordmark(width = wordmarkWidth)
                Spacer(modifier = Modifier.height(Spacing.lg))
                Text(
                    text = stringResource(R.string.onboarding_welcome_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(Spacing.xl))
                HorizontalDivider(thickness = Strokes.Hairline, color = Hairline)
                Spacer(modifier = Modifier.height(Spacing.xl))
            }

            // Outside the width cap: the locked pair decides stacking from the full width (side by side from 600dp).
            SynthXButtonPair(
                primary = {
                    SynthXPrimaryButton(onClick = onFreshInstall, width = SynthXButtonWidth.Wide) {
                        Text(text = stringResource(R.string.onboarding_fresh_install))
                    }
                },
                secondary = {
                    SynthXSecondaryButton(onClick = onRecoverFromBackup, width = SynthXButtonWidth.Wide) {
                        Text(text = stringResource(R.string.onboarding_recover_backup))
                    }
                },
            )
        }
    }
}

/**
 * The wordmark over a radial Accent glow that fades to transparent at its edge, drawn with a plain
 * brush behind it (no layer, no blur). Read out as the welcome title, a heading, instead of the
 * wordmark's own "synthX".
 */
@Composable
private fun WelcomeWordmark(width: Dp) {
    val welcome = stringResource(R.string.onboarding_welcome_title)
    Box(
        modifier = Modifier
            .drawBehind {
                val radius = width.toPx() * OnboardingSizes.GlowRadiusPerWordmarkWidth
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Accent.copy(alpha = Alpha.OnboardingGlow), Accent.copy(alpha = 0f)),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
            .clearAndSetSemantics {
                heading()
                text = AnnotatedString(welcome)
            },
        contentAlignment = Alignment.Center,
    ) {
        Wordmark(width = width)
    }
}

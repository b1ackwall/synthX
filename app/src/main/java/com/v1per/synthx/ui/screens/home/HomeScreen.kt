package com.v1per.synthx.ui.screens.home

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.ui.components.AnimatedGradientBackground
import com.v1per.synthx.ui.components.rememberWordmark
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.TextPrimary
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    onEnterLibrary: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    // Both start at 0f: the entry LaunchedEffect fades them up, so the first frame
    // doesn't flash them at full strength before the fade-in begins.
    var gradientAlpha by remember { mutableFloatStateOf(0f) }
    var arrowAlpha by remember { mutableFloatStateOf(0f) }
    var isNavigating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Tap sequence: navigate immediately and fade the gradient and arrow out in
    // parallel — the wordmark morph and the fade run together as one motion.
    fun onArrowClick() {
        if (isNavigating) return // prevent double tap
        isNavigating = true
        scope.launch {
            val spec = tween<Float>(
                durationMillis = Motion.DurationNormal,
                easing = Motion.EasingAccelerate,
            )
            // Navigate and fade simultaneously
            onEnterLibrary()
            val gradientAnim = Animatable(gradientAlpha)
            val arrowAnim = Animatable(arrowAlpha)
            launch { gradientAnim.animateTo(0f, spec) { gradientAlpha = value } }
            arrowAnim.animateTo(0f, spec) { arrowAlpha = value }
        }
    }

    // On (re-)entering Home the gradient and arrow fade back in, coordinated with the
    // screen's popEnterTransition fadeIn — skipped while a fade-out is in flight.
    LaunchedEffect(Unit) {
        if (!isNavigating) {
            arrowAlpha = 0f
            gradientAlpha = 0f
            val spec = tween<Float>(
                durationMillis = Motion.DurationNormal,
                easing = Motion.EasingDecelerate,
            )
            val gradAnim = Animatable(0f)
            val arrowAnim = Animatable(0f)
            launch { gradAnim.animateTo(1f, spec) { gradientAlpha = value } }
            arrowAnim.animateTo(1f, spec) { arrowAlpha = value }
        }
    }

    // MainScaffold's innerPadding already confines this screen to the safe area between
    // the system bars; the gradient clips its blur to those bounds itself.
    AnimatedGradientBackground(
        gradientAlpha = gradientAlpha,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val wordmark = rememberWordmark()

                with(sharedTransitionScope) {
                    Text(
                        text = wordmark,
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "wordmark"),
                            animatedVisibilityScope = animatedVisibilityScope,
                            renderInOverlayDuringTransition = false,
                            boundsTransform = { _, _ ->
                                tween(
                                    durationMillis = Motion.DurationNormal,
                                    easing = Motion.EasingAccelerate,
                                )
                            },
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.xl))

                IconButton(
                    onClick = { onArrowClick() },
                    modifier = Modifier
                        .size(48.dp)
                        .alpha(arrowAlpha),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.cd_enter_library),
                        tint = TextPrimary.copy(alpha = 0.6f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}

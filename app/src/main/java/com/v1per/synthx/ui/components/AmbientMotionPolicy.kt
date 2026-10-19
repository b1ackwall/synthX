package com.v1per.synthx.ui.components

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val MotionPolicyPollMillis = 1_000L

internal fun shouldReduceAmbientMotion(powerSaveMode: Boolean, animatorDurationScale: Float): Boolean =
    powerSaveMode || animatorDurationScale == 0f

internal fun shouldPauseAmbientMotion(instancePaused: Boolean, systemReducedMotionActive: Boolean): Boolean =
    instancePaused || systemReducedMotionActive

private fun systemReducedMotionActive(context: Context): Boolean {
    val powerSaveMode = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    val animatorDurationScale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    return shouldReduceAmbientMotion(powerSaveMode, animatorDurationScale)
}

/**
 * A lightweight shared policy for Home's blobs and the player background. System animation
 * scale and power saver change very rarely, so polling once a second avoids introducing app-wide receiver and
 * ContentObserver lifecycles solely for this decorative effect while still reacting promptly.
 */
@Composable
internal fun rememberSystemReducedMotionActive(): Boolean {
    val context = LocalContext.current.applicationContext
    var active by remember(context) { mutableStateOf(systemReducedMotionActive(context)) }
    LaunchedEffect(context) {
        while (isActive) {
            delay(MotionPolicyPollMillis)
            active = systemReducedMotionActive(context)
        }
    }
    return active
}

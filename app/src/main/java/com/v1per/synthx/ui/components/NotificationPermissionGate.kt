package com.v1per.synthx.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.v1per.synthx.R
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.theme.TextPrimary

/**
 * When synthX asks for POST_NOTIFICATIONS: only once the user has started work that posts a
 * notification, never at shell composition over the Welcome screen.
 *
 * The permission is a convenience, never a precondition: scan and restore run as foreground
 * work whose progress is reported in-app (RestoreStatusViewModel / the wizard's ScanningStep),
 * and a denial only hides the drawer notification. So nothing here ever waits on the system
 * prompt's result — see [NotificationPermissionGate.confirm].
 */
internal object NotificationPermissionPolicy {
    /** Below Android 13 POST_NOTIFICATIONS is not a runtime permission, so there is nothing to ask. */
    fun shouldAsk(sdkInt: Int, granted: Boolean, alreadyAsked: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted && !alreadyAsked
}

/**
 * State holder behind [rememberNotificationPermissionGate]. The permission and SDK lookups are
 * injected only so tests can drive the decision without a real system dialog.
 */
internal class NotificationPermissionGate(
    private val preferences: PreferencesManager,
    private val isGranted: () -> Boolean,
    private val launchRequest: () -> Unit,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    private var pendingProceed by mutableStateOf<(() -> Unit)?>(null)

    /** True while the rationale is on screen, waiting for the user to continue. */
    val rationaleVisible: Boolean get() = pendingProceed != null

    /**
     * Runs [proceed] straight away if there is nothing to ask (already granted, already asked,
     * or below Android 13). Otherwise shows the rationale first and runs [proceed] once the
     * user continues. Ignored while a rationale is already showing.
     */
    fun runAfterRationale(proceed: () -> Unit) {
        if (pendingProceed != null) return
        if (NotificationPermissionPolicy.shouldAsk(sdkInt, isGranted(), preferences.notificationPermissionAsked)) {
            pendingProceed = proceed
        } else {
            proceed()
        }
    }

    /**
     * Records that the user has been asked, fires the system prompt, and runs the deferred action
     * immediately after — not on the prompt's result. Scan/restore must behave identically
     * whether the answer is grant, deny, or still pending.
     */
    fun confirm() {
        val proceed = pendingProceed ?: return
        pendingProceed = null
        preferences.markNotificationPermissionAskedDurably()
        launchRequest()
        proceed()
    }
}

/**
 * Test seam: a screen that composes [rememberNotificationPermissionGate] can't be handed a gate
 * (its callers are nav routes), so a test provides this instead to supply the preferences behind
 * the "asked" flag and to replace the real system prompt with a counter. Always null in production.
 */
internal class NotificationPermissionBackend(
    val preferences: PreferencesManager,
    val isGranted: () -> Boolean,
    val launchRequest: () -> Unit,
)

internal val LocalNotificationPermissionBackend =
    staticCompositionLocalOf<NotificationPermissionBackend?> { null }

/** Also composes the rationale dialog; hold the returned gate wherever its trigger lives. */
// POST_NOTIFICATIONS is a compile-time constant; the request only runs when sdkInt >= TIRAMISU.
@SuppressLint("InlinedApi")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
internal fun rememberNotificationPermissionGate(): NotificationPermissionGate {
    val context = LocalContext.current
    val permissionState = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    val preferences = remember(context) { PreferencesManager(context.applicationContext) }
    val backend = LocalNotificationPermissionBackend.current
    val gate = remember(permissionState, preferences, backend) {
        NotificationPermissionGate(
            preferences = backend?.preferences ?: preferences,
            isGranted = backend?.isGranted ?: { permissionState.status.isGranted },
            launchRequest = backend?.launchRequest ?: { permissionState.launchPermissionRequest() },
        )
    }
    NotificationRationaleDialog(visible = gate.rationaleVisible, onContinue = gate::confirm)
    return gate
}

@Composable
internal fun NotificationRationaleDialog(visible: Boolean, onContinue: () -> Unit) {
    if (!visible) return
    AlertDialog(
        // Single-path dialog: the system prompt that follows carries the real allow/deny choice.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.notification_permission_title)) },
        text = { Text(stringResource(R.string.notification_permission_message)) },
        confirmButton = {
            TextButton(
                onClick = onContinue,
                colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary),
            ) { Text(stringResource(R.string.action_continue)) }
        },
    )
}

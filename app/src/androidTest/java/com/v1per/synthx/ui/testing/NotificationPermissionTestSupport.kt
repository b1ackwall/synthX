package com.v1per.synthx.ui.testing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals

/**
 * P9-T5: puts the debug app in the "notifications denied" state that scan/restore must behave
 * identically under. A freshly installed test APK already starts denied on Android 13+, so this
 * normally only asserts the precondition; if an earlier run or a manual grant left it granted it
 * revokes it first. Below Android 13 the permission does not exist, so there is nothing to deny.
 */
internal fun ensureNotificationPermissionDenied(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .revokeRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
    assertNotEquals(
        "POST_NOTIFICATIONS must be denied for this test to mean anything",
        PackageManager.PERMISSION_GRANTED,
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
    )
}

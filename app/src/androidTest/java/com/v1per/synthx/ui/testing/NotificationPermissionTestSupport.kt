package com.v1per.synthx.ui.testing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.junit.Assume.assumeTrue

/**
 * P9-T5: skips the calling test unless the debug app already has POST_NOTIFICATIONS denied, the
 * state scan/restore must behave identically under. It never revokes: the tests run inside the
 * app's own process, and revoking a runtime permission makes Android kill that process, which
 * aborts the whole instrumented run. A fresh install starts denied on Android 13+; a device where
 * it was granted needs it revoked from outside first (see the skip message). Below Android 13 the
 * permission does not exist, so there is nothing to deny.
 */
internal fun assumeNotificationPermissionDenied(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    assumeTrue(
        "This test needs POST_NOTIFICATIONS denied for ${context.packageName}, but it is granted. " +
            "Revoke it while the app is not running: adb -s <serial> shell pm revoke " +
            "${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED,
    )
}

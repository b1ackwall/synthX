package com.v1per.synthx.data.db

import android.os.Build
import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One-time/manual diagnostic, intentionally excluded from normal runs. Temporarily remove
 * [Ignore], run this single test on the target device, and copy its measured line to the comment
 * beside [SQLITE_IN_BATCH_SIZE]. The probe measures the effective Android connection rather than
 * trusting PRAGMA compile_options' build-time ceiling.
 */
@Ignore("Manual real-device host-parameter measurement only")
@RunWith(AndroidJUnit4::class)
class HostParameterLimitDiagnosticTest {
    @Test
    fun measureEffectiveHostParameterLimit() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = SynthXDatabase.createStandalone(
            context,
            "host-parameter-diagnostic-${System.nanoTime()}.db",
        )
        try {
            val sqlite = database.openHelper.writableDatabase
            val sqliteVersion = sqlite.query("SELECT sqlite_version()").use {
                it.moveToFirst(); it.getString(0)
            }
            var low = 0
            var high = 65_536
            while (low + 1 < high) {
                val candidate = low + (high - low) / 2
                if (accepts(sqlite, candidate)) low = candidate else high = candidate
            }
            Log.w(
                "HostParameterLimit",
                "device=${Build.MANUFACTURER} ${Build.MODEL}; api=${Build.VERSION.SDK_INT}; " +
                    "sqlite=$sqliteVersion; effectiveVariableLimit=$low",
            )
        } finally {
            val name = database.openHelper.databaseName
            database.close()
            name?.let(context::deleteDatabase)
        }
    }

    private fun accepts(database: androidx.sqlite.db.SupportSQLiteDatabase, count: Int): Boolean =
        runCatching {
            val placeholders = List(count) { "?" }.joinToString(",")
            database.query(
                SimpleSQLiteQuery("SELECT 1 WHERE 1 IN ($placeholders)", Array(count) { 1 })
            ).close()
        }.isSuccess
}

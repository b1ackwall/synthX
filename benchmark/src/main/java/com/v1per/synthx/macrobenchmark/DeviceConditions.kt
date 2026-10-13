package com.v1per.synthx.macrobenchmark

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry

/** Fail-fast checks that the phone is in a state where frame timings are comparable. */
internal object DeviceConditions {
    /** Minimum battery level accepted when the phone is not plugged in. */
    private const val MinUnpluggedBatteryPercent = 80

    fun requireReady() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val problems = buildList {
            batterySaverProblem(context)?.let(::add)
            powerProblem(context)?.let(::add)
            thermalProblem(context)?.let(::add)
            lockProblem(context)?.let(::add)
            compilationProblem()?.let(::add)
        }
        check(problems.isEmpty()) {
            "Device not ready for benchmarking:\n" + problems.joinToString("\n") { "  - $it" }
        }
    }

    private fun batterySaverProblem(context: Context): String? {
        val power = context.getSystemService(PowerManager::class.java)
        return if (power.isPowerSaveMode) {
            "Battery saver is on. Turn it off in Settings > Battery."
        } else {
            null
        }
    }

    private fun powerProblem(context: Context): String? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "Battery state is unavailable."
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return if (!plugged && percent < MinUnpluggedBatteryPercent) {
            "Phone is unplugged at $percent% battery. Plug it in (or charge it to at least " +
                "$MinUnpluggedBatteryPercent%)."
        } else {
            null
        }
    }

    private fun thermalProblem(context: Context): String? {
        val status = context.getSystemService(PowerManager::class.java).currentThermalStatus
        return if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
            "Phone is thermally throttling (thermal status $status). Let it cool down, " +
                "off the charger pad and out of direct sun, then re-run."
        } else {
            null
        }
    }

    private fun lockProblem(context: Context): String? {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return if (keyguard.isKeyguardLocked) {
            "Phone is locked. Unlock it and keep the screen on during the run."
        } else {
            null
        }
    }

    /**
     * Below Android 14 without root, Macrobenchmark resets compilation by uninstalling and
     * reinstalling the target, which erases the one-time setup.
     */
    private fun compilationProblem(): String? =
        if (Build.VERSION.SDK_INT < 34 && BenchmarkArgs.compilationEnabled()) {
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}): a compilation reset " +
                "here uninstalls the benchmark app and erases its setup. Run via " +
                "scripts/perf/run-benchmark.fish, which passes " +
                "androidx.benchmark.compilation.enabled=false on API 32-33."
        } else {
            null
        }
}

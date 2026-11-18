package com.example.airemote

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONObject

/**
 * Best-effort, permission-free device health snapshot for diagnostics.
 * No hardware identifiers, app list, unique IDs or background sampling.
 */
object SpectatorDeviceHealth {
    fun snapshot(context: Context): JSONObject {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val out = JSONObject()
            .put("heap_used_mb", used / 1_048_576)
            .put("heap_limit_mb", runtime.maxMemory() / 1_048_576)
            .put("background_monitoring", false)
            .put("thermal_throttle_verified", false)
        try {
            val battery = context.applicationContext.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (battery != null) {
                val temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (temp in 0..1000) out.put("battery_temperature_tenths_c", temp)
                if (level >= 0 && scale > 0 && level <= scale)
                    out.put("battery_percent", level * 100 / scale)
            }
        } catch (_: Exception) {
            out.put("battery_telemetry", "UNAVAILABLE")
        }
        return out
    }
}

package com.mostafa.phonecleaner.device

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.StringRes
import com.mostafa.phonecleaner.R
import com.mostafa.phonecleaner.util.DAY_MS
import com.mostafa.phonecleaner.util.sizeText
import java.text.SimpleDateFormat
import java.util.Locale

enum class Status { GOOD, WARN, BAD, INFO }

data class HealthItem(
    @StringRes val title: Int,
    val value: String,
    val status: Status,
    @StringRes val hint: Int = 0,
)

data class StorageInfo(val total: Long, val free: Long) {
    val used: Long get() = total - free
}

object DeviceHealth {

    fun storage(): StorageInfo = try {
        val st = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        StorageInfo(st.totalBytes, st.availableBytes)
    } catch (e: Exception) {
        StorageInfo(0, 0)
    }

    fun read(ctx: Context): List<HealthItem> {
        val out = ArrayList<HealthItem>()
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = ctx.getSystemService(BatteryManager::class.java)

        // ----- battery -----
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = if (level >= 0) level * 100 / scale else -1
            val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val levelText = when {
                pct < 0 -> "-"
                plugged -> "$pct% • " + ctx.getString(R.string.charging)
                else -> "$pct%"
            }
            out += HealthItem(
                R.string.h_battery_level,
                levelText,
                when {
                    pct in 0..14 && !plugged -> Status.WARN
                    else -> Status.GOOD
                }
            )

            val health = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            val (hText, hStatus) = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> R.string.bh_good to Status.GOOD
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.string.bh_overheat to Status.BAD
                BatteryManager.BATTERY_HEALTH_DEAD -> R.string.bh_dead to Status.BAD
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.string.bh_overvoltage to Status.BAD
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> R.string.bh_failure to Status.BAD
                BatteryManager.BATTERY_HEALTH_COLD -> R.string.bh_cold to Status.WARN
                else -> R.string.bh_unknown to Status.INFO
            }
            out += HealthItem(R.string.h_battery_health, ctx.getString(hText), hStatus, if (hStatus == Status.BAD) R.string.hint_battery_bad else 0)

            val temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (temp != Int.MIN_VALUE) {
                val c = temp / 10.0
                out += HealthItem(
                    R.string.h_battery_temp,
                    String.format(Locale.US, "%.1f °C", c),
                    when {
                        c >= 45 -> Status.BAD
                        c >= 40 -> Status.WARN
                        else -> Status.GOOD
                    },
                    if (c >= 40) R.string.hint_hot else 0
                )
            }

            val cycles = battery.getIntExtra("android.os.extra.CYCLE_COUNT", -1)
            if (cycles >= 0) {
                out += HealthItem(
                    R.string.h_battery_cycles, cycles.toString(),
                    when {
                        cycles > 800 -> Status.BAD
                        cycles > 500 -> Status.WARN
                        else -> Status.GOOD
                    },
                    if (cycles > 500) R.string.hint_cycles else 0
                )
            }

            val counter = try {
                bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Int.MIN_VALUE
            } catch (e: Exception) {
                Int.MIN_VALUE
            }
            if (counter > 0 && pct >= 20) {
                val est = (counter / 1000.0) / (pct / 100.0)
                // estimates above 1.5x or under 0.2x of typical phone batteries mean the counter is unreliable
                if (est in 800.0..12_000.0) {
                    out += HealthItem(
                        R.string.h_battery_capacity,
                        ctx.getString(R.string.mah_value, est.toInt()),
                        Status.INFO,
                        R.string.hint_capacity
                    )
                }
            }

            val voltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            if (voltage > 0) {
                out += HealthItem(R.string.h_battery_voltage, String.format(Locale.US, "%.2f V", voltage / 1000.0), Status.INFO)
            }
        }

        // ----- temperature state -----
        val power = ctx.getSystemService(PowerManager::class.java)
        if (power != null) {
            val t = power.currentThermalStatus
            out += HealthItem(
                R.string.h_thermal,
                ctx.getString(
                    when (t) {
                        PowerManager.THERMAL_STATUS_NONE -> R.string.thermal_none
                        PowerManager.THERMAL_STATUS_LIGHT -> R.string.thermal_light
                        PowerManager.THERMAL_STATUS_MODERATE -> R.string.thermal_moderate
                        else -> R.string.thermal_severe
                    }
                ),
                when {
                    t <= PowerManager.THERMAL_STATUS_LIGHT -> Status.GOOD
                    t == PowerManager.THERMAL_STATUS_MODERATE -> Status.WARN
                    else -> Status.BAD
                },
                if (t >= PowerManager.THERMAL_STATUS_MODERATE) R.string.hint_hot else 0
            )
            if (power.isPowerSaveMode) {
                out += HealthItem(R.string.h_power_save, ctx.getString(R.string.on), Status.INFO)
            }
        }

        // ----- memory -----
        val am = ctx.getSystemService(ActivityManager::class.java)
        if (am != null) {
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val freePct = if (mi.totalMem > 0) (mi.availMem * 100 / mi.totalMem).toInt() else 0
            out += HealthItem(
                R.string.h_ram,
                ctx.getString(R.string.free_of, ctx.sizeText(mi.availMem), ctx.sizeText(mi.totalMem)),
                when {
                    freePct < 10 || mi.lowMemory -> Status.BAD
                    freePct < 20 -> Status.WARN
                    else -> Status.GOOD
                },
                if (freePct < 20) R.string.hint_ram else 0
            )
        }

        // ----- storage -----
        val st = storage()
        if (st.total > 0) {
            val freePct = (st.free * 100 / st.total).toInt()
            out += HealthItem(
                R.string.h_storage,
                ctx.getString(R.string.free_of, ctx.sizeText(st.free), ctx.sizeText(st.total)),
                when {
                    freePct < 10 -> Status.BAD
                    freePct < 20 -> Status.WARN
                    else -> Status.GOOD
                },
                if (freePct < 20) R.string.hint_storage else 0
            )
        }

        // ----- uptime -----
        val upDays = SystemClock.elapsedRealtime() / DAY_MS
        val upHours = (SystemClock.elapsedRealtime() / 3_600_000) % 24
        out += HealthItem(
            R.string.h_uptime,
            ctx.getString(R.string.days_hours, upDays, upHours),
            if (upDays >= 7) Status.WARN else Status.GOOD,
            if (upDays >= 7) R.string.hint_restart else 0
        )

        // ----- security -----
        val patch = Build.VERSION.SECURITY_PATCH
        val patchAge = try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(patch)
            if (d != null) (System.currentTimeMillis() - d.time) / DAY_MS else -1
        } catch (e: Exception) {
            -1L
        }
        out += HealthItem(
            R.string.h_security_patch,
            patch,
            when {
                patchAge < 0 -> Status.INFO
                patchAge > 180 -> Status.BAD
                patchAge > 90 -> Status.WARN
                else -> Status.GOOD
            },
            if (patchAge > 90) R.string.hint_update else 0
        )

        val km = ctx.getSystemService(KeyguardManager::class.java)
        if (km != null) {
            val secure = km.isDeviceSecure
            out += HealthItem(
                R.string.h_screen_lock,
                ctx.getString(if (secure) R.string.on else R.string.off),
                if (secure) Status.GOOD else Status.BAD,
                if (!secure) R.string.hint_lock else 0
            )
        }

        val adb = try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (e: Exception) {
            false
        }
        out += HealthItem(
            R.string.h_usb_debug,
            ctx.getString(if (adb) R.string.on else R.string.off),
            if (adb) Status.WARN else Status.GOOD,
            if (adb) R.string.hint_adb else 0
        )

        // ----- info -----
        out += HealthItem(R.string.h_model, "${Build.MANUFACTURER} ${Build.MODEL}", Status.INFO)
        out += HealthItem(R.string.h_android, "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", Status.INFO)
        out += HealthItem(R.string.h_cpu, Runtime.getRuntime().availableProcessors().toString(), Status.INFO)

        return out
    }

    /** 0..100, based only on items that have a GOOD/WARN/BAD verdict. */
    fun score(items: List<HealthItem>): Int {
        val rated = items.filter { it.status != Status.INFO }
        if (rated.isEmpty()) return 100
        val points: List<Int> = rated.map {
            when (it.status) {
                Status.GOOD -> 100
                Status.WARN -> 60
                else -> 20
            }
        }
        return points.sum() / rated.size
    }
}

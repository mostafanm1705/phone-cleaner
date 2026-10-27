package com.mostafa.phonecleaner.apps

import android.Manifest
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import com.mostafa.phonecleaner.util.DAY_MS

enum class Drain { HIGH, MEDIUM, LOW }

data class AppInfo(
    val pkg: String,
    val label: String,
    val installTime: Long,
    val lastUsed: Long,
    /** foreground (on screen) time over the last 7 days */
    val fgTime: Long,
    /** time spent running a foreground service (background work) over the last 7 days */
    val bgTime: Long,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    val unrestricted: Boolean,
    val autoStart: Boolean,
    val drain: Drain,
    val grantedSensitive: List<String>,
) {
    val totalBytes: Long get() = appBytes + dataBytes
    fun unusedDays(now: Long): Long = (now - maxOf(lastUsed, installTime)) / DAY_MS
}

val SENSITIVE_PERMISSIONS = listOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.READ_SMS,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.BODY_SENSORS,
)

object AppAnalyzer {

    fun load(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val power = ctx.getSystemService(PowerManager::class.java)
        val now = System.currentTimeMillis()

        val week: Map<String, UsageStats> = try {
            usm?.queryAndAggregateUsageStats(now - 7 * DAY_MS, now) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
        val year: Map<String, UsageStats> = try {
            usm?.queryAndAggregateUsageStats(now - 365 * DAY_MS, now) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
        val bootPkgs: Set<String> = try {
            pm.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED), 0)
                .mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }

        val packages: List<PackageInfo> = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            emptyList()
        }

        return packages.mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (system || pi.packageName == ctx.packageName) return@mapNotNull null
            val pkg = pi.packageName

            val w = week[pkg]
            val fg = w?.totalTimeInForeground ?: 0L
            val bg = w?.totalTimeForegroundServiceUsed ?: 0L
            val y = year[pkg]
            val lastUsed = maxOf(y?.lastTimeUsed ?: 0L, y?.lastTimeVisible ?: 0L, w?.lastTimeUsed ?: 0L)
                .let { if (it > now) 0L else it }

            var appBytes = 0L
            var dataBytes = 0L
            var cacheBytes = 0L
            try {
                val st = ssm?.queryStatsForPackage(ai.storageUuid, pkg, Process.myUserHandle())
                if (st != null) {
                    appBytes = st.appBytes
                    dataBytes = st.dataBytes
                    cacheBytes = st.cacheBytes
                }
            } catch (e: Exception) {
            }

            val unrestricted = try {
                power?.isIgnoringBatteryOptimizations(pkg) ?: false
            } catch (e: Exception) {
                false
            }

            val perDay = bg / 7
            val drain = when {
                perDay > 60 * 60_000L || (unrestricted && perDay > 20 * 60_000L) -> Drain.HIGH
                perDay > 15 * 60_000L || (unrestricted && perDay > 0) -> Drain.MEDIUM
                else -> Drain.LOW
            }

            val granted = ArrayList<String>()
            val req = pi.requestedPermissions
            val flags = pi.requestedPermissionsFlags
            if (req != null && flags != null) {
                for (i in req.indices) {
                    if (i < flags.size && flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 &&
                        req[i] in SENSITIVE_PERMISSIONS
                    ) granted.add(req[i])
                }
            }

            AppInfo(
                pkg = pkg,
                label = try {
                    ai.loadLabel(pm).toString()
                } catch (e: Exception) {
                    pkg
                },
                installTime = pi.firstInstallTime,
                lastUsed = lastUsed,
                fgTime = fg,
                bgTime = bg,
                appBytes = appBytes,
                dataBytes = dataBytes,
                cacheBytes = cacheBytes,
                unrestricted = unrestricted,
                autoStart = pkg in bootPkgs,
                drain = drain,
                grantedSensitive = granted,
            )
        }.sortedBy { it.label.lowercase() }
    }
}

package com.mostafa.phonecleaner

import android.content.Context

fun versionName(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
} catch (e: Exception) {
    ""
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var trashDays: Int
        get() = sp.getInt("trash_days", 7)
        set(v) = sp.edit().putInt("trash_days", v).apply()

    var useTrash: Boolean
        get() = sp.getBoolean("use_trash", true)
        set(v) = sp.edit().putBoolean("use_trash", v).apply()

    var largeFileMb: Int
        get() = sp.getInt("large_mb", 100)
        set(v) = sp.edit().putInt("large_mb", v).apply()

    var unusedDays: Int
        get() = sp.getInt("unused_days", 30)
        set(v) = sp.edit().putInt("unused_days", v).apply()

    var screenshotDays: Int
        get() = sp.getInt("screenshot_days", 30)
        set(v) = sp.edit().putInt("screenshot_days", v).apply()

    var activityNotifications: Boolean
        get() = sp.getBoolean("notif_activity", true)
        set(v) = sp.edit().putBoolean("notif_activity", v).apply()

    var reminderEnabled: Boolean
        get() = sp.getBoolean("reminder_on", true)
        set(v) = sp.edit().putBoolean("reminder_on", v).apply()

    var reminderHour: Int
        get() = sp.getInt("reminder_hour", 22)
        set(v) = sp.edit().putInt("reminder_hour", v).apply()

    var reminderMinute: Int
        get() = sp.getInt("reminder_minute", 0)
        set(v) = sp.edit().putInt("reminder_minute", v).apply()

    var lowStorageAlert: Boolean
        get() = sp.getBoolean("low_storage_alert", true)
        set(v) = sp.edit().putBoolean("low_storage_alert", v).apply()
}

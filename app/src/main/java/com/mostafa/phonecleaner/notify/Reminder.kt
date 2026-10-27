package com.mostafa.phonecleaner.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mostafa.phonecleaner.Prefs
import com.mostafa.phonecleaner.R
import com.mostafa.phonecleaner.device.DeviceHealth
import com.mostafa.phonecleaner.scan.StorageScanner
import com.mostafa.phonecleaner.util.Perms
import java.util.Calendar

object Reminder {
    const val ACTION = "com.mostafa.phonecleaner.DAILY_REMINDER"

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 10,
        Intent(ctx, ReminderReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Next time the reminder fires, in millis. */
    fun nextTime(prefs: Prefs): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, prefs.reminderHour)
            set(Calendar.MINUTE, prefs.reminderMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis() + 5_000) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** (Re)schedules the daily alarm according to the settings. Safe to call any time. */
    fun schedule(ctx: Context) {
        val prefs = Prefs(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx)
        am.cancel(pi)
        if (!prefs.reminderEnabled) return
        val time = nextTime(prefs)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        }
    }
}

/** Fired by the daily alarm. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Reminder.ACTION) return
        Reminder.schedule(context) // tomorrow
        Notifier.reminder(context, context.getString(R.string.n_reminder_checking))
        WorkManager.getInstance(context).enqueueUniqueWork(
            "daily_check",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DailyCheckWorker>().build()
        )
    }
}

/** Re-creates the alarm after a restart, an app update or a clock change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminder.schedule(context)
    }
}

/** Runs a quick scan in the background and updates the reminder with real numbers. */
class DailyCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val storage = DeviceHealth.storage()
        if (!Perms.hasAllFiles()) {
            Notifier.reminder(ctx, ctx.getString(R.string.n_reminder_text))
            return Result.success()
        }
        val results = try {
            StorageScanner(ctx, Prefs(ctx)).scan(light = true) {}
        } catch (e: Exception) {
            null
        }
        val text = if (results != null) {
            Notifier.summary(ctx, results, storage) + "\n" + ctx.getString(R.string.n_reminder_tap)
        } else {
            ctx.getString(R.string.n_reminder_text)
        }
        Notifier.reminder(ctx, text)
        Notifier.lowStorage(ctx, storage)
        return Result.success()
    }
}

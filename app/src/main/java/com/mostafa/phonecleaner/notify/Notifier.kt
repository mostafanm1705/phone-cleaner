package com.mostafa.phonecleaner.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mostafa.phonecleaner.MainActivity
import com.mostafa.phonecleaner.Prefs
import com.mostafa.phonecleaner.R
import com.mostafa.phonecleaner.device.StorageInfo
import com.mostafa.phonecleaner.scan.Category
import com.mostafa.phonecleaner.scan.FileItem
import com.mostafa.phonecleaner.scan.quickCleanItems
import com.mostafa.phonecleaner.util.sizeText

object Notifier {
    private const val CH_PROGRESS = "progress"
    private const val CH_ACTIVITY = "activity"
    private const val CH_REMINDER = "reminder"
    private const val CH_ALERTS = "alerts"

    const val ID_SCAN = 1001
    private const val ID_RESULT = 1002
    private const val ID_CLEAN = 1003
    const val ID_REMINDER = 1004
    private const val ID_LOW = 1005
    private const val ID_TRASH = 1006

    const val EXTRA_START_SCAN = "start_scan"

    /** Percent of free storage under which the phone "needs cleaning". */
    const val LOW_STORAGE_PCT = 15

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_PROGRESS, ctx.getString(R.string.ch_progress), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_ACTIVITY, ctx.getString(R.string.ch_activity), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_REMINDER, ctx.getString(R.string.ch_reminder), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_ALERTS, ctx.getString(R.string.ch_alerts), NotificationManager.IMPORTANCE_HIGH),
            )
        )
    }

    fun canNotify(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    private fun openApp(ctx: Context, startScan: Boolean): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_START_SCAN, startScan)
        return PendingIntent.getActivity(
            ctx, if (startScan) 1 else 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun builder(ctx: Context, channel: String, title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF0F766E.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx, false))
            .setAutoCancel(true)

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        if (!canNotify(ctx)) return
        try {
            createChannels(ctx)
            NotificationManagerCompat.from(ctx).notify(id, b.build())
        } catch (_: Exception) {
        }
    }

    fun cancel(ctx: Context, id: Int) {
        try {
            NotificationManagerCompat.from(ctx).cancel(id)
        } catch (_: Exception) {
        }
    }

    private fun activityOn(ctx: Context) = Prefs(ctx).activityNotifications

    // ------------------------------------------------------------ scan

    fun scanProgress(ctx: Context, text: String, done: Int, total: Int) {
        if (!activityOn(ctx)) return
        val b = builder(ctx, CH_PROGRESS, ctx.getString(R.string.n_scan_running), text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(false)
        if (total > 0) b.setProgress(total, done.coerceAtMost(total), false) else b.setProgress(0, 0, true)
        post(ctx, ID_SCAN, b)
    }

    /** Human readable summary of a scan, used by the scan-finished and nightly notifications. */
    fun summary(ctx: Context, results: Map<Category, List<FileItem>>, storage: StorageInfo): String {
        val lines = ArrayList<String>()
        val junk = results.quickCleanItems()
        if (junk.isNotEmpty()) {
            lines += ctx.getString(R.string.n_junk_found, ctx.sizeText(junk.sumOf { it.size }), junk.size)
        } else {
            lines += ctx.getString(R.string.n_no_junk)
        }
        val social = results[Category.SOCIAL].orEmpty()
        if (social.isNotEmpty()) {
            lines += ctx.getString(R.string.n_social_found, social.size, ctx.sizeText(social.sumOf { it.size }))
        }
        if (storage.total > 0) {
            val usedPct = (storage.used * 100 / storage.total).toInt()
            lines += ctx.getString(R.string.n_storage_line, usedPct, ctx.sizeText(storage.free))
        }
        return lines.joinToString("\n")
    }

    fun scanDone(ctx: Context, results: Map<Category, List<FileItem>>, storage: StorageInfo) {
        cancel(ctx, ID_SCAN)
        if (!activityOn(ctx)) return
        post(ctx, ID_RESULT, builder(ctx, CH_ACTIVITY, ctx.getString(R.string.n_scan_done_title), summary(ctx, results, storage)))
    }

    fun scanFailed(ctx: Context) {
        cancel(ctx, ID_SCAN)
        if (!activityOn(ctx)) return
        post(ctx, ID_RESULT, builder(ctx, CH_ACTIVITY, ctx.getString(R.string.app_name), ctx.getString(R.string.scan_failed)))
    }

    // ------------------------------------------------------------ cleaning

    fun cleaned(ctx: Context, bytes: Long, count: Int, permanent: Boolean, trashDays: Int) {
        if (!activityOn(ctx)) return
        val text = if (permanent) ctx.getString(R.string.n_deleted, count, ctx.sizeText(bytes))
        else ctx.getString(R.string.n_trashed, count, ctx.sizeText(bytes), trashDays)
        post(ctx, ID_CLEAN, builder(ctx, CH_ACTIVITY, ctx.getString(R.string.n_clean_title), text))
    }

    fun restored(ctx: Context, count: Int) {
        if (!activityOn(ctx)) return
        post(ctx, ID_TRASH, builder(ctx, CH_ACTIVITY, ctx.getString(R.string.trash_title), ctx.getString(R.string.n_restored, count)))
    }

    fun trashEmptied(ctx: Context, count: Int, bytes: Long) {
        if (!activityOn(ctx)) return
        post(ctx, ID_TRASH, builder(ctx, CH_ACTIVITY, ctx.getString(R.string.trash_title), ctx.getString(R.string.n_trash_emptied, count, ctx.sizeText(bytes))))
    }

    fun purged(ctx: Context, count: Int, bytes: Long) {
        if (!activityOn(ctx) || count == 0) return
        post(ctx, ID_TRASH, builder(ctx, CH_ACTIVITY, ctx.getString(R.string.trash_title), ctx.getString(R.string.n_purged, count, ctx.sizeText(bytes))))
    }

    // ------------------------------------------------------------ reminder & alerts

    fun reminder(ctx: Context, text: String) {
        val b = builder(ctx, CH_REMINDER, ctx.getString(R.string.n_reminder_title), text)
            .setContentIntent(openApp(ctx, true))
            .addAction(0, ctx.getString(R.string.n_action_scan), openApp(ctx, true))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        post(ctx, ID_REMINDER, b)
    }

    fun lowStorage(ctx: Context, storage: StorageInfo) {
        if (!Prefs(ctx).lowStorageAlert || storage.total <= 0) return
        val freePct = (storage.free * 100 / storage.total).toInt()
        if (freePct >= LOW_STORAGE_PCT) return
        val b = builder(
            ctx, CH_ALERTS, ctx.getString(R.string.n_low_title),
            ctx.getString(R.string.n_low_text, ctx.sizeText(storage.free), freePct)
        )
            .setContentIntent(openApp(ctx, true))
            .addAction(0, ctx.getString(R.string.n_action_scan), openApp(ctx, true))
        post(ctx, ID_LOW, b)
    }
}

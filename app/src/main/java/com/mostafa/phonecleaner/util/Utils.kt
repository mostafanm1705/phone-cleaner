package com.mostafa.phonecleaner.util

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.mostafa.phonecleaner.R
import java.io.File
import java.text.DateFormat
import java.util.Date

const val DAY_MS = 24L * 60 * 60 * 1000

fun Context.sizeText(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)

fun dateText(ms: Long): String =
    if (ms <= 0) "-" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

fun durationText(ctx: Context, ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) ctx.getString(R.string.dur_hm, h, m) else ctx.getString(R.string.dur_m, m)
}

object Perms {
    fun hasAllFiles(): Boolean = Environment.isExternalStorageManager()

    fun hasUsage(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            ctx.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun requestAllFiles(ctx: Context) {
        ctx.launch(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        )
    }

    fun requestUsage(ctx: Context) {
        ctx.launch(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        )
    }

    fun openAppDetails(ctx: Context, pkg: String) {
        ctx.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    fun uninstall(ctx: Context, pkg: String) {
        ctx.launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
    }

    fun openBatterySettings(ctx: Context) {
        ctx.launch(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
    }
}

fun Context.launch(intent: Intent, fallback: Intent? = null) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        if (fallback != null) {
            try {
                startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }
}

fun openFile(ctx: Context, path: String) {
    val f = File(path)
    if (!f.exists() || f.isDirectory) {
        Toast.makeText(ctx, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, R.string.cannot_open, Toast.LENGTH_SHORT).show()
    }
}

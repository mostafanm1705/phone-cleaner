package com.mostafa.phonecleaner.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.mostafa.phonecleaner.MainViewModel
import com.mostafa.phonecleaner.R
import com.mostafa.phonecleaner.apps.AppInfo
import com.mostafa.phonecleaner.apps.Drain
import com.mostafa.phonecleaner.util.Perms
import com.mostafa.phonecleaner.util.dateText
import com.mostafa.phonecleaner.util.durationText
import com.mostafa.phonecleaner.util.sizeText

@Composable
fun AppsScreen(vm: MainViewModel, initialTab: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(initialTab) }
    LaunchedEffect(Unit) { if (!vm.appsLoaded) vm.loadApps() }

    Scaffold(topBar = { TopBar(stringResource(R.string.apps_title), onBack) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_battery)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_unused)) })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.tab_cache)) })
            }
            when {
                !vm.hasUsage -> Column(Modifier.padding(16.dp)) {
                    InfoNote(stringResource(R.string.perm_usage_explain))
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { Perms.requestUsage(ctx) }) { Text(stringResource(R.string.grant_access)) }
                }
                !vm.appsLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> AppList(vm, tab)
            }
        }
    }
}

@Composable
private fun AppList(vm: MainViewModel, tab: Int) {
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    val list: List<AppInfo> = when (tab) {
        0 -> vm.apps.sortedWith(compareBy<AppInfo>({ it.drain.ordinal }, { -it.bgTime }))
        1 -> vm.apps.filter { it.unusedDays(now) >= vm.prefs.unusedDays }.sortedByDescending { it.totalBytes }
        else -> vm.apps.filter { it.cacheBytes > 0 }.sortedByDescending { it.cacheBytes }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                when (tab) {
                    0 -> {
                        InfoNote(stringResource(R.string.battery_explain))
                        TextButton(onClick = { Perms.openBatterySettings(ctx) }) {
                            Text(stringResource(R.string.open_battery_settings))
                        }
                    }
                    1 -> InfoNote(stringResource(R.string.unused_explain, vm.prefs.unusedDays))
                    else -> InfoNote(
                        stringResource(R.string.cache_explain, ctx.sizeText(list.sumOf { it.cacheBytes }))
                    )
                }
            }
        }
        if (list.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.nothing_found),
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(list, key = { it.pkg }) { app ->
            AppRow(app, tab, now)
            HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
fun AppIcon(pkg: String, size: Int = 44) {
    val ctx = LocalContext.current
    val bmp: ImageBitmap? = remember(pkg) { loadIcon(ctx, pkg) }
    if (bmp != null) {
        Image(bmp, null, Modifier.size(size.dp))
    } else {
        Icon(Icons.Filled.Android, null, Modifier.size(size.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun loadIcon(ctx: Context, pkg: String): ImageBitmap? = try {
    ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
} catch (e: Exception) {
    null
}

@Composable
private fun AppRow(app: AppInfo, tab: Int, now: Long) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { Perms.openAppDetails(ctx, app.pkg) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app.pkg)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val small = MaterialTheme.typography.bodySmall
            val dim = MaterialTheme.colorScheme.onSurfaceVariant
            when (tab) {
                0 -> {
                    Text(stringResource(R.string.bg_per_day, durationText(ctx, app.bgTime / 7)), style = small, color = dim)
                    Text(stringResource(R.string.fg_per_day, durationText(ctx, app.fgTime / 7)), style = small, color = dim)
                    val flags = buildList {
                        if (app.unrestricted) add(stringResource(R.string.flag_unrestricted))
                        if (app.autoStart) add(stringResource(R.string.flag_autostart))
                    }
                    if (flags.isNotEmpty()) Text(flags.joinToString(" • "), style = small, color = dim)
                }
                1 -> {
                    Text(
                        if (app.lastUsed > 0) stringResource(R.string.last_used, dateText(app.lastUsed))
                        else stringResource(R.string.never_used_since, dateText(app.installTime)),
                        style = small, color = dim
                    )
                    Text(stringResource(R.string.app_size, ctx.sizeText(app.totalBytes)), style = small, color = dim)
                }
                else -> {
                    Text(stringResource(R.string.cache_size, ctx.sizeText(app.cacheBytes)), style = small, color = dim)
                    Text(stringResource(R.string.cache_how), style = small, color = dim)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        when (tab) {
            0 -> when (app.drain) {
                Drain.HIGH -> Badge(stringResource(R.string.drain_high), BadColor)
                Drain.MEDIUM -> Badge(stringResource(R.string.drain_medium), WarnColor)
                Drain.LOW -> Badge(stringResource(R.string.drain_low), GoodColor)
            }
            1 -> TextButton(onClick = { Perms.uninstall(ctx, app.pkg) }) { Text(stringResource(R.string.uninstall)) }
            else -> {}
        }
    }
}

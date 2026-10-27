package com.mostafa.phonecleaner.ui

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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Assessment
import com.mostafa.phonecleaner.util.dateTimeText
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PhotoSizeSelectSmall
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mostafa.phonecleaner.MainViewModel
import com.mostafa.phonecleaner.R
import com.mostafa.phonecleaner.apps.Drain
import com.mostafa.phonecleaner.device.StorageInfo
import com.mostafa.phonecleaner.scan.Category
import com.mostafa.phonecleaner.scan.ScanPhase
import com.mostafa.phonecleaner.scan.ScanProgress
import com.mostafa.phonecleaner.util.Perms
import com.mostafa.phonecleaner.util.sizeText

fun Category.icon(): ImageVector = when (this) {
    Category.JUNK -> Icons.Filled.DeleteSweep
    Category.EMPTY_FOLDERS -> Icons.Filled.FolderOpen
    Category.APKS -> Icons.Filled.Android
    Category.LEFTOVERS -> Icons.Filled.Inventory2
    Category.DUPLICATES -> Icons.Filled.ContentCopy
    Category.SIMILAR -> Icons.Filled.BurstMode
    Category.BLURRY -> Icons.Filled.BlurOn
    Category.LOW_RES -> Icons.Filled.PhotoSizeSelectSmall
    Category.SCREENSHOTS -> Icons.Filled.Screenshot
    Category.SOCIAL -> Icons.Filled.Forum
    Category.LARGE -> Icons.Filled.SdStorage
}

@Composable
fun HomeScreen(vm: MainViewModel, nav: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val progress by vm.progress.collectAsState()
    var confirmQuick by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopBar(stringResource(R.string.app_name), null) {
                IconButton(onClick = { nav(Screen.Settings) }) {
                    Icon(Icons.Filled.Settings, stringResource(R.string.settings))
                }
            }
        }
    ) { pad ->
        LazyColumn(
            Modifier
                .padding(pad)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { StorageCard(vm.storage) }
            item {
                ScanCard(
                    scanning = vm.scanning,
                    scanned = vm.scanned,
                    progress = progress,
                    onScan = { vm.startScan() },
                    onCancel = { vm.cancelScan() }
                )
            }

            if (vm.scanned && !vm.scanning) {
                item {
                    NavRow(
                        Icons.Filled.Assessment,
                        stringResource(R.string.report_open),
                        if (vm.lastScanTime > 0) stringResource(R.string.report_last, dateTimeText(vm.lastScanTime))
                        else stringResource(R.string.report_sub),
                        tint = MaterialTheme.colorScheme.primary
                    ) { nav(Screen.Report) }
                }
            }

            if (vm.scanned) {
                val quick = vm.quickCleanItems()
                val quickSize = quick.sumOf { it.size }
                if (vm.scanning) {
                    item { InfoNote(stringResource(R.string.partial_note)) }
                }
                if (quick.isNotEmpty() && !vm.scanning) {
                    item {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(R.string.quick_title, ctx.sizeText(quickSize)),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.quick_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(Modifier.height(10.dp))
                                Button(onClick = { confirmQuick = true }, enabled = !vm.busy) {
                                    Text(stringResource(R.string.quick_button))
                                }
                            }
                        }
                    }
                }

                item { SectionTitle(stringResource(R.string.section_files)) }
                items(Category.entries.toList()) { cat ->
                    val list = vm.results[cat].orEmpty()
                    val size = list.sumOf { it.size }
                    val subtitle = if (list.isEmpty()) stringResource(R.string.nothing_found)
                    else if (cat == Category.EMPTY_FOLDERS) stringResource(R.string.n_items, list.size)
                    else stringResource(R.string.n_items_size, list.size, ctx.sizeText(size))
                    NavRow(cat.icon(), stringResource(cat.title), subtitle, enabled = list.isNotEmpty()) {
                        nav(Screen.Files(cat))
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.section_apps)) }
            if (!vm.hasUsage) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.perm_usage_explain), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { Perms.requestUsage(ctx) }) {
                                Text(stringResource(R.string.grant_access))
                            }
                        }
                    }
                }
            }
            item {
                val high = vm.apps.count { it.drain == Drain.HIGH }
                NavRow(
                    Icons.Filled.BatteryAlert,
                    stringResource(R.string.apps_battery),
                    if (vm.appsLoaded && vm.hasUsage) stringResource(R.string.apps_battery_sub, high)
                    else stringResource(R.string.apps_battery_hint),
                    tint = if (high > 0) WarnColor else MaterialTheme.colorScheme.primary
                ) { nav(Screen.Apps(0)) }
            }
            item {
                val now = System.currentTimeMillis()
                val unused = vm.apps.count { it.unusedDays(now) >= vm.prefs.unusedDays }
                NavRow(
                    Icons.Filled.Apps,
                    stringResource(R.string.apps_unused),
                    if (vm.appsLoaded && vm.hasUsage) stringResource(R.string.apps_unused_sub, unused, vm.prefs.unusedDays)
                    else stringResource(R.string.apps_unused_hint)
                ) { nav(Screen.Apps(1)) }
            }
            item {
                val cache = vm.apps.sumOf { it.cacheBytes }
                NavRow(
                    Icons.Filled.Storage,
                    stringResource(R.string.apps_cache),
                    if (vm.appsLoaded && vm.hasUsage) stringResource(R.string.apps_cache_sub, ctx.sizeText(cache))
                    else stringResource(R.string.apps_cache_hint)
                ) { nav(Screen.Apps(2)) }
            }

            item { SectionTitle(stringResource(R.string.section_device)) }
            item {
                NavRow(Icons.Filled.HealthAndSafety, stringResource(R.string.health_title), stringResource(R.string.health_sub)) {
                    nav(Screen.Health)
                }
            }
            item {
                NavRow(Icons.Filled.Security, stringResource(R.string.audit_title), stringResource(R.string.audit_sub)) {
                    nav(Screen.PermissionAudit)
                }
            }
            item {
                val t = vm.trashItems
                NavRow(
                    Icons.Filled.Restore,
                    stringResource(R.string.trash_title),
                    if (t.isEmpty()) stringResource(R.string.trash_empty)
                    else stringResource(R.string.n_items_size, t.size, ctx.sizeText(t.sumOf { it.size }))
                ) { nav(Screen.Trash) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmQuick) QuickCleanDialog(vm) { confirmQuick = false }
}

@Composable
fun QuickCleanDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    run {
        val items = vm.quickCleanItems()
        var permanent by remember { mutableStateOf(!vm.prefs.useTrash) }
        AlertDialog(
            onDismissRequest = { onDismiss() },
            title = { Text(stringResource(R.string.quick_confirm_title)) },
            text = {
                Column {
                    Category.entries.filter { it.quickClean }.forEach { cat ->
                        val sel = vm.results[cat].orEmpty().filter { it.suggested }
                        if (sel.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(stringResource(cat.title), Modifier.weight(1f))
                                Text(stringResource(R.string.n_items_size, sel.size, ctx.sizeText(sel.sumOf { it.size })))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    PermanentToggle(permanent) { permanent = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    vm.delete(items, permanent)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
fun PermanentToggle(permanent: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = permanent, onCheckedChange = onChange)
        Column {
            Text(stringResource(R.string.delete_permanently), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (permanent) stringResource(R.string.permanent_note) else stringResource(R.string.trash_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StorageCard(info: StorageInfo) {
    val ctx = LocalContext.current
    val frac = if (info.total > 0) info.used.toFloat() / info.total else 0f
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.size(84.dp),
                    strokeWidth = 8.dp,
                    color = if (frac > 0.9f) BadColor else if (frac > 0.8f) WarnColor else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(18.dp))
            Column {
                Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.storage_used, ctx.sizeText(info.used), ctx.sizeText(info.total)),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.storage_free, ctx.sizeText(info.free)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ScanCard(
    scanning: Boolean,
    scanned: Boolean,
    progress: ScanProgress?,
    onScan: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (scanning) {
                val p = progress
                val text = when (p?.phase) {
                    ScanPhase.FILES, null -> stringResource(R.string.phase_files, p?.done ?: 0)
                    ScanPhase.DUPLICATES -> stringResource(R.string.phase_duplicates)
                    ScanPhase.IMAGES -> stringResource(R.string.phase_images, p?.done ?: 0, p?.total ?: 0)
                    ScanPhase.APPS -> stringResource(R.string.phase_apps)
                }
                Text(text, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                if (p != null && p.total > 0) {
                    LinearProgressIndicator(
                        progress = { p.done.toFloat() / p.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (!p?.current.isNullOrEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        p?.current?.substringAfterLast('/') ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            } else {
                Text(
                    stringResource(if (scanned) R.string.scan_done else R.string.scan_intro),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Folder, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (scanned) R.string.rescan else R.string.scan_full))
                }
            }
        }
    }
}

package com.mostafa.phonecleaner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mostafa.phonecleaner.MainViewModel
import com.mostafa.phonecleaner.R
import com.mostafa.phonecleaner.apps.Drain
import com.mostafa.phonecleaner.scan.Category
import com.mostafa.phonecleaner.scan.FileItem
import com.mostafa.phonecleaner.util.dateTimeText
import com.mostafa.phonecleaner.util.sizeText

private data class CatSummary(
    val cat: Category,
    val all: List<FileItem>,
    val suggested: List<FileItem>,
) {
    val allSize = all.sumOf { it.size }
    val suggestedSize = suggested.sumOf { it.size }
}

/**
 * The scan report: what was found, what can be deleted safely, what needs a decision,
 * plus apps and storage highlights. Every row opens the list of files behind it.
 */
@Composable
fun ReportScreen(vm: MainViewModel, nav: (Screen) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var confirmQuick by remember { mutableStateOf(false) }
    val results = vm.results

    val summaries = Category.entries.map { c ->
        val list = results[c].orEmpty()
        CatSummary(c, list, list.filter { it.suggested && !it.keep })
    }.filter { it.all.isNotEmpty() }

    // every file counted once, even if it appears in two categories
    val unique = results.values.flatten().distinctBy { it.path }
    val totalSize = unique.sumOf { it.size }
    val quick = vm.quickCleanItems()
    val quickSize = quick.sumOf { it.size }

    val safe = summaries.filter { it.cat.quickClean && it.suggested.isNotEmpty() }
    val review = summaries.filter { !it.cat.quickClean || it.suggested.isEmpty() }
        .sortedByDescending { it.allSize }

    val storage = vm.storage
    val usedPct = if (storage.total > 0) (storage.used * 100 / storage.total).toInt() else 0

    Scaffold(topBar = { TopBar(stringResource(R.string.report_title), onBack) }) { pad ->
        LazyColumn(
            Modifier
                .padding(pad)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---------- headline ----------
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        if (vm.lastScanTime > 0) {
                            Text(
                                stringResource(R.string.report_last, dateTimeText(vm.lastScanTime)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(
                            stringResource(R.string.report_found, unique.size, ctx.sizeText(totalSize)),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(10.dp))
                        StatLine(stringResource(R.string.report_safe), ctx.sizeText(quickSize), GoodColor)
                        val socialSize = results[Category.SOCIAL].orEmpty().sumOf { it.size }
                        StatLine(stringResource(R.string.report_social), ctx.sizeText(socialSize), WarnColor)
                        StatLine(
                            stringResource(R.string.report_storage),
                            "$usedPct% • " + stringResource(R.string.storage_free, ctx.sizeText(storage.free)),
                            if (usedPct >= 85) BadColor else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { usedPct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (usedPct >= 85) BadColor else MaterialTheme.colorScheme.primary,
                        )
                        if (quick.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = { confirmQuick = true },
                                enabled = !vm.busy && !vm.scanning,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.report_clean_safe, ctx.sizeText(quickSize)))
                            }
                        }
                    }
                }
            }

            if (vm.scanning) item { InfoNote(stringResource(R.string.partial_note)) }

            if (summaries.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, null, tint = GoodColor)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.report_clean_phone))
                        }
                    }
                }
            }

            // ---------- safe to delete ----------
            if (safe.isNotEmpty()) {
                item {
                    SectionTitle(stringResource(R.string.report_section_safe))
                    Text(
                        stringResource(R.string.report_section_safe_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                items(safe, key = { "s" + it.cat.name }) { s ->
                    NavRow(
                        s.cat.icon(),
                        stringResource(s.cat.title),
                        stringResource(R.string.report_row_safe, s.suggested.size, ctx.sizeText(s.suggestedSize)),
                        tint = GoodColor
                    ) { nav(Screen.Files(s.cat)) }
                }
            }

            // ---------- your decision ----------
            if (review.isNotEmpty()) {
                item {
                    SectionTitle(stringResource(R.string.report_section_review))
                    Text(
                        stringResource(R.string.report_section_review_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                items(review, key = { "r" + it.cat.name }) { s ->
                    val sub = if (s.cat == Category.EMPTY_FOLDERS) stringResource(R.string.n_items, s.all.size)
                    else stringResource(R.string.n_items_size, s.all.size, ctx.sizeText(s.allSize))
                    NavRow(
                        s.cat.icon(),
                        stringResource(s.cat.title),
                        sub,
                        tint = WarnColor
                    ) { nav(Screen.Files(s.cat)) }
                }
            }

            // ---------- apps ----------
            if (vm.appsLoaded && vm.hasUsage) {
                val now = System.currentTimeMillis()
                val high = vm.apps.count { it.drain == Drain.HIGH }
                val unused = vm.apps.filter { it.unusedDays(now) >= vm.prefs.unusedDays }
                val cache = vm.apps.sumOf { it.cacheBytes }
                item { SectionTitle(stringResource(R.string.section_apps)) }
                item {
                    NavRow(
                        Icons.Filled.BatteryAlert,
                        stringResource(R.string.apps_battery),
                        stringResource(R.string.apps_battery_sub, high),
                        tint = if (high > 0) BadColor else GoodColor
                    ) { nav(Screen.Apps(0)) }
                }
                item {
                    NavRow(
                        Icons.Filled.Apps,
                        stringResource(R.string.apps_unused),
                        stringResource(R.string.apps_unused_sub, unused.size, vm.prefs.unusedDays) +
                            " • " + ctx.sizeText(unused.sumOf { it.totalBytes }),
                        tint = if (unused.isNotEmpty()) WarnColor else GoodColor
                    ) { nav(Screen.Apps(1)) }
                }
                item {
                    NavRow(
                        Icons.Filled.Storage,
                        stringResource(R.string.apps_cache),
                        stringResource(R.string.apps_cache_sub, ctx.sizeText(cache))
                    ) { nav(Screen.Apps(2)) }
                }
            }

            item { SectionTitle(stringResource(R.string.section_device)) }
            item {
                NavRow(Icons.Filled.HealthAndSafety, stringResource(R.string.health_title), stringResource(R.string.health_sub)) {
                    nav(Screen.Health)
                }
            }
            item {
                OutlinedButton(
                    onClick = { vm.startScan() },
                    enabled = !vm.scanning,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.rescan)) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmQuick) QuickCleanDialog(vm) { confirmQuick = false }
}

@Composable
private fun StatLine(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
    }
}

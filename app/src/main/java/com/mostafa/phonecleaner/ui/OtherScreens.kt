@file:OptIn(ExperimentalMaterial3Api::class)

package com.mostafa.phonecleaner.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.mostafa.phonecleaner.versionName
import com.mostafa.phonecleaner.MainViewModel
import com.mostafa.phonecleaner.R
import com.mostafa.phonecleaner.apps.AppInfo
import com.mostafa.phonecleaner.device.DeviceHealth
import com.mostafa.phonecleaner.device.Status
import com.mostafa.phonecleaner.util.Perms
import com.mostafa.phonecleaner.util.dateText
import com.mostafa.phonecleaner.util.sizeText

// ---------------------------------------------------------------- Health

@Composable
fun HealthScreen(vm: MainViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadHealth() }
    val healthItems = vm.health
    Scaffold(topBar = { TopBar(stringResource(R.string.health_title), onBack) }) { pad ->
        if (healthItems.isEmpty()) {
            Box(
                Modifier
                    .padding(pad)
                    .fillMaxSize(), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        val score = DeviceHealth.score(healthItems)
        LazyColumn(
            Modifier
                .padding(pad)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val st = when {
                            score >= 85 -> Status.GOOD
                            score >= 60 -> Status.WARN
                            else -> Status.BAD
                        }
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { score / 100f },
                                modifier = Modifier.size(72.dp),
                                strokeWidth = 7.dp,
                                color = st.color(),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Text("$score", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(stringResource(R.string.health_score), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(
                                    when (st) {
                                        Status.GOOD -> R.string.score_good
                                        Status.WARN -> R.string.score_warn
                                        else -> R.string.score_bad
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            items(healthItems) { h ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            when (h.status) {
                                Status.GOOD -> Icons.Filled.CheckCircle
                                Status.WARN -> Icons.Filled.Warning
                                Status.BAD -> Icons.Filled.Error
                                Status.INFO -> Icons.Filled.Info
                            },
                            null,
                            tint = h.status.color()
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(h.title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text(h.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            if (h.hint != 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(h.hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Permission audit

private val PERMISSION_GROUPS: List<Pair<Int, List<String>>> = listOf(
    R.string.perm_camera to listOf(android.Manifest.permission.CAMERA),
    R.string.perm_mic to listOf(android.Manifest.permission.RECORD_AUDIO),
    R.string.perm_bg_location to listOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
    R.string.perm_location to listOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION
    ),
    R.string.perm_contacts to listOf(android.Manifest.permission.READ_CONTACTS),
    R.string.perm_sms to listOf(android.Manifest.permission.READ_SMS),
    R.string.perm_calllog to listOf(android.Manifest.permission.READ_CALL_LOG),
    R.string.perm_sensors to listOf(android.Manifest.permission.BODY_SENSORS),
)

@Composable
fun PermissionAuditScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { if (!vm.appsLoaded) vm.loadApps() }
    Scaffold(topBar = { TopBar(stringResource(R.string.audit_title), onBack) }) { pad ->
        if (!vm.appsLoaded) {
            Box(
                Modifier
                    .padding(pad)
                    .fillMaxSize(), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            Modifier
                .padding(pad)
                .fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                InfoNote(stringResource(R.string.audit_explain), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            for ((title, perms) in PERMISSION_GROUPS) {
                val apps: List<AppInfo> = vm.apps.filter { a -> a.grantedSensitive.any { it in perms } }
                if (apps.isEmpty()) continue
                item(key = "h$title") {
                    SectionTitle(stringResource(title) + " (${apps.size})")
                }
                items(apps, key = { "$title/${it.pkg}" }) { app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { Perms.openAppDetails(ctx, app.pkg) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(app.pkg, 36)
                        Spacer(Modifier.width(12.dp))
                        Text(app.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { Perms.openAppDetails(ctx, app.pkg) }) {
                            Text(stringResource(R.string.manage))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Trash

@Composable
fun TrashScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.loadTrash() }
    var confirmEmpty by remember { mutableStateOf(false) }
    val trashList = vm.trashItems
    val now = System.currentTimeMillis()
    Scaffold(topBar = {
        TopBar(stringResource(R.string.trash_title), onBack) {
            if (trashList.isNotEmpty()) {
                TextButton(onClick = { confirmEmpty = true }) { Text(stringResource(R.string.empty_trash)) }
            }
        }
    }) { pad ->
        LazyColumn(
            Modifier
                .padding(pad)
                .fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                InfoNote(
                    stringResource(R.string.trash_explain, vm.prefs.trashDays),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (trashList.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.trash_empty),
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                item {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { vm.restore(trashList.map { it.id }.toSet()) }) {
                            Text(stringResource(R.string.restore_all))
                        }
                    }
                }
            }
            items(trashList, key = { it.id }) { e ->
                val daysLeft = (vm.prefs.trashDays - (now - e.deletedAt) / (24L * 60 * 60 * 1000)).coerceAtLeast(0)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (e.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.trash_item_info, ctx.sizeText(e.size), dateText(e.deletedAt), daysLeft),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { vm.restore(setOf(e.id)) }) { Text(stringResource(R.string.restore)) }
                    TextButton(onClick = { vm.deleteForever(setOf(e.id)) }) { Text(stringResource(R.string.delete)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text(stringResource(R.string.empty_trash)) },
            text = { Text(stringResource(R.string.empty_trash_confirm, ctx.sizeText(trashList.sumOf { it.size }))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    vm.deleteForever(trashList.map { it.id }.toSet())
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

// ---------------------------------------------------------------- Settings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
    var useTrash by remember { mutableStateOf(vm.prefs.useTrash) }
    var trashDays by remember { mutableIntStateOf(vm.prefs.trashDays) }
    var largeMb by remember { mutableIntStateOf(vm.prefs.largeFileMb) }
    var unusedDays by remember { mutableIntStateOf(vm.prefs.unusedDays) }
    var shotDays by remember { mutableIntStateOf(vm.prefs.screenshotDays) }

    Scaffold(topBar = { TopBar(stringResource(R.string.settings), onBack) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SectionTitle(stringResource(R.string.language))
            listOf(
                "" to stringResource(R.string.lang_system),
                "ar" to "العربية",
                "en" to "English",
            ).forEach { (tag, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = current == tag, role = Role.RadioButton) {
                            AppCompatDelegate.setApplicationLocales(
                                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                                else LocaleListCompat.forLanguageTags(tag)
                            )
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = current == tag, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(label)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionTitle(stringResource(R.string.trash_title))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.use_trash))
                    Text(
                        stringResource(R.string.use_trash_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = useTrash, onCheckedChange = { useTrash = it; vm.prefs.useTrash = it })
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.trash_days))
            ChipChoice(listOf(3, 7, 14, 30), trashDays, { stringResource(R.string.n_days, it) }) {
                trashDays = it; vm.setTrashDays(it)
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionTitle(stringResource(R.string.scan_settings))
            Text(stringResource(R.string.large_threshold))
            ChipChoice(listOf(50, 100, 250, 500), largeMb, { "$it MB" }) {
                largeMb = it; vm.prefs.largeFileMb = it
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.screenshot_age))
            ChipChoice(listOf(7, 30, 90, 180), shotDays, { stringResource(R.string.n_days, it) }) {
                shotDays = it; vm.prefs.screenshotDays = it
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.unused_threshold))
            ChipChoice(listOf(14, 30, 60, 90), unusedDays, { stringResource(R.string.n_days, it) }) {
                unusedDays = it; vm.prefs.unusedDays = it
            }
            Text(
                stringResource(R.string.rescan_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionTitle(stringResource(R.string.about))
            Text(
                stringResource(R.string.about_text, versionName(LocalContext.current)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipChoice(options: List<Int>, value: Int, label: @Composable (Int) -> String, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(selected = value == o, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

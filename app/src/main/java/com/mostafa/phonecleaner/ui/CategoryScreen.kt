@file:OptIn(ExperimentalMaterial3Api::class)

package com.mostafa.phonecleaner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.mostafa.phonecleaner.MainViewModel
import com.mostafa.phonecleaner.R
import com.mostafa.phonecleaner.scan.Category
import com.mostafa.phonecleaner.scan.FileItem
import com.mostafa.phonecleaner.util.dateText
import com.mostafa.phonecleaner.util.openFile
import com.mostafa.phonecleaner.util.sizeText
import java.io.File

@Composable
fun CategoryScreen(vm: MainViewModel, cat: Category, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val all = vm.results[cat].orEmpty()
    val selected = remember(cat) {
        mutableStateMapOf<String, Boolean>().apply { all.forEach { if (it.suggested) put(it.path, true) } }
    }
    var filter by remember(cat) { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<FileItem?>(null) }
    var confirm by remember { mutableStateOf(false) }

    val details = remember(all) { all.map { it.detail }.filter { it.isNotEmpty() }.distinct() }
    val visible = if (filter == null) all else all.filter { it.detail == filter }
    val chosen = all.filter { selected[it.path] == true }
    val chosenSize = chosen.sumOf { it.size }

    Scaffold(
        topBar = { TopBar(stringResource(cat.title), onBack) },
        bottomBar = {
            if (chosen.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = { confirm = true },
                        enabled = !vm.busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(12.dp)
                    ) {
                        Text(stringResource(R.string.delete_selected, chosen.size, ctx.sizeText(chosenSize)))
                    }
                }
            }
        }
    ) { pad ->
        LazyColumn(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    InfoNote(stringResource(cat.desc))
                    if (details.size in 2..20) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = filter == null,
                                onClick = { filter = null },
                                label = { Text(stringResource(R.string.all)) }
                            )
                            details.forEach { d ->
                                val n = all.count { it.detail == d }
                                FilterChip(
                                    selected = filter == d,
                                    onClick = { filter = d },
                                    label = { Text("$d ($n)") }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            visible.forEach { selected[it.path] = it.suggested }
                        }) { Text(stringResource(R.string.select_suggested)) }
                        TextButton(onClick = {
                            visible.forEach { if (!it.keep) selected[it.path] = true }
                        }) { Text(stringResource(R.string.select_all)) }
                        TextButton(onClick = {
                            visible.forEach { selected.remove(it.path) }
                        }) { Text(stringResource(R.string.select_none)) }
                    }
                }
            }

            if (cat.grouped) {
                val groups = visible.groupBy { it.group }
                var index = 0
                for ((_, members) in groups) {
                    index++
                    val n = index
                    item(key = "g${members.first().group}") {
                        Text(
                            stringResource(R.string.group_n, n, members.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp)
                        )
                    }
                    items(members, key = { it.path }) { item ->
                        FileRow(item, selected[item.path] == true, { toggle(selected, item) }) { open(item, ctx) { preview = it } }
                    }
                }
            } else {
                items(visible, key = { it.path }) { item ->
                    FileRow(item, selected[item.path] == true, { toggle(selected, item) }) { open(item, ctx) { preview = it } }
                    HorizontalDivider(Modifier.padding(start = 84.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    preview?.let { p ->
        Dialog(onDismissRequest = { preview = null }) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    AsyncImage(
                        model = File(p.path),
                        contentDescription = p.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${ctx.sizeText(p.size)} • ${dateText(p.modified)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { openFile(ctx, p.path) }) { Text(stringResource(R.string.open_with)) }
                        TextButton(onClick = { preview = null }) { Text(stringResource(R.string.close)) }
                    }
                }
            }
        }
    }

    if (confirm) {
        var permanent by remember { mutableStateOf(!vm.prefs.useTrash) }
        val keepCount = chosen.count { it.keep }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.confirm_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.confirm_delete_body, chosen.size, ctx.sizeText(chosenSize)))
                    if (keepCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.warn_keep_selected),
                            color = BadColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    PermanentToggle(permanent) { permanent = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val list = chosen
                    list.forEach { selected.remove(it.path) }
                    vm.delete(list, permanent)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

private fun toggle(selected: MutableMap<String, Boolean>, item: FileItem) {
    if (selected[item.path] == true) selected.remove(item.path) else selected[item.path] = true
}

private fun open(item: FileItem, ctx: android.content.Context, showPreview: (FileItem) -> Unit) {
    if (item.isImage) showPreview(item) else if (!item.isDir) openFile(ctx, item.path)
}

@Composable
private fun FileRow(item: FileItem, checked: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onOpen() },
            contentAlignment = Alignment.Center
        ) {
            if (item.isImage || item.isVideo) {
                AsyncImage(
                    model = File(item.path),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (item.isVideo) {
                    Icon(Icons.Filled.PlayCircle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Icon(
                    when {
                        item.isDir -> Icons.Filled.Folder
                        item.name.endsWith(".apk", true) -> Icons.Filled.Android
                        item.name.substringAfterLast('.').lowercase() in setOf("mp3", "m4a", "aac", "opus", "ogg", "wav", "amr") -> Icons.Filled.AudioFile
                        item.name.substringAfterLast('.').lowercase() in setOf("pdf", "doc", "docx", "txt", "xls", "xlsx") -> Icons.Filled.Description
                        else -> Icons.Filled.InsertDriveFile
                    },
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val reason = if (item.reason != 0) stringResource(item.reason, item.reasonArg) else item.parent.substringAfterLast('/')
            Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    (if (item.isDir && item.size == 0L) "" else ctx.sizeText(item.size) + " • ") + dateText(item.modified),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when {
                    item.keep -> Badge(stringResource(R.string.tag_keep), GoodColor)
                    item.suggested -> Badge(stringResource(R.string.tag_suggested), BadColor)
                    else -> Badge(stringResource(R.string.tag_review), WarnColor)
                }
            }
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

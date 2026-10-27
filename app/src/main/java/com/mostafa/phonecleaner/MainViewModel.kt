package com.mostafa.phonecleaner

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mostafa.phonecleaner.apps.AppAnalyzer
import com.mostafa.phonecleaner.apps.AppInfo
import com.mostafa.phonecleaner.device.DeviceHealth
import com.mostafa.phonecleaner.device.HealthItem
import com.mostafa.phonecleaner.device.StorageInfo
import com.mostafa.phonecleaner.scan.Category
import com.mostafa.phonecleaner.scan.FileItem
import com.mostafa.phonecleaner.scan.ScanPhase
import com.mostafa.phonecleaner.scan.ScanProgress
import com.mostafa.phonecleaner.scan.StorageScanner
import com.mostafa.phonecleaner.trash.TrashEntry
import com.mostafa.phonecleaner.trash.TrashManager
import com.mostafa.phonecleaner.util.Perms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-shot message for the snackbar. */
data class UiMessage(val text: Int, val arg: Long = 0, val id: Long = System.nanoTime())

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    private val trash = TrashManager(app)

    var hasAllFiles by mutableStateOf(false)
        private set
    var hasUsage by mutableStateOf(false)
        private set

    val progress = MutableStateFlow<ScanProgress?>(null)
    var scanning by mutableStateOf(false)
        private set
    var scanned by mutableStateOf(false)
        private set
    var results by mutableStateOf<Map<Category, List<FileItem>>>(emptyMap())
        private set

    var apps by mutableStateOf<List<AppInfo>>(emptyList())
        private set
    var appsLoading by mutableStateOf(false)
        private set
    var appsLoaded by mutableStateOf(false)
        private set

    var health by mutableStateOf<List<HealthItem>>(emptyList())
        private set
    var storage by mutableStateOf(StorageInfo(0, 0))
        private set
    var trashItems by mutableStateOf<List<TrashEntry>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<UiMessage?>(null)

    private var scanJob: Job? = null

    init {
        refreshPermissions()
        refreshStorage()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                trash.purge(prefs.trashDays)
            } catch (_: Exception) {
            }
            loadTrashNow()
        }
    }

    fun refreshPermissions() {
        val ctx = getApplication<Application>()
        val hadUsage = hasUsage
        hasAllFiles = Perms.hasAllFiles()
        hasUsage = Perms.hasUsage(ctx)
        if (!hadUsage && hasUsage && appsLoaded) loadApps()
    }

    fun refreshStorage() {
        storage = DeviceHealth.storage()
    }

    fun startScan() {
        if (scanning || !hasAllFiles) return
        scanJob = viewModelScope.launch {
            scanning = true
            try {
                val r = StorageScanner(getApplication(), prefs).scan { progress.value = it }
                results = r
                scanned = true
                progress.value = ScanProgress(ScanPhase.APPS, 0, 0)
                apps = withContext(Dispatchers.IO) { AppAnalyzer.load(getApplication()) }
                appsLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = UiMessage(R.string.scan_failed)
            } finally {
                scanning = false
                progress.value = null
                refreshStorage()
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    fun loadApps() {
        if (appsLoading) return
        appsLoading = true
        viewModelScope.launch {
            try {
                apps = withContext(Dispatchers.IO) { AppAnalyzer.load(getApplication()) }
                appsLoaded = true
            } catch (_: Exception) {
            } finally {
                appsLoading = false
            }
        }
    }

    fun loadHealth() {
        viewModelScope.launch {
            health = withContext(Dispatchers.IO) {
                try {
                    DeviceHealth.read(getApplication())
                } catch (e: Exception) {
                    emptyList()
                }
            }
            refreshStorage()
        }
    }

    private suspend fun loadTrashNow() {
        val list = withContext(Dispatchers.IO) { trash.list() }
        trashItems = list
    }

    fun loadTrash() {
        viewModelScope.launch { loadTrashNow() }
    }

    /** Items the "quick clean" button removes: only pre-selected items from the safe categories. */
    fun quickCleanItems(): List<FileItem> =
        results.filterKeys { it.quickClean }.values.flatten().filter { it.suggested }.distinctBy { it.path }

    fun delete(items: List<FileItem>, permanent: Boolean) {
        if (items.isEmpty() || busy) return
        busy = true
        viewModelScope.launch {
            try {
                val unique = items.distinctBy { it.path }
                val removed = withContext(Dispatchers.IO) {
                    trash.moveToTrash(unique.map { it.path to it.size }, permanent)
                }
                val removedSet = removed.toHashSet()
                val removedDirs = unique.filter { it.isDir && it.path in removedSet }.map { it.path + "/" }
                fun gone(p: String) = p in removedSet || removedDirs.any { p.startsWith(it) }

                results = results.mapValues { (cat, list) ->
                    val left = list.filterNot { gone(it.path) }
                    if (cat.grouped) {
                        // a group with only one file left is no longer a duplicate
                        val counts = left.groupingBy { it.group }.eachCount()
                        left.filter { (counts[it.group] ?: 0) > 1 }
                    } else left
                }
                val bytes = unique.filter { it.path in removedSet }.sumOf { it.size }
                message = if (permanent) UiMessage(R.string.msg_deleted, bytes) else UiMessage(R.string.msg_trashed, bytes)
                refreshStorage()
                loadTrashNow()
            } finally {
                busy = false
            }
        }
    }

    fun restore(ids: Set<String>) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { trash.restore(ids) }
            message = UiMessage(R.string.msg_restored, n.toLong())
            loadTrashNow()
            refreshStorage()
        }
    }

    fun deleteForever(ids: Set<String>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { trash.deleteForever(ids) }
            loadTrashNow()
            refreshStorage()
        }
    }

    fun setTrashDays(days: Int) {
        prefs.trashDays = days
        viewModelScope.launch {
            withContext(Dispatchers.IO) { trash.purge(days) }
            loadTrashNow()
        }
    }
}

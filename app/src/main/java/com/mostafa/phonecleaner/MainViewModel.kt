package com.mostafa.phonecleaner

import android.app.Application
import android.os.SystemClock
import com.mostafa.phonecleaner.notify.Notifier
import com.mostafa.phonecleaner.notify.Reminder
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
import com.mostafa.phonecleaner.scan.ResultStore
import com.mostafa.phonecleaner.scan.StorageScanner
import com.mostafa.phonecleaner.scan.quickCleanItems
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

    /** Time of the scan whose results are shown (0 = never scanned). */
    var lastScanTime by mutableStateOf(0L)
        private set

    /** Set when a scan finishes: the UI opens the report and resets it. */
    var reportRequest by mutableStateOf(false)

    private val resultStore = ResultStore(app)
    private var scanJob: Job? = null

    init {
        refreshPermissions()
        refreshStorage()
        Notifier.createChannels(app)
        Reminder.schedule(app)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (n, bytes) = trash.purge(prefs.trashDays)
                Notifier.purged(app, n, bytes)
            } catch (_: Exception) {
            }
            loadTrashNow()
        }
        // show the last report right away, even after the app was closed
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { resultStore.load() }
            if (saved != null && !scanned) {
                results = saved.first
                lastScanTime = saved.second
                scanned = true
            }
        }
        Notifier.lowStorage(app, DeviceHealth.storage())
    }

    private val ctx: Application get() = getApplication()

    @Volatile
    private var lastProgressNotif = 0L

    private fun onProgress(p: ScanProgress) {
        progress.value = p
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressNotif < 800) return
        lastProgressNotif = now
        val text = when (p.phase) {
            ScanPhase.FILES -> ctx.getString(R.string.phase_files, p.done)
            ScanPhase.DUPLICATES -> ctx.getString(R.string.phase_duplicates)
            ScanPhase.IMAGES -> ctx.getString(R.string.phase_images, p.done, p.total)
            ScanPhase.APPS -> ctx.getString(R.string.phase_apps)
        }
        Notifier.scanProgress(ctx, text, p.done, p.total)
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
            lastProgressNotif = 0L
            onProgress(ScanProgress(ScanPhase.FILES, 0, 0))
            try {
                val r = StorageScanner(ctx, prefs).scan(onPartial = { partial ->
                    viewModelScope.launch(Dispatchers.Main) {
                        results = partial
                        scanned = true
                        lastScanTime = System.currentTimeMillis()
                    }
                    // keep the partial report even if the app is closed before the scan ends
                    resultStore.save(partial, System.currentTimeMillis())
                }) { onProgress(it) }
                results = r
                scanned = true
                lastScanTime = System.currentTimeMillis()
                lastProgressNotif = 0L
                onProgress(ScanProgress(ScanPhase.APPS, 0, 0))
                try {
                    apps = withContext(Dispatchers.IO) { AppAnalyzer.load(ctx) }
                    appsLoaded = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                refreshStorage()
                val time = lastScanTime
                withContext(Dispatchers.IO) { resultStore.save(r, time) }
                Notifier.scanDone(ctx, r, storage)
                Notifier.lowStorage(ctx, storage)
                reportRequest = true
            } catch (e: CancellationException) {
                Notifier.cancel(ctx, Notifier.ID_SCAN)
                throw e
            } catch (e: Exception) {
                message = UiMessage(R.string.scan_failed)
                Notifier.scanFailed(ctx)
                if (results.isNotEmpty()) reportRequest = true
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
    fun quickCleanItems(): List<FileItem> = results.quickCleanItems()

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
                if (removed.isNotEmpty()) Notifier.cleaned(ctx, bytes, removed.size, permanent, prefs.trashDays)
                val snapshot = results
                val time = lastScanTime
                withContext(Dispatchers.IO) { resultStore.save(snapshot, time) }
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
            if (n > 0) Notifier.restored(ctx, n)
            loadTrashNow()
            refreshStorage()
        }
    }

    fun deleteForever(ids: Set<String>) {
        viewModelScope.launch {
            val (n, bytes) = withContext(Dispatchers.IO) { trash.deleteForever(ids) }
            if (n > 0) Notifier.trashEmptied(ctx, n, bytes)
            loadTrashNow()
            refreshStorage()
        }
    }

    fun setTrashDays(days: Int) {
        prefs.trashDays = days
        viewModelScope.launch {
            val (n, bytes) = withContext(Dispatchers.IO) { trash.purge(days) }
            Notifier.purged(ctx, n, bytes)
            loadTrashNow()
        }
    }
}

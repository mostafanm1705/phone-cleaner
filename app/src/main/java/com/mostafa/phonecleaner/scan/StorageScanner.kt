package com.mostafa.phonecleaner.scan

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import com.mostafa.phonecleaner.Prefs
import com.mostafa.phonecleaner.R
import com.mostafa.phonecleaner.trash.TrashManager
import com.mostafa.phonecleaner.util.DAY_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class StorageScanner(private val ctx: Context, private val prefs: Prefs) {

    private class Entry(val path: String, val size: Long, val modified: Long) {
        val lower: String = path.lowercase()
        val name: String = path.substringAfterLast('/')
        val ext: String = name.substringAfterLast('.', "").lowercase()
    }

    private val root: File = Environment.getExternalStorageDirectory()
    private val rootPath: String = root.absolutePath
    private val androidLower = "${rootPath.lowercase()}/android/"
    private val skipDirs = listOf(
        "$rootPath/Android/data",
        "$rootPath/Android/obb",
        TrashManager.trashDir().absolutePath,
    ).map { it.lowercase() }

    /**
     * @param light skip duplicate hashing and photo analysis (used by the nightly background check)
     */
    suspend fun scan(light: Boolean = false, onProgress: (ScanProgress) -> Unit): Map<Category, List<FileItem>> =
        withContext(Dispatchers.IO) {
            val out = HashMap<Category, MutableList<FileItem>>()
            Category.entries.forEach { out[it] = mutableListOf() }
            fun add(c: Category, item: FileItem) = out.getValue(c).add(item)

            // ---------- 1. walk the whole storage ----------
            val files = ArrayList<Entry>(32_000)
            val dirs = ArrayList<String>(4_000)
            val stack = ArrayDeque<File>()
            stack.add(root)
            var count = 0
            while (stack.isNotEmpty()) {
                ensureActive()
                val dir = stack.removeLast()
                val children = dir.listFiles() ?: continue
                for (c in children) {
                    val p = c.absolutePath
                    if (c.isDirectory) {
                        val pl = p.lowercase()
                        if (skipDirs.any { pl == it || pl.startsWith("$it/") }) continue
                        if (java.nio.file.Files.isSymbolicLink(c.toPath())) continue
                        dirs.add(p)
                        stack.add(c)
                    } else {
                        files.add(Entry(p, c.length(), c.lastModified()))
                        count++
                        if (count % 250 == 0) onProgress(ScanProgress(ScanPhase.FILES, count, 0, p))
                    }
                }
            }
            onProgress(ScanProgress(ScanPhase.FILES, count, 0, ""))

            // ---------- 2. classify each file ----------
            val pm = ctx.packageManager
            val now = System.currentTimeMillis()
            val largeBytes = prefs.largeFileMb.toLong() * 1024 * 1024
            val screenshotAge = prefs.screenshotDays * DAY_MS
            val excluded = HashSet<String>() // junk / apk: not candidates for duplicate / photo checks
            val images = ArrayList<Entry>()

            for (e in files) {
                if (e.name == ".nomedia") {
                    excluded.add(e.path); continue
                }
                val inAndroid = e.lower.startsWith(androidLower)
                val isImage = e.ext in IMAGE_EXT
                val isVideo = e.ext in VIDEO_EXT

                val junkReason = when {
                    e.lower.contains("/.thumbnails/") -> R.string.reason_thumbnails
                    e.name.startsWith(".thumbdata") -> R.string.reason_thumbnails
                    (e.ext == "tmp" || e.ext == "temp" || e.ext == "part" || e.ext == "crdownload") &&
                        now - e.modified > DAY_MS -> R.string.reason_temp
                    e.ext == "log" && !inAndroid -> R.string.reason_log
                    e.size == 0L && !inAndroid -> R.string.reason_empty_file
                    !inAndroid && (e.lower.contains("/cache/") || e.lower.contains("/.cache/")) -> R.string.reason_cache
                    else -> 0
                }
                if (junkReason != 0) {
                    add(Category.JUNK, FileItem(e.path, e.size, e.modified, suggested = true, reason = junkReason))
                    excluded.add(e.path)
                    continue
                }

                if (e.ext == "apk") {
                    add(Category.APKS, apkItem(pm, e))
                    excluded.add(e.path)
                    continue
                }

                if (e.size >= largeBytes) {
                    add(
                        Category.LARGE,
                        FileItem(e.path, e.size, e.modified, isImage = isImage, isVideo = isVideo, detail = typeLabel(e.ext))
                    )
                }

                val social = socialApp(e)
                if (social != null && !isChatBackup(e)) {
                    add(
                        Category.SOCIAL,
                        FileItem(
                            e.path, e.size, e.modified, suggested = true,
                            detail = social, kind = socialKind(e),
                            isImage = isImage, isVideo = isVideo
                        )
                    )
                }

                val isScreenshot = e.lower.contains("/screenshots/") || e.lower.contains("/screen recordings/")
                if (isScreenshot && (isImage || isVideo) && now - e.modified > screenshotAge) {
                    add(
                        Category.SCREENSHOTS,
                        FileItem(e.path, e.size, e.modified, isImage = isImage, isVideo = isVideo)
                    )
                }

                // photos worth analyzing for blur / resolution / similarity
                if (isImage && e.ext != "gif" && e.size >= 20 * 1024 && !isScreenshot &&
                    !e.lower.contains("/.") && !e.lower.contains("/stickers/") &&
                    (!inAndroid || e.lower.contains("whatsapp images") || e.lower.contains("whatsapp business images"))
                ) {
                    images.add(e)
                }
            }

            // ---------- 3. empty folders ----------
            val nonEmpty = HashSet<String>()
            for (e in files) {
                var p = e.path.substringBeforeLast('/')
                while (p.length > rootPath.length && nonEmpty.add(p)) p = p.substringBeforeLast('/')
            }
            val protectedDirs = listOf(
                "DCIM", "Download", "Pictures", "Music", "Movies", "Documents", "Alarms",
                "Notifications", "Podcasts", "Ringtones", "Audiobooks", "Recordings", "Android"
            ).map { "$rootPath/$it".lowercase() }.toSet()
            val emptyDirs = dirs.filter {
                it !in nonEmpty && it.lowercase() !in protectedDirs && !it.lowercase().startsWith(androidLower)
            }
            val emptySet = emptyDirs.toHashSet()
            for (d in emptyDirs) {
                if (d.substringBeforeLast('/') in emptySet) continue // report only the top-most empty folder
                add(
                    Category.EMPTY_FOLDERS,
                    FileItem(d, 0, File(d).lastModified(), isDir = true, suggested = true, reason = R.string.reason_empty_folder)
                )
            }

            // ---------- 4. leftovers of uninstalled apps ----------
            File(root, "Android/media").listFiles()?.filter { it.isDirectory }?.forEach { d ->
                val pkg = d.name
                if (!pkg.contains('.')) return@forEach
                val installed = try {
                    pm.getPackageInfo(pkg, 0); true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
                if (!installed) {
                    val prefix = d.absolutePath + "/"
                    val size = files.filter { it.path.startsWith(prefix) }.sumOf { it.size }
                    add(
                        Category.LEFTOVERS,
                        FileItem(
                            d.absolutePath, size, d.lastModified(), isDir = true,
                            suggested = size < 100L * 1024 * 1024,
                            reason = R.string.reason_leftover, reasonArg = pkg
                        )
                    )
                }
            }

            if (light) {
                out.getValue(Category.JUNK).sortByDescending { it.size }
                out.getValue(Category.SOCIAL).sortByDescending { it.size }
                return@withContext out
            }

            // ---------- 5. exact duplicates ----------
            onProgress(ScanProgress(ScanPhase.DUPLICATES, 0, 0))
            val dupPaths = HashSet<String>()
            val sameSize = files.asSequence()
                .filter { it.size >= 4096 && it.path !in excluded }
                .groupBy { it.size }
                .values.filter { it.size > 1 }
            var groupId = 0
            sameSize.forEachIndexed { idx, group ->
                ensureActive()
                if (idx % 20 == 0) onProgress(ScanProgress(ScanPhase.DUPLICATES, idx, sameSize.size))
                val quick = group.groupBy { quickHash(it.path, it.size) }
                for ((qh, q) in quick) {
                    if (qh == null || q.size < 2) continue
                    val full = if (q.first().size <= 32_768) mapOf(qh to q) else q.groupBy { fullHash(it.path) }
                    for ((fh, same) in full) {
                        if (fh == null || same.size < 2) continue
                        val sorted = same.sortedWith(compareBy<Entry>({ keepRank(it) }, { it.modified }))
                        val keep = sorted.first()
                        sorted.forEachIndexed { i, e ->
                            add(
                                Category.DUPLICATES,
                                FileItem(
                                    e.path, e.size, e.modified,
                                    suggested = i > 0, keep = i == 0, group = groupId,
                                    reason = if (i == 0) R.string.reason_dup_keep else R.string.reason_dup_copy,
                                    reasonArg = keep.name,
                                    isImage = e.ext in IMAGE_EXT, isVideo = e.ext in VIDEO_EXT
                                )
                            )
                            dupPaths.add(e.path)
                        }
                        groupId++
                    }
                }
            }

            // ---------- 6. photo analysis ----------
            val cache = ImageCache(ctx)
            cache.load()
            val stats = ConcurrentHashMap<String, ImgStats>()
            val done = AtomicInteger()
            val total = images.size
            onProgress(ScanProgress(ScanPhase.IMAGES, 0, total))
            if (images.isNotEmpty()) {
                val chunkSize = (images.size + 3) / 4
                coroutineScope {
                    images.chunked(chunkSize).map { chunk ->
                        async(Dispatchers.Default) {
                            for (e in chunk) {
                                ensureActive()
                                val s = cache.get(e.path, e.modified, e.size)
                                    ?: ImageAnalyzer.analyze(e.path)?.also { cache.put(e.path, e.modified, e.size, it) }
                                if (s != null) stats[e.path] = s
                                val c = done.incrementAndGet()
                                if (c % 10 == 0) onProgress(ScanProgress(ScanPhase.IMAGES, c, total, e.path))
                            }
                        }
                    }.awaitAll()
                }
            }
            cache.save(images.map { it.path }.toHashSet())

            val analyzed = images.filter { stats.containsKey(it.path) }
            for (e in analyzed) {
                val s = stats.getValue(e.path)
                val pixels = s.w.toLong() * s.h
                if (pixels in 1 until 300_000) {
                    add(
                        Category.LOW_RES,
                        FileItem(
                            e.path, e.size, e.modified, isImage = true,
                            reason = R.string.reason_lowres, reasonArg = "${s.w}×${s.h}"
                        )
                    )
                } else if (s.sharpness < 60.0) {
                    val very = s.sharpness < 15.0
                    add(
                        Category.BLURRY,
                        FileItem(
                            e.path, e.size, e.modified, isImage = true, suggested = very,
                            reason = if (very) R.string.reason_very_blurry else R.string.reason_blurry,
                            reasonArg = s.sharpness.toInt().toString()
                        )
                    )
                }
            }
            out.getValue(Category.BLURRY).sortBy { it.reasonArg.toIntOrNull() ?: 0 }

            // ---------- 7. similar photos ----------
            val simList = analyzed.filter {
                it.path !in dupPaths && stats.getValue(it.path).let { s -> s.w.toLong() * s.h >= 300_000 }
            }.sortedBy { it.modified }
            val parent = IntArray(simList.size) { it }
            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) r = parent[r]
                var c = x
                while (parent[c] != r) {
                    val n = parent[c]; parent[c] = r; c = n
                }
                return r
            }
            fun union(a: Int, b: Int) {
                val ra = find(a); val rb = find(b)
                if (ra != rb) parent[rb] = ra
            }
            val hashes = LongArray(simList.size) { stats.getValue(simList[it].path).hash }
            for (i in simList.indices) {
                if (i % 200 == 0) ensureActive()
                for (j in i + 1 until simList.size) {
                    val dt = simList[j].modified - simList[i].modified
                    val d = ImageAnalyzer.distance(hashes[i], hashes[j])
                    // burst shots (taken within 2 minutes) may differ a bit, others must be near-identical
                    if ((dt <= 120_000 && d <= 10) || d <= 3) union(i, j)
                }
            }
            val simGroups = simList.indices.groupBy { find(it) }.values.filter { it.size > 1 }
            var simId = 0
            for (g in simGroups) {
                val members = g.map { simList[it] }
                val best = members.maxByOrNull { stats.getValue(it.path).sharpness } ?: continue
                val ordered = listOf(best) + members.filter { it !== best }
                ordered.forEachIndexed { i, e ->
                    add(
                        Category.SIMILAR,
                        FileItem(
                            e.path, e.size, e.modified, isImage = true,
                            suggested = i > 0, keep = i == 0, group = simId,
                            reason = if (i == 0) R.string.reason_similar_best else R.string.reason_similar,
                            reasonArg = best.name
                        )
                    )
                }
                simId++
            }

            // ---------- sorting ----------
            out.getValue(Category.JUNK).sortByDescending { it.size }
            out.getValue(Category.APKS).sortByDescending { it.size }
            out.getValue(Category.LARGE).sortByDescending { it.size }
            out.getValue(Category.SOCIAL).sortByDescending { it.size }
            out.getValue(Category.LEFTOVERS).sortByDescending { it.size }
            out.getValue(Category.LOW_RES).sortByDescending { it.size }
            out.getValue(Category.SCREENSHOTS).sortBy { it.modified }
            out.getValue(Category.EMPTY_FOLDERS).sortBy { it.path }
            out
        }

    private fun apkItem(pm: PackageManager, e: Entry): FileItem {
        val info = try {
            pm.getPackageArchiveInfo(e.path, 0)
        } catch (t: Throwable) {
            null
        }
        if (info == null) {
            return FileItem(e.path, e.size, e.modified, suggested = true, reason = R.string.reason_apk_broken)
        }
        val label = info.applicationInfo?.let { ai ->
            ai.sourceDir = e.path
            ai.publicSourceDir = e.path
            try {
                pm.getApplicationLabel(ai).toString()
            } catch (t: Throwable) {
                null
            }
        } ?: info.packageName
        val installed = try {
            pm.getPackageInfo(info.packageName, 0)
        } catch (t: Exception) {
            null
        }
        return when {
            installed != null && installed.longVersionCode >= info.longVersionCode ->
                FileItem(e.path, e.size, e.modified, suggested = true, reason = R.string.reason_apk_installed, reasonArg = label)
            installed != null ->
                FileItem(e.path, e.size, e.modified, reason = R.string.reason_apk_newer, reasonArg = label)
            else ->
                FileItem(e.path, e.size, e.modified, reason = R.string.reason_apk_not_installed, reasonArg = label)
        }
    }

    /** Lower = better copy to keep. */
    private fun keepRank(e: Entry): Int = when {
        e.lower.contains("/dcim/") -> 0
        e.lower.contains("/whatsapp") || e.lower.contains("/sent/") -> 3
        e.lower.contains("/download") -> 2
        else -> 1
    }

    /** Name of the social app a file belongs to, or null. */
    private fun socialApp(e: Entry): String? {
        if (!e.path.startsWith("$rootPath/")) return null
        val seg = e.path.substring(rootPath.length + 1).split('/')
        if (seg.size < 2) return null
        val s0 = seg[0].lowercase()
        if (s0 == "android") {
            // Android/media/<package>/... (needs at least one folder level inside the package folder)
            return if (seg.size >= 4 && seg[1].equals("media", ignoreCase = true)) SOCIAL_PACKAGES[seg[2]] else null
        }
        SOCIAL_FOLDERS[s0]?.let { return it } // old layout: /WhatsApp/..., /Telegram/...
        if (seg.size >= 3 && s0 in MEDIA_ROOTS) SOCIAL_FOLDERS[seg[1].lowercase()]?.let { return it }
        return null
    }

    /** Never offer chat backups / databases: deleting them can lose chat history. */
    private fun isChatBackup(e: Entry): Boolean =
        e.lower.contains("/databases/") || e.lower.contains("/backups/") ||
            e.name.startsWith("msgstore") || e.ext == "crypt14" || e.ext == "crypt15" || e.ext == "db"

    private fun socialKind(e: Entry): String = ctx.getString(
        when {
            e.lower.contains("/.statuses/") -> R.string.kind_statuses
            e.lower.contains("voice notes") || e.lower.contains("/voice/") -> R.string.kind_voice
            e.lower.contains("stickers") -> R.string.kind_stickers
            e.ext in IMAGE_EXT -> R.string.kind_images
            e.ext in VIDEO_EXT -> R.string.kind_videos
            e.ext in AUDIO_EXT -> R.string.kind_audio
            e.ext in DOC_EXT -> R.string.kind_documents
            else -> R.string.kind_other
        }
    )

    companion object {
        private val MEDIA_ROOTS = setOf("pictures", "movies", "dcim", "download", "documents", "music", "recordings")

        val SOCIAL_FOLDERS = mapOf(
            "whatsapp" to "WhatsApp",
            "whatsapp business" to "WhatsApp Business",
            "facebook" to "Facebook",
            "messenger" to "Messenger",
            "instagram" to "Instagram",
            "threads" to "Threads",
            "telegram" to "Telegram",
            "telegram x" to "Telegram X",
            "tiktok" to "TikTok",
            "snapchat" to "Snapchat",
            "twitter" to "X (Twitter)",
            "viber" to "Viber",
            "imo" to "imo",
            "signal" to "Signal",
            "pinterest" to "Pinterest",
            "reddit" to "Reddit",
            "likee" to "Likee",
            "discord" to "Discord",
        )

        val SOCIAL_PACKAGES = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.facebook.katana" to "Facebook",
            "com.facebook.lite" to "Facebook",
            "com.facebook.orca" to "Messenger",
            "com.facebook.mlite" to "Messenger",
            "com.instagram.android" to "Instagram",
            "com.instagram.lite" to "Instagram",
            "com.instagram.barcelona" to "Threads",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram",
            "org.thunderdog.challegram" to "Telegram X",
            "org.telegram.plus" to "Telegram",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok",
            "com.zhiliaoapp.musically.go" to "TikTok",
            "com.snapchat.android" to "Snapchat",
            "com.twitter.android" to "X (Twitter)",
            "com.viber.voip" to "Viber",
            "com.imo.android.imoim" to "imo",
            "com.imo.android.imoimlite" to "imo",
            "org.thoughtcrime.securesms" to "Signal",
            "com.pinterest" to "Pinterest",
            "com.reddit.frontpage" to "Reddit",
            "video.like" to "Likee",
            "com.discord" to "Discord",
            "com.linkedin.android" to "LinkedIn",
            "jp.naver.line.android" to "LINE",
            "com.tencent.mm" to "WeChat",
        )
    }

    private fun typeLabel(ext: String): String = when (ext) {
        in IMAGE_EXT -> ctx.getString(R.string.type_image)
        in VIDEO_EXT -> ctx.getString(R.string.type_video)
        "mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr" -> ctx.getString(R.string.type_audio)
        "zip", "rar", "7z", "tar", "gz" -> ctx.getString(R.string.type_archive)
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt" -> ctx.getString(R.string.type_document)
        else -> ctx.getString(R.string.type_other)
    }

    private fun quickHash(path: String, size: Long): String? = try {
        RandomAccessFile(path, "r").use { raf ->
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(16_384)
            val n = raf.read(buf)
            if (n > 0) md.update(buf, 0, n)
            if (size > 32_768) {
                raf.seek(size - 16_384)
                val m = raf.read(buf)
                if (m > 0) md.update(buf, 0, m)
            }
            md.digest().toHex()
        }
    } catch (e: Exception) {
        null
    }

    private fun fullHash(path: String): String? = try {
        FileInputStream(path).use { input ->
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(65_536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            md.digest().toHex()
        }
    } catch (e: Exception) {
        null
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }
}

package com.mostafa.phonecleaner.scan

import android.content.Context
import java.io.File

/**
 * Keeps the last scan on disk so the report is still there after the app is closed
 * or opened from a notification. String resources are stored by name, not by id,
 * because ids can change between app versions.
 */
class ResultStore(private val ctx: Context) {
    private val file = File(ctx.filesDir, "last_scan_v1.tsv")

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    fun save(results: Map<Category, List<FileItem>>, time: Long) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                w.write("#time\t$time\n")
                for ((cat, list) in results) {
                    for (i in list) {
                        val reason = if (i.reason != 0) {
                            try {
                                ctx.resources.getResourceEntryName(i.reason)
                            } catch (_: Exception) {
                                ""
                            }
                        } else ""
                        w.write(
                            listOf(
                                cat.name, clean(i.path), i.size, i.modified, b(i.isDir), b(i.suggested), b(i.keep),
                                i.group, reason, clean(i.reasonArg), clean(i.detail), clean(i.kind), b(i.isImage), b(i.isVideo)
                            ).joinToString("\t")
                        )
                        w.write("\n")
                    }
                }
            }
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    private fun b(v: Boolean) = if (v) "1" else "0"

    /** Returns the saved results (only files that still exist) and the scan time, or null. */
    fun load(): Pair<Map<Category, List<FileItem>>, Long>? {
        if (!file.exists()) return null
        return try {
            var time = 0L
            val out = LinkedHashMap<Category, MutableList<FileItem>>()
            Category.entries.forEach { out[it] = mutableListOf() }
            val reasonIds = HashMap<String, Int>()
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 2 && p[0] == "#time") {
                    time = p[1].toLongOrNull() ?: 0L
                    return@forEachLine
                }
                if (p.size != 14) return@forEachLine
                val cat = try {
                    Category.valueOf(p[0])
                } catch (_: Exception) {
                    return@forEachLine
                }
                val reason = if (p[8].isEmpty()) 0 else reasonIds.getOrPut(p[8]) {
                    @Suppress("DiscouragedApi")
                    ctx.resources.getIdentifier(p[8], "string", ctx.packageName)
                }
                out.getValue(cat).add(
                    FileItem(
                        path = p[1],
                        size = p[2].toLongOrNull() ?: 0L,
                        modified = p[3].toLongOrNull() ?: 0L,
                        isDir = p[4] == "1",
                        suggested = p[5] == "1",
                        keep = p[6] == "1",
                        group = p[7].toIntOrNull() ?: -1,
                        reason = reason,
                        reasonArg = p[9],
                        detail = p[10],
                        kind = p[11],
                        isImage = p[12] == "1",
                        isVideo = p[13] == "1",
                    )
                )
            }
            // drop files deleted outside the app since the scan
            val existing = out.mapValues { (cat, list) ->
                val left = list.filter { File(it.path).exists() }
                if (cat.grouped) {
                    val counts = left.groupingBy { it.group }.eachCount()
                    left.filter { (counts[it.group] ?: 0) > 1 }
                } else left
            }
            existing to time
        } catch (_: Exception) {
            null
        }
    }
}

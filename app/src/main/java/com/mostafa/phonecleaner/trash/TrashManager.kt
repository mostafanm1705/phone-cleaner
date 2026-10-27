package com.mostafa.phonecleaner.trash

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

data class TrashEntry(
    val id: String,
    val original: String,
    val deletedAt: Long,
    val size: Long,
    val isDir: Boolean,
) {
    val name: String get() = original.substringAfterLast('/')
}

/**
 * Deleted files are moved to a hidden folder first, so a mistake can be undone.
 * They are removed for good after [days] days or when the user empties the trash.
 */
class TrashManager(private val ctx: Context) {

    companion object {
        fun trashDir(): File = File(Environment.getExternalStorageDirectory(), ".PhoneCleanerTrash")
    }

    private val dir = trashDir()
    private val index = File(dir, "index.tsv")
    private var counter = 0

    private fun ensureDir() {
        if (!dir.exists()) dir.mkdirs()
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) try {
            nomedia.createNewFile()
        } catch (_: Exception) {
        }
    }

    private fun read(): MutableList<TrashEntry> {
        if (!index.exists()) return mutableListOf()
        return try {
            index.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size != 5) null
                else TrashEntry(p[0], p[1], p[2].toLongOrNull() ?: 0, p[3].toLongOrNull() ?: 0, p[4] == "1")
            }.filter { File(dir, it.id).exists() }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun write(list: List<TrashEntry>) {
        ensureDir()
        try {
            index.writeText(list.joinToString("") {
                "${it.id}\t${it.original}\t${it.deletedAt}\t${it.size}\t${if (it.isDir) "1" else "0"}\n"
            })
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun list(): List<TrashEntry> = read().sortedByDescending { it.deletedAt }

    @Synchronized
    fun totalSize(): Long = read().sumOf { it.size }

    /** Returns the paths that were removed from their original place. */
    @Synchronized
    fun moveToTrash(items: List<Pair<String, Long>>, permanent: Boolean): List<String> {
        val done = ArrayList<String>()
        val scan = ArrayList<String>()
        val list = read()
        if (!permanent) ensureDir()
        for ((path, size) in items) {
            val src = File(path)
            if (!src.exists()) continue
            val isDir = src.isDirectory
            if (isDir) collectFiles(src, scan) else scan.add(path)
            val ok = if (permanent || (isDir && size == 0L)) {
                if (isDir) src.deleteRecursively() else src.delete()
            } else {
                val id = "${System.currentTimeMillis()}_${counter++}"
                val dst = File(dir, id)
                val moved = src.renameTo(dst) || try {
                    src.copyRecursively(dst, overwrite = true) && src.deleteRecursively()
                } catch (_: Exception) {
                    false
                }
                if (moved) list.add(TrashEntry(id, path, System.currentTimeMillis(), size, isDir))
                moved
            }
            if (ok) done.add(path)
        }
        write(list)
        notifyMedia(scan)
        return done
    }

    @Synchronized
    fun restore(ids: Set<String>): Int {
        val list = read()
        var n = 0
        val scan = ArrayList<String>()
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val e = iterator.next()
            if (e.id !in ids) continue
            val src = File(dir, e.id)
            var dst = File(e.original)
            if (dst.exists()) {
                val base = dst.nameWithoutExtension
                val ext = if (dst.extension.isEmpty()) "" else ".${dst.extension}"
                var i = 1
                while (dst.exists()) {
                    dst = File(dst.parentFile, "$base ($i)$ext"); i++
                }
            }
            dst.parentFile?.mkdirs()
            val ok = src.renameTo(dst) || try {
                src.copyRecursively(dst, overwrite = false) && src.deleteRecursively()
            } catch (_: Exception) {
                false
            }
            if (ok) {
                iterator.remove()
                n++
                if (dst.isDirectory) collectFiles(dst, scan) else scan.add(dst.absolutePath)
            }
        }
        write(list)
        notifyMedia(scan)
        return n
    }

    /** Returns (number of items, bytes freed). */
    @Synchronized
    fun deleteForever(ids: Set<String>): Pair<Int, Long> {
        val list = read()
        var n = 0
        var bytes = 0L
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val e = iterator.next()
            if (e.id in ids) {
                if (File(dir, e.id).deleteRecursively()) {
                    n++
                    bytes += e.size
                }
                iterator.remove()
            }
        }
        write(list)
        return n to bytes
    }

    /** Removes items older than [days]. Returns (number of items, bytes freed). */
    @Synchronized
    fun purge(days: Int): Pair<Int, Long> {
        val limit = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val old = read().filter { it.deletedAt < limit }.map { it.id }.toSet()
        return if (old.isNotEmpty()) deleteForever(old) else 0 to 0L
    }

    private fun collectFiles(d: File, out: MutableList<String>) {
        if (out.size > 3000) return
        d.walkTopDown().filter { it.isFile }.take(3000).forEach { out.add(it.absolutePath) }
    }

    /** Lets the gallery and other apps know the files are gone (or back). */
    private fun notifyMedia(paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
        } catch (_: Exception) {
        }
    }
}

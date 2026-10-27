package com.mostafa.phonecleaner.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** w/h = real resolution, sharpness = variance of Laplacian on a 512px copy, hash = 64-bit dHash */
data class ImgStats(val w: Int, val h: Int, val sharpness: Double, val hash: Long)

object ImageAnalyzer {
    private const val WORK_SIDE = 512

    fun analyze(path: String): ImgStats? = try {
        analyzeInternal(path)
    } catch (t: Throwable) {
        null
    }

    private fun analyzeInternal(path: String): ImgStats? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        while (max(w, h) / (sample * 2) >= WORK_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        try {
            val scale = WORK_SIDE.toFloat() / max(bmp.width, bmp.height)
            val work = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(3),
                    (bmp.height * scale).toInt().coerceAtLeast(3),
                    true
                )
            } else bmp
            val sharp = laplacianVariance(work)
            val small = Bitmap.createScaledBitmap(work, 9, 8, true)
            val hash = dHash(small)
            if (small !== work) small.recycle()
            if (work !== bmp) work.recycle()
            return ImgStats(w, h, sharp, hash)
        } finally {
            bmp.recycle()
        }
    }

    private fun lum(c: Int): Int =
        ((c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114) / 1000

    private fun laplacianVariance(b: Bitmap): Double {
        val w = b.width
        val h = b.height
        if (w < 3 || h < 3) return 0.0
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val g = IntArray(w * h) { lum(px[it]) }
        var sum = 0.0
        var sq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val v = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]).toDouble()
                sum += v
                sq += v * v
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sq / n - mean * mean
    }

    private fun dHash(b: Bitmap): Long {
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (lum(b.getPixel(x, y)) > lum(b.getPixel(x + 1, y))) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}

/** Remembers analysis results between scans so a re-scan only analyzes new photos. */
class ImageCache(context: Context) {
    private val file = File(context.filesDir, "image_cache_v1.tsv")
    private val map = ConcurrentHashMap<String, Pair<String, ImgStats>>()

    private fun key(modified: Long, size: Long) = "$modified:$size"

    fun load() {
        map.clear()
        if (!file.exists()) return
        try {
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 7) {
                    val w = p[3].toIntOrNull()
                    val h = p[4].toIntOrNull()
                    val s = p[5].toDoubleOrNull()
                    val hash = p[6].toLongOrNull()
                    if (w != null && h != null && s != null && hash != null) {
                        map[p[0]] = key(p[1].toLong(), p[2].toLong()) to ImgStats(w, h, s, hash)
                    }
                }
            }
        } catch (_: Exception) {
            map.clear()
        }
    }

    fun get(path: String, modified: Long, size: Long): ImgStats? {
        val e = map[path] ?: return null
        return if (e.first == key(modified, size)) e.second else null
    }

    fun put(path: String, modified: Long, size: Long, s: ImgStats) {
        map[path] = key(modified, size) to s
    }

    fun save(keepPaths: Set<String>) {
        try {
            file.bufferedWriter().use { out ->
                for ((path, v) in map) {
                    if (path !in keepPaths || path.contains('\t') || path.contains('\n')) continue
                    val (k, s) = v
                    val (mod, size) = k.split(':')
                    out.write("$path\t$mod\t$size\t${s.w}\t${s.h}\t${s.sharpness}\t${s.hash}\n")
                }
            }
        } catch (_: Exception) {
        }
    }
}

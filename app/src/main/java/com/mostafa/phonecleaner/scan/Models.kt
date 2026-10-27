package com.mostafa.phonecleaner.scan

import androidx.annotation.StringRes
import com.mostafa.phonecleaner.R

/**
 * quickClean = category whose suggested items are safe to remove in one tap
 * (they are still shown to the user in a confirmation dialog first).
 */
enum class Category(
    @StringRes val title: Int,
    @StringRes val desc: Int,
    val quickClean: Boolean,
    val grouped: Boolean = false,
    /** false = items are marked as not needed but the user picks them one by one */
    val preselect: Boolean = true,
) {
    JUNK(R.string.cat_junk, R.string.cat_junk_desc, true),
    SOCIAL(R.string.cat_social, R.string.cat_social_desc, false, preselect = false),
    EMPTY_FOLDERS(R.string.cat_empty, R.string.cat_empty_desc, true),
    APKS(R.string.cat_apks, R.string.cat_apks_desc, true),
    LEFTOVERS(R.string.cat_leftovers, R.string.cat_leftovers_desc, true),
    DUPLICATES(R.string.cat_duplicates, R.string.cat_duplicates_desc, true, grouped = true),
    SIMILAR(R.string.cat_similar, R.string.cat_similar_desc, false, grouped = true),
    BLURRY(R.string.cat_blurry, R.string.cat_blurry_desc, false),
    LOW_RES(R.string.cat_lowres, R.string.cat_lowres_desc, false),
    SCREENSHOTS(R.string.cat_screenshots, R.string.cat_screenshots_desc, false),
    LARGE(R.string.cat_large, R.string.cat_large_desc, false),
}

data class FileItem(
    val path: String,
    val size: Long,
    val modified: Long,
    val isDir: Boolean = false,
    /** true = recommended to delete, pre-selected */
    val suggested: Boolean = false,
    /** true = the copy we recommend keeping inside a duplicate / similar group */
    val keep: Boolean = false,
    val group: Int = -1,
    @StringRes val reason: Int = 0,
    val reasonArg: String = "",
    /** free-form sub-category, used for filter chips (e.g. the social app name) */
    val detail: String = "",
    /** second filter level (e.g. images / videos / voice notes) */
    val kind: String = "",
    val isImage: Boolean = false,
    val isVideo: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')
    val parent: String get() = path.substringBeforeLast('/')
}

enum class ScanPhase { FILES, DUPLICATES, IMAGES, APPS }

data class ScanProgress(val phase: ScanPhase, val done: Int, val total: Int, val current: String = "")

/** Items the "quick clean" button removes: only pre-selected items from the safe categories. */
fun Map<Category, List<FileItem>>.quickCleanItems(): List<FileItem> =
    filterKeys { it.quickClean }.values.flatten().filter { it.suggested }.distinctBy { it.path }

val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr")
val DOC_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "zip", "rar", "7z")

val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif")
val VIDEO_EXT = setOf("mp4", "mkv", "3gp", "webm", "mov", "avi")

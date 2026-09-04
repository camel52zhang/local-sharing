package com.localsharing.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "FileUtil"

/** zip 解压防护：单压缩包允许的最大条目数（防 zip bomb 打爆文件数） */
private const val MAX_ZIP_ENTRIES = 10_000

/** zip 解压防护：单压缩包允许解压出的最大总字节数（2GB，防 zip bomb 打满存储） */
private const val MAX_ZIP_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

/** 解压拷贝缓冲区大小（字节） */
private const val COPY_BUFFER_SIZE = 8 * 1024

/** 人类可读大小 */
fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/** 取 Uri 的显示名（空安全：cursor 为 null / 取不到 / 取到空值均回退到 lastPathSegment 或 "file"） */
fun getDisplayName(context: Context, uri: Uri): String {
    return try {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                val v = c.getString(idx)
                if (!v.isNullOrBlank()) name = v
            }
        }
        name ?: uri.lastPathSegment ?: "file"
    } catch (e: Exception) {
        Log.w(TAG, "getDisplayName failed: ${e.message}")
        uri.lastPathSegment ?: "file"
    }
}

/** 取 Uri 的大小（未知返回 0） */
fun getSize(context: Context, uri: Uri): Long {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else 0L
        } ?: 0L
    } catch (e: Exception) {
        0L
    }
}

/**
 * 将一组 Uri 打包为 zip（用于“文件夹”发送）。
 * 每个条目使用其显示名作为 zip 内文件名。
 */
suspend fun zipUris(
    context: Context,
    uris: List<Uri>,
    destPath: String,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): Boolean = withContext(Dispatchers.IO) {
    try {
        val total = uris.size
        var done = 0
        ZipOutputStream(BufferedOutputStream(java.io.FileOutputStream(destPath))).use { zos ->
            for (uri in uris) {
                val name = getDisplayName(context, uri)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val entry = ZipEntry(name)
                    zos.putNextEntry(entry)
                    val bis = BufferedInputStream(input)
                    val buf = ByteArray(8192)
                    var read: Int
                    while (bis.read(buf).also { read = it } != -1) {
                        zos.write(buf, 0, read)
                    }
                    zos.closeEntry()
                }
                done++
                onProgress(done, total)
            }
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "zipUris failed: ${e.message}")
        false
    }
}

/**
 * 判断 zip 条目名是否可疑（绝对路径或含 `..` 路径段）。
 *
 * 只按路径段比较，因此文件名中本身含 `..`（如 `report..pdf`）不会被误判。
 *
 * @param name zip 条目名
 * @return true 表示可疑，应跳过该条目
 */
private fun isSuspiciousEntryName(name: String): Boolean {
    if (name.isEmpty()) return true
    // Unix 绝对路径 `/sdcard/evil.apk` 与 Windows 反斜杠前缀
    if (name.startsWith("/") || name.startsWith("\\")) return true
    // Windows 盘符绝对路径，如 `C:/evil.apk`
    if (name.length >= 2 && name[1] == ':') return true
    for (seg in name.split('/', '\\')) {
        if (seg == "..") return true
    }
    return false
}

/**
 * 校验解压目标是否落在基准目录之内，防 Zip Slip 路径穿越（CWE-22）。
 *
 * 采用 canonicalPath 前缀比较，可覆盖条目名混淆之外的软链接逃逸等情形。
 * 注意：Java 的 `File(File parent, String child)` 会把绝对路径条目拼接成
 * `parent + child`，因此绝对路径本身不会逃逸，但仍由 [isSuspiciousEntryName] 显式拒绝。
 *
 * @param base 基准目录（解压根目录）
 * @param target 待写入的目标文件/目录
 * @return true 表示目标位于 base 之内，允许写入
 */
internal fun isSafeChild(base: File, target: File): Boolean {
    return try {
        val canonicalBase = base.canonicalPath
        val canonicalTarget = target.canonicalPath
        canonicalTarget == canonicalBase ||
            canonicalTarget.startsWith(canonicalBase + File.separator)
    } catch (e: Exception) {
        Log.w(TAG, "canonical path resolve failed: ${e.message}")
        false
    }
}

/**
 * 拷贝 zip 当前条目到目标文件，并在写入过程中累计字节数。
 *
 * @param zis 已定位到目标条目的 ZipInputStream
 * @param target 目标文件
 * @param byteBudget 剩余可用字节预算；超出即中止
 * @return 实际写入字节数；返回 -1 表示超出预算，调用方应中止整个解压
 */
private fun copyCurrentEntry(zis: ZipInputStream, target: File, byteBudget: Long): Long {
    var written = 0L
    val buffer = ByteArray(COPY_BUFFER_SIZE)
    FileOutputStream(target).use { out ->
        while (true) {
            val read = zis.read(buffer)
            if (read == -1) break
            written += read
            if (written > byteBudget) return -1
            out.write(buffer, 0, read)
        }
    }
    return written
}

/**
 * 解压中止时清理本次已写出的内容，避免留下半截文件/目录。
 *
 * 仅删除本次解压实际创建的条目；outDir 为空目录时顺带移除，非空时
 * [File.delete] 会自然失败，不会误删既有数据。
 *
 * @param outDir 解压根目录
 * @param extracted 本次解压已创建的条目（按创建顺序）
 */
private fun cleanupExtracted(outDir: File, extracted: List<File>) {
    for (f in extracted.asReversed()) {
        try {
            if (f.exists() && !f.delete()) {
                Log.w(TAG, "cleanup: 删除失败 ${f.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanup failed: ${e.message}")
        }
    }
    try {
        outDir.delete()
    } catch (e: Exception) {
        Log.w(TAG, "cleanup outDir failed: ${e.message}")
    }
}

/**
 * 解压 zip 到目标目录（用于接收电脑推送的“文件夹”）。
 *
 * 安全约束：
 * 1. 每个条目写出前做 canonical 前缀校验，绝对/穿越条目直接跳过（防 Zip Slip，CWE-22）；
 * 2. 条目数上限 [MAX_ZIP_ENTRIES] 与解压总字节上限 [MAX_ZIP_TOTAL_BYTES]，
 *    超限立即中止并清理已解压内容（防 zip bomb）。
 *
 * @param zipFile 源 zip 文件
 * @param outDir 解压目标目录（条目只允许落在该目录内）
 * @return true 表示解压成功；false 表示解压失败或被安全策略中止
 */
suspend fun extractZip(zipFile: File, outDir: File): Boolean = withContext(Dispatchers.IO) {
    if (!outDir.exists()) outDir.mkdirs()
    val extracted = mutableListOf<File>()
    try {
        var entryCount = 0
        var totalBytes = 0L
        var limitExceeded = false
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES) {
                    Log.w(TAG, "zip 条目数超过上限 $MAX_ZIP_ENTRIES，中止解压")
                    limitExceeded = true
                    return@use
                }
                val target = File(outDir, entry.name)
                if (isSuspiciousEntryName(entry.name) || !isSafeChild(outDir, target)) {
                    Log.w(TAG, "拒绝可疑 zip 条目（路径穿越）: ${entry.name}")
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    extracted.add(target)
                } else {
                    target.parentFile?.mkdirs()
                    extracted.add(target)
                    val written = copyCurrentEntry(zis, target, MAX_ZIP_TOTAL_BYTES - totalBytes)
                    if (written < 0) {
                        Log.w(TAG, "解压总字节数超过上限 $MAX_ZIP_TOTAL_BYTES，中止解压")
                        limitExceeded = true
                        return@use
                    }
                    totalBytes += written
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (limitExceeded) {
            cleanupExtracted(outDir, extracted)
            return@withContext false
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "extractZip failed: ${e.message}")
        cleanupExtracted(outDir, extracted)
        false
    }
}

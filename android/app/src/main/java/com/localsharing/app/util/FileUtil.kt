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

/** 人类可读大小 */
fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/** 取 Uri 的显示名 */
fun getDisplayName(context: Context, uri: Uri): String {
    var name = "file"
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
    } catch (e: Exception) {
        Log.w(TAG, "getDisplayName failed: ${e.message}")
    }
    return name
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

/** 解压 zip 到目标目录（用于接收电脑推送的“文件夹”） */
suspend fun extractZip(zipFile: File, outDir: File): Boolean = withContext(Dispatchers.IO) {
    if (!outDir.exists()) outDir.mkdirs()
    try {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = File(outDir, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "extractZip failed: ${e.message}")
        false
    }
}

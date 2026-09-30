package com.localsharing.app.receiver

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.localsharing.app.ui.theme.LocalSharingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 电视接收模式主界面：与电脑端仪表盘同构的信息结构——
 * 左：二维码 + 连接地址 + 保存位置 + 操作按钮；
 * 右：已连接设备（含在线状态）+ 接收记录（含来源设备，APK 可一键安装）。
 * 为遥控器/D-pad 优化：可聚焦元素仅按钮类。
 */
class TvReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 电视接收期间保持屏幕常亮：否则系统息屏后前台服务可能被回收，
        // 且扫码界面本身需要长时间可见。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // API 33+ 前台服务通知需要通知权限（拒绝也不影响接收，仅通知不显示）
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            LocalSharingTheme {
                ReceiverScreen(onStop = { finish() })
            }
        }
        ReceiverService.start(this)
    }

    override fun onResume() {
        super.onResume()
        if (!ReceiverService.status.value.running) ReceiverService.start(this)
    }
}

@Composable
private fun ReceiverScreen(onStop: () -> Unit) {
    val context = LocalContext.current
    val status by ReceiverService.status.collectAsState()
    val online by DevicePresence.online.collectAsState()

    var received by remember { mutableStateOf(ReceiverStore.loadReceived(context)) }
    var devices by remember { mutableStateOf(ReceiverStore.loadDevices(context)) }
    var savePath by remember { mutableStateOf(ReceiverStore.savePathLabel(context)) }
    var showSettings by remember { mutableStateOf(false) }
    var lastSeenId by remember { mutableStateOf<String?>(null) }

    // 事件驱动刷新：服务端每次写入（新文件/新设备）都会自增 revision，UI 订阅它按需重载；
    // 读盘放在 IO 线程，避免在电视这种弱 CPU 上每 2 秒阻塞主线程。
    val revision by ReceiverStore.revision.collectAsState()
    LaunchedEffect(revision) {
        val (r, d) = withContext(Dispatchers.IO) {
            ReceiverStore.loadReceived(context) to ReceiverStore.loadDevices(context)
        }
        val newest = r.firstOrNull()
        // 仅在上次已有基线且首条变化时提示，避免首屏误报
        if (newest != null && lastSeenId != null && newest.id != lastSeenId) {
            Toast.makeText(context, "已接收：${newest.name}", Toast.LENGTH_SHORT).show()
        }
        lastSeenId = newest?.id
        received = r
        devices = d
    }

    MaterialTheme {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101418))
                .padding(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ---------- 左：二维码 + 保存位置 + 操作 ----------
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(360.dp),
            ) {
                Text("手机扫码，直传电视", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                val url = if (status.running) "http://${status.lanIp}:${status.port}" else ""
                if (url.isNotEmpty()) {
                    QrImage(url, 270.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(url, color = Color(0xFF9AE6B4), fontSize = 20.sp)
                } else {
                    Text("服务启动中…", color = Color(0xFFFFB74D), fontSize = 20.sp)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "保存位置：$savePath",
                    color = Color(0xFF8899A6),
                    fontSize = 13.sp,
                    maxLines = 2,
                )
                Spacer(Modifier.height(14.dp))
                Row {
                    Button(onClick = { showSettings = true }) { Text("保存位置") }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = {
                        ReceiverService.stop(context)
                        onStop()
                    }) { Text("停止接收", color = Color.White) }
                }
            }

            Spacer(Modifier.width(28.dp))

            // ---------- 右：已连接设备 + 接收记录 ----------
            Column(modifier = Modifier.fillMaxSize()) {
                Text("已连接设备", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                DeviceList(devices, online)
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("接收记录", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Text("共 ${received.size} 项", color = Color(0xFF8899A6), fontSize = 13.sp)
                    Spacer(Modifier.width(16.dp))
                    if (received.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            ReceiverStore.clearReceived(context)
                            received = emptyList()
                        }) { Text("清空记录", color = Color.White) }
                    }
                }
                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(received, key = { it.id }) { item ->
                        ReceivedRow(
                            item = item,
                            fromName = ReceiverStore.displayName(context, item.fromDevice),
                            onOpen = {
                                if (item.isApk) installApk(context, item)
                                else openReceived(context, item)
                            },
                            onDelete = {
                                ReceiverStore.deleteReceived(context, item.id)
                                received = ReceiverStore.loadReceived(context)
                            },
                        )
                    }
                    if (received.isEmpty()) {
                        item {
                            Text("暂无文件 · 手机扫码后即可推送", color = Color(0xFF8899A6), fontSize = 16.sp)
                        }
                    }
                }
            }
        }

        if (showSettings) {
            SaveLocationDialog(
                initial = ReceiverStore.getSaveSubdir(context),
                onDismiss = { showSettings = false },
                onConfirm = { sub ->
                    ReceiverStore.setSaveSubdir(context, sub)
                    savePath = ReceiverStore.savePathLabel(context)
                    showSettings = false
                },
            )
        }
    }
}

@Composable
private fun DeviceList(devices: List<ReceiverDevice>, online: Set<String>) {
    if (devices.isEmpty()) {
        Text("暂无设备连接", color = Color(0xFF8899A6), fontSize = 15.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        devices.takeLast(6).reversed().forEach { d ->
            val isOnline = d.clientId in online
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isOnline) "●" else "○",
                    color = if (isOnline) Color(0xFF68D391) else Color(0xFF5A6672),
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    d.name,
                    color = if (isOnline) Color.White else Color(0xFF8899A6),
                    fontSize = 16.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isOnline) "在线" else "离线",
                    color = if (isOnline) Color(0xFF68D391) else Color(0xFF5A6672),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun SaveLocationDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存位置") },
        text = {
            Column {
                Text(
                    "文件保存在「下载/local-sharing」下。可填写子目录名（留空则直接用根目录）。",
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("子目录，如 电影 / tv") },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "最终位置：下载/local-sharing/" + text.trim().trim('/'),
                    color = Color(0xFF5A6672),
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ReceivedRow(
    item: ReceivedItem,
    fromName: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1B2229))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, color = Color.White, fontSize = 16.sp, maxLines = 1)
            Text(
                "来自 $fromName · ${formatSize(item.size)} · " +
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.time)),
                color = Color(0xFF8899A6),
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (item.isApk) {
            Button(onClick = onOpen) { Text("安装") }
        } else {
            OutlinedButton(onClick = onOpen) { Text("打开", color = Color.White) }
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onDelete) { Text("删除", color = Color(0xFF8899A6)) }
    }
}

@Composable
private fun QrImage(content: String, size: Dp) {
    val bitmap = remember(content) {
        val side = 600
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, side, side,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        // 批量写入像素（setPixels）比逐像素 setPixel 快一个数量级，
        // 电视端 CPU 较弱，避免首屏二维码渲染卡顿。
        val pixels = IntArray(side * side)
        for (y in 0 until side) {
            val row = y * side
            for (x in 0 until side) {
                pixels[row + x] =
                    if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        val bmp = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.RGB_565)
        bmp.setPixels(pixels, 0, side, 0, 0, side, side)
        bmp.asImageBitmap()
    }
    Image(bitmap = bitmap, contentDescription = "连接二维码", modifier = Modifier.size(size))
}

private fun formatSize(bytes: Long): String = when {
    bytes >= (1L shl 30) -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= (1L shl 20) -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= (1L shl 10) -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 一键安装 APK：
 * 1) 先申请「安装未知应用」授权（TCL/小米首次都会拦）
 * 2) 把 APK 从 Downloads 复制到 cacheDir/installs，经 FileProvider 交给系统安装器
 */
private fun installApk(context: android.content.Context, item: ReceivedItem) {
    // 授权检查
    if (Build.VERSION.SDK_INT >= 26 &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package://${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return
    }
    try {
        val installsDir = File(context.cacheDir, "installs").apply { mkdirs() }
        val target = File(installsDir, item.name)
        // 从 Downloads（MediaStore 或公共目录）把 APK 读出来复制到缓存
        val copied = copyFromDownloads(context, item.name, target)
        if (!copied) return
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", target,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * 用系统播放器/查看器打开已接收的文件（图片、视频、音频、PDF 等）。
 * API 29+ 走 MediaStore 内容 URI；API 28- 经 FileProvider 共享公共 Downloads 下的文件。
 */
private fun openReceived(context: android.content.Context, item: ReceivedItem) {
    try {
        val mime = guessMime(item.name)
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = findDownloadsUri(context, item.name) ?: run {
                Toast.makeText(context, "文件已不在下载目录", Toast.LENGTH_SHORT).show()
                return
            }
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } else {
            val sub = ReceiverStore.getSaveSubdir(context)
            val dir = File(
                android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                if (sub.isEmpty()) ReceiverStore.SAVE_ROOT else "${ReceiverStore.SAVE_ROOT}/$sub",
            )
            val f = File(dir, item.name)
            if (!f.exists()) {
                Toast.makeText(context, "文件已不在下载目录", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开：${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/** 按显示名在公共 Downloads 中查 content URI */
private fun findDownloadsUri(context: android.content.Context, displayName: String): Uri? {
    context.contentResolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
        arrayOf(displayName),
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            return Uri.withAppendedPath(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0).toString(),
            )
        }
    }
    return null
}

private fun guessMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    return when (ext) {
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        else -> "*/*"
    }
}

/** 按显示名从公共 Downloads（含子目录）读取文件内容到 target；找不到返回 false */private fun copyFromDownloads(
    context: android.content.Context,
    displayName: String,
    target: File,
): Boolean {
    if (Build.VERSION.SDK_INT >= 29) {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(displayName),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                return true
            }
            return false
        }
        return false
    }
    val sub = ReceiverStore.getSaveSubdir(context)
    val dir = File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
        if (sub.isEmpty()) ReceiverStore.SAVE_ROOT else "${ReceiverStore.SAVE_ROOT}/$sub",
    )
    val source = File(dir, displayName)
    if (!source.exists()) return false
    source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
    return true
}

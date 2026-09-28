package com.localsharing.app.receiver

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 电视接收模式主界面：大屏二维码（手机扫码连入）+ 接收文件列表 + APK 一键安装。
 * 为遥控器/D-pad 优化：仅「停止接收」「安装」两类可聚焦元素。
 */
class TvReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var received by remember { mutableStateOf(ReceiverStore.loadReceived(context)) }

    // 周期刷新接收列表（新文件由服务落盘并持久化记录）
    LaunchedEffect(status.log) {
        received = ReceiverStore.loadReceived(context)
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            received = ReceiverStore.loadReceived(context)
        }
    }

    MaterialTheme {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101418))
                .padding(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：二维码 + 地址
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(360.dp),
            ) {
                Text("手机扫码，直传电视", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                val url = if (status.running) "http://${status.lanIp}:${status.port}" else ""
                if (url.isNotEmpty()) {
                    QrImage(url, 300.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(url, color = Color(0xFF9AE6B4), fontSize = 22.sp)
                } else {
                    Text("服务启动中…", color = Color(0xFFFFB74D), fontSize = 20.sp)
                }
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = {
                    ReceiverService.stop(context)
                    onStop()
                }) { Text("停止接收", color = Color.White) }
            }

            Spacer(Modifier.width(40.dp))

            // 右侧：接收文件列表
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("已接收文件", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(received, key = { it.id }) { item ->
                        ReceivedRow(item)
                    }
                    if (received.isEmpty()) {
                        item {
                            Text("暂无文件 · 手机扫码后即可推送", color = Color(0xFF8899A6), fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceivedRow(item: ReceivedItem) {
    val context = LocalContext.current
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
                "${formatSize(item.size)} · " +
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.time)),
                color = Color(0xFF8899A6),
                fontSize = 12.sp,
            )
        }
        if (item.isApk) {
            Spacer(Modifier.width(12.dp))
            Button(onClick = { installApk(context, item) }) { Text("安装") }
        }
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
        val bmp = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until side) for (y in 0 until side) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
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

/** 按显示名从公共 Downloads/local-sharing/ 读取文件内容到 target；找不到返回 false */
private fun copyFromDownloads(
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
    val dir = File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
        "local-sharing",
    )
    val source = File(dir, displayName)
    if (!source.exists()) return false
    source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
    return true
}

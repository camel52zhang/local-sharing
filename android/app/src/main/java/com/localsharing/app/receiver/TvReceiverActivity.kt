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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
    companion object {
        /**
         * 「在线」判据的兜底宽限期：DevicePresence 是纯内存 object，[ReceiverServer.stop] 会 clear()，
         * 电视服务被系统回收后手机侧的 WS 可能并未真正断开。此时用 lastSeen（每次注册由
         * upsertDevice 刷新）兜底，避免服务一重启就把所有设备误判为离线。
         */
        internal const val ONLINE_GRACE_MS = 120_000L

        /** API 28- 写公共存储的运行时权限请求码 */
        private const val REQ_WRITE_EXTERNAL = 2001
    }

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
        // API 23-28：写公共 Downloads 目录需要运行时授权（Android 6.0 引入运行时权限）。
        // 之前只声明未申请，API 28 实测 mkdirs 静默失败 → 上传全部 500。
        // 拒绝也没关系：ReceiverServer 会降级到应用私有外部目录，只是文件不在公共 Download 目录。
        //
        // 下界必须收在 23：checkSelfPermission/requestPermissions 都是 API 23 才有的方法
        // （Android 6.0 之前权限在安装时一次性授予，无需也不能运行时申请）。
        // 原来的条件写成 SDK_INT <= 28，在 Android 5.0(21) 上恰好为真 → NoSuchMethodError 闪退。
        if (Build.VERSION.SDK_INT in 23..28 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_WRITE_EXTERNAL,
            )
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
    var showLogs by remember { mutableStateOf(false) }
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

    // 设备名映射：displayName() 会读 SharedPreferences + 解析 JSON，
    // 若放进 items{} 的 item lambda 就是「每次重组 × 每行」重复读盘，
    // 这是电视上界面抖动的真正来源。提到这里随 devices 一起 memo。
    val nameById = remember(devices) { devices.associate { it.clientId to it.name } }

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
                // 无可用网卡时 lanIp 为空串，此时拼出的 "http://:8080" 是废二维码，
                // 手机扫码必然失败且电视端看不出异常，所以必须显式区分「没起来」和「没 IP」。
                val hasUrl = status.running && status.lanIp.isNotBlank()
                val url = if (hasUrl) "http://${status.lanIp}:${status.port}" else ""
                if (hasUrl) {
                    QrImage(url, 270.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(url, color = Color(0xFF9AE6B4), fontSize = 20.sp)
                } else if (status.running) {
                    Text("未连接网络，无法生成配对码", color = Color(0xFF8899A6), fontSize = 20.sp)
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
                    OutlinedButton(onClick = { showLogs = true }) { Text("日志", color = Color.White) }
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
                            fromName = nameById[item.fromDevice] ?: item.fromDevice.ifBlank { "未知设备" },
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

        // 服务端运行日志：电视上没有 logcat 可看，排障时可直接截图反馈
        if (showLogs) {
            AlertDialog(
                onDismissRequest = { showLogs = false },
                title = { Text("服务日志（最近 ${status.log.size} 条）") },
                text = {
                    if (status.log.isEmpty()) {
                        Text("暂无日志", fontSize = 13.sp)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                            items(status.log.reversed()) { line ->
                                // 深色文字：Material3 浅色主题的弹窗背景是白色，
                                // 用浅色字会完全看不见（排障时日志形同虚设）
                                Text(line, color = Color(0xFF1B2430), fontSize = 12.sp)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showLogs = false }) { Text("关闭") } },
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
            val isOnline = d.clientId in online ||
                (System.currentTimeMillis() - d.lastSeen) < TvReceiverActivity.ONLINE_GRACE_MS
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

/**
 * 保存位置对话框：应用内自建的目录树浏览器。
 *
 * 为什么不用系统文件选择器（ACTION_OPEN_DOCUMENT_TREE）：实测 AOSP TV 镜像里
 * com.android.documentsui 根本不存在，该 Intent 被 frameworkpackagestubs 的占位
 * activity 静默吸收（不崩溃、无UI、直接 RESULT_CANCELED），装第三方文件管理器也无效
 * （文件管理器是 DocumentsProvider，不是 picker）。故改为自建目录树。
 *
 * 交互：←/→ 或 D-pad 在目录间移动，「进入」下钻，「返回」上一级，
 * 「新建文件夹」弹出输入框。所有可交互元素均为 Button/TextField，天然可聚焦。
 */
@Composable
private fun SaveLocationDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val context = LocalContext.current
    // 单一状态：当前浏览位置 == 将要保存的位置（导航即选择）。
    // 早期版本拆成 cwd/picked 两个状态，导致「进到电影后按确认」仍存根目录——
    // 两个值在 UI 上并排显示且样式相同，用户无从分辨哪个是落盘目标。
    var cwd by remember { mutableStateOf(initial) }
    var kids by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(true) }
    var showNew by remember { mutableStateOf(false) }

    // 目录扫描要读 SharedPreferences + 查 MediaStore，必须在 IO 线程。
    // 状态与 cwd 绑定成一份，避免切目录那一帧用「新面包屑 + 旧列表」拼出不存在路径。
    var viewCwd by remember { mutableStateOf(initial) }
    var viewKids by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(cwd) {
        loading = true
        val list = try {
            withContext(Dispatchers.IO) { ReceiverStore.listSubDirs(context, cwd) }
        } catch (t: Throwable) {
            // 不让单个目录读取失败把对话框卡在「读取中…」
            emptyList()
        }
        viewKids = list
        viewCwd = cwd
        loading = false
    }
    kids = viewKids
    val fresh = viewCwd == cwd

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择保存文件夹") },
        text = {
            Column {
                // 面包屑：当前位置 == 落盘目标
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            if (cwd.isNotEmpty()) cwd = cwd.substringBeforeLast('/', "")
                        },
                        enabled = cwd.isNotEmpty(),
                        modifier = Modifier.width(88.dp),
                    ) { Text("返回") }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "下载/${ReceiverStore.SAVE_ROOT}" + if (cwd.isEmpty()) "" else "/$cwd",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "文件将存入上面这个目录",
                    color = Color(0xFF5A6672),
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))

                if (loading) {
                    Text("读取中…", fontSize = 13.sp)
                } else if (!fresh) {
                    Text("读取中…", fontSize = 13.sp)
                } else if (kids.isEmpty()) {
                    Text(
                        "此目录下暂无子文件夹，点下方「新建文件夹」创建一个。",
                        color = Color(0xFF5A6672),
                        fontSize = 13.sp,
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(kids, key = { it }) { name ->
                            val child = if (cwd.isEmpty()) name else "$cwd/$name"
                            // 每行只留一个可聚焦元素：双按钮会让 D-pad 下键要按两次
                            // 才推进一行，目录一多就是「按 N 次换 1 屏」。
                            OutlinedButton(
                                onClick = { cwd = child },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            ) { Text("📁 $name", maxLines = 1) }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showNew = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("＋ 新建文件夹") }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(cwd) }) { Text("保存到此处") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showNew) {
        NewFolderDialog(
            parentPath = cwd,
            onDismiss = { showNew = false },
            onConfirm = { rawName ->
                val rel = ReceiverStore.createFolder(context, cwd, rawName)
                showNew = false
                if (rel == null) {
                    Toast.makeText(context, "名称无效（不能为空/含非法字符/过长或层级太深）", Toast.LENGTH_SHORT).show()
                } else {
                    cwd = rel
                }
            },
        )
    }
}

/**
 * 新建文件夹的名称输入。
 *
 * TV 上不能只靠 OutlinedTextField：实测这台 AOSP TV 确实装了 LatinIME（默认 IME），
 * 但 TV 版的 IME 布局与手机差异很大，D-pad 能否可靠打出中文未经验证，
 * 因此额外提供**常用目录名一键按钮**作为不依赖 IME 的兜底路径。
 * （预设名走的是 createFolder，与手输路径完全同一条代码。）
 */
@Composable
private fun NewFolderDialog(
    parentPath: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val presets = listOf("电影", "音乐", "照片", "文档", "安装包")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件夹") },
        text = {
            Column {
                Text(
                    "位置：下载/${ReceiverStore.SAVE_ROOT}" +
                        if (parentPath.isEmpty()) "" else "/$parentPath",
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("文件夹名称（可直接点下方预设）") },
                )
                Spacer(Modifier.height(10.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.heightIn(max = 130.dp),
                ) {
                    items(presets) { p ->
                        OutlinedButton(
                            onClick = { name = p },
                            modifier = Modifier.padding(2.dp),
                        ) { Text(p, maxLines = 1) }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("创建") }
        },
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

// 负数表示大小未知（见 FileUtil.getSize：SAF provider 可能不提供 OpenableColumns.SIZE 列），
// 直接落到 else 会输出字面量 "-1 B"
private fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "大小未知"
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
            // API 24-28：先找应用私有降级目录（API28 上公共目录可能不可写，
            // 见 ReceiverServer.saveToDownloads 的降级逻辑），再退回公共目录
            val f = locateReceivedFile(context, item.name)
            if (f == null) {
                Toast.makeText(context, "文件已不在接收目录", Toast.LENGTH_SHORT).show()
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

/**
 * 按显示名在公共 Downloads 中查 content URI。
 *
 * 只允许在 API 29+ 调用：MediaStore.Downloads 及其 EXTERNAL_CONTENT_URI 是 API 29 才有的
 * （API 28 及以下连该列在 MediaProvider 里都不存在，实测报 "no such column: relative_path"）。
 * 唯一调用点openReceived 的 `SDK_INT >= 29` 分支内，Lint 跨函数看不出来所以标了 @RequiresApi。
 */
@androidx.annotation.RequiresApi(29)
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

/**
 * API 24-28 定位已接收文件。
 * 依次尝试：① 当前保存位置下的应用私有降级目录（ReceiverServer 在公共目录不可写时用）
 *          ② 当前保存位置下的公共 Downloads 子目录
 * 都找不到返回 null。
 */
private fun locateReceivedFile(context: android.content.Context, displayName: String): File? {
    val sub = ReceiverStore.getSaveSubdir(context)
    val rel = if (sub.isEmpty()) ReceiverStore.SAVE_ROOT else "${ReceiverStore.SAVE_ROOT}/$sub"
    val candidates = buildList {
        context.getExternalFilesDir(null)?.let { add(File(it, rel)) }
        add(
            File(
                android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                rel,
            ),
        )
    }
    return candidates.firstOrNull { File(it, displayName).exists() }
        ?.let { File(it, displayName) }
}

/** 按显示名从公共 Downloads（含子目录）读取文件内容到 target；找不到返回 false */
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
    val source = locateReceivedFile(context, displayName) ?: return false
    source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
    return true
}

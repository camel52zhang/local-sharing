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

/** 安装流程的 logcat tag，便于 `adb logcat -s TvInstall` 单独抓安装链路 */
private const val INSTALL_TAG = "TvInstall"

/** APK 的 MIME 类型 */
private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * 安装流程日志：双通道输出。
 * - [ReceiverService.appendLog] → 电视 UI 的「服务日志」弹窗。用户下次点安装卡住时，
 *   截图那几行 `[install]` 就能定位到断在哪一步，不必连 adb（电视上通常也没有 adb 环境）。
 * - [android.util.Log] → logcat，留给开发侧抓完整上下文。
 */
private fun installLog(msg: String) {
    android.util.Log.i(INSTALL_TAG, msg)
    ReceiverService.appendLog("[install] $msg")
}

/**
 * 拉起「安装未知应用」授权页，返回是否成功打开了页面。
 *
 * 为什么要试三种形式 —— AOSP 对 [Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES] 的 data URI
 * 只说了「可选，要指定包名则形如 `package:com.my.app`」（单斜杠），但 ROM 侧解析代码分两派：
 * - `getSchemeSpecificPart()` 派系：`package:com.x` → `com.x` ✅；`package://com.x` → `//com.x` ❌
 * - `getAuthority()` 派系：`package://com.x` → `com.x` ✅；`package:com.x` → null ❌
 * 即两种写法各覆盖约一半定制 ROM，都不能单独依赖。
 * 而**不带 data** 的形式（跳到全局「未知来源应用」列表页）是官方明确允许的，
 * 不受上述解析分歧影响，是唯一全覆盖的兜底。
 *
 * 返回 false 表示本机 ROM 根本没有这个 Activity（深度定制 TV ROM 常见），
 * 调用方据此转入「手动安装」兜底，而不是让异常冒泡崩溃。
 */
private fun requestInstallPermission(context: android.content.Context): Boolean {
    val pkg = context.packageName
    val attempts = listOf(
        "package:$pkg" to "package:单斜杠",
        "package://$pkg" to "package:// 双斜杠",
        null to "无 data（全局未知来源列表页）",
    )
    for ((dataUri, label) in attempts) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            // 变量名不能叫 data：apply 作用域里 data 会解析到 Intent.setData()，不是外层的形参
            if (dataUri != null) setData(Uri.parse(dataUri))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            installLog("已拉起授权页：$label")
            return true
        } catch (e: android.content.ActivityNotFoundException) {
            // 定制 ROM 可能没有这个 Activity，或不接受这种 data 形式
            installLog("授权页 $label 不存在（ActivityNotFoundException）")
        } catch (e: Exception) {
            installLog("授权页 $label 拉起失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }
    installLog("本机没有可用的「安装未知应用」授权页")
    return false
}

/** 判断 child 是否位于 dir 之下（用 canonicalPath 消解 `..` 与软链）；解析失败按「不在内」处理 */
private fun isUnder(child: File, dir: File): Boolean = try {
    val c = child.canonicalPath
    val d = dir.canonicalPath
    c != d && c.startsWith(d + File.separator)
} catch (e: Exception) {
    false
}

/**
 * 安装失败时的最终兜底：把 APK 落到用户能用电视自带「文件管理 / 应用中心」找到的位置，
 * 并**明确把真实绝对路径告诉用户**。
 *
 * 之前这条链路的所有失败分支要么静默 return、要么只 printStackTrace（电视上没人看得见堆栈），
 * 用户视角就是「点安装没反应」。这里保证任何路径下都有反馈。
 */
private fun fallbackToManualInstall(
    context: android.content.Context,
    item: ReceivedItem,
    source: File?,
) {
    if (source == null) {
        installLog("兜底失败：源文件定位不到，无法提示路径")
        Toast.makeText(
            context,
            "未在接收目录找到该文件，可能已被清理。请用手机重新发送一次再安装。",
            Toast.LENGTH_LONG,
        ).show()
        return
    }

    // 已经在公共 Downloads 下就不用搬了，直接报位置（电视文件管理能直接浏览到）
    if (isUnder(source, publicDownloadDir())) {
        installLog("兜底：文件已在公共下载目录，无需搬运：${source.absolutePath}")
        Toast.makeText(
            context,
            "本机没有系统安装器，请用电视的「文件管理 / 应用中心」打开并安装。\nAPK 路径：${source.absolutePath}",
            Toast.LENGTH_LONG,
        ).show()
        return
    }

    // 尝试搬进公共 Download（算法与 ReceiverServer.saveToDownloads 的 API 28- 分支同构）
    val publicDir = publicDownloadDir()
    val moved = runCatching {
        val dir = File(publicDir, ReceiverStore.SAVE_ROOT)
        if (!dir.exists() && !dir.mkdirs()) return@runCatching null
        val dest = File(dir, item.name)
        source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        dest.takeIf { it.length() > 0L }
    }.getOrNull()

    if (moved != null) {
        installLog("兜底：已复制到公共下载目录：${moved.absolutePath} (${moved.length()} B)")
        Toast.makeText(
            context,
            "本机没有系统安装器，请用电视的「文件管理 / 应用中心」打开并安装。\nAPK 路径：${moved.absolutePath}",
            Toast.LENGTH_LONG,
        ).show()
        return
    }

    // 公共目录不可写（API 24~28 上实测存在：进程拿不到 sdcard_rw 组）。
    // 再退一步到应用私有外部目录：必定可写、且已在 file_paths.xml 的 external-files-path 内，
    // 用户仍可用系统文件管理配合本应用「打开」功能处理。
    val privCopy = runCatching {
        val privBase = context.getExternalFilesDir(null) ?: return@runCatching null
        val dir = File(privBase, ReceiverStore.SAVE_ROOT)
        if (!dir.exists() && !dir.mkdirs()) return@runCatching null
        val dest = File(dir, item.name)
        source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        dest.takeIf { it.length() > 0L }
    }.getOrNull()

    if (privCopy != null) {
        installLog("兜底：公共目录不可写，已复制到应用私有目录：${privCopy.absolutePath}")
        Toast.makeText(
            context,
            "系统安装器不可用且公共下载目录不可写。\n已放到应用目录，可用下方「打开」按钮查看：\n${privCopy.absolutePath}",
            Toast.LENGTH_LONG,
        ).show()
        return
    }

    installLog("兜底失败：所有候选目录均不可写，保留原路径 ${source.absolutePath}")
    Toast.makeText(
        context,
        "系统安装器不可用，且没有可写入的目录。\n文件仍在原处：${source.absolutePath}",
        Toast.LENGTH_LONG,
    ).show()
}

/**
 * 公共 Downloads 根目录。
 * `getExternalStoragePublicDirectory` 返回的目录未必真实存在（外置存储未挂载时），
 * 调用方要用isUnder / mkdirs 处理这种情况，故返回原对象、不做校验。
 */
@Suppress("DEPRECATION") // 项目里 ReceiverServer/locateReceivedFile 都用同一 API，保持一致
private fun publicDownloadDir(): File =
    android.os.Environment
        .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)

/**
 * 一键安装 APK。设计原则：**无论走哪条路径，用户都必须看到明确反馈，绝不静默失败，更不能崩溃。**
 *
 * 1) 先定位文件真实位置（后续所有失败提示都要靠它报路径）
 * 2) API 26+ 申请「安装未知应用」授权；三种 URI 形式依次尝试，ROM 没有该页面则转手动兜底
 * 3) 复制到 cacheDir/installs，经 FileProvider 交给系统安装器（ACTION_VIEW / ACTION_INSTALL_PACKAGE 都试）
 * 4) 任一环节失败 → [fallbackToManualInstall]，把 APK 放到用户找得到的位置并报出路径
 *
 * 历史上这里是全项目唯一一处「日志与错误处理双缺」的地方：第 594 行的
 * `startActivity` 裸奔（定制 TV ROM 上 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 不存在时抛
 * `ActivityNotFoundException`，RuntimeException 直接冒泡导致 Activity 闪退），
 * 末尾的 `e.printStackTrace()` 在电视上等于什么都没做。
 */
private fun installApk(context: android.content.Context, item: ReceivedItem) {
    installLog("=== 开始安装 ${item.name} (${formatSize(item.size)}) sdk=${Build.VERSION.SDK_INT}")

    // ---- 步骤 1：先定位文件。任何失败提示都要靠它报出真实路径，所以必须放在最前面 ----
    val source = try {
        locateReceivedFile(context, item.name)
    } catch (e: Exception) {
        installLog("定位文件时异常：${e.javaClass.simpleName}: ${e.message}")
        null
    }
    if (source == null) {
        // 注意：locateReceivedFile 用的是「当前」的保存子目录，而接收记录里没存落盘时的目录。
        // 用户如果接收后改了保存位置，这里就会去新目录找、找不到旧文件 —— 这是已知缺陷。
        installLog("WARN 接收目录里找不到 ${item.name}（可能已被清理，或接收后改过保存位置）")
    } else {
        installLog("源文件：${source.absolutePath} (${source.length()} B)")
    }

    // ---- 步骤 2：安装未知应用授权（API 26 起）----
    if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
        if (requestInstallPermission(context)) {
            Toast.makeText(
                context,
                "请在系统设置里允许本应用「安装未知应用」，然后返回再点一次安装。",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        installLog("未获授权且本机无授权页，转手动安装兜底")
        fallbackToManualInstall(context, item, source)
        return
    }

    // ---- 步骤 3：复制到 cacheDir/installs，经 FileProvider 交给系统安装器 ----
    try {
        val installsDir = File(context.cacheDir, "installs")
        // mkdirs() 的返回值必须检查：它失败时文件确实写不进去，而后续
        // FileProvider.getUriForFile 会抛 IllegalArgumentException("Failed to find configured root")，
        // 报错完全指不到真正的原因。
        if (!installsDir.exists() && !installsDir.mkdirs()) {
            installLog("WARN 缓存安装目录创建失败：${installsDir.absolutePath}")
        }
        val target = File(installsDir, item.name)

        val copied = copyFromDownloads(context, item.name, target)
        if (!copied) {
            installLog("复制失败：接收目录中已无 ${item.name}")
            Toast.makeText(
                context,
                "文件已不在接收目录，可能已被清理。请用手机重新发送一次再安装。",
                Toast.LENGTH_LONG,
            ).show()
            fallbackToManualInstall(context, item, source)
            return
        }
        if (!target.exists() || target.length() == 0L) {
            installLog("复制失败：目标不存在或长度为 0（${target.absolutePath}）")
            Toast.makeText(
                context,
                "复制安装包失败（目标文件为空），请重新发送后再试。",
                Toast.LENGTH_LONG,
            ).show()
            fallbackToManualInstall(context, item, source)
            return
        }
        installLog("已复制到缓存：${target.absolutePath} (${target.length()} B)")

        // getUriForFile 单独 try/catch：它抛的是 IllegalArgumentException，
        // 与外层其它异常混在一起会掩盖「file_paths.xml 与实际路径不匹配」这类配置错误。
        val uri = try {
            FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", target,
            )
        } catch (e: IllegalArgumentException) {
            installLog("FileProvider 生成 URI 失败：${e.message}")
            fallbackToManualInstall(context, item, source)
            return
        }
        installLog("content uri = $uri")

        // 两种action 都试：厂商 ROM 的安装器可能只认其中一个
        //（ACTION_INSTALL_PACKAGE 在 API 29 起被标记废弃，但定制 ROM 的安装器仍可能只认它，
        //  所以这里的废弃是"有意使用"而非遗漏）
        @Suppress("DEPRECATION")
        for (action in listOf(Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE)) {
            try {
                val intent = Intent(action).apply {
                    setDataAndType(uri, APK_MIME)
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK,
                    )
                }
                context.startActivity(intent)
                installLog("已调起系统安装器：action=$action")
                return
            } catch (e: android.content.ActivityNotFoundException) {
                installLog("action=$action 无对应安装器（ActivityNotFoundException）")
            } catch (e: Exception) {
                installLog("action=$action 拉起失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }

        installLog("两种安装 action 均失败，转手动安装兜底")
        fallbackToManualInstall(context, item, source)
    } catch (e: Exception) {
        // 兜底中的最后一道：绝不让安装流程的任何异常冒泡到 Activity 造成闪退
        installLog("安装流程异常：${e.javaClass.simpleName}: ${e.message}")
        android.util.Log.e(INSTALL_TAG, "installApk crashed", e)
        fallbackToManualInstall(context, item, source)
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

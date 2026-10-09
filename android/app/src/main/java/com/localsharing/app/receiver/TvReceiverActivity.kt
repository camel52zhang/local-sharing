package com.localsharing.app.receiver

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.localsharing.app.BuildConfig
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.animateColorAsState
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
 * Activity ↔ Compose 的焦点桥。
 *
 * 背景（真机实拍 111-19 反馈）：「保存位置 / 日志 / 停止接收 / 清空记录 / 安装 / 删除」
 * 全部都已是带高亮的 TvButton，但用户截图里**没有任何按钮处于焦点态**——
 * 问题不在按钮样式，而在**焦点整体丢失**（Compose Dialog 关闭不归还焦点、
 * requestFocus 单发静默失败等）。焦点一丢，D-pad 按键在 Compose 里没有接收者，
 * 表现就是「按了没反应、看不出选中了谁」。
 *
 * - Compose 侧：根节点 onFocusChanged 上报「子树是否有焦点」→ [hasFocus]
 * - Activity 侧：[TvReceiverActivity.dispatchKeyEvent] 在 View 层拦截按键
 *   （该层不依赖 Compose 焦点，无焦点时也一定收到 D-pad 事件），
 *   检测到「D-pad + 无焦点」→ 调 [recall] 让 Compose 把焦点交回首按钮。
 */
internal object TvFocusBridge {
    /** Compose 树内是否有任何聚焦节点（由根节点 onFocusChanged 维护） */
    @Volatile var hasFocus: Boolean = true

    /** 焦点召回回调（ReceiverScreen 进入组合时注册） */
    @Volatile var recall: (() -> Unit)? = null
}

/** 遥控器导航键集合：方向键 + OK 键（DPAD_CENTER）+ 回车 */
private fun isDpadKey(code: Int): Boolean = when (code) {
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    -> true
    else -> false
}

/**
 * 电视接收模式主界面：与电脑端仪表盘同构的信息结构——
 * 左：二维码 + 连接地址 + 保存位置 + 操作按钮；
 * 右：已连接设备（含在线状态）+ 接收记录（含来源设备，APK 可一键安装）。
 * 为遥控器/D-pad 优化：可聚焦元素仅按钮类。
 */
class TvReceiverActivity : ComponentActivity() {

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 无焦点死区兜底之一（按键路径）：Compose 里没有聚焦节点时 D-pad 事件
        // 理论上会无人消费落到这里，此时召回焦点。（实测 Compose 在无焦点时
        // 也可能吃掉事件，所以这只是第二道防线；主防线是下面的窗口焦点回调。）
        // hasFocus 检查不可省：焦点在但按键移到列表边界时事件也会落到这里，
        // 无条件召回会把焦点「吸」回首按钮，破坏正常导航。
        if (!TvFocusBridge.hasFocus && isDpadKey(keyCode)) {
            android.util.Log.i(FOCUS_TAG, "无焦点 D-pad 按键(code=$keyCode)，召回焦点")
            TvFocusBridge.recall?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        android.util.Log.i(FOCUS_TAG, "窗口焦点 ${if (hasFocus) "获得" else "失去"}")
        // ★ 无焦点死区兜底之二（主防线）：FocusRequester.requestFocus() 要求窗口
        //   本身持有输入焦点，否则静默失败——这正是「启动/从别的界面回来后按
        //   方向键没有任何按钮高亮」的根因：组合时窗口还没拿到焦点，3 次重试
        //   全部白打，之后再没人请求。这里在窗口真正拿到焦点的瞬间再召回一次。
        if (hasFocus) TvFocusBridge.recall?.invoke()
    }

    companion object {
        /**
         * 「在线」判据的兜底宽限期：DevicePresence 是纯内存 object，[ReceiverServer.stop] 会 clear()，
         * 电视服务被系统回收后手机侧的 WS 可能并未真正断开。此时用 lastSeen（每次注册由
         * upsertDevice 刷新）兜底，避免服务一重启就把所有设备误判为离线。
         */
        internal const val ONLINE_GRACE_MS = 120_000L

        /** API 28- 写公共存储的运行时权限请求码 */
        private const val REQ_WRITE_EXTERNAL = 2001

        /** 仅 debug：adb 广播触发安装流程的 action（release 包不注册，见 registerInstallDebugHook） */
        const val ACTION_DEBUG_INSTALL = "com.localsharing.app.DEBUG_INSTALL"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_FILE_SIZE = "file_size"

        /** 仅 debug：触发单条「删除」（删记录 + 删文件） */
        const val ACTION_DEBUG_DELETE = "com.localsharing.app.DEBUG_DELETE"

        /** 仅 debug：触发「清空记录」（只删记录，保留文件） */
        const val ACTION_DEBUG_CLEAR = "com.localsharing.app.DEBUG_CLEAR"
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
        registerInstallDebugHook()
        ReceiverService.start(this)
    }

    /**
     * **仅 debug 构建注册**的安装流程测试入口。
     *
     * 用途：模拟器/真机上无法可靠点击 Compose 的「安装」按钮（uiautomator 对 Compose
     * 支持弱、headless 截图全黑），需要一种可脚本化触发 `installApk` 的方式，
     * 以便用 `adb shell am broadcast` 精确复现「点安装→崩溃」并抓完整 logcat。
     *
     * 走的是**与真实按钮完全相同的调用链**（同一个 `installApk` 入口），
     * 所以验证有效。用 `BuildConfig.DEBUG` 圈定，release 包不注册这个 receiver，
     * 正式交付路径上不存在此代码。
     */
    private fun registerInstallDebugHook() {
        if (!BuildConfig.DEBUG) return
        val filter = android.content.IntentFilter()
        filter.addAction(ACTION_DEBUG_INSTALL)
        filter.addAction(ACTION_DEBUG_DELETE)
        filter.addAction(ACTION_DEBUG_CLEAR)
        filter.addCategory(android.content.Intent.CATEGORY_DEFAULT)
        try {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_DEBUG_INSTALL -> {
                            val name = intent.getStringExtra(EXTRA_FILE_NAME)
                            val size = intent.getLongExtra(EXTRA_FILE_SIZE, 0L)
                            if (name.isNullOrBlank()) {
                                installLog("DEBUG 广播缺少 $EXTRA_FILE_NAME，中止")
                                return
                            }
                            val item = ReceivedItem(
                                id = "debug-${System.currentTimeMillis()}",
                                name = name,
                                size = size,
                                fromDevice = "debug-hook",
                                time = System.currentTimeMillis(),
                                isApk = name.endsWith(".apk", ignoreCase = true),
                            )
                            installLog("DEBUG 广播触发安装：$name (${formatSize(size)})")
                            installApk(this@TvReceiverActivity, item)
                        }
                        ACTION_DEBUG_DELETE -> {
                            val name = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
                            installLog("DEBUG 广播触发删除：$name")
                            deleteRecordAndFile(this@TvReceiverActivity, name, name)
                        }
                        ACTION_DEBUG_CLEAR -> {
                            val n = ReceiverStore.loadReceived(this@TvReceiverActivity).size
                            ReceiverStore.clearReceived(this@TvReceiverActivity)
                            installLog("DEBUG 广播触发清空记录：$n 条，文件全部保留")
                            Toast.makeText(
                                this@TvReceiverActivity,
                                "已清空 $n 条记录，文件都还在",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
            // ★ 必须显式指定 EXPORTED / NOT_EXPORTED。
            //实测（API 34 / Android 14）：不指定会抛 SecurityException
            //   "One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED should be specified"，
            //   导致 hook 注册失败、自动化验证完全做不了。
            //这里选 EXPORTED：debug-only 测试入口本来就要被 adb shell 触发；
            // 用 NOT_EXPORTED 会把 shell 广播一并拦掉（实测收不到）。
            //release 安全性由 BuildConfig.DEBUG=false 保证：函数不会执行到这里。
            if (Build.VERSION.SDK_INT >= 26) {
                registerReceiver(
                    receiver, filter, android.content.Context.RECEIVER_EXPORTED,
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
            android.util.Log.i(INSTALL_TAG, "DEBUG hook registered (install/delete/clear)")
        } catch (t: Throwable) {
            android.util.Log.e(INSTALL_TAG, "DEBUG hook 注册失败", t)
        }
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

    // ★ D-pad 初始焦点：必须显式请求，否则启动后焦点无处可落，方向键无反应
    val firstFocus = remember { FocusRequester() }

    // ★ 焦点召回开关：弹窗关闭、窗口焦点恢复、或 Activity 检测到「无焦点时按了
    //   D-pad」都会自增它，触发把焦点交回「保存位置」。
    // ★ 实测（模拟器 1080p TV 复现）：Compose 的 FocusRequester.requestFocus()
    //   依赖宿主 AndroidComposeView 持有 **View 层焦点**；启动初期 View 焦点还没
    //   落到 ComposeView 时，requestFocus 会静默 no-op（无异常、无事件）。
    //   所以召回前先把 View 层焦点拉到 ComposeView（LocalView 即 AndroidComposeView），
    //   再请求 Compose 焦点——启动后不按任何键，「保存位置」也是亮的。
    val composeView = LocalView.current
    var focusNonce by remember { mutableStateOf(0) }
    LaunchedEffect(focusNonce) {
        if (focusNonce > 0) {
            kotlinx.coroutines.delay(200) // 等 Dialog 完全退出组合再要焦点
            android.util.Log.i(FOCUS_TAG, "召回：准备 requestFocus（nonce=$focusNonce）")
            runCatching {
                if (!composeView.hasFocus()) composeView.requestFocus()
                firstFocus.requestFocus()
            }.onFailure { android.util.Log.e(FOCUS_TAG, "召回 requestFocus 抛异常", it) }
        }
    }
    DisposableEffect(Unit) {
        TvFocusBridge.recall = {
            android.util.Log.i(FOCUS_TAG, "召回请求到达（recall invoke）")
            focusNonce++
        }
        onDispose {
            TvFocusBridge.recall = null
            TvFocusBridge.hasFocus = true
        }
    }

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
                // 上报整个界面是否还有焦点（子树内任一节点聚焦即算有），
                // 供 Activity 的 View 层兜底逻辑判断「无焦点死区」
                .onFocusChanged { TvFocusBridge.hasFocus = it.hasFocus }
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
                // 无可用网卡时 lanIp 为空串，此时拼出的 "http://:38080" 是废二维码，
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
                Spacer(Modifier.height(4.dp))
                // 界面上直接显示构建版本：真机排障截图一眼识别装的是哪个包，
                // 避免「修复了但电视上跑的还是旧包」这种反复空转
                Text(
                    "版本 ${com.localsharing.app.BuildConfig.VERSION_NAME}",
                    color = Color(0xFF607086),
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row {
                    TvButton("保存位置", { showSettings = true }, focusRequester = firstFocus)
                    Spacer(Modifier.width(12.dp))
                    TvButton("日志", { showLogs = true })
                    Spacer(Modifier.width(12.dp))
                    TvButton(
                        "停止接收",
                        {
                            ReceiverService.stop(context)
                            onStop()
                        },
                        danger = true,
                    )
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
                        // 用户明确要求：「清空记录」只清列表，**不删文件**；
                        // 只有单条「删除」才彻底删文件。按钮文案直接说明语义。
                        TvButton("清空记录（保留文件）", {
                            val n = received.size
                            ReceiverStore.clearReceived(context)
                            received = emptyList()
                            Toast.makeText(
                                context,
                                "已清空 $n 条记录，文件都还在",
                                Toast.LENGTH_SHORT,
                            ).show()
                        })
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
                                // 用户明确要求：「删除」= 删记录 + 从下载目录彻底删文件。
                                deleteRecordAndFile(context, item.id, item.name)
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
                onDismiss = {
                    showSettings = false
                    focusNonce++ // Compose Dialog 关闭不归还焦点，主动召回
                },
                onConfirm = { sub ->
                    ReceiverStore.setSaveSubdir(context, sub)
                    savePath = ReceiverStore.savePathLabel(context)
                    showSettings = false
                    focusNonce++
                },
            )
        }

        // 服务端运行日志：电视上没有 logcat 可看，排障时可直接截图反馈
        if (showLogs) {
            AlertDialog(
                onDismissRequest = {
                    showLogs = false
                    focusNonce++ // 返回键关弹窗同样不归还焦点，主动召回
                },
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
                confirmButton = {
                    TextButton(onClick = {
                        showLogs = false
                        focusNonce++ // Compose Dialog 关闭不归还焦点，主动召回
                    }) { Text("关闭") }
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
                        "${downloadsDirName()}/${ReceiverStore.SAVE_ROOT}" + if (cwd.isEmpty()) "" else "/$cwd",
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
                    "位置：${downloadsDirName()}/${ReceiverStore.SAVE_ROOT}" +
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

/**
 * 删除一条接收记录**及其对应的已落盘文件**。
 *
 * 用户需求（2026-10-07）：点「删除」要真正把文件从下载目录删掉，
 * 而「清空记录」只清列表、保留文件。
 *
 * 定位复用 [locateReceivedFile] —— 与 openReceived / installApk 同一套逻辑
 * （应用私有目录优先，再退公共 Download），确保删的就是界面上显示的那个文件。
 * 返回 true 表示文件确实被删掉了。
 */
private fun deleteRecordAndFile(context: android.content.Context, id: String, name: String): Boolean {
    val file = locateReceivedFile(context, name)
    val fileGone = file?.let { it.delete() || !it.exists() } ?: false
    ReceiverStore.deleteReceived(context, id)
    android.util.Log.i(
        INSTALL_TAG,
        "删除 $name：文件${if (fileGone) "已彻底删除" else "未找到（可能已被系统清理）"}" +
            if (file != null) " @ ${file.absolutePath}" else "",
    )
    Toast.makeText(
        context,
        if (fileGone) "已删除记录和文件"
        else "已删除记录（文件未找到，可能已清理）",
        Toast.LENGTH_SHORT,
    ).show()
    return fileGone
}

/**
 * D-pad 焦点高亮配色。
 * 问题：电视没有触屏，全靠遥控器方向键操作。此前全项目**零焦点处理**，
 * 只靠 Material Button 默认焦点态 —— 在本应用的深色背景（0xFF0E1417 一带）上
 * 几乎不可辨，用户反馈「要非常认真才能看出选中的是哪个 tab」。
 *
 * 方案：焦点态用**高饱和琥珀色实心底 + 深色文字 + 3dp 亮色描边**，
 * 未聚焦态为深灰描边 + 白字。三个信号叠加（底色/文字色/描边），
 * 保证在电视亮度下、隔着几米也能一眼看出焦点在哪。
 */
private const val FOCUS_TAG = "TvFocus"

private val FocusAmber = Color(0xFFFFB020)
private val FocusIdleBorder = Color(0xFF39434E)

/** 按钮圆角：与 Material3 Button 默认 shape 一致，避免内外圆角不匹配 */
private val ButtonShape = RoundedCornerShape(14.dp)

/**
 * 通用焦点高亮按钮：Material Button 在深色背景上焦点态不可辨，这里统一替换。
 * [primary] 为 true 时用实心高亮（用于「安装」「打开」这类主动作）。
 */
@Composable
private fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    /** 首个按钮传FocusRequester(requestFocus)，解决「启动后焦点无处可落」 */
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        focusRequester?.let { fr ->
            // 单发可能静默失败（窗口未持输入焦点 / FocusNode 未挂进 hierarchy），
            // 连发 3 次兜底；窗口焦点真正到位的一击由 Activity.onWindowFocusChanged 负责。
            // 注意用命名参数 fr：repeat 的隐式 it 是循环索引，会遮蔽 let 外层的 it
            repeat(3) { n ->
                kotlinx.coroutines.delay(150)
                runCatching { fr.requestFocus() }
                    .onFailure {
                        android.util.Log.e(FOCUS_TAG, "requestFocus 第${n + 1}次抛异常", it)
                    }
            }
        }
    }
    // ★ 焦点态底色：琥珀色是本项目唯一与所有常态色都高对比的颜色。
    // primary 的蓝色 0xFF1F6FEB 在电视上与琥珀太接近（用户实拍反馈「看不出选中哪个」），
    // 所以焦点态**不再沿用 primary 的蓝**，一律换成琥珀。
    val bg by animateColorAsState(
        if (focused) FocusAmber else if (primary) Color(0xFF1F6FEB) else Color(0xFF232C34),
        label = "tvBtnBg",
    )
    val fg by animateColorAsState(
        when {
            focused -> Color(0xFF0E1417)                 // 焦点态深色字，在琥珀底上对比最强
            danger -> Color(0xFFFF8A80)
            primary -> Color.White
            else -> Color(0xFFD6DEE6)
        },
        label = "tvBtnFg",
    )
    Button(
        onClick = onClick,
        modifier = modifier
            // 非焦点：1dp 极淡描边（空心 vs 实心 = 不依赖颜色的形状信号）；
            // 焦点：1.15x 放大 + 3dp 白色外圈（用 ButtonShape 同圆角，不会出现方框套方框）
            .then(
                // ★★ 关键：**填充 vs 描边** 是不依赖颜色的第二信号。
                // 电视隔着几米、亮度高/偏色时，颜色差异不可靠；
                // 但「实心填充」与「空心描边」的形状差异一眼可辨。
                // 焦点态：明显放大 + 无描边（纯实心） + 前置●标记
                // 非焦点：1.15x 缩放足够醒目 + 极淡描边
                if (focused) Modifier
                    .scale(1.15f)                // 1.06 在 1080p 电视上几乎看不出，提到 1.15
                else Modifier
                    .scale(1f)
                    .border(width = 1.dp, color = FocusIdleBorder, shape = ButtonShape),
            )
            // 焦点态加一圈亮色外描边（叠在实心底上，等于"高亮+填充"双信号）
            .then(
                if (focused) Modifier.border(
                    width = 3.dp,
                    color = Color.White.copy(alpha = 0.9f),
                    shape = ButtonShape,
                ) else Modifier,
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged {
                focused = it.isFocused
                // 焦点自检日志：电视上「按了没反应」时，靠这个判断焦点到底落在哪。
                android.util.Log.i(FOCUS_TAG, "焦点 ${if (it.isFocused) "获得" else "失去"}: $text")
            },
        // ★★ 千万不能再加 .focusable()：Material3 Button 内部已自带 focusTarget，
        // 再叠一个会造成「一按钮两个焦点目标」——D-pad 把焦点移到内部目标时，
        // 外层 onFocusChanged 永远收不到事件，琥珀高亮永远不亮（真机 111-19
        // 「看不出选中哪个」+ 模拟器 uiautomator 实证 focused=true 但 UI 无反应的根因）。
        // 焦点观察/请求用上面的 focusRequester + onFocusChanged 挂在 Button 自身即可。
        shape = ButtonShape,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = fg,
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp),
    ) {
        // 前置圆点必须用**固定宽度盒子占位**：Button 宽度包内容，
        // 若聚焦时才把「● 」拼进文本，按钮会突然变宽 → 整行按钮向右抖动移位。
        // 现在聚焦与否只改圆点可见性，按钮宽度恒定。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "\u25CF",
                color = if (focused) fg else Color.Transparent,
                fontSize = 9.sp,
                modifier = Modifier.width(14.dp),
            )
            Text(
                text,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
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
            TvButton("安装", onOpen, primary = true)
        } else {
            TvButton("打开", onOpen)
        }
        Spacer(Modifier.width(8.dp))
        // 「删除」现在会连文件一起删，用红色+ 明确文案，避免误触
        TvButton("删除文件", onDelete, danger = true)
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
 * 拉起「安装未知应用」授权页，返回是否成功打开了页面。返回 false 表示本机 ROM 根本没有
 * 任何可用的授权页（深度定制 TV ROM 常见），调用方据此转入「手动安装」兜底。
 *
 * ## 为什么不是「一次点击里把三种形式依次试完」
 *
 * AOSP 对 [Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES] 的 data URI 只说了「可选，
 * 要指定包名则形如 `package:com.my.app`」（单斜杠），但 ROM 侧解析代码分两派：
 * - `getSchemeSpecificPart()` 派系：`package:com.x` → `com.x` ✅；`package://com.x` → `//com.x` ❌
 * - `getAuthority()` 派系：`package://com.x` → `com.x` ✅；`package:com.x` → null ❌
 *
 * 关键盲区：**两种写法都可能被 ROM「成功接收」却解析不出包名** —— 此时startActivity
 * 不抛异常，我们误以为成功并 return，而用户看到的是空白页/闪一下就finish 的页面，
 * 于是第二、三种形式**永远不会被尝试**。try/catch 递进只对「抛异常」有效，
 * 对「不抛异常地失败」完全无效。
 *
 * ## 现在的策略：跨次点击推进
 *
 * - 第 1 次点击：单斜杠 `package:<pkg>`（AOSP 主流写法）→ 双斜杠（覆盖 getAuthority 派系）
 * - 第 2 次点击（用户返回后再点，[KEY_TRIED_GLOBAL_LIST] 为 true）：**直接跳不带 data 的全局
 *   「未知来源应用」列表页**。AOSP javadoc 明确说 data 可选，这是唯一不受上述解析分歧影响、
 *   100% 能到达可用页面的形式。
 *
 * 这样无论 ROM 用哪派解析，两次点击内必然有一次落到真正可用的页面；代价只是多点一次。
 * 授权成功后（[clearTriedGlobalFlag]）标志清除，下次从头开始。
 */
private fun requestInstallPermission(context: android.content.Context): Boolean {
    val pkg = context.packageName
    val alreadyTriedGlobal = installPrefs(context)
        .getBoolean(KEY_TRIED_GLOBAL_LIST, false)

    val attempts: List<Pair<String?, String>> = if (alreadyTriedGlobal) {
        installLog("已尝试过全局列表页，本次直接跳该页（跨次推进）")
        listOf(
            null to "无 data · 全局未知来源列表页（第2次点击）",
            "package:$pkg" to "package:单斜杠（复访）",
        )
    } else {
        listOf(
            "package:$pkg" to "package:单斜杠",
            "package://$pkg" to "package:// 双斜杠",
        )
    }

    for ((dataUri, label) in attempts) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            // 变量名不能叫 data：apply 作用域里 data 会解析到 Intent.setData()，不是外层的形参
            if (dataUri != null) setData(Uri.parse(dataUri))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            installLog("已拉起授权页：$label")
            // 记下「全局列表页已经用过」：若用户在该页里没授权就返回，下次点击直接上全局页，
            // 不再重复走注定解析不出包名的单/双斜杠。
            if (dataUri == null && !alreadyTriedGlobal) {
                installPrefs(context).edit().putBoolean(KEY_TRIED_GLOBAL_LIST, true).apply()
                installLog("已标记 tried_global_list=true，下次点击将直接跳全局列表页")
            }
            return true
        } catch (e: android.content.ActivityNotFoundException) {
            // 定制 ROM 可能没有这个 Activity，或不接受这种 data 形式
            installLog("授权页 $label 不存在（ActivityNotFoundException）")
        } catch (e: Exception) {
            installLog("授权页 $label 拉起失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    if (!alreadyTriedGlobal) {
        // 本次两种形式都拉不起来，也先把全局页标记打上：
        // 部分 ROM 会在 ActivityNotFound 后短暂未注册，下次点击直接试全局页更可能成功
        installPrefs(context).edit().putBoolean(KEY_TRIED_GLOBAL_LIST, true).apply()
    }
    installLog("本机没有可用的「安装未知应用」授权页")
    return false
}

/** 授权成功后清除跨次推进标志（[KEY_TRIED_GLOBAL_LIST]） */
private fun clearTriedGlobalFlag(context: android.content.Context) {
    if (installPrefs(context).getBoolean(KEY_TRIED_GLOBAL_LIST, false)) {
        installPrefs(context).edit().putBoolean(KEY_TRIED_GLOBAL_LIST, false).apply()
        installLog("已授权，清除 tried_global_list 标志")
    }
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
 * 之前这条链路的所有失败分支要么静默 return、要么只printStackTrace（电视上没人看得见堆栈），
 * 用户视角就是「点安装没反应」。这里保证任何路径下都有反馈。
 *
 * ## 为什么整个函数体包在 runCatching 里
 *
 * 这个函数本身就是为「ROM 没有安装器 / 没有授权页」准备的兜底，而它自己依赖的
 * `Environment.getExternalStoragePublicDirectory`、`getExternalFilesDir`、
 * `File.mkdirs` 在外置存储未挂载 / sdcard_rw 组缺失的定制TV ROM 上**都会抛**。
 * 也就是说：**兜底路径自己会崩**，异常从 installApk 冒泡到 Compose onClick lambda → 闪退。
 * 这正是「为 ROM 缺失设计的兜底，在该场景下失效」的最坏形态，故整体包一层。
 * 同理安装主流程 [installApk] 也做了整体包装，两层独立。
 *
 * ## TODO(下轮)：私有目录分支给的是用户打不开的路径
 *
 * 最后兜底落在 `getExternalFilesDir` = `Android/data/<pkg>/files/`。Android 8.0 上普通文件
 *管理器默认看不到 `Android/data`（Android 11 才开放），电视 ROM 的文件管理器通常只扫公共目录，
 *所以最可能走到的兜底分支恰恰是用户绝对找不到的。根治方案是利用电视端**已经在跑的 HTTP 服务**
 * 加一个 `GET /apk/<receivedId>` 下载端点，Toast 改成提示「请在手机浏览器打开
 * http://<lanIp>:<port>/apk/<id> 下载后安装」—— 完全绕开电视 ROM 的安装器缺失与存储权限限制，
 * 而这正是本项目「局域网传输」的本职。本轮只做代码层准备（[apkHttpUrl] + 日志），
 * **端点本身不在本轮实现**。
 */
private fun fallbackToManualInstall(
    context: android.content.Context,
    item: ReceivedItem,
    source: File?,
) {
    runCatching {
        if (source == null) {
            installLog("兜底失败：源文件定位不到，无法提示路径")
            Toast.makeText(
                context,
                "未在接收目录找到该文件，可能已被清理。请用手机重新发送一次再安装。",
                Toast.LENGTH_LONG,
            ).show()
            return@runCatching
        }

        // ---- ① 接收侧已经标记过降级目录：文件就在那个目录里，别再搬一次 ----
        // ReceiverServer.saveToDownloads 在公共目录不可写时会 markFallbackDir 存下真实路径，
        // 那才是这份文件实际的落盘位置。重新试探公共目录既可能失败、又可能在别处复制出
        // 一份重复的 24.8MB（电视上最坏 48MB IO）。
        ReceiverStore.getFallbackDir(context)?.let { base ->
            val baseDir = File(base)
            if (source.absolutePath == baseDir.absolutePath || isUnder(source, baseDir)) {
                installLog("兜底：文件已在接收侧记录的降级目录，无需搬运：${source.absolutePath}")
                toastManualPath(context, source.absolutePath)
                return@runCatching
            }
        }

        // ---- ② 公共 Downloads 下就不用搬了，直接报位置（电视文件管理能直接浏览到） ----
        val publicDir = publicDownloadDirOrNull()
        if (publicDir == null) {
            // 外置存储未挂载/异常时 getExternalStoragePublicDirectory 会抛，这里降级为「不可用」
            installLog("兜底：公共下载目录不可用（外置存储未挂载或异常），跳过该候选")
        } else if (isUnder(source, publicDir)) {
            installLog("兜底：文件已在公共下载目录，无需搬运：${source.absolutePath}")
            toastManualPath(context, source.absolutePath)
            return@runCatching
        } else {
            // ---- ③ 搬进公共 Download（算法与 ReceiverServer.saveToDownloads 的 API 28- 分支同构） ----
            // 24.8MB 的复制必须在后台线程，见 copyInBackground。
            val dir = File(publicDir, ReceiverStore.SAVE_ROOT)
            installLog("兜底：开始后台复制到公共下载目录 ${dir.absolutePath}")
            copyInBackground(context, source, dir, item.name) { moved ->
                if (moved != null) {
                    installLog("兜底：已复制到公共下载目录：${moved.absolutePath} (${moved.length()} B)")
                    toastManualPath(context, moved.absolutePath)
                } else {
                    installLog("兜底：复制到公共下载目录失败，转应用私有目录")
                    copyToPrivateDir(context, item, source)
                }
            }
            return@runCatching
        }

        // ---- ④ 公共目录不可用 → 直接退到应用私有外部目录 ----
        copyToPrivateDir(context, item, source)
    }.onFailure { t ->
        installLog("兜底流程异常：${t.javaClass.simpleName}: ${t.message}")
        android.util.Log.e(INSTALL_TAG, "fallbackToManualInstall failed", t)
        // 兜底自己都出错了：用户视角仍必须有一句可读反馈，不能静默
        runCatching {
            Toast.makeText(
                context,
                "安装兜底也失败了，请查看「日志」按钮里的 [install] 记录。",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

/**
 * 兜底第④ 级：复制到应用私有外部目录（必定可写、已在 file_paths.xml 的 external-files-path 内）。
 * 独立成函数是因为它在两个地方被调用（公共目录不可用 / 公共目录复制失败），都需要各自的保护。
 */
private fun copyToPrivateDir(
    context: android.content.Context,
    item: ReceivedItem,
    source: File,
) {
    val privBase = runCatching { context.getExternalFilesDir(null) }.getOrNull()
    if (privBase == null) {
        installLog("兜底失败：getExternalFilesDir 返回 null，无可写目录")
        toastNowritable(context, source)
        return
    }
    val dir = File(privBase, ReceiverStore.SAVE_ROOT)
    installLog("兜底：开始后台复制到应用私有目录 ${dir.absolutePath}")
    copyInBackground(context, source, dir, item.name) { moved ->
        if (moved != null) {
            installLog("兜底：公共目录不可用，已复制到应用私有目录：${moved.absolutePath}")
            installLog("WARN 该路径在 Android/data 下，电视文件管理器很可能看不到（见函数注释 TODO）")
            runCatching {
                Toast.makeText(
                    context,
                    "系统安装器不可用且公共下载目录不可写。\n" +
                        "已放到应用目录，可用下方「打开」按钮查看：\n${moved.absolutePath}",
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure { android.util.Log.e(INSTALL_TAG, "toast failed", it) }
        } else {
            installLog("兜底失败：所有候选目录均不可写，保留原路径 ${source.absolutePath}")
            toastNowritable(context, source)
        }
    }
}

/** 提示「用电视文件管理打开这个绝对路径」；[path] 一定拼进文案，用户才有事可做 */
/** 系统里下载目录的真实名字（通常是英文 `Download`）。
 *  UI 上不要写「下载」这种中文本地化词——用户在文件管理器里看到的是英文，
 *  显示中文会让用户怀疑自己找错了地方。 */
private fun downloadsDirName(): String = try {
    android.os.Environment
        .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        .name
} catch (t: Throwable) {
    android.os.Environment.DIRECTORY_DOWNLOADS
}

private fun toastManualPath(context: android.content.Context, path: String) {
    runCatching {
        Toast.makeText(
            context,
            "本机没有系统安装器，请用电视的「文件管理 / 应用中心」打开并安装。\nAPK 路径：$path",
            Toast.LENGTH_LONG,
        ).show()
    }.onFailure { android.util.Log.e(INSTALL_TAG, "toast failed", it) }
}

/** 所有目录都写不进去时的最后一句话 */
private fun toastNowritable(context: android.content.Context, source: File) {
    runCatching {
        Toast.makeText(
            context,
            "系统安装器不可用，且没有可写入的目录。\n文件仍在原处：${source.absolutePath}",
            Toast.LENGTH_LONG,
        ).show()
    }.onFailure { android.util.Log.e(INSTALL_TAG, "toast failed", it) }
}

/**
 * 本项目自己的 HTTP 服务上「按 receivedId 取回 APK」的地址，用于下轮的`GET /apk/<id>` 端点
 * （见 [fallbackToManualInstall] 的 TODO）。**本轮端点尚未实现**，所以只写进日志不写进Toast——
 * 给用户一个点不动的链接比不给更糟。端点落地后把这里的地址加进 Toast 文案即可。
 */
private fun apkHttpUrl(item: ReceivedItem): String? {
    val st = ReceiverService.status.value
    if (!st.running || st.lanIp.isBlank()) return null
    return "http://${st.lanIp}:${st.port}/apk/${item.id}"
}

/**
 * 公共 Downloads 根目录。
 * `getExternalStoragePublicDirectory` 返回的目录未必真实存在（外置存储未挂载时），
 * 调用方要用isUnder / mkdirs 处理这种情况，故返回原对象、不做校验。
 *
 * 但它**自己也会抛**（外置存储异常/未挂载时抛 IllegalStateException/"Unable to create"），
 * 所以调用链上不允许裸调——统一走 [publicDownloadDirOrNull]，拿不到就当「公共目录不可用」。
 */
@Suppress("DEPRECATION") // 项目里 ReceiverServer/locateReceivedFile 都用同一 API，保持一致
private fun publicDownloadDir(): File =
    android.os.Environment
        .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)

/** [publicDownloadDir] 的不抛版本：拿不到公共目录时返回 null（调用方按「不可用」处理） */
private fun publicDownloadDirOrNull(): File? = runCatching { publicDownloadDir() }.getOrNull()

// ---- 授权页跨次推进的状态（SharedPreferences）----

/**
 * 单独一个 prefs 文件，不复用 ReceiverStore 的 `receiver`：那个文件存的是接收侧配置
 * （保存子目录/降级目录），安装流程的 UI 状态混进去会让两边职责互相污染。
 */
private const val INSTALL_PREFS = "tv_install"

/** 见 [requestInstallPermission]：已尝试过「不带 data 的全局未知来源列表页」 */
private const val KEY_TRIED_GLOBAL_LIST = "tried_global_list"

private fun installPrefs(context: android.content.Context) =
    context.getSharedPreferences(INSTALL_PREFS, android.content.Context.MODE_PRIVATE)

/**
 * 把一段文件复制丢到后台线程，完成后回主线程回调 [onDone]（成功传目标文件，失败传 null）。
 *
 * 为什么必须有这个：电视 CPU + 慢闪存下，24.8MB 的 `copyTo` 在主线程极易吃满 5s ANR 阈值。
 * 原实现在崩点 1 必崩时用户永远走不到复制这一步，修复后才第一次真正执行到——
 * 也就是说 ANR 是「修复后新暴露」的风险，必须同轮一起解决，不能留到下轮。
 *
 * 用裸 [Thread] 而非协程：调用点在非 suspend 的 onClick/回调里，且要严格「拷贝完成再回主线程」，
 * 不需要结构化并发。
 */
private fun copyInBackground(
    context: android.content.Context,
    source: File,
    dir: File,
    name: String,
    onDone: (File?) -> Unit,
) {
    Thread {
        val dest = File(dir, name)
        val ok = runCatching {
            if (!dir.exists() && !dir.mkdirs()) return@runCatching false
            source.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            dest.length() > 0L
        }.getOrDefault(false)
        // 拷贝可能失败（源被清理/空间不足/目录建不出来），统一回主线程给 Toast，
        // 绝不在后台线程里碰 UI。
        onMainThread(context) { onDone(if (ok) dest else null) }
    }.start()
}

/**
 * 把回调切回主线程执行；context 不是 Activity 时（如 Service 上下文）退到主 Looper。
 *
 * 这里**整体包 runCatching**：本函数的所有调用点都在「后台复制完成 → 回主线程」的位置，
 * 该位置已经脱离了 [fallbackToManualInstall] / [installApk] 的 try 作用域，
 * 回调里任何异常都会直接杀掉主线程（Android 8.0 上是「电视直接黑屏/回到桌面」，
 * 连 crash 弹窗都不会有）。宁可多包一层，也不要在这里留缝。
 */
private fun onMainThread(context: android.content.Context, block: () -> Unit) {
    val activity = context as? android.app.Activity
    val run = Runnable {
        runCatching(block).onFailure { t ->
            android.util.Log.e(INSTALL_TAG, "主线程回调异常", t)
            runCatching {
                ReceiverService.appendLog("[install] 主线程回调异常：${t.javaClass.simpleName}: ${t.message}")
                Toast.makeText(context, "安装回调异常，已记录到日志。", Toast.LENGTH_LONG).show()
            }
        }
    }
    if (activity != null) {
        activity.runOnUiThread(run)
    } else {
        android.os.Handler(android.os.Looper.getMainLooper()).post(run)
    }
}

/**
 * 一键安装 APK。设计原则：**无论走哪条路径，用户都必须看到明确反馈，绝不静默失败，更不能崩溃。**
 *
 * 1) 先定位文件真实位置（后续所有失败提示都要靠它报路径）
 * 2) API 26+ 申请「安装未知应用」授权；URI 形式按「已试过全局列表页」标志跨次点击推进，
 *    ROM 完全没有授权页则转手动兜底（见 [requestInstallPermission]）
 * 3) **后台**复制到 cacheDir/installs，完成后回主线程经 FileProvider 交给系统安装器
 *    （ACTION_VIEW / ACTION_INSTALL_PACKAGE 都试）
 * 4) 任一环节失败 → [fallbackToManualInstall]，把 APK 放到用户找得到的位置并报出路径
 *
 * ## 崩溃史（这才是本函数存在的原因）
 *
 * 原始版本的 `startActivity` 是裸奔的：定制 TV ROM 上
 * `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 不存在时抛 `ActivityNotFoundException`，
 * RuntimeException 直接冒泡到 Compose 的 onClick lambda → Activity 闪退，
 * 末尾的 `e.printStackTrace()` 在电视上等于什么都没做（用户只看到「点一下就没了」）。
 *
 *澄清一条曾经写错、现已被推翻的论断：**`FileProvider.getUriForFile` 抛 IAE从来不是崩溃点**。
 * `cacheDir` 是应用私有 data 分区，必然存在且可写；且原代码那行本来就在 try 块内，
 * 抛了也会被外层 catch 掉。它只是一条**误导性错误信息**的来源（真正原因是 mkdirs 失败，
 * 但异常文案指向 file_paths.xml 配置），值得单独 try/catch 仅为可诊断性，与「崩溃」无关。
 * 记录在此以免后续排查再次被这条错误线索带偏。
 *
 * ## 为什么整体包 runCatching
 *
 * 入口是 Compose `onOpen` lambda，那里**没有任何** try/catch，异常直达 Activity。
 * 所以本函数从入口到每一条 return 路径都必须被 Throwable 级保护罩住：
 * `fallbackToManualInstall` 自己依赖的存储 API 在同一批定制 ROM 上也会抛，
 * 若只保护主流程、兜底裸奔，闪退照样发生（这正是上一轮的真实漏洞）。
 * 宁可整体包一次，也不要留缝——电视上崩溃我们看不到任何信息。
 *
 * ## 已知缺陷（本轮不修，影响测试规程）
 *
 * [ReceivedItem] 没有记录文件**实际落盘路径**，而定位走的是「当前」的保存子目录
 * （[locateReceivedFile]）。用户接收后若改了保存位置，就会去新目录找旧文件 → 找不到。
 * 判读影响：出现「接收目录里找不到 xxx」时，**先确认用户是否改过保存位置**，
 * 改过则这条日志是预期行为而非接收侧 bug。本轮靠 Toast 明确提示「请重新发送」，不会误报成功。
 */
private fun installApk(context: android.content.Context, item: ReceivedItem) {
    // 步骤 1~3 全部包在 runCatching 里；onFailure 只做「记日志 + Toast」，绝不自己再抛。
    runCatching { installApkSteps(context, item) }.onFailure { t ->
        installLog("安装流程未捕获异常：${t.javaClass.simpleName}: ${t.message}")
        android.util.Log.e(INSTALL_TAG, "installApk crashed", t)
        runCatching {
            Toast.makeText(context, "安装流程异常，已记录到日志。", Toast.LENGTH_LONG).show()
        }
    }
}

/** [installApk] 的真实实现。异常一律交给 [installApk] 的 runCatching，本函数内不自己兜。 */
private fun installApkSteps(context: android.content.Context, item: ReceivedItem) {
    installLog("=== 开始安装 ${item.name} (${formatSize(item.size)}) sdk=${Build.VERSION.SDK_INT}")
    apkHttpUrl(item)?.let { installLog("下轮HTTP 下载端点地址（尚未实现）：$it") }

    // ---- 步骤 1：先定位文件。任何失败提示都要靠它报出真实路径，所以必须放在最前面 ----
    val source = runCatching { locateReceivedFile(context, item.name) }
        .onFailure { installLog("定位文件时异常：${it.javaClass.simpleName}: ${it.message}") }
        .getOrNull()
    if (source == null) {
        // 见函数注释「已知缺陷」：接收记录里没存落盘时的目录。
        installLog("WARN 接收目录里找不到 ${item.name}（可能已被清理，或接收后改过保存位置）")
    } else {
        installLog("源文件：${source.absolutePath} (${source.length()} B)")
    }

    // ---- 步骤 2：安装未知应用授权（API 26 起）----
    if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
        if (requestInstallPermission(context)) {
            runCatching {
                Toast.makeText(
                    context,
                    "请在系统设置里允许本应用「安装未知应用」，然后返回再点一次安装。",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        installLog("未获授权且本机无授权页，转手动安装兜底")
        fallbackToManualInstall(context, item, source)
        return
    }

    // 已授权：清掉跨次推进标志，下次点安装从头走正常流程
    clearTriedGlobalFlag(context)

    // ---- 步骤 3：后台复制到 cacheDir/installs，完成后回主线程交给系统安装器 ----
    // mkdirs() 的返回值要检查：失败时文件确实写不进去，后续 FileProvider.getUriForFile
    // 会抛 IllegalArgumentException("Failed to find configured root")，报错指不到真正原因。
    // 目录建不出来就没必要启动后台线程了。
    val installsDir = File(context.cacheDir, "installs")
    if (!installsDir.exists() && !installsDir.mkdirs()) {
        installLog("WARN 缓存安装目录创建失败：${installsDir.absolutePath}")
        fallbackToManualInstall(context, item, source)
        return
    }
    val target = File(installsDir, item.name)

    // 24.8MB 的复制绝不能在主线程：电视 CPU + 慢闪存极易吃满 5s ANR。
    // copyFromDownloads 有 MediaStore(API29+) / File 两条分支，留在它内部处理分支，
    // 这里只负责「搬到后台线程 + 回主线程」。
    copyForInstallInBackground(context, item.name, target) { ok ->
        // 回调已在主线程，但仍要独立保护：这里是新线程的栈，异常会直接杀掉主线程
        runCatching { continueInstallOnUiThread(context, item, source, target, ok) }
            .onFailure { t ->
                installLog("安装收尾异常：${t.javaClass.simpleName}: ${t.message}")
                android.util.Log.e(INSTALL_TAG, "continueInstall crashed", t)
                runCatching { fallbackToManualInstall(context, item, source) }
            }
    }
}

/** 把 [copyFromDownloads] 搬到后台线程执行，结果回主线程回调（true=复制成功） */
private fun copyForInstallInBackground(
    context: android.content.Context,
    displayName: String,
    target: File,
    onDone: (Boolean) -> Unit,
) {
    Thread {
        installLog("开始后台复制 ${displayName} → ${target.absolutePath}")
        val ok = runCatching { copyFromDownloads(context, displayName, target) }
            .onFailure { installLog("后台复制异常：${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
        installLog("后台复制结束：${if (ok) "成功" else "失败"}（耗时复制已完成）")
        onMainThread(context) { onDone(ok) }
    }.start()
}

/** 后台复制成功后在主线程执行：校验 → FileProvider → 拉起安装器 → 兜底。 */
private fun continueInstallOnUiThread(
    context: android.content.Context,
    item: ReceivedItem,
    source: File?,
    target: File,
    copied: Boolean,
) {
    if (!copied) {
        installLog("复制失败：接收目录中已无 ${item.name}")
        runCatching {
            Toast.makeText(
                context,
                "文件已不在接收目录，可能已被清理。请用手机重新发送一次再安装。",
                Toast.LENGTH_LONG,
            ).show()
        }
        fallbackToManualInstall(context, item, source)
        return
    }
    if (!target.exists() || target.length() == 0L) {
        installLog("复制失败：目标不存在或长度为 0（${target.absolutePath}）")
        runCatching {
            Toast.makeText(
                context,
                "复制安装包失败（目标文件为空），请重新发送后再试。",
                Toast.LENGTH_LONG,
            ).show()
        }
        fallbackToManualInstall(context, item, source)
        return
    }
    installLog("已复制到缓存：${target.absolutePath} (${target.length()} B)")

    // getUriForFile 单独 catch：它抛的是 IllegalArgumentException，
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

    installLog("两种安装 action 均失败，尝试应用内安装（PackageInstaller API）")
    if (installViaPackageInstaller(context, target)) return

    installLog("应用内安装也不可用，转手动安装兜底")
    fallbackToManualInstall(context, item, source)
}
/**
 * 用 [android.content.pm.PackageInstaller] 走**应用内安装**，绕开「系统没有安装器 app」的问题。
 *
 * ## 为什么需要这条路径
 * 部分定制电视 ROM（TCL 65V6-A65F 实测）**根本没有 `com.android.packageinstaller`**，
 * 也没有任何 activity 响应 `ACTION_VIEW` / `ACTION_INSTALL_PACKAGE` +
 * `application/vnd.android.package-archive`，`startActivity` 抛 `ActivityNotFoundException`，
 * 用户只能拿到一句「本机没有系统安装器」——功能上等于没有「安装」按钮。
 *
 * `PackageInstaller` 是**框架 API**，由系统包管理器自己弹确认界面，
 * **不依赖任何第三方安装器 app**，因此在这类 ROM 上仍可用。
 *
 * ## API 硬限制（不要误认为能做到静默安装）
 * - **API 21+**（本项目 minSdk 21，刚好满足）
 * - **必须用户确认**。普通应用没有 `INSTALL_PACKAGES` 签名级权限，
 *   静默安装会失败且属于违规；这里走系统弹确认界面的合规路径。
 * - `REQUEST_INSTALL_PACKAGES`（API 26+）是用户确认式安装的前置权限，
 *   缺会抛 `SecurityException`，需用户先去系统设置里开启。
 *
 * @return true 表示已成功交给系统安装流程（用户会看到确认界面）
 */
private fun installViaPackageInstaller(
    context: android.content.Context,
    apk: File,
): Boolean {
    if (Build.VERSION.SDK_INT < 21) return false
    if (!apk.exists() || apk.length() <= 0L) {
        installLog("PackageInstaller：APK 不存在或为空，放弃")
        return false
    }
    var sessionId = -1
    return try {
        // ★ 必须用 getPackageManager().packageInstaller 显式取：
        // `packageInstaller` 是 Context 的 Kotlin 扩展属性，当参数静态类型是
        // android.content.Context（非 ContextWrapper 子类）时扩展解析不出来。
        val pi = context.getPackageManager().packageInstaller
        // ★ 统一用 SessionParams(MODE_FULL_INSTALL) —— 这个重载从 API 21 就有，
        // 无参 createSession() 在 SDK 34 的 stub 里已被移除（编译报缺参数 p0），
        // 用版本分支反而在低版本上踩坑。
        sessionId = pi.createSession(
            android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ),
        )

        // Kotlin 1.9 下 PackageInstaller.Session 的 use{} 扩展在 Android SDK 34 上有歧义
        // （Session 实现 Closeable 但 SDK stub 里close() 标了throws），直接用 try/finally 更稳。
        val session = pi.openSession(sessionId)
        try {
            val out = session.openWrite("base.apk", 0, -1)
            try {
                apk.inputStream().use { input -> input.copyTo(out, 128 * 1024) }
            } finally {
                runCatching { out.close() }
            }
            // ★ 统一用带 IntentSender 的 commit，无版本分支。
            //   - IntentSender 从 API 21 就有，低版本同样可用
            //   - 无参 commit() 在 SDK 34 的 stub 里已被移除（编译报缺参数 p0）
            //   - FLAG_MUTABLE 是 API 31+ 常量，必须版本守卫，
            //     低版本直接引用会是 NoSuchFieldError（Error，不是 Exception）
            val piIntent = android.content.Intent(context, InstallResultReceiver::class.java)
            val mutable = if (Build.VERSION.SDK_INT >= 31)
                android.app.PendingIntent.FLAG_MUTABLE else 0
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or mutable
            // PendingIntent.getBroadcast 返回平台类型（PendingIntent!），
            // Kotlin 不允许隐式赋给 IntentSender，必须显式 as
            val sender = android.app.PendingIntent
                .getBroadcast(context, sessionId, piIntent, flags)
                as android.content.IntentSender
            session.commit(sender)
        } finally {
            runCatching { session.close() }
        }
        installLog("PackageInstaller：会话已提交 sessionId=$sessionId，等待系统确认安装")
        Toast.makeText(context, "已交给系统安装，请确认", Toast.LENGTH_LONG).show()
        true
    } catch (t: Throwable) {
        // 常见失败：REQUEST_INSTALL_PACKAGES 未授权(SecurityException)、空间不足、签名冲突。
        // **全部如实上报，绝不做假成功。**
        installLog("PackageInstaller 失败：${t.javaClass.simpleName}: ${t.message}")
        if (sessionId >= 0) runCatching { context.getPackageManager().packageInstaller.abandonSession(sessionId) }
        val needPerm = Build.VERSION.SDK_INT >= 26 &&
            !context.packageManager.canRequestPackageInstalls()
        Toast.makeText(
            context,
            if (needPerm) "请先在系统设置里允许「安装未知应用」"
            else "本机无法应用内安装，请用文件管理/应用中心安装",
            Toast.LENGTH_LONG,
        ).show()
        false
    }
}

/**
 * 接收 PackageInstaller 的会话回调（API 21+）：只如实上报，不自动重试。
 *
 * ★ 回调时序必须知道：`session.commit()` 之后**第一个**回调状态是
 * `STATUS_PENDING_USER_ACTION`(-1)，系统把「安装确认界面」的 Intent 塞在
 * `EXTRA_INTENT` 里交还给应用，**必须由应用自己 startActivity 拉起**。
 * 不处理这个状态 = 用户永远看不到确认界面 = PackageInstaller 路径整体失效
 * （表现恰是「Toast 说已交给系统安装，随后却弹 安装失败：null」——首轮实现踩过的坑）。
 */
class InstallResultReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        val status = intent.getIntExtra(
            android.content.pm.PackageInstaller.EXTRA_STATUS,
            android.content.pm.PackageInstaller.STATUS_FAILURE,
        )
        android.util.Log.i(INSTALL_TAG, "系统安装回调 status=$status")
        if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // getParcelableExtra(String) 在 API 33 起标废弃，但全版本可用且此处必须用裸 Intent 版本。
            // 注意 extra key 是 Intent.EXTRA_INTENT（官方示例即此写法），
            // PackageInstaller 类里没有 EXTRA_INTENT 这个常量。
            @Suppress("DEPRECATION")
            val confirm = runCatching {
                intent.getParcelableExtra<android.content.Intent>(Intent.EXTRA_INTENT)
            }.getOrNull()
            if (confirm == null) {
                android.util.Log.e(INSTALL_TAG, "PENDING_USER_ACTION 但 EXTRA_INTENT 为空")
                Toast.makeText(context, "安装确认界面不可用，请改用文件管理安装", Toast.LENGTH_LONG).show()
                return
            }
            try {
                // 广播接收器没有 Activity 栈，必须带 NEW_TASK 才能拉起系统界面
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                android.util.Log.i(INSTALL_TAG, "已拉起系统安装确认界面，等待用户确认")
            } catch (t: Throwable) {
                // 定制 ROM 可能连系统的确认 activity 都没有——如实上报，不装成功
                android.util.Log.e(INSTALL_TAG, "拉起确认界面失败", t)
                Toast.makeText(
                    context,
                    "本机无法弹出安装确认界面，请用文件管理/应用中心安装",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
        android.util.Log.i(INSTALL_TAG, "系统安装结果 status=$status msg=$msg")
        val text = if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS)
            "安装成功"
        else
            "安装失败：${msg ?: "未知原因"}"
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
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

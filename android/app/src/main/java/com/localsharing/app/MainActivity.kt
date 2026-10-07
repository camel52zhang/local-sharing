package com.localsharing.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.localsharing.app.ui.ConnectScreen
import com.localsharing.app.ui.DevicePickerScreen
import com.localsharing.app.ui.HistoryScreen
import com.localsharing.app.ui.HomeScreen
import com.localsharing.app.ui.ScannerScreen
import com.localsharing.app.ui.theme.LocalSharingTheme
import com.localsharing.app.viewmodel.ShareViewModel

/**
 * 为什么是 [ComponentActivity] 而非 [FragmentActivity]：
 *
 * FragmentActivity 在 [requestPermissions] 时通过 checkForValidRequestCode() 校验
 * requestCode 必须 ≤ 0xFFFF。该 requestCode 由 ActivityResultRegistry.mNextRc 分配——
 * 在 activity-compose 1.9.0 下 Compose launcher 反复 register 把 mNextRc 推到了上限之后，
 * 即便换成 Activity 层原生 launcher 也共享同一个 registry → 继承高值 → 校验抛异常崩溃。
 * （栈：FragmentActivity.checkForValidRequestCode → ActivityCompat.requestPermissions → crash）
 *
 * ComponentActivity 无此校验，也不依赖 Fragment 管理，对纯 Compose 应用完全够用。
 * 同时 activity-compose 已升级到 1.9.3，rememberLauncherForActivityResult 不再重组重注册。
 */
class MainActivity : ComponentActivity() {
    private val vm: ShareViewModel by viewModels()
    private val _scanning = mutableStateOf(false)

    // 原生 registerForActivityResult：Activity 构造时注册一次，永不重注册。
    // ComponentActivity 无需关心 requestCode 大小——它不校验低 16 位。
    private val camPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) _scanning.value = true
        else Toast.makeText(this, "扫码需要相机权限", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 电视设备兜底：无论用户从哪个入口打开（安装器"打开"按钮/第三方桌面/文件管理器
        // 都会落到默认 LAUNCHER 即本 Activity），只要是电视环境就重定向到电视接收模式。
        // 电视桌面(LEANBACK_LAUNCHER)本应直达 TvReceiverActivity，但部分 TCL/小米/第三方
        // 电视桌面会解析到默认启动项，导致用户看到手机版"扫码连接"界面——电视没有相机
        // 也没有触屏，该界面完全不可用，故此处必须兜底。
        if (isTvDevice()) {
            startActivity(Intent(this, com.localsharing.app.receiver.TvReceiverActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            LocalSharingTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(
                        vm = vm,
                        scanning = _scanning,
                        onScanRequest = {
                            try {
                                camPermissionLauncher.launch(Manifest.permission.CAMERA)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "camLaunch: ${e.message}", e)
                                Toast.makeText(this@MainActivity, "扫码失败: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }
            }
        }
        // 冷启动时被系统分享面板唤起：处理带入的分享 Intent
        handleSend(intent)
    }

    /**
     * App 已在运行时，用户再次从系统分享面板选择本应用 → singleTask 复用同一 Activity 并走到 onNewIntent。
     * 这里更新 Intent 并分发分享内容。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSend(intent)
    }

    /** 若 Intent 为分享动作，则交给 VM 解析并暂存 */
    private fun handleSend(intent: Intent?) {
        if (intent?.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            vm.handleIncomingShare(intent)
        }
    }

    /** 判定当前是否电视/盒子设备：leanback 特征、television 特征或系统 UI 模式为电视任一命中 */
    private fun isTvDevice(): Boolean {
        val pm = packageManager
        if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) return true
        if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION)) return true
        return try {
            // 必须用 getSystemService(String)：Class 重载是 API 23+。
            // Android 5.0 上会抛 NoSuchMethodError —— 那是 Error 不是 Exception，
            // 原来的 catch (e: Exception) 根本拦不住，会导致 onCreate 直接崩。
            val umm = getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            umm?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        } catch (t: Throwable) {
            false
        }
    }
}

/**
 * 根导航：扫码 / 设备选择 / 发送页 / 首次连接（PRD 5.1 四态）。
 *
 * 关键规则（PRD 5.2②）：**只有 ≥2 台设备时才进 [DevicePickerScreen]**。
 * 单设备时首页顶部的目标条点了直接回连当前设备 —— 没有得选，多一次点击纯属噪音。
 */
@Composable
fun AppRoot(
    vm: ShareViewModel,
    scanning: MutableState<Boolean>,
    onScanRequest: () -> Unit,
) {
    val devices by vm.devices.collectAsState()
    val active by vm.activeDevice.collectAsState()
    // 切到设备列表/历史的局部导航状态。用 rememberSaveable 让返回键与旋转后不丢
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }

    when {
        scanning.value -> ScannerScreen(
            onResult = { url ->
                scanning.value = false
                vm.connectFromUrl(url)
            },
            onCancel = { scanning.value = false },
        )

        showHistory -> HistoryScreen(vm, onBack = { showHistory = false })

        showPicker -> DevicePickerScreen(
            vm = vm,
            onScan = onScanRequest,
            onBack = { showPicker = false },
            onPicked = { showPicker = false },
        )

        // 有目标设备即可进发送页：连接中/重连中也要能进去看到横幅与进度（PRD §3.2）
        active != null -> HomeScreen(
            vm = vm,
            onOpenPicker = { showPicker = true },
            onOpenHistory = { showHistory = true },
        )

        // 设备列表为空 = 首次使用
        devices.isEmpty() -> ConnectScreen(
            vm = vm,
            onScan = onScanRequest,
            onOpenManual = { showPicker = true },
            showManual = false,
        )

        // 列表非空但当前无目标（如用户显式断开过）→ 回到设备列表重新选一台
        else -> {
            // 这个 else 分支里 showPicker 本来就是 false，原来的 onBack={showPicker=false}
            // 是空操作 → 系统返回键完全无效，用户被困在这一页。
            // 兜底：让返回键有真实退路 —— 选中列表第一台（等价于「取消断开」），
            // 列表为空则回到扫码页。
            val fallback = remember(devices) { devices.firstOrNull() }
            DevicePickerScreen(
                vm = vm,
                onScan = onScanRequest,
                onBack = {
                    if (fallback != null) vm.selectDevice(fallback.id) else onScanRequest()
                },
                onPicked = { showPicker = false },
            )
        }
    }
}

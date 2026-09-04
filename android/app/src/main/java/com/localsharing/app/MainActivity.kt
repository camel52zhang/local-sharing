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
import androidx.compose.ui.Modifier
import com.localsharing.app.ui.ConnectScreen
import com.localsharing.app.ui.HomeScreen
import com.localsharing.app.ui.ScannerScreen
import com.localsharing.app.ui.theme.LocalSharingTheme
import com.localsharing.app.viewmodel.ConnState
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
}

@Composable
fun AppRoot(
    vm: ShareViewModel,
    scanning: MutableState<Boolean>,
    onScanRequest: () -> Unit,
) {
    val conn by vm.connState.collectAsState()

    when {
        scanning.value -> ScannerScreen(
            onResult = { url ->
                scanning.value = false
                vm.connectFromUrl(url)
            },
            onCancel = { scanning.value = false },
        )
        conn is ConnState.Connected -> HomeScreen(vm)
        else -> ConnectScreen(vm, onScan = onScanRequest)
    }
}

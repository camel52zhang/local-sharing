package com.localsharing.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.localsharing.app.model.SenderDevice
import com.localsharing.app.viewmodel.ConnState
import com.localsharing.app.viewmodel.ShareViewModel

/**
 * 设备选择页：切发送目标 + 管理设备列表（PRD R5）。
 *
 * 入口：HomeScreen 顶部「发送至：xxx ▾」/ ConnectScreen 列表的「管理」。
 * 单设备时**不进本页**（PRD 5.2②）——没有得选，进页反而多一次无意义点击。
 *
 * @param onBack 返回上一页；选定设备后由调用方决定是否 pop。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerScreen(
    vm: ShareViewModel,
    onScan: () -> Unit,
    onBack: () -> Unit,
    onPicked: () -> Unit = onBack,
) {
    val devices by vm.devices.collectAsState()
    val active by vm.activeDevice.collectAsState()
    val conn by vm.connState.collectAsState()

    // ★ 系统返回键必须被接管。只在顶栏箭头 onClick 里处理 onBack是不够的 ——
    // 电视/手机的实体返回键、 gesturer back手势走的都是这里，
    // 不注册的话用户在「已断开」状态下会被困在这一页（QA 在 P1-2 抓到的）。
    BackHandler(onBack = onBack)
    val uploading by vm.uploading.collectAsState()
    val error by vm.error.collectAsState()
    val dup by vm.pendingDup.collectAsState()

    var renaming by remember { mutableStateOf<SenderDevice?>(null) }
    var removing by remember { mutableStateOf<SenderDevice?>(null) }
    var showManual by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择接收端") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 当前目标信息条：非可点，纯信息（PRD 5.2②）
            if (active != null) {
                item {
                    Column {
                        Text(
                            "当前：${active?.label.orEmpty()}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "${active?.key.orEmpty()} · ${timeAgoDesc(active?.lastOkAt ?: 0L)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            if (devices.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.QrCodeScanner,
                        title = "还没有添加接收端",
                        subtitle = "扫码添加，或手动输入 IP 地址",
                    )
                }
            }

            items(devices, key = { it.id }) { d ->
                var menuOpen by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeviceRow(
                        device = d,
                        isActive = d.id == active?.id,
                        online = d.id == active?.id && conn == ConnState.Connected,
                        // 上传中置灰：交互层门闸（正确性由SendTarget 快照保证）
                        enabled = !uploading,
                        onClick = {
                            vm.selectDevice(d.id)
                            onPicked()
                        },
                        menuContent = { open ->
                            DropdownMenu(expanded = open, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    onClick = { menuOpen = false; renaming = d },
                                )
                                DropdownMenuItem(
                                    text = { Text("移除") },
                                    enabled = !uploading && d.id != active?.id,
                                    onClick = { menuOpen = false; removing = d },
                                )
                            }
                        },
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onScan,
                    enabled = !uploading,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                ) {
                    Icon(Icons.Filled.QrCodeScanner, null, Modifier.height(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("扫码添加设备", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showManual = !showManual },
                    enabled = !uploading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (showManual) "收起手动输入" else "手动输入 IP") }

                if (error.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (showManual) {
                item {
                    ManualAddCard(
                        busy = conn == ConnState.Connecting,
                        onSubmit = { host, port, code ->
                            vm.addDeviceManually(host, port, code)
                            showManual = false
                        },
                    )
                }
            }
        }
    }

    // ---- 重命名对话框 ----
    renaming?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.alias ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名「${target.label}」") },
            text = {
                Column {
                    Text(
                        "留空则清除别名，显示设备真实名称",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text("别名") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameDevice(target.id, text)
                    renaming = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            },
        )
    }

    // ---- 移除确认 ----
    removing?.let { target ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除「${target.label}」？") },
            text = { Text("只从列表移除，已发送的文件不受影响。重新扫码可再次添加。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeDevice(target.id)
                    removing = null
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text("取消") }
            },
        )
    }

    // ---- 同名设备去重确认（PRD R3/D2）----
    dup?.let {
        AlertDialog(
            onDismissRequest = { vm.dismissDuplicate() },
            title = { Text("已存在「${it.name}」") },
            text = {
                Text(
                    "列表里已有一台名为「${it.name}」的设备（${it.existingId.take(6)}…）。\n" +
                        "这台的地址是 ${it.host}:${it.port}。\n\n" +
                        "同型号的两台设备名字会一样，请选择如何处理。",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.resolveDuplicate(updateExisting = false) }) {
                    Text("添加为新设备")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.resolveDuplicate(updateExisting = true) }) {
                        Text("更新原设备")
                    }
                    TextButton(onClick = { vm.dismissDuplicate() }) { Text("取消") }
                }
            },
        )
    }
}

/** 手动输入卡片（PRD R4/Q5：局域网里手动输入仍是唯一的救命通道，故保留但降级为次要入口） */
@Composable
private fun ManualAddCard(
    busy: Boolean,
    onSubmit: (host: String, port: String, code: String) -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("38080") }
    var code by remember { mutableStateOf("") }

    OutlinedTextField(
        value = host, onValueChange = { host = it },
        label = { Text("IP 地址") }, placeholder = { Text("例如 192.168.1.20") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
            label = { Text("端口") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.weight(0.4f),
        )
        OutlinedTextField(
            value = code, onValueChange = { code = it },
            label = { Text("共享码（可选）") },
            singleLine = true, modifier = Modifier.weight(0.6f),
        )
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { onSubmit(host.trim(), port.trim(), code.trim()) },
        enabled = host.isNotBlank() && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("添加")
    }
}
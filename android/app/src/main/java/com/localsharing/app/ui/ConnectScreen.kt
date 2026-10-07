package com.localsharing.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
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
import com.localsharing.app.viewmodel.ConnState
import com.localsharing.app.viewmodel.ShareViewModel

/**
 * 首屏（设备列表为空时）/ 无目标态。
 *
 * 上半：设备列表（每行整块可点 → 直接进入发送页），与 [DevicePickerScreen] 共用 [DeviceRow]。
 * 下半：扫码 CTA + 手动输入表单（PRD Q5：局域网里手动输入仍是唯一的救命通道，故保留）。
 */
@Composable
fun ConnectScreen(
    vm: ShareViewModel,
    onScan: () -> Unit,
    onOpenManual: () -> Unit = {},
    showManual: Boolean = false,
) {
    val devices by vm.devices.collectAsState()
    val active by vm.activeDevice.collectAsState()
    val conn by vm.connState.collectAsState()
    val error by vm.error.collectAsState()
    val pending by vm.pendingShare.collectAsState()
    val uploading by vm.uploading.collectAsState()

    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        // 系统分享暂存提示：选定接收端后将自动填入发送列表（不阻塞连接流程）
        if (pending.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Text(
                    "已收到 ${pending.size} 个文件，选择接收端后将自动填入发送列表",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        // 品牌 header
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp)) {
            BrandMark()
            Column(Modifier.padding(start = 12.dp)) {
                Text("局域网互传", style = MaterialTheme.typography.headlineSmall)
                Text("local-sharing", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        // ---- 已记住的设备 ----
        if (devices.isEmpty()) {
            Text(
                "还没有添加接收端",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(
                    "我的接收端",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenManual) { Text("管理 / 添加") }
            }
        }

        devices.forEach { d ->
            DeviceRow(
                device = d,
                isActive = d.id == active?.id,
                online = d.id == active?.id && conn == ConnState.Connected,
                enabled = !uploading,
                onClick = { vm.selectDevice(d.id) },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // 扫码 CTA 置顶（最高频路径）
                Button(
                    onClick = onScan,
                    enabled = conn != ConnState.Connecting,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                ) {
                    Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("扫码添加设备", style = MaterialTheme.typography.labelLarge)
                }

                if (showManual) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    var ip by remember { mutableStateOf("") }
                    var port by remember { mutableStateOf("38080") }
                    var code by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = ip, onValueChange = { ip = it },
                        label = { Text("接收端 IP 地址") }, placeholder = { Text("例如 192.168.1.20") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                            label = { Text("端口") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.weight(0.4f),
                        )
                        OutlinedTextField(
                            value = code, onValueChange = { code = it },
                            label = { Text("共享码（可选）") }, singleLine = true, modifier = Modifier.weight(0.6f),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { vm.addDeviceManually(ip.trim(), port.trim(), code.trim()) },
                        enabled = ip.isNotBlank() && conn != ConnState.Connecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (conn == ConnState.Connecting) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.height(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("手动添加")
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenManual, modifier = Modifier.fillMaxWidth()) {
                        Text("手动输入 IP")
                    }
                }

                if (error.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "提示：先在电脑/电视上启动接收端，再用本机扫描其界面上的二维码，或手动输入 IP。" +
                "扫过的设备会记住，下次直接从列表里选。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

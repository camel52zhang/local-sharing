package com.localsharing.app.ui

import android.os.Build
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.localsharing.app.util.Prefs
import com.localsharing.app.viewmodel.ConnState
import com.localsharing.app.viewmodel.ShareViewModel

@Composable
fun ConnectScreen(vm: ShareViewModel, onScan: () -> Unit) {
    val context = LocalContext.current
    val last = remember { Prefs.getLastConnection(context) }
    var ip by remember { mutableStateOf(last?.first?.removePrefix("http://")?.substringBefore(":") ?: "") }
    var port by remember { mutableStateOf(last?.first?.substringAfterLast(":") ?: "8080") }
    var code by remember { mutableStateOf("") }
    val conn by vm.connState.collectAsState()
    val error by vm.error.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        // 品牌 header
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp)) {
            BrandMark()
            Column(Modifier.padding(start = 12.dp)) {
                Text("局域网互传", style = MaterialTheme.typography.headlineSmall)
                Text("local-sharing", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                OutlinedTextField(
                    value = ip, onValueChange = { ip = it },
                    label = { Text("电脑 IP 地址") }, placeholder = { Text("例如 192.168.1.20") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                        label = { Text("端口") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(0.4f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    OutlinedTextField(
                        value = code, onValueChange = { code = it },
                        label = { Text("共享码（可选）") }, singleLine = true, modifier = Modifier.weight(0.6f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
                Spacer(Modifier.height(16.dp))

                // 扫码 CTA 置顶（最高频路径）
                Button(
                    onClick = onScan,
                    enabled = conn != ConnState.Connecting,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                ) {
                    Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("扫码连接", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.connect(ip, port, code, Build.MODEL, "phone") },
                    enabled = ip.isNotBlank() && conn != ConnState.Connecting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (conn == ConnState.Connecting) {
                        androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("手动连接")
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
            "提示：先在电脑端启动「局域网互传工具」，再用本机扫描其界面上的二维码，或手动输入电脑 IP。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

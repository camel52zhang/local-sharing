package com.localsharing.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localsharing.app.model.SendOutcome
import com.localsharing.app.model.TransferRecord
import com.localsharing.app.util.formatSize
import com.localsharing.app.viewmodel.ShareViewModel

/**
 * 传输历史页（PRD R9，本轮纳入范围）。
 *
 * **诚实标注**：这是**手机侧本地记账**，不是接收端回查（安卓端无 `/api/transfer/` 端点，
 * 见 PRD G3）。每条记录的 `receivedCount` 为 null 时（老版本接收端）显式标注来源，
 * 不把「不知道」粉饰成「成功」。
 *
 * 入口：HomeScreen TopAppBar actions 的「历史」。与「收到的文件」是**两回事**：
 * 后者是别的设备推给本机的，这里是本机发出的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: ShareViewModel, onBack: () -> Unit) {
    val history by vm.history.collectAsState()
    val uploading by vm.uploading.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    // 页面进入时重新读盘：历史是唯一「跨页面可能被别人改过」的数据源
    LaunchedEffect(Unit) { vm.reloadHistory() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("传输历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (history.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text("清空") }
                    }
                },
            )
        },
    ) { padding ->
        if (history.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = "还没有发送记录",
                    subtitle = "发送文件后，这里会记录每一次的结果",
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "共 ${history.size} 条 · 本机记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(history, key = { it.id }) { r ->
                HistoryRow(
                    record = r,
                    // 上传中不显示重发按钮：避免「重发」与「正在发送」两个任务语义打架
                    showRetry = !uploading,
                    onRetry = { vm.retryHistory(r.id) },
                    onDelete = { vm.deleteHistory(r.id) },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部历史？") },
            text = { Text("仅清除这份发送记录列表，已发送到各设备的文件不受影响。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearHistory()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

/** 单条历史记录卡片 */
@Composable
private fun HistoryRow(
    record: TransferRecord,
    showRetry: Boolean,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val (icon, tint, statusText) = when (record.outcome) {
        SendOutcome.SUCCESS -> Triple(
            Icons.Filled.CheckCircle,
            MaterialTheme.colorScheme.tertiary,
            "已发送",
        )
        // PARTIAL 用警告色而非红色：部分落盘不是错误，红色会让人以为要重做
        SendOutcome.PARTIAL -> Triple(
            Icons.Filled.WarningAmber,
            MaterialTheme.colorScheme.tertiary,
            "部分落盘 ${record.receivedCount ?: 0}/${record.requestedCount}",
        )
        SendOutcome.FAILED -> Triple(
            Icons.Filled.WarningAmber,
            MaterialTheme.colorScheme.error,
            "发送失败",
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(20.dp), tint = tint)
                Spacer(Modifier.size(8.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.titleSmall,
                    color = tint,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    fullTimeDesc(record.startedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                record.deviceLabel,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append("${record.targetHost}:${record.targetPort}")
                    append(" · ")
                    // 总量未知（SAF provider 不提供 SIZE 列）时不能显示「0 B」
                    if (!record.sizeUnknown) append(formatSize(record.totalBytes))
                    append(" · 耗时 ${formatDuration(record.durationMs)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val names = record.fileNames
            Text(
                if (names.isEmpty()) "（无文件名）"
                else if (names.size == 1) names[0]
                else "${names[0]} 等 ${names.size} 个文件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )

            // 降级/失败原因必须显式写出，否则用户会以为「静默失败」
            if (record.detail.isNotBlank()) {
                Text(
                    record.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (record.httpCode != 0) {
                Text(
                    "HTTP ${record.httpCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) { Text("删除") }
                if (showRetry) {
                    TextButton(onClick = onRetry) { Text("重发") }
                }
            }
        }
    }
}

/** 耗时文案：不足 1 秒不显示「0 秒」（会让用户以为没发过） */
private fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "不到 1 秒"
    ms < 60_000 -> "${ms / 1000} 秒"
    else -> "${ms / 60_000} 分 ${(ms % 60_000) / 1000} 秒"
}

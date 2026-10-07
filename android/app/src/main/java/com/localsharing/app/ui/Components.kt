package com.localsharing.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Card
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 统一空状态：图标环 + 标题 + 说明 + 可选操作 */
@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape, tonalElevation = 0.dp,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(56.dp),
        ) { Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { Spacer(Modifier.height(16.dp)); it() }
    }
}

/** 漂亮的文件卡片：类型图标 + 名称/元信息 + 可选进度 + 可选操作按钮 */
@Composable
fun FileCard(
    name: String,
    meta: String,
    isFolder: Boolean,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    progress: Float? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (isFolder) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                    null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (progress != null) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
            if (actionLabel != null) {
                Spacer(Modifier.padding(start = 8.dp))
                androidx.compose.material3.Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/**
 * 连接状态横幅。[label] 是设备展示名（alias ?: name）。
 *
 * 离线设备一律用中性文案（「昨天可达」），**不用红色报错** ——
 * 电视关机是常态，用户不该以为设备坏了。
 */
@Composable
fun ConnectionBanner(connected: Boolean, reconnecting: Boolean, label: String) {
    val container = when {
        reconnecting -> MaterialTheme.colorScheme.errorContainer
        connected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = when {
        reconnecting -> MaterialTheme.colorScheme.onErrorContainer
        connected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    val dot = when {
        reconnecting -> MaterialTheme.colorScheme.error
        connected -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    val text = when {
        reconnecting -> "连接已断开，正在重连…"
        connected -> "已连接到 $label"
        label.isNotBlank() -> "未连接到 $label"
        else -> "未连接到设备"
    }
    Surface(color = container, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(8.dp), shape = CircleShape, color = dot) {}
            Spacer(Modifier.size(8.dp))
            Text(text, color = onContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 通用分区卡片：标题 + 内容槽。发送区/ 设备区统一用它，避免各页自造标题样式 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** 品牌标记（⇄ 渐变圆角方），用于连接页 header */
@Composable
fun BrandMark() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(44.dp),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⇄", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}

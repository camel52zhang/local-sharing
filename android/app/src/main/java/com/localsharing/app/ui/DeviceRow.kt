package com.localsharing.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localsharing.app.model.SenderDevice
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 「最近可达」相对时间文案。
 *
 * **为什么手写而不用 `java.time`（API 26）/ `DateUtils`**：
 * - `java.time.*` 是 API 26+，本项目 minSdk 21，Lint 把 NewApi 配成 error，会直接构建失败；
 * - `android.text.format.DateUtils` 虽是 API 1，但输出格式不可控，中文语境需要自己挑措辞。
 *
 * 阈值与设计文档 T10 一致：<1min「刚刚」/今天「今天 HH:mm」/昨天「昨天 HH:mm」/更早「M-dd」。
 *
 * @param now 当前时刻，仅用于测试可注入；生产传 [System.currentTimeMillis]
 */
fun timeAgoDesc(ts: Long, now: Long = System.currentTimeMillis()): String {
    if (ts <= 0L) return "从未连通"
    val diff = now - ts
    // 未来时间（系统时钟被改过）不展示负数，按刚刚处理
    if (diff < 0) return "刚刚"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        isSameDay(ts, now) -> "今天 ${hhmm(ts)}"
        isYesterday(ts, now) -> "昨天 ${hhmm(ts)}"
        days < 7 -> "$days 天前"
        else -> monthDay(ts)
    }
}

/**
 * 完整时间戳（历史页用），格式固定 yyyy-MM-dd HH:mm:ss
 *
 * ⚠️ 必须显式传 `Locale.US`：数字格式化受默认 Locale 影响，
 * 在使用非阿拉伯数字locale 的系统（如 fa-IR）会把 2026 渲染成 ٢٠٢٦。
 * 历史记录是对账依据，不能因系统语言而变形。
 */
fun fullTimeDesc(ts: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    return String.format(
        Locale.US,
        "%04d-%02d-%02d %02d:%02d:%02d",
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH) + 1,
        c.get(Calendar.DAY_OF_MONTH),
        c.get(Calendar.HOUR_OF_DAY),
        c.get(Calendar.MINUTE),
        c.get(Calendar.SECOND),
    )
}

private fun hhmm(ts: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

private fun monthDay(ts: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    return String.format(Locale.US, "%d-%02d", c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
        ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

private fun isYesterday(a: Long, b: Long): Boolean {
    val yesterday = Calendar.getInstance().apply {
        timeInMillis = b
        add(Calendar.DAY_OF_YEAR, -1)
    }
    return isSameDay(a, yesterday.timeInMillis)
}

/**
 * 设备列表行。`DevicePickerScreen` 与 `ConnectScreen` 共用 ——
 * PRD R4 手动输入也入库，两处都要渲染同一行，避免样式漂移。
 *
 * @param isActive 是否为当前发送目标：显示状态点，且行左侧描边高亮
 * @param online 该设备会话是否已连接（**仅当前目标**有意义，非目标恒为 false）
 * @param menuContent 行尾 ⋮ 菜单本体（通常是 [androidx.compose.material3.DropdownMenu]）；
 *   为 null 时不渲染菜单按钮。参数是「菜单当前是否展开」，菜单自行绑定该状态。
 */
@Composable
fun DeviceRow(
    device: SenderDevice,
    isActive: Boolean,
    online: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    menuContent: (@Composable (Boolean) -> Unit)? = null,
) {
    // 菜单展开状态提升到行内：IconButton 的 onClick 需要能改它，
    // 而 DropdownMenu 本身必须常驻组合树（它不是普通回调，不能在 onClick 里调用）
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        // enabled=false（上传中）时整行不可点：置灰只是体验层，
        // 正确性由 ShareViewModel.selectDevice 的守卫兜底
        onClick = { if (enabled) onClick() },
        enabled = enabled,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 状态点：**仅当前目标**显示（PRD 5.2②；P1 才考虑全列表探测，R11）
            Box(Modifier.size(width = 10.dp, height = 10.dp)) {
                Surface(
                    shape = CircleShape,
                    color = when {
                        !isActive -> MaterialTheme.colorScheme.surfaceVariant
                        online -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(10.dp),
                ) {}
            }
            Spacer(Modifier.size(12.dp))
            Icon(
                Icons.Filled.Tv,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    // 离线设备的可达性用普通灰字而非红色报错（PRD 5.2②）：
                    // 电视临时关机是常态，报错色会让用户以为设备坏了
                    device.host + ":" + device.port + " · " + timeAgoDesc(device.lastOkAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (menuContent != null) {
                // ≥48dp 可点区域（PRD §5.3 为将来留 D-pad 门）
                Box {
                    IconButton(
                        onClick = { menuOpen = !menuOpen },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "更多操作",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    menuContent(menuOpen)
                }
            }
        }
    }
}
package com.localsharing.app.model

import org.json.JSONObject

/** 电脑端信息（扫码后 GET /api/info） */
data class PcInfo(
    val name: String = "",
    val port: Int = 0,
    val lanIp: String? = null,
    val connectUrl: String = "",
    val requiresCode: Boolean = false,
)

/** 设备注册结果（POST /api/devices） */
data class RegisterResult(
    val deviceId: String = "",
    val token: String = "",
    val wsUrl: String = "",
)

/** 待接收的传输（电脑推送到本机） */
data class IncomingTransfer(
    val id: String,
    val name: String,
    val size: Long,
    val kind: String, // "file" | "folder"
    val saving: Boolean = false,
    val progress: Float = 0f,
    val savedPath: String? = null,
)

/** 已保存到本机的文件 */
data class SavedFile(
    val name: String,
    val size: Long,
    val kind: String,
    val savedPath: String,
    val savedAt: Long = System.currentTimeMillis(),
)

/** 待发送的项（来自文件/文件夹选择） */
data class OutgoingItem(
    val uri: android.net.Uri,
    val displayName: String,
    val size: Long,
    val isFolder: Boolean,
)

/**
 * `POST /api/upload` 响应中的回执部分。
 *
 * **为什么全部字段都是可选**：两端接收端的 `/api/upload` 响应只做了「加法式扩展」，
 * 旧版本 APK 一个字段都没有。解析端必须假设任何一个字段都可能缺席。
 * `null` 的语义是「接收端未回报」，与「回报了 0」严格区分——后者是 PARTIAL 的判据。
 */
data class UploadReceipt(
    /** 接收端确认落盘的个数；null = 接收端未回报（不能据此判PARTIAL） */
    val count: Int? = null,
    /** 接收端侧的传输 id，与电视端接收记录对账用；null = 未回报 */
    val transferId: String? = null,
    /** 真实落盘文件（name含 MediaStore 同名自动改名，如 photo (1).jpg） */
    val files: List<ReceiptFile> = emptyList(),
) {
    /** 是否有任何可用的回执细节：全空说明是老版本接收端，调用方应走本地名兜底 */
    val hasDetail: Boolean get() = count != null || files.isNotEmpty()
}

/** 回执里的单个已落盘文件 */
data class ReceiptFile(val name: String, val size: Long)

/**
 * 解析 `/api/upload` 响应体为回执。
 *
 * **必须整体 try/catch**：这是纯增强信息，解析失败绝不能影响上传成功与否的判定
 * （调用方在 `resp.isSuccessful` 为 true 之后才调它）。任何畸形输入都退化为空回执。
 *
 * 用`opt*` 而非 `get*`：字段可能缺失、类型可能不符，`opt*` 不抛异常。
 */
fun parseReceipt(body: String?): UploadReceipt = try {
    val o = JSONObject(body ?: "{}")
    val arr = o.optJSONArray("files")
    UploadReceipt(
        count = parseCountOrNull(o.opt("count")),
        transferId = o.optString("transferId").takeIf { it.isNotEmpty() },
        files = buildList {
            // files 为对象而非数组时 optJSONArray 返回 null，已天然处理
            if (arr != null) for (i in 0 until arr.length()) {
                val fo = arr.optJSONObject(i) ?: continue
                val n = fo.optString("name")
                if (n.isNotEmpty()) add(ReceiptFile(n, fo.optLong("size", -1L)))
            }
        },
    )
} catch (_: Exception) {
    // "not json" / "{}" / {"files":{}} / {"count":"abc"} 等畸形输入都落这里
    UploadReceipt()
}

/**
 * 解析 `count`，**非数字一律返回 null（视为「未回报」）**。
 *
 * 为什么不能用 `optInt`：它在解析失败时返回默认值 `0`，于是 `{"count":"abc"}`
 * 会得到 `count = 0`。而 `count` 的语义是「接收端确认落盘了几个」，
 * 0 会被调用方判为 PARTIAL（发3 个只落地 0 个）——**一条类型脏数据被误报成部分失败**。
 * 「未回报」与「回报 0」必须严格区分，所以这里显式做类型判定。
 */
private fun parseCountOrNull(raw: Any?): Int? = when (raw) {
    // JSONObject.NULL 也要归为「未回报」
    is Number -> raw.toInt()
    // 宽松接受可解析的数字字符串（不严谨的服务端可能这么写），其余一律 null
    is String -> raw.toIntOrNull()
    else -> null
}

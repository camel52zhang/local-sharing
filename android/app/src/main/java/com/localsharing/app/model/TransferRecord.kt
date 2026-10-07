package com.localsharing.app.model

import org.json.JSONArray
import org.json.JSONObject

/** 一次发送的终态结果 */
enum class SendOutcome {
    SUCCESS,

    /** 部分成功：接收端回报的落盘数少于手机发出的文件数（同名覆盖、单个 part 落盘失败等） */
    PARTIAL,

    FAILED,
}

/**
 * 一条**终态**传输记录（PRD §3.3）。
 *
 * **为什么只记终态、不记进行中**：`/api/upload` 是同步的——返回 200 就代表接收端已落盘完成。
 * 进程被杀意味着这次传输本来就没有结论，落一个「进行中」记录只会留下幽灵状态。
 *
 * **为什么 [deviceLabel] / [targetHost] / [targetPort] 是快照而不是引用**：
 * 用户可能在历史里看到「3 天前发给客厅电视」，而那台设备已被重命名或删除。
 * 记录必须独立于设备条目的生命周期存在。
 */
data class TransferRecord(
    val id: String,
    /** 本地 `SenderDevice.id`，rename 后仍指向同一条目 */
    val deviceId: String,
    /** 发送时刻冻结的展示名 */
    val deviceLabel: String,
    val targetHost: String,
    val targetPort: Int,
    /** 优先用接收端回报的真实落盘名；缺失则用本地入参名 */
    val fileNames: List<String>,
    /** -1 = 总量未知（SAF provider 不提供 SIZE 列） */
    val totalBytes: Long,
    /** 手机发出了几个 */
    val requestedCount: Int,
    /** 接收端确认了几个；null = 接收端未回报（旧版接收端） */
    val receivedCount: Int?,
    val outcome: SendOutcome,
    val startedAt: Long,
    val finishedAt: Long,
    val durationMs: Long,
    /** 0 = 根本没拿到响应（连接失败） */
    val httpCode: Int,
    val receiverTransferId: String? = null,
    /** 失败原因 / 降级说明（如「接收端未回报数量」） */
    val detail: String = "",
) {
    val fileCount: Int get() = fileNames.size
    val sizeUnknown: Boolean get() = totalBytes < 0

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("deviceId", deviceId)
        .put("deviceLabel", deviceLabel)
        .put("targetHost", targetHost)
        .put("targetPort", targetPort)
        .put("fileNames", JSONArray().apply { fileNames.forEach { put(it) } })
        .put("totalBytes", totalBytes)
        .put("requestedCount", requestedCount)
        .put("receivedCount", receivedCount ?: -1)
        .put("outcome", outcome.name)
        .put("startedAt", startedAt)
        .put("finishedAt", finishedAt)
        .put("durationMs", durationMs)
        .put("httpCode", httpCode)
        .put("receiverTransferId", receiverTransferId ?: "")
        .put("detail", detail)

    companion object {
        /** 用 `opt*` 解析：历史文件可能来自旧版本，缺字段一律退化为默认值，绝不抛异常 */
        fun fromJson(o: JSONObject): TransferRecord = TransferRecord(
            id = o.optString("id"),
            deviceId = o.optString("deviceId"),
            deviceLabel = o.optString("deviceLabel"),
            targetHost = o.optString("targetHost"),
            targetPort = o.optInt("targetPort"),
            fileNames = buildList {
                val arr = o.optJSONArray("fileNames") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val n = arr.optString(i)
                    if (n.isNotEmpty()) add(n)
                }
            },
            totalBytes = o.optLong("totalBytes", -1L),
            requestedCount = o.optInt("requestedCount"),
            receivedCount = if (o.has("receivedCount") && o.optInt("receivedCount") >= 0) o.optInt("receivedCount") else null,
            outcome = runCatching { SendOutcome.valueOf(o.optString("outcome")) }.getOrDefault(SendOutcome.FAILED),
            startedAt = o.optLong("startedAt"),
            finishedAt = o.optLong("finishedAt"),
            durationMs = o.optLong("durationMs"),
            httpCode = o.optInt("httpCode"),
            receiverTransferId = o.optString("receiverTransferId").takeIf { it.isNotEmpty() },
            detail = o.optString("detail"),
        )
    }
}
package com.localsharing.app.model

import org.json.JSONObject

/**
 * 手机本地生成身份的接收端条目（PRD D1/G1）。
 *
 * **为什么主键是本地 UUID**：两端 `/api/info` 都不返回稳定设备 ID（安卓端只回 `name`，
 * 见 `ReceiverServer.serveInfo`），二维码内容也是纯 `http://{lanIp}:{port}`，
 * 不含设备名/ID。因此协议不改就没有第二个选择，只能由手机端本地生成身份。
 *
 * 字段职责严格区分：
 * - [id] 是唯一主键，rename 后不变（传输历史靠它指向同一条目）
 * - [name] 只作显示与去重提示，**不是主键**（同型号两台电视 name 完全相同）
 * - [host] / [port] 是**可变地址**：DHCP 会让 IP 变，端口会在 38080~38085 顺延
 */
data class SenderDevice(
    val id: String,
    val name: String,
    val alias: String? = null,
    val host: String,
    val port: Int,
    val requiresCode: Boolean = false,
    val shareCode: String? = null,
    val lastOkAt: Long = 0L,
) {
    /** 展示名：用户别名优先，别名为空白时回退到设备名 */
    val label: String get() = alias?.takeIf { it.isNotBlank() } ?: name

    val baseUrl: String get() = "http://$host:$port"

    /** 去重键：同一台设备的地址。扫同一台电视两次应命中此项→ 静默更新 */
    val key: String get() = "$host:$port"
}

/**
 * JSON 编解码 + 容量淘汰。
 *
 * 解析一律用 `org.json` 的 `opt*`：旧版本写下的记录可能缺字段，
 * 任何缺失都必须退化为默认值而**不能抛异常**，否则一条坏记录会让整个设备列表打不开。
 */
object SenderDeviceCodec {

    fun toJson(d: SenderDevice): JSONObject = JSONObject()
        .put("id", d.id)
        .put("name", d.name)
        .put("alias", d.alias ?: "")
        .put("host", d.host)
        .put("port", d.port)
        .put("requiresCode", d.requiresCode)
        .put("shareCode", d.shareCode ?: "")
        .put("lastOkAt", d.lastOkAt)

    fun fromJson(o: JSONObject): SenderDevice = SenderDevice(
        id = o.optString("id"),
        name = o.optString("name"),
        alias = o.optString("alias").takeIf { it.isNotEmpty() },
        host = o.optString("host"),
        port = o.optInt("port"),
        requiresCode = o.optBoolean("requiresCode", false),
        shareCode = o.optString("shareCode").takeIf { it.isNotEmpty() },
        lastOkAt = o.optLong("lastOkAt", 0L),
    )

    /**
     * 超出上限时淘汰 `lastOkAt` **最小**（最久没成功连通）的那些。
     *
     * **LRU 键为什么只用 `lastOkAt`、不另设 `lastUsedAt`**：这是刻意的取舍——
     * 最久没成功连通的设备恰好就是最该被淘汰的那个（连不上的留着没用），
     * 少一个字段就少一处会不同步的状态。代价是「刚选中但没连上」的设备分数不刷新，
     * 用 [protectId] 守卫兜住：当前目标设备绝不淘汰（它可能正在重连）。
     *
     * ⚠️ 必须 `sortedByDescending`（新的在前）再 `take`：升序+take 会把
     * **最久没连通的**留在列表里、淘汰掉刚连通过的 —— 与 LRU 意图正好相反。
     */
    fun trimToLimit(list: List<SenderDevice>, limit: Int = MAX_DEVICES, protectId: String? = null): List<SenderDevice> {
        if (list.size <= limit) return list
        // 保护项（当前目标，可能正在重连）无条件保留
        val protected = list.filter { it.id == protectId }
        // 其余按 lastOkAt 降序（最近连通过的在前），取够 limit 条 = 淘汰最久未连通的
        val rest = list.filter { it.id != protectId }.sortedByDescending { it.lastOkAt }
        val keep = (limit - protected.size).coerceAtLeast(0)
        return protected + rest.take(keep)
    }

    /** 设备条数上限：与电视端 `ReceiverStore.upsertDevice` 的 `takeLast(20)` 一致 */
    const val MAX_DEVICES = 20
}
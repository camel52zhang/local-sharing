package com.localsharing.app.viewmodel

import com.localsharing.app.model.SenderDevice

/**
 * 单台设备的运行时连接态。**进程内，不持久化**。
 *
 * 为什么不持久化：`token` / `remoteDeviceId` 每次 `register()` 都能重新换到
 * （安卓端 `ReceiverStore.tokenFor` 对同一 clientId 返回固定 token），
 * 持久化反而会在设备重装/换IP 后留下过期凭据。
 *
 * 生命周期上它是「每设备一份，但只有 active 那份活着」——
 * 见设计文档 §1.3，非 active 设备的会话留在 map 里，切回去时 token 还在。
 */
data class DeviceSession(
    /** 本地 `SenderDevice.id`（注意与 [remoteDeviceId] 区分） */
    val deviceId: String,
    val baseUrl: String,
    /** register() 返回的 deviceId（接收端分配） */
    val remoteDeviceId: String,
    val token: String,
    val wsUrl: String,
    val state: ConnState,
    val lastError: String = "",
)

/**
 * 发送目标的**不可变快照**。
 *
 * ★ 这是「上传中切到另一台设备会怎样」的正确性保证（设计文档 §1.4）：
 * `sendSelected()` 启动时把 `baseUrl`/`token`/`remoteDeviceId` 拷进本对象，
 * 此后上传协程**只读它，不再回读任何 StateFlow**。
 * 因此「切设备」这个动作（只改 `_activeDeviceId`）在物理上碰不到 target，
 * 文件绝不会发到用户没选的那台设备。
 *
 * 状态机层（`selectDevice` 在上传中拒绝）与交互层（入口置灰）都只是**体验**，
 * 只有本快照才是**正确性**。三者要分别验收。
 */
data class SendTarget(
    val deviceId: String,
    /** 冻结的展示名：发送期间用户改别名，历史记录里也不该变 */
    val label: String,
    val host: String,
    val port: Int,
    val baseUrl: String,
    val token: String,
    val remoteDeviceId: String,
) {
    companion object {
        /** 从设备条目 + 会话冻结出目标；会话缺失时返回 null */
        fun of(device: SenderDevice, session: DeviceSession): SendTarget? {
            if (session.token.isEmpty() || session.remoteDeviceId.isEmpty()) return null
            return SendTarget(
                deviceId = device.id,
                label = device.label,
                host = device.host,
                port = device.port,
                baseUrl = device.baseUrl,
                token = session.token,
                remoteDeviceId = session.remoteDeviceId,
            )
        }
    }
}

/** 单次发送状态。同一时刻只可能存在一个（范围约束：一次只发一台） */
sealed interface SendState {
    data object Idle : SendState

    data class Running(
        /** 冻结快照，协程内只读它 */
        val target: SendTarget,
        val startedAt: Long,
        /** < 0 = 总量未知（SAF provider 不提供 SIZE 列） */
        val totalBytes: Long,
        val sentBytes: Long,
    ) : SendState {
        /**
         * -1f = 不确定态。
         * ⚠️ 写 0f 会让进度永远停在 0%（原实现踩过这个坑）：
         * 总量未知时宁可显示不确定态，也不显示误导性的 0%。
         */
        val progress: Float get() = if (totalBytes > 0) sentBytes.toFloat() / totalBytes else -1f
        val indeterminate: Boolean get() = totalBytes <= 0
    }
}
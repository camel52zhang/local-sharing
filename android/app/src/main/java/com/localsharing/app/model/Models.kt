package com.localsharing.app.model

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

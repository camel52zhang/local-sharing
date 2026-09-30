package com.localsharing.app.receiver

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条接收记录 */
data class ReceivedItem(
    val id: String,
    val name: String,
    val size: Long,
    val fromDevice: String,
    val time: Long,
    /** 是否可一键安装（APK） */
    val isApk: Boolean,
    /** 安装用临时副本路径（仅 APK 且已准备安装时存在） */
    val installPath: String? = null,
)

/** 曾连接过电视的手机/设备（电视端「已连接设备」列表用） */
data class ReceiverDevice(
    /** 手机端持久 clientId（同一台手机稳定不变） */
    val clientId: String,
    /** 设备名（手机注册时上报的 Build.MODEL） */
    val name: String,
    /** 最近一次连接时间 */
    val lastSeen: Long,
)

/**
 * 电视端「在线设备」实时状态（内存态，随 WS 连接/断开变化）。
 * 通过 StateFlow 供 Compose 直接订阅，无需轮询。
 */
object DevicePresence {
    private val _online = MutableStateFlow<Set<String>>(emptySet())
    val online: StateFlow<Set<String>> = _online.asStateFlow()

    fun setOnline(clientId: String, isOnline: Boolean) {
        if (clientId.isEmpty()) return
        _online.value = if (isOnline) _online.value + clientId else _online.value - clientId
    }

    fun clear() {
        _online.value = emptySet()
    }
}

/**
 * 电视接收模式的数据层：
 * - token 按 clientId 固定化（持久化），手机重连/电视重启后无需重新配对
 * - 设备名注册表持久化（谁连过电视）
 * - 接收记录持久化到 filesDir/received.json
 * - 保存子目录设置（持久化）
 */
object ReceiverStore {
    private const val PREF = "receiver"
    private const val KEY_TOKENS = "tokens"
    private const val KEY_DEVICES = "devices"
    private const val KEY_SUBDIR = "save_subdir"
    private const val RECEIVED_FILE = "received.json"

    /** 接收记录上限：避免 JSON 无限增长导致电视端读取/渲染变慢 */
    private const val MAX_RECORDS = 200

    /** 数据版本号：任何写操作自增，UI 订阅它做事件驱动的刷新（替代 2s 轮询） */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun bumpRevision() {
        _revision.value += 1
    }

    /** 保存根目录名（公共 Downloads 下） */
    const val SAVE_ROOT = "local-sharing"

    // ---- token ----

    /** 取（或首次生成）某 clientId 的固定 token；clientId 为空时每次随机 */
    fun tokenFor(context: Context, clientId: String): String {
        if (clientId.isEmpty()) return randomToken()
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val all = prefs.getStringSet(KEY_TOKENS, emptySet()) ?: emptySet()
        for (entry in all) {
            val idx = entry.indexOf('=')
            if (idx > 0 && entry.substring(0, idx) == clientId) {
                return entry.substring(idx + 1)
            }
        }
        val t = randomToken()
        prefs.edit().putStringSet(KEY_TOKENS, all + "$clientId=$t").apply()
        return t
    }

    /** 反查 token 对应的 clientId（鉴权用）；未注册返回 null */
    fun clientIdForToken(context: Context, token: String): String? {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        for (entry in prefs.getStringSet(KEY_TOKENS, emptySet()) ?: emptySet()) {
            val idx = entry.indexOf('=')
            if (idx > 0 && entry.substring(idx + 1) == token) return entry.substring(0, idx)
        }
        return null
    }

    private fun randomToken(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString { repeat(24) { append(chars.random()) } }
    }

    // ---- 设备注册表 ----

    /** 记录/刷新设备名（手机每次注册都会调用） */
    fun upsertDevice(context: Context, clientId: String, name: String) {
        if (clientId.isEmpty()) return
        val list = loadDevices(context).toMutableList()
        val idx = list.indexOfFirst { it.clientId == clientId }
        val entry = ReceiverDevice(clientId, name.ifBlank { "未知设备" }, System.currentTimeMillis())
        if (idx >= 0) list[idx] = entry else list.add(entry)
        saveDevices(context, list.takeLast(20))
    }

    fun loadDevices(context: Context): List<ReceiverDevice> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_DEVICES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ReceiverDevice(
                    clientId = o.optString("clientId"),
                    name = o.optString("name", "未知设备"),
                    lastSeen = o.optLong("lastSeen", 0L),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveDevices(context: Context, list: List<ReceiverDevice>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(
                JSONObject()
                    .put("clientId", d.clientId)
                    .put("name", d.name)
                    .put("lastSeen", d.lastSeen),
            )
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEVICES, arr.toString()).apply()
        bumpRevision()
    }

    /** 用 clientId / 名称片段解析显示名 */
    fun displayName(context: Context, clientIdOrName: String): String =
        loadDevices(context).firstOrNull { it.clientId == clientIdOrName }?.name
            ?: clientIdOrName.ifBlank { "未知设备" }

    // ---- 保存目录设置 ----

    /** 自定义保存子目录（相对 Download/local-sharing/），空串表示直接用根目录 */
    fun getSaveSubdir(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_SUBDIR, "") ?: ""

    /** 设置子目录；会清洗非法字符 */
    fun setSaveSubdir(context: Context, subdir: String) {
        val clean = subdir.trim().trim('/').replace(Regex("[\\\\:*?\"<>|]"), "_")
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUBDIR, clean).apply()
    }

    /** 展示用保存路径（电视端界面显示） */
    fun savePathLabel(context: Context): String {
        val sub = getSaveSubdir(context)
        return if (sub.isEmpty()) "下载/$SAVE_ROOT" else "下载/$SAVE_ROOT/$sub"
    }

    // ---- 接收记录 ----

    fun loadReceived(context: Context): List<ReceivedItem> {
        val f = File(context.filesDir, RECEIVED_FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ReceivedItem(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    size = o.getLong("size"),
                    fromDevice = o.optString("fromDevice"),
                    time = o.getLong("time"),
                    isApk = o.optBoolean("isApk", false),
                    installPath = o.optString("installPath").takeIf { it.isNotEmpty() },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun appendReceived(context: Context, item: ReceivedItem) {
        saveReceived(context, mutableListOf(item).apply { addAll(loadReceived(context)) })
    }

    /** 清空全部接收记录（不含已保存的文件本身） */
    fun clearReceived(context: Context) {
        saveReceived(context, emptyList())
    }

    fun updateReceived(context: Context, id: String, installPath: String) {
        saveReceived(
            context,
            loadReceived(context).map { if (it.id == id) it.copy(installPath = installPath) else it },
        )
    }

    /** 删除单条记录（仅移除记录，不删除已落盘文件） */
    fun deleteReceived(context: Context, id: String) {
        saveReceived(context, loadReceived(context).filterNot { it.id == id })
    }

    private fun saveReceived(context: Context, list: List<ReceivedItem>) {
        // 容量上限：只保留最近 MAX_RECORDS 条，避免文件与渲染无限增长
        val capped = list.take(MAX_RECORDS)
        val arr = JSONArray()
        capped.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("size", r.size)
                    .put("fromDevice", r.fromDevice)
                    .put("time", r.time)
                    .put("isApk", r.isApk)
                    .put("installPath", r.installPath ?: ""),
            )
        }
        File(context.filesDir, RECEIVED_FILE).writeText(arr.toString())
        bumpRevision()
    }
}

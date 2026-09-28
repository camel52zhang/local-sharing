package com.localsharing.app.receiver

import android.content.Context
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

/**
 * 电视接收模式的数据层：
 * - token 按 clientId 固定化（持久化），手机重连/电视重启后无需重新配对
 * - 接收记录持久化到 filesDir/received.json
 */
object ReceiverStore {
    private const val PREF = "receiver"
    private const val KEY_TOKENS = "tokens"
    private const val RECEIVED_FILE = "received.json"

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
        val list = mutableListOf(item).apply { addAll(loadReceived(context)) }
        val arr = JSONArray()
        list.forEach { r ->
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
    }

    fun updateReceived(context: Context, id: String, installPath: String) {
        val list = loadReceived(context).map {
            if (it.id == id) it.copy(installPath = installPath) else it
        }
        val arr = JSONArray()
        list.forEach { r ->
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
    }
}

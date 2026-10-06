package com.localsharing.app.receiver

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

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
    private const val KEY_FOLDERS = "save_folders"
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

    /** 单级目录名的非法字符（与 saveToDownloads 的文件名清洗保持一致） */
    private val ILLEGAL_NAME = Regex("[\\\\/:*?\"<>|]")

    /**
     * 规范化多级相对路径：逐级清洗、丢弃空段与 . / ..，用 / 连接。
     *
     * 安全性论证（已逐输入验证）：`split('/')` 之后每段内不可能再出现 `/`，
     * 而 `..` 作为整段被 filter 掉，因此输出恒为相对路径、恒不含 `..` 段、
     * 恒不以 `/` 开头 —— 无法跳出Download/local-sharing 根。
     * 注意语义是「丢弃 .. 段」而非「出栈」，所以 `a/../b` 得到 `a/b`（偏保守，可接受）。
     */
    private fun normalizeRelPath(input: String): String =
        input.trim().split('/')
            .map { it.trim().replace(ILLEGAL_NAME, "_").trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

    /**
     * 读时也规范化：旧版本 setSaveSubdir 只做 trim().trim('/')，`..` 这类值能被原样存进去
     * （旧正则已含 `/`，所以穿越本来也不成立，但脏值会直达 RELATIVE_PATH 让MediaProvider 拒绝）。
     * 读时兜一道，天然免疫任何历史遗留值。
     */
    fun getSaveSubdir(context: Context): String =
        normalizeRelPath(
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_SUBDIR, "") ?: "",
        )

    /** 设置子目录；逐级清洗非法字符并拒绝路径穿越 */
    fun setSaveSubdir(context: Context, subdir: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUBDIR, normalizeRelPath(subdir)).apply()
        bumpRevision()
    }

    /** 展示用保存路径（电视端界面显示） */
    fun savePathLabel(context: Context): String {
        // 降级过就如实显示应用私有目录，否则用户会以为文件在公共 Download 里却找不到
        getFallbackDir(context)?.let { return it }
        val sub = getSaveSubdir(context)
        return if (sub.isEmpty()) "下载/$SAVE_ROOT" else "下载/$SAVE_ROOT/$sub"
    }

    // ---- API 24-28 降级目录标记 ----

    private const val KEY_FALLBACK = "fallback_dir"

    /**
     * 记录「公共目录不可写，已降级到应用私有目录」的事实及其绝对路径。
     * API 24~28 上实测存在这个场景：进程拿不到 sdcard_rw 组，
     * WRITE_EXTERNAL_STORAGE 授权了也依然 Permission denied。
     */
    fun markFallbackDir(context: Context, path: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_FALLBACK, path).apply()
    }

    fun getFallbackDir(context: Context): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_FALLBACK, null)?.takeIf { it.isNotEmpty() && File(it).exists() }

    // ---- 保存目录树（电视端自建文件夹浏览） ----

    /**
     * 用户在电视端「新建文件夹」出来的目录（持久化）。
     *
     * 为什么需要自己存一份：API 29+ 上是MediaStore 按需创建目录的，
     * **空目录不会出现在 MediaStore 查询结果里**（它只索引真实文件）。
     * 若只靠扫盘，用户建的空文件夹会「凭空消失」；
     * 而 Android 11+ 未经MANAGE_EXTERNAL_STORAGE 又不能稳定地 `File.mkdirs()`。
     * 因此以本注册表为准，MediaStore 扫到的目录作为补充（能显示历史遗留目录）。
     */
    fun loadFolders(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()
        val out = mutableSetOf<String>()
        for (rel in raw) {
            val clean = normalizeRelPath(rel)
            if (clean.isNotEmpty()) out += clean
        }
        return out
    }

    /** 单级目录名长度上限 */
    private const val MAX_NAME_LEN = 60

    /**
     * 相对路径总长度与层级上限。
     * 必要性：MediaStore 的 RELATIVE_PATH 过长/过深时 insert 返回 null，
     * 而 saveToDownloads 会抛 IllegalStateException → 上传全部 500，
     * 且用户无法从电视界面自救。必须在写入前拦住。
     */
    private const val MAX_REL_LEN = 180
    private const val MAX_DEPTH = 8

    /**
     * 在 [parent] 下新建一个目录，返回规范化后的完整相对路径（已存在则原样返回）。
     * 返回 null 表示名称非法（空 / 全是清洗后空白 / 超长 / 超深）。
     */
    fun createFolder(context: Context, parent: String, rawName: String): String? {
        val name = rawName.trim().replace(ILLEGAL_NAME, "_").trim()
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.length > MAX_NAME_LEN) return null
        val rel = normalizeRelPath(if (parent.isEmpty()) name else "$parent/$name")
        if (rel.isEmpty()) return null
        // 拦住会让 MediaStore insert 失败的路径（见 MAX_REL_LEN 注释）
        if (rel.length > MAX_REL_LEN || rel.count { it == '/' } >= MAX_DEPTH) return null
        // 降级模式下必须真的把目录建出来，否则又变成「注册表里有、磁盘上没有」的幽灵目录
        getFallbackDir(context)?.let { base ->
            val dir = File(base, rel)
            if (!dir.exists()) dir.mkdirs()
        }
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_FOLDERS, loadFolders(context) + rel).apply()
        bumpRevision()
        return rel
    }

    /**
     * 列出 [current] 下的子目录（相对 Download/local-sharing/）。
     * 来源 = 本应用注册表 ∪ MediaStore 扫到的实际目录，取并集去重。
     * 必须在 IO 线程调用（会读 SharedPreferences + 查 MediaStore）。
     */
    fun listSubDirs(context: Context, current: String): List<String> {
        val cur = normalizeRelPath(current)
        val children = mutableSetOf<String>()

        // 1) 注册表里的目录
        for (rel in loadFolders(context)) {
            val parent = rel.substringBeforeLast('/', "")
            if (parent == cur) children += rel.substringAfterLast('/')
        }

        // 1.5) 降级目录：API 24~28 公共目录不可写时，文件落在应用私有外部目录。
        // 那里没有任何索引，必须直接扫盘，否则用户在界面上看不到任何已存在的目录。
        getFallbackDir(context)?.let { base ->
            val relBase = if (cur.isEmpty()) base else File(base, cur).absolutePath
            File(relBase).listFiles()?.forEach { child ->
                if (child.isDirectory) children += child.name
            }
        }

        // 2) MediaStore 实际目录（补上历史遗留 / 其它来源建的目录）
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val prefix = "${android.os.Environment.DIRECTORY_DOWNLOADS}/$SAVE_ROOT"
                context.contentResolver.query(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH),
                    "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("$prefix/%"),
                    null,
                )?.use { c ->
                    val idx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.RELATIVE_PATH)
                    if (idx >= 0) {
                        while (c.moveToNext()) {
                            val rel = normalizeRelPath(
                                (c.getString(idx) ?: "").trim().trim('/')
                                    .removePrefix(prefix).trim('/'),
                            )
                            if (rel.isEmpty()) continue
                            if (rel.substringBeforeLast('/', "") == cur) {
                                children += rel.substringAfterLast('/')
                            }
                        }
                    }
                }
            }
        }

        return children.sortedBy { it.lowercase(Locale.US) }
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

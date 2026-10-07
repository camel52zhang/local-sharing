package com.localsharing.app.util

import android.content.Context
import android.net.Uri
import com.localsharing.app.model.SenderDevice
import com.localsharing.app.model.SenderDeviceCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.UUID

/**
 * L1 设备仓库：手机端「我记住了哪些接收端」的持久化层。
 *
 * **为什么用 SharedPreferences 而不是文件**（与 [TransferHistoryStore] 相反）：
 * 只有 [SenderDeviceCodec.MAX_DEVICES] 条、只改不增、且要在启动时同步读出用于首屏预填——
 * 这正是 `ReceiverStore.upsertDevice`（存 `KEY_DEVICES` JSON 字符串）的形状，照抄它。
 * 而传输历史是「常写的流水」，才该走文件。
 *
 * 与 [Prefs] 共用同一个 prefs 文件（`local_sharing_prefs`）：迁移需要读那里的旧键。
 */
object SenderDeviceStore {
    private const val KEY_DEVICES = "sender_devices"
    private const val KEY_MIGRATED = "sender_devices_migrated"

    /**
     * 数据版本号：任何写操作自增，UI 订阅它做事件驱动刷新。
     * （照抄 `ReceiverStore._revision`，替代 2s 轮询）
     */
    private val _revision = MutableStateFlow(0)

    private fun bumpRevision() {
        _revision.value += 1
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("local_sharing_prefs", Context.MODE_PRIVATE)

    // ---- 读 ----

    fun loadDevices(context: Context): List<SenderDevice> {
        val raw = prefs(context).getString(KEY_DEVICES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                // getJSONObject 在元素不是对象时会抛 JSONException；单条坏记录不应拖垮整个列表
                runCatching { SenderDeviceCodec.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }.filter { it.id.isNotEmpty() && it.host.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getDevice(context: Context, id: String): SenderDevice? =
        loadDevices(context).firstOrNull { it.id == id }

    /** 按地址查（去重用：扫同一台电视两次会命中） */
    fun findByKey(context: Context, key: String): SenderDevice? =
        loadDevices(context).firstOrNull { it.key == key }

    /** 按设备名查（同名去重确认用：同型号两台电视 name 相同） */
    fun findByName(context: Context, name: String): SenderDevice? =
        loadDevices(context).firstOrNull { name.isNotBlank() && it.name == name }

    // ---- 写 ----

    /**
     * 写入或更新一条设备记录。
     *
     * [id] 为空时视为新增（生成本地 UUID 主键）；否则按 id 覆盖。
     * [protectId] 用于 LRU 淘汰时保护当前目标设备（见 `SenderDeviceCodec.trimToLimit`）。
     * 返回落库后的条目（含新生成的 id）。
     */
    fun upsertDevice(context: Context, device: SenderDevice, protectId: String? = null): SenderDevice {
        val list = loadDevices(context).toMutableList()
        val entry = if (device.id.isBlank()) device.copy(id = UUID.randomUUID().toString()) else device
        val idx = list.indexOfFirst { it.id == entry.id }
        if (idx >= 0) list[idx] = entry else list.add(entry)
        // ★ 本次新增/更新的条目必须自动受保护，不依赖调用点记得传 protectId。
        //
        // 背景：列表满 MAX_DEVICES 时新增设备，若不保护，新设备的 lastOkAt=0
        // （默认值，从未成功连通过）会在 LRU 排序里排最后 → **第一个被淘汰**。
        // 结果：扫了码没反应、无任何提示（selectDevice 找不到设备静默 return），
        // 属于静默数据丢失。实测复现：21 台输入时新设备必被淘汰。
        //
        // 这里传 `protectId ?: entry.id`，让「刚被用户选中的这条」一定留在列表里。
        saveDevices(context, list, protectId ?: entry.id)
        return entry
    }

    /** 重命名；alias 为空串或 null 表示清除别名（回退显示真实name） */
    fun updateAlias(context: Context, id: String, alias: String?, protectId: String? = null) {
        val list = loadDevices(context).map {
            if (it.id == id) it.copy(alias = alias?.takeIf { s -> s.isNotBlank() }) else it
        }
        saveDevices(context, list, protectId)
    }

    fun removeDevice(context: Context, id: String) {
        saveDevices(context, loadDevices(context).filterNot { it.id == id })
    }

    fun replaceAll(context: Context, list: List<SenderDevice>) {
        saveDevices(context, list)
    }

    /** 记录一次成功连通：刷新 lastOkAt（既是「可达」文案源，也是 LRU 键） */
    fun markReachable(context: Context, id: String, at: Long = System.currentTimeMillis()) {
        val list = loadDevices(context).map { if (it.id == id) it.copy(lastOkAt = at) else it }
        saveDevices(context, list, protectId = id)
    }

    private fun saveDevices(context: Context, list: List<SenderDevice>, protectId: String? = null) {
        val capped = SenderDeviceCodec.trimToLimit(list, SenderDeviceCodec.MAX_DEVICES, protectId)
        val arr = JSONArray()
        capped.forEach { arr.put(SenderDeviceCodec.toJson(it)) }
        prefs(context).edit().putString(KEY_DEVICES, arr.toString()).apply()
        bumpRevision()
    }

    // ---- 老数据迁移 ----

    /**
     * 把旧版单条连接记录（`last_base_url` / `last_device_name`）合成一条设备条目。
     *
     * -仅在「设备列表为空且尚未迁移过」时执行，保证老用户无感。
     * - **迁移成功后刻意保留 Prefs 里的原键不删**：万一新版本出问题，
     *   降级回旧版时旧数据还在，回滚安全。
     */
    fun migrateLegacyIfNeeded(context: Context): Boolean {
        if (prefs(context).getBoolean(KEY_MIGRATED, false)) return false
        val p = prefs(context)
        // 标记先置位：即使下面失败也不重复迁移（重复迁移会造出第二条重复设备）
        p.edit().putBoolean(KEY_MIGRATED, true).apply()
        if (loadDevices(context).isNotEmpty()) return false

        val url = p.getString("last_base_url", null)?.takeIf { it.isNotBlank() } ?: return false
        val name = p.getString("last_device_name", null).orEmpty()
        val host = Uri.parse(url).host
        if (host.isNullOrBlank()) return false
        val port = Uri.parse(url).port.takeIf { it > 0 } ?: 38080

        upsertDevice(
            context,
            SenderDevice(
                id = "",
                name = name.ifBlank { "未知设备" },
                host = host,
                port = port,
                // 旧版不记录这两项；共享码缺失会在首次连接时被requiresCode 校验拦下并提示
                lastOkAt = 0L,
            ),
        )
        return true
    }
}
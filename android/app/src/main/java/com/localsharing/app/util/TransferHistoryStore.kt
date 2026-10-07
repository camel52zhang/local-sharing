package com.localsharing.app.util

import android.content.Context
import com.localsharing.app.model.TransferRecord
import org.json.JSONArray
import java.io.File

/**
 * 传输历史持久化（L1 流水层）。
 *
 * **为什么用文件而不是 SharedPreferences**（与 [SenderDeviceStore] 相反）：
 * 这是「每次发送终态都写」的流水，200 条 × ~300B ≈ 60KB。
 * 塞进 `local_sharing_prefs` 会与设置项混在一起，且每次 `apply()` 都要重写整个 XML。
 * 独立文件不污染设置，整体读写的形状又与 `ReceiverStore.loadReceived/saveReceived`
 * （`received.json`，已在电视端跑了多个版本）完全同构——不另起炉灶。
 *
 * **只记终态**：`/api/upload` 同步返回即代表落盘完成，没有「进行中」的中间态需要持久化。
 */
object TransferHistoryStore {
    private const val FILE = "sender_history.json"

    /** 保留条数：与 `ReceiverStore.MAX_RECORDS = 200` 一致，不新造数字 */
    private const val MAX = 200



    /** 读文件 + JSONArray 解析，**必须在 IO 线程调用**。损坏 JSON 返回空列表不崩溃 */
    fun load(context: Context): List<TransferRecord> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                runCatching { TransferRecord.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 插入一条（最新在数组首位），并按上限截断 */
    fun append(context: Context, r: TransferRecord) {
        save(context, listOf(r) + load(context))
    }

    fun delete(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    fun clear(context: Context) {
        save(context, emptyList())
    }

    private fun save(context: Context, list: List<TransferRecord>) {
        val arr = JSONArray()
        list.take(MAX).forEach { arr.put(it.toJson()) }
        File(context.filesDir, FILE).writeText(arr.toString())
    }
}
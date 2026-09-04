package com.localsharing.app.util

import android.content.Context
import androidx.core.content.edit

/** 轻量本地偏好（不引入额外依赖），持久化自动保存开关与上次连接。 */
object Prefs {
    private const val NAME = "local_sharing_prefs"
    private const val KEY_AUTO_SAVE = "auto_save"
    private const val KEY_LAST_URL = "last_base_url"
    private const val KEY_LAST_NAME = "last_device_name"
    private const val KEY_DEVICE_UUID = "device_uuid"
    /** 接收文件保存目录（SAF tree Uri 字符串）；为空时回退应用 Downloads */
    private const val KEY_SAVE_TREE_URI = "save_tree_uri"

    fun isAutoSave(context: Context): Boolean {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_AUTO_SAVE, false)
    }

    fun setAutoSave(context: Context, on: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_AUTO_SAVE, on) }
    }

    fun getLastConnection(context: Context): Pair<String, String>? {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val url = p.getString(KEY_LAST_URL, null)
        val name = p.getString(KEY_LAST_NAME, null)
        return if (url != null && name != null) url to name else null
    }

    fun saveLastConnection(context: Context, baseUrl: String, deviceName: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_LAST_URL, baseUrl); putString(KEY_LAST_NAME, deviceName) }
    }

    /** 返回稳定且持久化的设备 UUID：首次生成并存储，之后复用，用于桌面端复用同一设备条目 */
    fun getDeviceUuid(context: Context): String {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val existing = p.getString(KEY_DEVICE_UUID, null)
        if (!existing.isNullOrBlank()) return existing
        val uuid = java.util.UUID.randomUUID().toString()
        p.edit { putString(KEY_DEVICE_UUID, uuid) }
        return uuid
    }

    /** 读取用户选择的接收保存目录（SAF tree Uri 字符串），未设置返回 null */
    fun getSaveTreeUri(context: Context): String? {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return p.getString(KEY_SAVE_TREE_URI, null)?.takeIf { it.isNotBlank() }
    }

    /** 持久化接收保存目录（tree Uri 字符串） */
    fun setSaveTreeUri(context: Context, uri: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_SAVE_TREE_URI, uri) }
    }

    /** 清除接收保存目录设置（回退默认 Downloads） */
    fun clearSaveTreeUri(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_SAVE_TREE_URI) }
    }

    /** 读取保存目录的展示名（用于 UI 回显） */
    fun getSaveTreeName(context: Context): String? {
        return getSaveTreeUri(context)?.let { uriStr ->
            try {
                val uri = android.net.Uri.parse(uriStr)
                val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                doc?.name
            } catch (e: Exception) { null }
        }
    }
}

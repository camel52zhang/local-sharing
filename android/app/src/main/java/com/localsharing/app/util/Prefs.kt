package com.localsharing.app.util

import android.content.Context
import androidx.core.content.edit

/** 轻量本地偏好（不引入额外依赖），持久化自动保存开关与上次连接。 */
object Prefs {
    private const val NAME = "local_sharing_prefs"
    private const val KEY_AUTO_SAVE = "auto_save"
    private const val KEY_LAST_URL = "last_base_url"
    private const val KEY_LAST_NAME = "last_device_name"

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
}

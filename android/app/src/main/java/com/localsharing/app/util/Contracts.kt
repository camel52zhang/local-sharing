package com.localsharing.app.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * 选择多个文档（替代 OpenDocument 仅返回单个 Uri 的限制）。
 * 通过 clipData 解析多选结果。
 */
class OpenDocuments : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData
        if (clip != null) {
            val list = mutableListOf<Uri>()
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { list.add(it) }
            }
            return list
        }
        intent.data?.let { return listOf(it) }
        return emptyList()
    }
}

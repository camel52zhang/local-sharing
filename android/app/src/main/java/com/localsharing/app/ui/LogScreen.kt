package com.localsharing.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.localsharing.app.util.CrashLogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticLogScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        lines = withContext(Dispatchers.IO) { CrashLogCollector.readLastLines(2000) }
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("诊断日志（最近 2000 行）") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { copyText(context, lines.joinToString("\n")) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
                    }
                    IconButton(onClick = { shareText(context, lines.joinToString("\n")) }) {
                        Icon(Icons.Filled.Share, contentDescription = "分享")
                    }
                },
            )
        },
    ) { padding ->
        when {
            !loaded -> Text("加载中…", modifier = Modifier.padding(padding).padding(16.dp))
            lines.isEmpty() -> Text("暂无日志", modifier = Modifier.padding(padding).padding(16.dp))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(lines) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("crashlog", text))
    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "导出诊断日志（可发微信/邮件）"))
}

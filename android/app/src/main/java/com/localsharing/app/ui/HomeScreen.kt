package com.localsharing.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import com.localsharing.app.model.OutgoingItem
import com.localsharing.app.util.OpenDocuments
import com.localsharing.app.util.formatSize
import com.localsharing.app.util.zipUris
import com.localsharing.app.viewmodel.ShareViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: ShareViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pcInfo by vm.pcInfo.collectAsState()
    val error by vm.error.collectAsState()
    val selected by vm.selectedItems.collectAsState()
    val uploading by vm.uploading.collectAsState()
    val progress by vm.uploadProgress.collectAsState()
    val incoming by vm.incoming.collectAsState()
    val saved by vm.saved.collectAsState()

    val pickFiles = rememberLauncherForActivityResult(OpenDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val items = uris.map { vm.toOutgoingItem(it) }
            vm.setSelectedItems(selected + items)
        }
    }

    val pickFolder = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            val uris = walkTree(context, treeUri)
            if (uris.isNotEmpty()) {
                val treeName = DocumentFile.fromTreeUri(context, treeUri)?.name ?: "folder"
                val zipFile = File(context.cacheDir, "$treeName.zip")
                scope.launch {
                    val ok = zipUris(context, uris, zipFile.absolutePath)
                    if (ok) {
                        val item = OutgoingItem(
                            uri = Uri.fromFile(zipFile),
                            displayName = "$treeName.zip",
                            size = zipFile.length(),
                            isFolder = true,
                        )
                        vm.setSelectedItems(selected + item)
                    } else {
                        // 压缩失败，回退为逐文件
                        vm.setSelectedItems(selected + uris.map { vm.toOutgoingItem(it) })
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("局域网互传工具"); Text("已连接：${pcInfo.name}", style = MaterialTheme.typography.bodySmall) } },
                actions = {
                    Button(onClick = { vm.disconnect() }) { Text("断开") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- 发送到电脑 ----
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("发送到电脑", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { pickFiles.launch(Unit) }, modifier = Modifier.weight(1f)) {
                                Text("选择文件")
                            }
                            Button(onClick = { pickFolder.launch(null) }, modifier = Modifier.weight(1f)) {
                                Text("选择文件夹")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (selected.isEmpty()) {
                            Text("尚未选择文件", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        } else {
                            selected.forEachIndexed { idx, item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (item.isFolder) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                        contentDescription = null,
                                    )
                                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(formatSize(item.size), style = MaterialTheme.typography.bodySmall)
                                    }
                                    IconButton(onClick = { vm.removeSelected(idx) }) {
                                        Text("✕")
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { vm.sendSelected() },
                                enabled = !uploading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (uploading) {
                                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.height(8.dp))
                                    Text(" ${(progress * 100).toInt()}%")
                                } else {
                                    Text("发送到电脑")
                                }
                            }
                            if (uploading) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }

            // ---- 收到的文件（来自电脑推送） ----
            item { Text("收到的文件", style = MaterialTheme.typography.titleMedium) }
            if (incoming.isEmpty()) {
                item { Text("暂无来自电脑的文件", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
            }
            items(incoming) { t ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${if (t.kind == "folder") "文件夹" else "文件"} · ${formatSize(t.size)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (t.saving) {
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (!t.saving) {
                            Button(onClick = { vm.saveIncoming(t) }) { Text("保存") }
                        }
                    }
                }
            }

            // ---- 已保存 ----
            if (saved.isNotEmpty()) {
                item { Text("已保存", style = MaterialTheme.typography.titleMedium) }
                items(saved) { f ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = if (f.kind == "folder") Icons.Filled.Folder else Icons.Filled.InsertDriveFile, contentDescription = null)
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(f.savedPath, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            if (error.isNotBlank()) {
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** 递归收集 tree 下的所有文件 Uri */
private fun walkTree(context: android.content.Context, treeUri: Uri): List<Uri> {
    val result = mutableListOf<Uri>()
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return result
    fun walk(doc: DocumentFile) {
        for (f in doc.listFiles()) {
            if (f.isDirectory) walk(f) else f.uri?.let { result.add(it) }
        }
    }
    walk(root)
    return result
}

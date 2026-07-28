package com.localsharing.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.localsharing.app.model.OutgoingItem
import com.localsharing.app.util.OpenDocuments
import com.localsharing.app.util.formatSize
import com.localsharing.app.util.zipUris
import com.localsharing.app.viewmodel.ConnState
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
    val conn by vm.connState.collectAsState()
    val autoSave by vm.autoSave.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        if (error.isNotBlank()) snackbarHostState.showSnackbar(error)
    }

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
                        vm.setSelectedItems(selected + uris.map { vm.toOutgoingItem(it) })
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("local-sharing")
                        Text("已连接：${pcInfo.name}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    OutlinedButton(onClick = { vm.disconnect() }) { Text("断开") }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                ) { Text(data.visuals.message) }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 连接状态横幅
            item {
                ConnectionBanner(
                    connected = conn == ConnState.Connected,
                    reconnecting = conn == ConnState.Reconnecting,
                    pcName = pcInfo.name,
                )
            }

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
                            EmptyState(
                                icon = Icons.Filled.InsertDriveFile,
                                title = "还没有选择文件",
                                subtitle = "选择文件或文件夹，即可发送到电脑",
                            )
                        } else {
                            selected.forEachIndexed { idx, item ->
                                FileCard(
                                    name = item.displayName,
                                    meta = formatSize(item.size),
                                    isFolder = item.isFolder,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                    actionLabel = "✕",
                                    onAction = { vm.removeSelected(idx) },
                                )
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
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("收到的文件", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("自动保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Switch(checked = autoSave, onCheckedChange = { vm.setAutoSave(it) })
                    }
                }
            }
            if (incoming.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.CloudOff,
                        title = "还没有收到文件",
                        subtitle = "让电脑端发送，文件会显示在这里",
                    )
                }
            }
            items(incoming) { t ->
                FileCard(
                    name = t.name,
                    meta = if (t.kind == "folder") "文件夹 · ${formatSize(t.size)}" else "文件 · ${formatSize(t.size)}",
                    isFolder = t.kind == "folder",
                    modifier = Modifier.fillMaxWidth(),
                    actionLabel = if (t.saving) null else "保存",
                    onAction = { vm.saveIncoming(t) },
                    progress = if (t.saving) t.progress else null,
                )
            }

            // ---- 已保存 ----
            if (saved.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已保存", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.clearSaved() }) { Text("清空") }
                    }
                }
                items(saved) { f ->
                    FileCard(
                        name = f.name,
                        meta = f.savedPath,
                        isFolder = f.kind == "folder",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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

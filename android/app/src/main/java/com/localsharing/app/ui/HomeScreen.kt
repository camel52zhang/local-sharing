package com.localsharing.app.ui

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.clickable
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.localsharing.app.util.CrashLogCollector
import com.localsharing.app.util.OpenDocuments
import com.localsharing.app.util.formatSize
import com.localsharing.app.util.zipUris
import com.localsharing.app.viewmodel.ConnState
import com.localsharing.app.viewmodel.ShareViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// contract 必须是稳定单例：内联 new 会在每次重组时生成新实例，导致 rememberLauncherForActivityResult
// 反复 register，ActivityResultRegistry 的 mNextRc 计数器越过 16 位上限
// （Can only use lower 16 bits for requestCode），下次 launch() 即崩溃。
private val openDocumentsContract = OpenDocuments()
private val openDocumentTreeContract = ActivityResultContracts.OpenDocumentTree()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: ShareViewModel, onOpenPicker: () -> Unit, onOpenHistory: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeDevice by vm.activeDevice.collectAsState()
    val error by vm.error.collectAsState()
    val selected by vm.selectedItems.collectAsState()
    val uploading by vm.uploading.collectAsState()
    val progress by vm.uploadProgress.collectAsState()
    val incoming by vm.incoming.collectAsState()
    val saved by vm.saved.collectAsState()
    val conn by vm.connState.collectAsState()
    val pending by vm.pendingShare.collectAsState()
    val autoSave by vm.autoSave.collectAsState()
    val saveDirName by vm.saveDirName.collectAsState()
    val saveDirUri by vm.saveDirUri.collectAsState()

    val targetLabel = activeDevice?.label.orEmpty()
    val snackbarHostState = remember { SnackbarHostState() }
    val noticeHostState = remember { SnackbarHostState() }
    var showLog by remember { mutableStateOf(false) }
    val crashedLastRun = remember { CrashLogCollector.lastRunCrashed }
    LaunchedEffect(error) {
        if (error.isNotBlank()) {
            // 错误提示弹出前先清掉中性提示：两个 SnackbarHost 叠在同一点，
            // 不互斥会视觉重叠
            noticeHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(error)
        }
    }
    // 中性/成功提示：与 error 分开走，避免「已更新地址」被显示成错误样式
    val notice by vm.notice.collectAsState()
    LaunchedEffect(notice) {
        if (notice.isNotBlank()) {
            // 同上：中性提示弹出前清掉错误提示，避免重叠
            snackbarHostState.currentSnackbarData?.dismiss()
            noticeHostState.showSnackbar(notice)
            vm.consumeNotice()
        }
    }

    // 连接成功后，把系统分享暂存的项自动填入发送列表（pending 清空后不再重复加入）
    LaunchedEffect(conn, pending) {
        if (conn == ConnState.Connected && pending.isNotEmpty()) vm.flushPendingToSelected()
    }

    // onResult 必须稳定：用 remember 包一层，避免 HomeScreen 因 9 路 collectAsState 频繁重组时
    // 反复 register，导致 mNextRc 快速越过 16 位上限（Can only use lower 16 bits for requestCode）。
    val onPickFilesResult = remember {
        { uris: List<Uri> ->
            try {
                if (uris.isNotEmpty()) {
                    val items = uris.map { vm.toOutgoingItem(it) }
                    vm.setSelectedItems(vm.selectedItems.value + items)
                }
            } catch (e: Exception) {
                // 结果回调内的任何未捕获异常都会直接杀死进程；这里兜底为上报错误而非闪退。
                Log.e("HomeScreen", "pickFiles failed: ${e.message}")
                vm.reportError("选择文件失败：${e.message ?: "未知错误"}")
            }
        }
    }
    val pickFiles = rememberLauncherForActivityResult(openDocumentsContract, onPickFilesResult)

    val onPickFolderResult = remember {
        { treeUri: Uri? ->
            try {
                if (treeUri != null) {
                    scope.launch {
                        // SAF 遍历是逐文件 IPC，大目录在主线程会 ANR：切到 IO
                        val uris = try {
                            withContext(Dispatchers.IO) { walkTree(context, treeUri) }
                        } catch (e: Exception) {
                            Log.e("HomeScreen", "pickFolder failed: ${e.message}")
                            vm.reportError("选择文件夹失败：${e.message ?: "未知错误"}")
                            return@launch
                        }
                        if (uris.isNotEmpty()) {
                            try {
                                val treeName = withContext(Dispatchers.IO) {
                                    DocumentFile.fromTreeUri(context, treeUri)?.name
                                } ?: "folder"
                                val zipFile = File(context.cacheDir, "$treeName.zip")
                                val ok = zipUris(context, uris, zipFile.absolutePath)
                                if (ok) {
                                    val item = OutgoingItem(
                                        uri = Uri.fromFile(zipFile),
                                        displayName = "$treeName.zip",
                                        size = zipFile.length(),
                                        isFolder = true,
                                    )
                                    vm.setSelectedItems(vm.selectedItems.value + item)
                                } else {
                                    vm.setSelectedItems(vm.selectedItems.value + uris.map { vm.toOutgoingItem(it) })
                                }
                            } catch (e: Exception) {
                                // 打包失败回退为逐文件（不崩溃），并向用户提示。
                                Log.e("HomeScreen", "zipUris failed: ${e.message}")
                                vm.reportError("打包文件夹失败，已改为逐文件处理：${e.message ?: "未知错误"}")
                                try {
                                    vm.setSelectedItems(vm.selectedItems.value + uris.map { vm.toOutgoingItem(it) })
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeScreen", "pickFolder failed: ${e.message}")
                vm.reportError("选择文件夹失败：${e.message ?: "未知错误"}")
            }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(
        openDocumentTreeContract,
        onPickFolderResult,
    )

    // 复用同一套“选目录”单例 + 稳定回调（与 pickFolder 同构），避免 mNextRc 越界崩溃。
    // 仅用于让用户挑选“接收文件保存目录”，结果交给 VM 持久化并申请持久访问权。
    val onPickSaveDirResult = remember {
        { treeUri: Uri? ->
            try {
                if (treeUri != null) {
                    vm.setSaveDir(treeUri)
                }
            } catch (e: Exception) {
                Log.e("HomeScreen", "pickSaveDir failed: ${e.message}")
                vm.reportError("选择保存目录失败：${e.message ?: "未知错误"}")
            }
        }
    }
    val pickSaveDir = rememberLauncherForActivityResult(
        openDocumentTreeContract,
        onPickSaveDirResult,
    )

    if (showLog) {
        DiagnosticLogScreen(onClose = { showLog = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("local-sharing")
                        Text(
                            "发送至：$targetLabel",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    OutlinedButton(onClick = onOpenHistory) { Text("历史") }
                    OutlinedButton(onClick = { showLog = true }) { Text("诊断日志") }
                    OutlinedButton(onClick = { vm.disconnect() }) { Text("断开") }
                },
            )
        },
        snackbarHost = {
            // ★ 错误提示（红色）
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                ) { Text(data.visuals.message) }
            }
            // ★ 中性/成功提示（深色）。必须与错误提示视觉可区分 —— 上一轮只把消息
            // 从 _error 分流到 _notice，但渲染层仍统一用 errorContainer，
            // 用户看到的还是红色错误样式，等于没修（QA 在 P1-4 抓到）。
            // 两个独立 SnackbarHostState 是零风险方案：不用 SnackbarData.actionLabel
            // 之类的内部标记，也不碰 SnackbarDuration（实验性 API）。
            SnackbarHost(noticeHostState) { data ->
                Snackbar(
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                ) { Text(data.visuals.message) }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 上次运行崩溃提示（点击直接进诊断日志）
            if (crashedLastRun) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { showLog = true },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "上次运行崩溃，点此导出诊断日志",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { showLog = true }) { Text("查看") }
                        }
                    }
                }
            }

            // 连接状态横幅
            item {
                ConnectionBanner(
                    connected = conn == ConnState.Connected,
                    reconnecting = conn == ConnState.Reconnecting,
                    label = targetLabel,
                )
            }

            // ---- 发送区：选文件 → 选目标 → 发送（自上而下，PRD 关键布局约束）----
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("选择要发送的内容", style = MaterialTheme.typography.titleMedium)
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
                                subtitle = "选择文件或文件夹，再选择发给哪台设备",
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
                            // 目标选择器放在文件列表之后：选文件与目标无关（PRD G5），
                            // 让系统分享进来的文件也能直接选目标（US-5）
                            OutlinedButton(
                                onClick = onOpenPicker,
                                // 上传中置灰：入口层门闸。正确性由SendTarget 冻结快照保证，
                                // 状态机层 selectDevice 也会拒绝 —— 三者独立成立
                                enabled = !uploading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("发送至：$targetLabel ▾") }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { vm.sendSelected() },
                                enabled = !uploading && conn == ConnState.Connected,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (uploading) {
                                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.height(8.dp))
                                    // progress < 0 = 总量未知（provider 不提供 SIZE 列），不显示误导性的 0%
                                    Text(if (progress < 0f) " 正在发送…" else " ${(progress * 100).toInt()}%")
                                } else {
                                    // 文案带目标名：避免发错设备的最后一道确认
                                    Text(if (targetLabel.isBlank()) "发送" else "发送到 $targetLabel")
                                }
                            }
                            if (uploading) {
                                Spacer(Modifier.height(8.dp))
                                if (progress < 0f) {
                                    // 不带 progress 参数 = 不确定态动画
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                } else {
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                }
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
            // 接收保存目录设置行（SAF 选目录，持久化记忆）
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("保存位置", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (saveDirUri.isNotBlank() && saveDirName.isNotBlank())
                                    saveDirName else "默认：应用 Downloads",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (saveDirUri.isNotBlank()) {
                                TextButton(onClick = { vm.clearSaveDir() }) { Text("清除") }
                            }
                            TextButton(onClick = { pickSaveDir.launch(null) }) {
                                Text(if (saveDirUri.isNotBlank()) "更改" else "选择目录")
                            }
                        }
                        if (saveDirUri.isNotBlank() && saveDirName.isBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                saveDirUri,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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

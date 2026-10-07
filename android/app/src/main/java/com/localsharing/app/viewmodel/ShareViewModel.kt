package com.localsharing.app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localsharing.app.model.IncomingTransfer
import com.localsharing.app.model.OutgoingItem
import com.localsharing.app.model.PcInfo
import com.localsharing.app.model.SavedFile
import com.localsharing.app.network.ShareApi
import com.localsharing.app.network.ShareWebSocket
import com.localsharing.app.network.WsEvent
import com.localsharing.app.util.MAX_ZIP_ENTRIES
import com.localsharing.app.util.MAX_ZIP_TOTAL_BYTES
import com.localsharing.app.util.Prefs
import com.localsharing.app.util.extractZip
import com.localsharing.app.util.getDisplayName
import com.localsharing.app.util.getSize
import com.localsharing.app.util.isSafeChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

sealed class ConnState {
    object Disconnected : ConnState()
    object Connecting : ConnState()
    object Connected : ConnState()
    object Reconnecting : ConnState()
}

class ShareViewModel(app: Application) : AndroidViewModel(app) {

    private val _connState = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val connState = _connState.asStateFlow()

    private val _pcInfo = MutableStateFlow(PcInfo())
    val pcInfo = _pcInfo.asStateFlow()

    private val _baseUrl = MutableStateFlow("")
    private val _deviceId = MutableStateFlow("")
    private val _token = MutableStateFlow("")
    private val _wsUrl = MutableStateFlow("")

    private val _error = MutableStateFlow("")
    val error = _error.asStateFlow()

    private val _selectedItems = MutableStateFlow<List<OutgoingItem>>(emptyList())
    val selectedItems = _selectedItems.asStateFlow()

    /** 系统分享（Share Target）暂存的待发送项；连接成功后由 HomeScreen 调用 flush 进 selectedItems */
    private val _pendingShare = MutableStateFlow<List<OutgoingItem>>(emptyList())
    val pendingShare = _pendingShare.asStateFlow()

    private val _uploading = MutableStateFlow(false)
    val uploading = _uploading.asStateFlow()

    private val _uploadProgress = MutableStateFlow(0f)
    val uploadProgress = _uploadProgress.asStateFlow()

    private val _incoming = MutableStateFlow<List<IncomingTransfer>>(emptyList())
    val incoming = _incoming.asStateFlow()

    private val _saved = MutableStateFlow<List<SavedFile>>(emptyList())
    val saved = _saved.asStateFlow()

    private val _autoSave = MutableStateFlow(Prefs.isAutoSave(app))
    val autoSave = _autoSave.asStateFlow()

    /** 用户选择的接收保存目录（SAF tree Uri 字符串），空表示回退应用 Downloads */
    private val _saveDirUri = MutableStateFlow(Prefs.getSaveTreeUri(app) ?: "")
    val saveDirUri = _saveDirUri.asStateFlow()

    /** 保存目录展示名（用于 UI 回显）；空表示“默认：应用 Downloads” */
    private val _saveDirName = MutableStateFlow(Prefs.getSaveTreeName(app) ?: "")
    val saveDirName = _saveDirName.asStateFlow()

    private var ws: ShareWebSocket? = null
    private var manualDisconnect = false
    private var reconnecting = false

    /** 稳定的设备身份（持久化于 SharedPreferences），用于桌面端复用同一设备条目 */
    private val clientId = Prefs.getDeviceUuid(getApplication())

    /** 通过 IP + 端口连接 */
    fun connect(ip: String, port: String, code: String, deviceName: String, deviceType: String) {
        val base = ShareApi.normalizeBaseUrl("$ip:$port") ?: run {
            _error.value = "无效的地址"
            return
        }
        manualDisconnect = false
        reconnecting = false
        _baseUrl.value = base
        _connState.value = ConnState.Connecting
        viewModelScope.launch {
            try {
                val info = ShareApi.getInfo(base)
                _pcInfo.value = info
                if (info.requiresCode && code.isEmpty()) {
                    _error.value = "该电脑要求输入共享码"
                    _connState.value = ConnState.Disconnected
                    return@launch
                }
                val reg = ShareApi.register(base, deviceName.ifEmpty { Build.MODEL }, deviceType, code.ifEmpty { null }, clientId = clientId)
                if (reg.deviceId.isEmpty()) {
                    _error.value = "注册设备失败"
                    _connState.value = ConnState.Disconnected
                    return@launch
                }
                _deviceId.value = reg.deviceId
                _token.value = reg.token
                _wsUrl.value = reg.wsUrl.ifEmpty { "${base.replace("http", "ws")}/ws?token=${reg.token}" }
                Prefs.saveLastConnection(getApplication(), base, info.name)
                connectWs()
                _connState.value = ConnState.Connected
            } catch (e: Exception) {
                _error.value = e.message ?: "连接失败"
                _connState.value = ConnState.Disconnected
            }
        }
    }

    /** 通过扫码得到的 URL 连接 */
    fun connectFromUrl(url: String) {
        val base = ShareApi.normalizeBaseUrl(url) ?: run {
            _error.value = "无效的二维码内容"
            return
        }
        // base 形如 scheme://host:port（normalizeBaseUrl 已剥离 path/query），
        // 用 Uri 稳健解析，兼容 http(s):// 与 ws(s):// 以及带路径/查询参数的二维码内容
        val uri = android.net.Uri.parse(base)
        val ip = uri.host
        if (ip.isNullOrBlank()) {
            _error.value = "无效的二维码内容"
            return
        }
        // 二维码未携带端口时回退到默认端口 38080（与电视端 ReceiverService.PORT_RANGE_START 一致；
        // 电视端实际端口可能顺延到 38081~38085，那时二维码里会带真实端口）
        val port = if (uri.port != -1) uri.port.toString() else "38080"
        connect(ip, port, "", Build.MODEL, "phone")
    }

    private fun connectWs() {
        ws = ShareWebSocket(_wsUrl.value) { onWsEvent(it) }.apply { connect() }
    }

    private fun onWsEvent(ev: WsEvent) {
        when (ev) {
            is WsEvent.Open -> { /* 连接建立 */ }
            is WsEvent.Incoming -> {
                _incoming.update { it + ev.transfer }
                // 自动保存开启时，收到即保存
                if (_autoSave.value) saveIncoming(ev.transfer)
            }
            is WsEvent.Failure -> {
                _error.value = ev.msg
                maybeReconnect()
            }
            is WsEvent.Closed -> maybeReconnect()
        }
    }

    /** WS 断开后，非用户主动断开则指数退避重连 */
    private fun maybeReconnect() {
        if (manualDisconnect || reconnecting) return
        if (_connState.value == ConnState.Disconnected) return
        reconnecting = true
        _connState.value = ConnState.Reconnecting
        viewModelScope.launch {
            var attempt = 0
            while (!manualDisconnect) {
                val delayMs = (1000L * (1 shl attempt.coerceAtMost(5))).coerceAtMost(30_000L)
                attempt++
                delay(delayMs)
                if (manualDisconnect) break
                try {
                    val info = ShareApi.getInfo(_baseUrl.value)
                    val reg = ShareApi.register(_baseUrl.value, Build.MODEL, "phone", null, clientId = clientId)
                    if (reg.deviceId.isEmpty()) continue
                    _deviceId.value = reg.deviceId
                    _token.value = reg.token
                    _wsUrl.value = reg.wsUrl.ifEmpty { "${_baseUrl.value.replace("http", "ws")}/ws?token=${reg.token}" }
                    connectWs()
                    reconnecting = false
                    _connState.value = ConnState.Connected
                    break
                } catch (e: Exception) {
                    // 继续重试
                }
            }
        }
    }

    fun disconnect() {
        manualDisconnect = true
        reconnecting = false
        ws?.close()
        ws = null
        _connState.value = ConnState.Disconnected
        _incoming.value = emptyList()
        _selectedItems.value = emptyList()
    }

    fun setAutoSave(on: Boolean) {
        _autoSave.value = on
        Prefs.setAutoSave(getApplication(), on)
    }

    /** 设置接收保存目录：持久化 treeUri 并向系统申请跨重启的持久访问权 */
    fun setSaveDir(uri: android.net.Uri) {
        val ctx = getApplication<Application>()
        try {
            // 申请对目录树的持久访问权限，使重启后仍可读写
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            ctx.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            // 部分机型/Uri 不支持持久权限，忽略，仍可本次会话使用
            Log.w("ShareViewModel", "takePersistableUriPermission failed: ${e.message}")
        }
        Prefs.setSaveTreeUri(ctx, uri.toString())
        _saveDirUri.value = uri.toString()
        _saveDirName.value = androidx.documentfile.provider.DocumentFile
            .fromTreeUri(ctx, uri)?.name ?: ""
    }

    /** 清除接收保存目录（回退默认 Downloads） */
    fun clearSaveDir() {
        val ctx = getApplication<Application>()
        Prefs.clearSaveTreeUri(ctx)
        _saveDirUri.value = ""
        _saveDirName.value = ""
    }

    fun setSelectedItems(items: List<OutgoingItem>) {
        _selectedItems.value = items
        _error.value = ""
    }

    /** UI 层（如选择文件/文件夹失败）安全上报错误，避免未捕获异常直接闪退 */
    fun reportError(msg: String) {
        _error.value = msg
    }

    fun removeSelected(index: Int) {
        _selectedItems.update { it.filterIndexed { i, _ -> i != index } }
    }

    /** 发送已选项到电脑 */
    fun sendSelected() {
        val items = _selectedItems.value
        if (items.isEmpty() || _connState.value != ConnState.Connected) return
        val asFolder = items.any { it.isFolder }
        _uploading.value = true
        _uploadProgress.value = 0f
        viewModelScope.launch {
            val res = ShareApi.uploadFiles(
                getApplication(),
                _baseUrl.value,
                _token.value,
                _deviceId.value,
                items,
                asFolder,
            ) { sent, total ->
                // total < 0 = 总量未知（SAF provider 不提供 SIZE 列），用 -1f 表达，
                // UI 据此切到不确定态；写 0f 会让进度永远停在 0%
                _uploadProgress.value = if (total > 0) sent.toFloat() / total else -1f
            }
            _uploading.value = false
            if (res.ok) {
                _selectedItems.value = emptyList()
                _error.value = ""
            } else {
                // 带上传服务端的真实原因（HTTP 状态码 / 响应体 / 异常类型），
                // 只写「请检查连接」无法区分鉴权失败、解析异常与连接中断
                _error.value = "发送失败：${res.detail.ifBlank { "未知错误" }}"
            }
        }
    }

    /** 保存电脑推送过来的文件（文件夹会自动解压） */
    fun saveIncoming(transfer: IncomingTransfer) {
        val ctx = getApplication<Application>()
        // 先下载到缓存临时文件，再落到最终目标目录（Downloads 或 SAF 树）
        val tmpFile = File(ctx.cacheDir, "incoming_${System.currentTimeMillis()}_${transfer.name}")
        _incoming.update { it.map { t -> if (t.id == transfer.id) t.copy(saving = true, progress = 0f) else t } }
        viewModelScope.launch {
            val ok = ShareApi.downloadFile(_baseUrl.value, transfer.id, tmpFile, _token.value) { sent, total ->
                val p = if (total > 0) sent.toFloat() / total else 0f
                _incoming.update { list -> list.map { t -> if (t.id == transfer.id) t.copy(progress = p) else t } }
            }
            if (!ok) {
                tmpFile.delete()
                _incoming.update { it.map { t -> if (t.id == transfer.id) t.copy(saving = false) else t } }
                _error.value = "接收失败"
                return@launch
            }

            // 解析保存目录：用户选择的 SAF 树优先，否则回退应用 Downloads
            val treeUriStr = _saveDirUri.value
            val targetTreeUri = if (treeUriStr.isNotBlank()) {
                try { android.net.Uri.parse(treeUriStr) } catch (_: Exception) { null }
            } else null

            // SAF/MediaStore 写入与 zip 解压均为重 IO/IPC：切到 IO 线程，避免主线程 ANR
            val result = if (targetTreeUri != null) {
                // 写入 SAF 树目录；目录失效（读取失败）时静默回退 Downloads
                try {
                    withContext(Dispatchers.IO) { writeToTree(ctx, targetTreeUri, transfer, tmpFile) }
                } catch (e: Exception) {
                    Log.w("ShareViewModel", "writeToTree failed, fallback Downloads: ${e.message}")
                    withContext(Dispatchers.IO) { writeToDownloads(ctx, transfer, tmpFile) }
                }
            } else {
                withContext(Dispatchers.IO) { writeToDownloads(ctx, transfer, tmpFile) }
            }

            if (result != null) {
                val (savedPath, kind) = result
                _saved.update { it + SavedFile(name = transfer.name, size = transfer.size, kind = kind, savedPath = savedPath) }
                _incoming.update { it.filter { t -> t.id != transfer.id } }
                ws?.sendAck(transfer.id)
            } else {
                _incoming.update { it.map { t -> if (t.id == transfer.id) t.copy(saving = false) else t } }
                _error.value = "保存失败"
            }
            tmpFile.delete()
        }
    }

    /** 写入用户选择的 SAF 树目录，返回 (展示路径, 实际 kind)。失败抛异常。 */
    private suspend fun writeToTree(
        ctx: android.content.Context,
        treeUri: android.net.Uri,
        transfer: IncomingTransfer,
        tmpFile: File,
    ): Pair<String, String>? {
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, treeUri)
            ?: return null
        if (transfer.kind == "folder" && transfer.name.endsWith(".zip", ignoreCase = true)) {
            // 文件夹：在树目录下建子目录并解压
            val dirName = transfer.name.removeSuffix(".zip")
            val dirDoc = root.createDirectory(dirName)
                ?: root.createDirectory("$dirName-${System.currentTimeMillis()}")
                ?: return null
            val ok = extractZipToTree(tmpFile, dirDoc)
            if (!ok) return null
            return dirDoc.uri.toString() to "folder"
        }
        // 单文件：在树目录下建同名文件
        val fileDoc = root.createFile(guessMime(transfer.name), transfer.name) ?: return null
        ctx.contentResolver.openOutputStream(fileDoc.uri)?.use { out ->
            java.io.FileInputStream(tmpFile).use { it.copyTo(out) }
        } ?: return null
        return fileDoc.uri.toString() to transfer.kind
    }

    /** 写入应用 Downloads（默认回退路径），返回 (展示路径, 实际 kind) */
    private suspend fun writeToDownloads(
        ctx: android.content.Context,
        transfer: IncomingTransfer,
        tmpFile: File,
    ): Pair<String, String>? {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val dest = File(dir, transfer.name)
        // transfer.name 来自远端，写出前必须做 canonical 前缀校验，防目录穿越（CWE-22）
        if (!isSafeChild(dir, dest)) {
            Log.w("ShareViewModel", "拒绝可疑文件名（路径穿越）: ${transfer.name}")
            return null
        }
        var kind = transfer.kind
        if (transfer.kind == "folder" && transfer.name.endsWith(".zip", ignoreCase = true)) {
            val outDir = File(dir, transfer.name.removeSuffix(".zip"))
            // 解压根目录同样来自远端文件名，需同样校验后再交给 extractZip
            if (!isSafeChild(dir, outDir)) {
                Log.w("ShareViewModel", "拒绝可疑目录名（路径穿越）: ${transfer.name}")
                return null
            }
            if (extractZip(tmpFile, outDir)) {
                return outDir.absolutePath to "folder"
            }
            // 解压失败则保留 zip
            kind = "file"
        }
        tmpFile.copyTo(dest, overwrite = true)
        return dest.absolutePath to kind
    }

    /**
     * 将 zip 解压进 SAF 树目录（条目数/总字节受限，防 zip bomb 打满存储；
     * SAF 文档 ID 天然约束在树 URI 内，无文件系统路径穿越风险）。
     */
    private fun extractZipToTree(zipFile: File, dirDoc: androidx.documentfile.provider.DocumentFile): Boolean {
        var entryCount = 0
        var totalBytes = 0L
        val buf = ByteArray(8192)
        return try {
            var limitExceeded = false
            val zis = java.util.zip.ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(zipFile)))
            zis.use { z ->
                var entry = z.nextEntry
                while (entry != null && !limitExceeded) {
                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        Log.w("ShareViewModel", "zip 条目数超过上限 $MAX_ZIP_ENTRIES，中止解压")
                        limitExceeded = true
                        break
                    }
                    val name = entry.name ?: run { entry = z.nextEntry; "" }
                    if (name.isEmpty()) continue
                    if (entry.isDirectory) {
                        if (name.trimEnd('/').isNotEmpty()) dirDoc.createDirectory(name.trimEnd('/'))
                    } else {
                        val parent = name.substringBeforeLast('/', "")
                        val targetDir = if (parent.isNotEmpty()) {
                            dirDoc.createDirectory(parent) ?: dirDoc
                        } else dirDoc
                        val fileName = name.substringAfterLast('/')
                        val fileDoc = targetDir.createFile(guessMime(fileName), fileName)
                        fileDoc?.uri?.let { uri ->
                            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                                var read: Int
                                while (z.read(buf).also { read = it } != -1) {
                                    totalBytes += read
                                    if (totalBytes > MAX_ZIP_TOTAL_BYTES) {
                                        Log.w("ShareViewModel", "解压总字节超过上限 $MAX_ZIP_TOTAL_BYTES，中止解压")
                                        limitExceeded = true
                                        break
                                    }
                                    out.write(buf, 0, read)
                                }
                            }
                        }
                    }
                    z.closeEntry()
                    entry = z.nextEntry
                }
            }
            !limitExceeded
        } catch (e: Exception) {
            Log.e("ShareViewModel", "extractZipToTree failed: ${e.message}")
            false
        }
    }

    /** 根据文件名猜测 MIME（SAF createFile 需要，错误不影响实际写入） */
    private fun guessMime(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "zip" -> "application/zip"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /** 清空已保存列表（仅清列表，文件保留在 Downloads，避免误删） */
    fun clearSaved() {
        _saved.value = emptyList()
    }

    /** 由 Uri 构造 OutgoingItem */
    fun toOutgoingItem(uri: android.net.Uri, displayName: String? = null, isFolder: Boolean = false): OutgoingItem {
        val ctx = getApplication<Application>()
        val name = displayName ?: getDisplayName(ctx, uri)
        return OutgoingItem(uri = uri, displayName = name, size = getSize(ctx, uri), isFolder = isFolder)
    }

    // ============ 系统分享目标（Share Target） ============

    /**
     * 解析分享 Intent 并把结果累积进 [_pendingShare]。
     *
     * 关键点：分享进来的 Uri 通常带“临时读取授权”，后续（尤其冷启动/跨进程）再读可能已失效。
     * 因此这里立即在 IO 线程把内容复制到 [Application.cacheDir] 下的缓存文件，
     * 用 [Uri.fromFile] 生成的 file:// Uri 作为上传管线输入（与 HomeScreen 打包文件夹上传同构）。
     *
     * 任何单项异常都用 try/catch 包裹，跳过坏项并 [Log.w]，不因单个坏项中断整体。
     */
    fun handleIncomingShare(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        // (uri, 期望展示名)；展示名留空时由原始 Uri 推导
        val uriItems = mutableListOf<Pair<Uri, String?>>()
        var textItem: String? = null

        if (action == Intent.ACTION_SEND) {
            val streamUri = getParcelableUri(intent, Intent.EXTRA_STREAM)
            if (streamUri != null) {
                uriItems.add(streamUri to null)
            } else {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) textItem = text
            }
        } else { // ACTION_SEND_MULTIPLE
            val list = getParcelableUriList(intent, Intent.EXTRA_STREAM)
            if (!list.isNullOrEmpty()) {
                list.forEach { uriItems.add(it to null) }
            } else {
                // 兜底：EXTRA_STREAM 以 ClipData 形式携带
                val clip = intent.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        val u = clip.getItemAt(i).uri
                        if (u != null) uriItems.add(u to null)
                    }
                }
            }
        }

        // 逐个文件：复制到缓存后入队 _pendingShare（update 原子，天然并发合并）
        uriItems.forEach { (uri, _) ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val ctx = getApplication<Application>()
                    val srcName = getDisplayName(ctx, uri)
                    val cacheFile = copyUriToCache(ctx, uri, srcName) ?: run {
                        Log.w("ShareViewModel", "handleIncomingShare: copy failed for $uri")
                        return@launch
                    }
                    val item = OutgoingItem(
                        uri = Uri.fromFile(cacheFile),
                        displayName = srcName,
                        size = cacheFile.length(),
                        isFolder = false,
                    )
                    _pendingShare.update { it + item }
                } catch (e: Exception) {
                    Log.w("ShareViewModel", "handleIncomingShare: uri item failed: ${e.message}")
                }
            }
        }

        if (textItem != null) {
            val text = textItem
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val ctx = getApplication<Application>()
                    val ts = System.currentTimeMillis()
                    val cacheFile = File(ctx.cacheDir, "shared_text_$ts.txt")
                    cacheFile.writeText(text, StandardCharsets.UTF_8)
                    val item = OutgoingItem(
                        uri = Uri.fromFile(cacheFile),
                        displayName = "shared_text_$ts.txt",
                        size = cacheFile.length(),
                        isFolder = false,
                    )
                    _pendingShare.update { it + item }
                } catch (e: Exception) {
                    Log.w("ShareViewModel", "handleIncomingShare: text item failed: ${e.message}")
                }
            }
        }
    }

    /**
     * 连接成功后，把 [_pendingShare] 合并进 [_selectedItems]，随后清空 pending。
     * 由 HomeScreen 在 conn == Connected 且 pending 非空时调用；此处再做一次 Connected 守卫以防竞态。
     */
    fun flushPendingToSelected() {
        if (_connState.value != ConnState.Connected) return
        // 用 update 在 CAS 内原子完成「读取当前 + 置空」，规避并发 update 写入被非原子置空丢弃的竞态；
        // 复用已有的 kotlinx.coroutines.flow.update 扩展，无需新增 import。
        var pending: List<OutgoingItem> = emptyList()
        _pendingShare.update { cur -> pending = cur; emptyList() }
        if (pending.isEmpty()) return
        setSelectedItems(buildList {
            addAll(_selectedItems.value)
            addAll(pending)
        })
    }

    /** API 33+ 类型安全重载，旧版本回退到无 class 重载 */
    @Suppress("DEPRECATION")
    private fun getParcelableUri(intent: Intent, key: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key)
        }
    }

    /** API 33+ 类型安全重载，旧版本回退到无 class 重载 */
    @Suppress("DEPRECATION")
    private fun getParcelableUriList(intent: Intent, key: String): ArrayList<Uri>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(key)
        }
    }

    /** 将 Uri 内容复制到缓存文件，消除分享 Uri 临时授权失效风险；返回缓存文件或 null */
    private fun copyUriToCache(ctx: Application, uri: Uri, displayName: String): File? {
        val ts = System.currentTimeMillis()
        // 文件名安全化：去除路径分隔符，避免目录穿越
        val safeName = displayName.replace('/', '_').replace('\\', '_')
        val cacheFile = File(ctx.cacheDir, "shared_${ts}_$safeName")
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { out ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                    }
                }
            } ?: run {
                Log.w("ShareViewModel", "copyUriToCache: openInputStream null for $uri")
                return null
            }
            cacheFile
        } catch (e: Exception) {
            Log.w("ShareViewModel", "copyUriToCache failed: ${e.message}")
            cacheFile.delete()
            null
        }
    }
}

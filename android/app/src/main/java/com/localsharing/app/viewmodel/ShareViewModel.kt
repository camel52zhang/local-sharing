package com.localsharing.app.viewmodel

import android.app.Application
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localsharing.app.model.IncomingTransfer
import com.localsharing.app.model.OutgoingItem
import com.localsharing.app.model.PcInfo
import com.localsharing.app.model.SavedFile
import com.localsharing.app.network.ShareApi
import com.localsharing.app.network.ShareWebSocket
import com.localsharing.app.network.WsEvent
import com.localsharing.app.util.Prefs
import com.localsharing.app.util.extractZip
import com.localsharing.app.util.getDisplayName
import com.localsharing.app.util.getSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

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

    private var ws: ShareWebSocket? = null
    private var manualDisconnect = false
    private var reconnecting = false

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
                val reg = ShareApi.register(base, deviceName.ifEmpty { Build.MODEL }, deviceType, code.ifEmpty { null })
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
        val hostPort = base.removePrefix("http://")
        val parts = hostPort.split(":")
        val ip = parts[0]
        val port = parts.getOrNull(1) ?: "8080"
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
                    val reg = ShareApi.register(_baseUrl.value, Build.MODEL, "phone", null)
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

    fun setSelectedItems(items: List<OutgoingItem>) {
        _selectedItems.value = items
        _error.value = ""
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
            val ok = ShareApi.uploadFiles(
                getApplication(),
                _baseUrl.value,
                _token.value,
                _deviceId.value,
                items,
                asFolder,
            ) { sent, total ->
                _uploadProgress.value = if (total > 0) sent.toFloat() / total else 0f
            }
            _uploading.value = false
            if (ok) {
                _selectedItems.value = emptyList()
            } else {
                _error.value = "发送失败，请检查连接"
            }
        }
    }

    /** 保存电脑推送过来的文件（文件夹会自动解压） */
    fun saveIncoming(transfer: IncomingTransfer) {
        val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val dest = File(dir, transfer.name)
        _incoming.update { it.map { t -> if (t.id == transfer.id) t.copy(saving = true, progress = 0f) else t } }
        viewModelScope.launch {
            val ok = ShareApi.downloadFile(_baseUrl.value, transfer.id, dest) { sent, total ->
                val p = if (total > 0) sent.toFloat() / total else 0f
                _incoming.update { list -> list.map { t -> if (t.id == transfer.id) t.copy(progress = p) else t } }
            }
            if (ok) {
                var savedPath = dest.absolutePath
                var kind = transfer.kind
                if (transfer.kind == "folder" && transfer.name.endsWith(".zip", ignoreCase = true)) {
                    val outDir = File(dir, transfer.name.removeSuffix(".zip"))
                    if (extractZip(dest, outDir)) {
                        savedPath = outDir.absolutePath
                        dest.delete()
                    }
                }
                _saved.update { it + SavedFile(name = transfer.name, size = transfer.size, kind = kind, savedPath = savedPath) }
                _incoming.update { it.filter { t -> t.id != transfer.id } }
                ws?.sendAck(transfer.id)
            } else {
                _incoming.update { it.map { t -> if (t.id == transfer.id) t.copy(saving = false) else t } }
                _error.value = "接收失败"
            }
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
}

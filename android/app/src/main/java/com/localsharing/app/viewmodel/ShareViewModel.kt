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
import com.localsharing.app.model.SavedFile
import com.localsharing.app.model.SenderDevice
import com.localsharing.app.model.SendOutcome
import com.localsharing.app.model.TransferRecord
import com.localsharing.app.network.ShareApi
import com.localsharing.app.network.ShareWebSocket
import com.localsharing.app.network.WsEvent
import com.localsharing.app.util.MAX_ZIP_ENTRIES
import com.localsharing.app.util.MAX_ZIP_TOTAL_BYTES
import com.localsharing.app.util.Prefs
import com.localsharing.app.util.SenderDeviceStore
import com.localsharing.app.util.TransferHistoryStore
import com.localsharing.app.util.extractZip
import com.localsharing.app.util.getDisplayName
import com.localsharing.app.util.getSize
import com.localsharing.app.util.isSafeChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

sealed class ConnState {
    object Disconnected : ConnState()
    object Connecting : ConnState()
    object Connected : ConnState()
    object Reconnecting : ConnState()
}

/**
 * 同名设备去重确认的待决状态（PRD R3 / D2）。
 *
 * 两台同型号电视的 `/api/info.name` 完全相同，自动合并会丢设备、
 * 不合并会重复 —— 唯一不失错的方案是问用户一句。
 */
data class PendingDup(
    val host: String,
    val port: Int,
    val name: String,
    val requiresCode: Boolean,
    val existingId: String,
)

class ShareViewModel(app: Application) : AndroidViewModel(app) {

    // ============ L1 设备仓库（持久化，跨重启） ============

    private val _devices = MutableStateFlow<List<SenderDevice>>(emptyList())
    val devices: StateFlow<List<SenderDevice>> = _devices.asStateFlow()

    // ============ L2 运行时会话（每设备一份，只有 active 那份活着） ============

    private val _sessions = MutableStateFlow<Map<String, DeviceSession>>(emptyMap())
    private val _activeDeviceId = MutableStateFlow<String?>(null)

    /**
     * ★ 竞态防护（设计文档 §6.1 R1，本方案最容易漏的一处）。
     *
     * 切设备时旧 socket 的 `onClosed`/`onFailure` 往往在新连接建立之后才回调，
     * 此时若直接处理就会用**新** baseUrl 触发重连，状态彻底错乱。
     * `connectWs()` 时自增并作为回调参数传入，所有 WsEvent 分支首行校验 `gen == connGen`。
     */
    private val connGen = java.util.concurrent.atomic.AtomicInteger(0)

    /** 每设备各自的重连 Job（切设备时 cancel 掉旧的，防重连风暴/ 跨设备重连） */
    private val reconnectJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /** 当前活动目标的会话；UI 唯一需要订阅的连接态。非active 设备的会话留在 map 里，切回去时 token 还在 */
    val activeSession: StateFlow<DeviceSession?> =
        combine(_sessions, _activeDeviceId) { s, id -> id?.let { s[it] } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 当前目标设备条目；HomeScreen 顶部文案与发送按钮用它 */
    val activeDevice: StateFlow<SenderDevice?> =
        combine(_devices, _activeDeviceId) { d, id -> id?.let { i -> d.firstOrNull { it.id == i } } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * active 设备的连接态。**语义与原 `connState` 完全一致（4 态）**，
     * 所以 MainActivity/HomeScreen/ConnectScreen 的 if 分支零改动。
     */
    val connState: StateFlow<ConnState> = activeSession
        .map { it?.state ?: ConnState.Disconnected }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnState.Disconnected)

    private val _error = MutableStateFlow("")
    /** 中性/成功提示：与 _error 分开，避免成功信息被显示成错误样式 */
    private val _notice = MutableStateFlow("")
    val notice: StateFlow<String> = _notice.asStateFlow()

    /** UI 消费完提示后调用，避免重组时重复弹出同一条 snackbar */
    fun consumeNotice() {
        _notice.value = ""
    }
    val error = _error.asStateFlow()

    private val _selectedItems = MutableStateFlow<List<OutgoingItem>>(emptyList())
    val selectedItems = _selectedItems.asStateFlow()

    /** 系统分享（Share Target）暂存的待发送项；连接成功后由 HomeScreen 调用 flush 进 selectedItems */
    private val _pendingShare = MutableStateFlow<List<OutgoingItem>>(emptyList())
    val pendingShare = _pendingShare.asStateFlow()

    // ============ L3 单次发送状态（同一时刻只允许一个） ============

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    /** 由 SendState 派生，语义与原字段一致，UI 零改动 */
    val uploading: StateFlow<Boolean> = _sendState
        .map { it is SendState.Running }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val uploadProgress: StateFlow<Float> = _sendState
        .map { (it as? SendState.Running)?.progress ?: 0f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    /** 正在发送的目标标签（冻结快照）；非上传态为 null。UI 可据此显示「正在发给谁」 */
    val sendingTargetLabel: StateFlow<String?> = _sendState
        .map { (it as? SendState.Running)?.target?.label }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ============ 同名设备去重确认 ============

    /** 非 null 时 UI 应弹确认框：已存在同名设备，是否更新它的地址？ */
    private val _pendingDup = MutableStateFlow<PendingDup?>(null)
    val pendingDup: StateFlow<PendingDup?> = _pendingDup.asStateFlow()

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

    /**
     * 传输历史（只记终态，进程被杀重启后仍在：落 filesDir/sender_history.json）。
     *
     * 声明位置在init 之前 —— 见下方 init 块里的注释。
     */
    private val _history = MutableStateFlow<List<TransferRecord>>(emptyList())
    val history: StateFlow<List<TransferRecord>> = _history.asStateFlow()

    // WS 连接恒为单例（PRD D3）：只有 active 目标持一条连接。
    // 多目标各持一条会让电视端（NanoWSD 每 20s ping、liveSockets 无界）连接数翻倍，
    // 而收益仅是一个「在线」小圆点。
    private var ws: ShareWebSocket? = null
    private var wsDeviceId: String? = null

    /** 手机的稳定身份（持久化于 SharedPreferences），用于接收端复用同一设备条目。
     *  ⚠️ 与 `SenderDevice.id` 是**不同概念**：这个是「这台手机是谁」，
     *  那个是「我要发给哪台设备」。勿混。*/
    private val clientId = Prefs.getDeviceUuid(getApplication())

    private val appCtx: Application get() = getApplication()

    init {
        // 老数据迁移：把旧版单条连接记录合成一条设备条目，保证老用户无感
        SenderDeviceStore.migrateLegacyIfNeeded(appCtx)
        reloadDevices()
        // 恢复上次的目标：静默尝试，失败也不打扰用户（设备可能已关机）
        val last = _devices.value.firstOrNull()
        if (last != null) connectDevice(last.id, restoreOnly = true)
        // 传输历史是常写流水（读文件 + JSONArray 解析），必须在 IO 线程
        viewModelScope.launch(Dispatchers.IO) {
            _history.value = TransferHistoryStore.load(appCtx)
        }
    }

    private fun reloadDevices() {
        _devices.value = SenderDeviceStore.loadDevices(appCtx)
    }

    // ============ 设备入库与连接 ============

    /**
     * probe + register + 写入设备仓库 + 建立会话。
     *
     * @param restoreOnly 启动恢复上次目标时为 true：失败只静默记录，不弹错误打扰用户
     * @param code 共享码；为空则回退用设备已存的 shareCode
     */
    private fun connectDevice(deviceId: String, code: String? = null, restoreOnly: Boolean = false) {
        val device = _devices.value.firstOrNull { it.id == deviceId } ?: return
        val shareCode = code?.takeIf { it.isNotBlank() } ?: device.shareCode
        val base = device.baseUrl
        val gen = connGen.incrementAndGet()

        _activeDeviceId.value = deviceId
        updateSession(deviceId) { DeviceSession(deviceId, base, "", "", "", ConnState.Connecting) }

        viewModelScope.launch {
            try {
                val info = ShareApi.getInfo(base)
                if (info.requiresCode && shareCode.isNullOrEmpty()) {
                    fail(deviceId, "该设备要求输入共享码", restoreOnly)
                    return@launch
                }
                val reg = ShareApi.register(
                    base, Build.MODEL.ifEmpty { "phone" }, "phone",
                    shareCode?.takeIf { it.isNotEmpty() }, clientId = clientId,
                )
                if (reg.deviceId.isEmpty()) {
                    fail(deviceId, "注册设备失败", restoreOnly)
                    return@launch
                }
                val wsUrl = reg.wsUrl.ifEmpty { "${base.replace("http", "ws")}/ws?token=${reg.token}" }

                // 落库：刷新 name / requiresCode / lastOkAt。主机地址可能被改过，
                // 以本次实际连通的 baseUrl 为准（端口顺延后重扫要能纠正条目）
                val stored = SenderDeviceStore.upsertDevice(
                    appCtx,
                    device.copy(
                        name = info.name.ifBlank { device.name },
                        host = base.substringAfter("://").substringBefore(":"),
                        port = base.substringAfterLast(":").toIntOrNull() ?: device.port,
                        requiresCode = info.requiresCode,
                        shareCode = shareCode?.takeIf { it.isNotEmpty() } ?: device.shareCode,
                        lastOkAt = System.currentTimeMillis(),
                    ),
                    protectId = deviceId,
                )
                reloadDevices()
                Prefs.saveLastConnection(appCtx, base, stored.name)

                updateSession(deviceId) {
                    DeviceSession(deviceId, base, reg.deviceId, reg.token, wsUrl, ConnState.Connected)
                }
                connectWs(deviceId, wsUrl, gen)
            } catch (e: Exception) {
                fail(deviceId, e.message ?: "连接失败", restoreOnly)
            }
        }
    }

    private fun fail(deviceId: String, msg: String, quiet: Boolean) {
        updateSession(deviceId) { it?.copy(state = ConnState.Disconnected, lastError = msg) }
        if (!quiet) _error.value = msg
    }

    /**
     * probe 后决定如何入库。
     *
     * 去重规则（设计文档 §6.5，设计阶段补充 R3 之外的场景）：
     * ① `host:port` 完全相同 → **静默更新**该条目。
     *    「扫同一台电视两次」是最高频操作，弹窗确认纯属骚扰；端口顺延后重扫也要能纠错。
     * ② `host:port` 不同但 `name` 相同 → 需用户确认（同型号两台电视，不能静默合并，
     *    合并会丢设备，不合并会重复，唯一不失错的方案是问一句）。
     * ③ 其余 → 新增。
     */
    fun addDeviceByUrl(url: String) {
        val base = ShareApi.normalizeBaseUrl(url) ?: run {
            _error.value = "无效的二维码内容"
            return
        }
        // 用 Uri 稳健解析，兼容 http(s):// 与 ws(s):// 以及带路径/查询参数的二维码内容
        val uri = android.net.Uri.parse(base)
        val host = uri.host
        if (host.isNullOrBlank()) {
            _error.value = "无效的二维码内容"
            return
        }
        // 二维码未携带端口时回退到默认端口 38080（与电视端 ReceiverService.PORT_RANGE_START 一致）
        val port = if (uri.port != -1) uri.port else 38080

        viewModelScope.launch {
            val probe = runCatching { ShareApi.getInfo(base) }.getOrNull()
            if (probe == null) {
                _error.value = "无法连接该设备，请检查地址是否可达"
                return@launch
            }
            val sameKey = SenderDeviceStore.findByKey(appCtx, "$host:$port")
            if (sameKey != null) {
                // ① 静默更新（不弹窗）
                SenderDeviceStore.upsertDevice(
                    appCtx,
                    sameKey.copy(
                        name = probe.name.ifBlank { sameKey.name },
                        requiresCode = probe.requiresCode,
                    ),
                    protectId = sameKey.id,
                )
                reloadDevices()
                selectDevice(sameKey.id)
                _notice.value = "已更新「${probe.name.ifBlank { sameKey.name }}」的地址"
                return@launch
            }
            val sameName = SenderDeviceStore.findByName(appCtx, probe.name)
            if (sameName != null) {
                // ② 同名不同地址：交给 UI 弹确认，暂不入库
                _pendingDup.value = PendingDup(
                    host = host, port = port,
                    name = probe.name.ifBlank { "未知设备" },
                    requiresCode = probe.requiresCode,
                    existingId = sameName.id,
                )
                return@launch
            }
            // ③ 新增
            val added = SenderDeviceStore.upsertDevice(
                appCtx,
                SenderDevice(
                    id = "", name = probe.name.ifBlank { "未知设备" },
                    host = host, port = port, requiresCode = probe.requiresCode,
                ),
            )
            reloadDevices()
            selectDevice(added.id)
        }
    }

    /** 手动输入 IP + 端口 + 共享码后入库（PRD R4，局域网里这是唯一的救命通道） */
    fun addDeviceManually(host: String, port: String, code: String) {
        val base = ShareApi.normalizeBaseUrl("$host:$port") ?: run {
            _error.value = "无效的地址"
            return
        }
        val h = base.substringAfter("://").substringBefore(":")
        val p = base.substringAfterLast(":").toIntOrNull() ?: 0
        val cleanCode = code.trim().takeIf { it.isNotEmpty() }

        viewModelScope.launch {
            val probe = runCatching { ShareApi.getInfo(base) }.getOrNull()
            if (probe == null) {
                _error.value = "无法连接该设备，请检查 IP 与端口"
                return@launch
            }
            val existing = SenderDeviceStore.findByKey(appCtx, "$h:$p")
            val added = SenderDeviceStore.upsertDevice(
                appCtx,
                (existing ?: SenderDevice(id = "", name = "", host = h, port = p)).copy(
                    name = probe.name.ifBlank { existing?.name ?: "未知设备" },
                    requiresCode = probe.requiresCode,
                    shareCode = cleanCode ?: existing?.shareCode,
                ),
            )
            reloadDevices()
            selectDevice(added.id)
        }
    }

    /** 确认「已存在同名设备，是否更新它的地址」 */
    fun resolveDuplicate(updateExisting: Boolean) {
        val dup = _pendingDup.value ?: return
        _pendingDup.value = null
        if (updateExisting) {
            val existing = SenderDeviceStore.getDevice(appCtx, dup.existingId)
            if (existing != null) {
                val added = SenderDeviceStore.upsertDevice(
                    appCtx,
                    existing.copy(
                        host = dup.host, port = dup.port,
                        name = dup.name, requiresCode = dup.requiresCode,
                    ),
                    protectId = existing.id,
                )
                reloadDevices()
                selectDevice(added.id)
            }
        } else {
            val added = SenderDeviceStore.upsertDevice(
                appCtx,
                SenderDevice(
                    id = "", name = dup.name, host = dup.host, port = dup.port,
                    requiresCode = dup.requiresCode,
                ),
            )
            reloadDevices()
            selectDevice(added.id)
        }
    }

    fun dismissDuplicate() {
        _pendingDup.value = null
    }

    /**
     * 切换发送目标。
     *
     * ★ 三层保证的第 2 层（状态机）：上传中直接 return，不发事件也不改状态。
     * 但请注意——**第1 层（[SendTarget] 冻结快照）才是正确性**，本方法只是体验。
     */
    fun selectDevice(deviceId: String) {
        if (_sendState.value is SendState.Running) {
            _error.value = "正在发送中，完成后再切换"
            return
        }
        if (deviceId == _activeDeviceId.value) {
            // 已经是当前目标：仍要确保会话活着（首屏恢复时可能还没连上）
            if (activeSession.value?.state == null || activeSession.value?.token.isNullOrEmpty()) {
                connectDevice(deviceId)
            }
            return
        }
        // 旧目标断连而非后台保活（PRD D4）：保活会让「收到的文件」跨设备混淆归属
        leaveTarget()
        val device = _devices.value.firstOrNull { it.id == deviceId } ?: return
        connectDevice(device.id)
    }

    /**
     * 离开当前目标（切设备用）。
     *
     * 与 [disconnectAll] 的关键区别：**不清 [selectedItems]**。
     * 待发文件与目标无关（PRD G5）——切设备后已选文件必须仍在。
     */
    private fun leaveTarget() {
        connGen.incrementAndGet() // 让在途的 WS 回调全部失效
        val leaving = _activeDeviceId.value
        if (leaving != null) {
            reconnectJobs.remove(leaving)?.cancel()
            updateSession(leaving) { null }
        }
        ws?.close()
        ws = null
        wsDeviceId = null
        _incoming.value = emptyList()
    }

    /**
     * 扫码得到的 URL（旧入口，等价于 addDeviceByUrl）。
     * 保留是为了让 ScannerScreen 的调用点不必改语义。
     */
    fun connectFromUrl(url: String) = addDeviceByUrl(url)

    private fun connectWs(deviceId: String, wsUrl: String, gen: Int) {
        ws?.close()
        wsDeviceId = deviceId
        ws = ShareWebSocket(wsUrl) { onWsEvent(deviceId, gen, it) }.apply { connect() }
    }

    private fun onWsEvent(deviceId: String, gen: Int, ev: WsEvent) {
        // ★ 竞态防护 R1：过期回调必须在这里就被丢弃，
        // 否则旧 socket 的 onClosed 会在新连接建立后触发一次指向错误 baseUrl 的重连
        if (gen != connGen.get()) return
        // 只有 active 设备的会话事件才处理
        if (deviceId != _activeDeviceId.value) return
        when (ev) {
            is WsEvent.Open -> { /* 连接建立 */ }
            is WsEvent.Incoming -> {
                _incoming.update { it + ev.transfer }
                // 自动保存开启时，收到即保存
                if (_autoSave.value) saveIncoming(ev.transfer)
            }
            is WsEvent.Failure -> {
                _error.value = ev.msg
                maybeReconnect(deviceId, gen)
            }
            is WsEvent.Closed -> maybeReconnect(deviceId, gen)
        }
    }

    /**
     * WS 断开后指数退避重连（仅作用于当前目标，PRD D3/§3.2）。
     *
     * 退避公式 `(1000L * (1 shl attempt.coerceAtMost(5))).coerceAtMost(30_000L)` 原样保留，已验证可用。
     * 每台设备的 attempt 计数存在各自的 Job 里，切设备不共享。
     */
    private fun maybeReconnect(deviceId: String, gen: Int) {
        if (deviceId != _activeDeviceId.value) return
        if (gen != connGen.get()) return
        if (reconnectJobs[deviceId]?.isActive == true) return // 已在重连中，不重复起
        updateSession(deviceId) { it?.copy(state = ConnState.Reconnecting) }

        val job = viewModelScope.launch {
            var attempt = 0
            while (gen == connGen.get() && deviceId == _activeDeviceId.value) {
                val delayMs = (1000L * (1 shl attempt.coerceAtMost(5))).coerceAtMost(30_000L)
                attempt++
                delay(delayMs)
                if (gen != connGen.get() || deviceId != _activeDeviceId.value) break
                try {
                    // ★ 修既有 bug：原来这里传 code = null，而桌面端 app.ts 在 shareCode 非空时
                    // 会校验 req.body.code —— 带共享码的电脑重连**必然 403**，永久卡在 Reconnecting。
                    // 必须从 SenderDevice.shareCode 取码传入（每台设备各存各的）。
                    val device = _devices.value.firstOrNull { it.id == deviceId } ?: break
                    ShareApi.getInfo(device.baseUrl) // 探活
                    val reg = ShareApi.register(
                        device.baseUrl, Build.MODEL, "phone",
                        device.shareCode?.takeIf { it.isNotEmpty() }, clientId = clientId,
                    )
                    if (reg.deviceId.isEmpty()) continue
                    val wsUrl = reg.wsUrl.ifEmpty { "${device.baseUrl.replace("http", "ws")}/ws?token=${reg.token}" }
                    updateSession(deviceId) {
                        it?.copy(
                            remoteDeviceId = reg.deviceId, token = reg.token, wsUrl = wsUrl,
                            state = ConnState.Connected,
                        )
                    }
                    SenderDeviceStore.markReachable(appCtx, deviceId)
                    reloadDevices()
                    connectWs(deviceId, wsUrl, gen)
                    break
                } catch (e: Exception) {
                    // 继续重试；退避到30s 封顶。设备已关机时永远失败，
                    // 但**保留设备条目**（电视临时关机会回来，不该删记录），且绝不自动切到别的设备
                }
            }
            reconnectJobs.remove(deviceId)
        }
        reconnectJobs[deviceId] = job
    }

    private fun updateSession(deviceId: String, block: (DeviceSession?) -> DeviceSession?) {
        _sessions.update { m -> block(m[deviceId])?.let { m + (deviceId to it) } ?: m - deviceId }
    }

    /**
     * 用户显式断开。
     *
     * 保留原有「全清」语义（含清空已选文件）—— 用户主动断开意味着放弃这次发送。
     * 注意与 [leaveTarget] 区分：切设备绝不能清 `_selectedItems`（PRD G5）。
     */
    fun disconnect() {
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        connGen.incrementAndGet()
        ws?.close()
        ws = null
        wsDeviceId = null
        _activeDeviceId.value = null
        _sessions.value = emptyMap()
        _incoming.value = emptyList()
        _selectedItems.value = emptyList()
    }

    // ============ 设备管理 ============

    /** 重命名；alias 空串表示清除别名（回退显示真实 name） */
    fun renameDevice(id: String, alias: String?) {
        SenderDeviceStore.updateAlias(appCtx, id, alias?.trim()?.takeIf { it.isNotEmpty() }, protectId = id)
        reloadDevices()
    }

    /** 移除设备。上传中拒绝；当前目标拒绝（否则用户会停在无目标的悬空态） */
    fun removeDevice(id: String) {
        if (_sendState.value is SendState.Running) {
            _error.value = "正在发送中，完成后再删除设备"
            return
        }
        if (id == _activeDeviceId.value) {
            _error.value = "请先切换到其他设备再删除"
            return
        }
        reconnectJobs.remove(id)?.cancel()
        _sessions.update { it - id }
        SenderDeviceStore.removeDevice(appCtx, id)
        reloadDevices()
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

    /** 发送已选项到当前目标设备 */
    fun sendSelected() {
        val items = _selectedItems.value
        // 守卫：只允许一个发送任务。正在发送时重复点击直接忽略
        if (items.isEmpty() || _sendState.value is SendState.Running) return
        val device = _devices.value.firstOrNull { it.id == _activeDeviceId.value } ?: return
        val session = _sessions.value[device.id] ?: return
        if (session.state != ConnState.Connected) return

        // ★★★ 冻结目标快照（正确性保证，设计文档 §1.4）★★★
        // 上传目标（baseUrl/token/remoteDeviceId）从这一行起只读 target 这个不可变对象，
        // **不再回读 _sessions / _devices / _activeDeviceId**。
        // 「上传中切设备」只改 _activeDeviceId，物理上碰不到 target —— 文件绝不会发到别的设备，
        // token 也不可能串到别的设备上。
        val target = SendTarget.of(device, session) ?: return
        val asFolder = items.any { it.isFolder }
        val startedAt = System.currentTimeMillis()
        val localNames = items.map { it.displayName }
        val localTotal = items.sumOf { if (it.size > 0) it.size else 0L }
            .let { sum -> if (items.any { it.size <= 0 }) -1L else sum }

        _sendState.value = SendState.Running(target, startedAt, localTotal, 0L)

        viewModelScope.launch {
            var res: ShareApi.UploadResult
            try {
                res = ShareApi.uploadFiles(
                    appCtx,
                    target.baseUrl,      // ← 只读冻结快照
                    target.token,        // ← 不读 _sessions，绝不会串到别的设备的 token
                    target.remoteDeviceId,
                    items,
                    asFolder,
                ) { sent, total ->
                    val st = _sendState.value
                    if (st is SendState.Running && st.target.deviceId == target.deviceId) {
                        // total < 0 = 总量未知（SAF provider 不提供 SIZE 列），
                        // 此时保留启动时算出的本地总量，不要用 -1 覆盖掉已知值
                        _sendState.value = st.copy(
                            sentBytes = sent,
                            totalBytes = if (total > 0) total else st.totalBytes,
                        )
                    }
                }
            } catch (e: Exception) {
                // 网络异常也要落到终态并写历史，否则会留下永久卡在 Running 的死状态
                res = ShareApi.UploadResult(false, "${e.javaClass.simpleName}: ${e.message}")
            }
            val finishedAt = System.currentTimeMillis()
            _sendState.value = SendState.Idle

            if (res.ok) {
                _selectedItems.value = emptyList()
                _error.value = ""
            } else {
                // 带上传服务端的真实原因（HTTP 状态码 / 响应体 / 异常类型），
                // 只写「请检查连接」无法区分鉴权失败、解析异常与连接中断
                _error.value = "发送失败：${res.detail.ifBlank { "未知错误" }}"
            }
            writeHistory(target, items.size, localNames, res, startedAt, finishedAt, localTotal)
        }
    }

    /**
     * 写一条传输历史（**只在终态写**，进行中不落盘）。
     *
     * 回执降级策略（设计文档 §3.4）：
     * - `count` 缺失 → 用请求文件数兜底记SUCCESS，并在 detail 注明「接收端未回报数量」
     * - `files[]` 缺失 → 用手机端本地文件名兜底
     * - `receivedCount < requestedCount` → 记 **PARTIAL**，不谎报 SUCCESS
     *   （这是回执带来的**真实**部分成功判据，不是假进度）
     */
    private fun writeHistory(
        target: SendTarget,
        requested: Int,
        localNames: List<String>,
        res: com.localsharing.app.network.ShareApi.UploadResult,
        startedAt: Long,
        finishedAt: Long,
        totalBytes: Long,
    ) {
        val receipt = res.receipt
        val received = receipt.count
        val outcome = when {
            !res.ok -> SendOutcome.FAILED
            received != null && received < requested -> SendOutcome.PARTIAL
            else -> SendOutcome.SUCCESS
        }
        val detail = buildString {
            if (!res.ok) append(res.detail.ifBlank { "未知错误" })
            if (received == null) {
                if (isNotEmpty()) append("；")
                append("接收端未回报数量")
            }
            if (receipt.files.isEmpty()) {
                if (isNotEmpty()) append("；")
                append("文件名来自本机（接收端未回报落盘名）")
            }
        }
        val record = TransferRecord(
            id = java.util.UUID.randomUUID().toString(),
            deviceId = target.deviceId,
            deviceLabel = target.label,
            targetHost = target.host,
            targetPort = target.port,
            // 优先用接收端回报的真实落盘名（含 MediaStore 同名改名）
            fileNames = receipt.files.map { it.name }.ifEmpty { localNames },
            totalBytes = totalBytes,
            requestedCount = requested,
            receivedCount = received,
            outcome = outcome,
            startedAt = startedAt,
            finishedAt = finishedAt,
            durationMs = finishedAt - startedAt,
            httpCode = res.httpCode,
            receiverTransferId = receipt.transferId,
            detail = detail,
        )
        // 写文件必须在 IO 线程（TransferHistoryStore 读+写整个 JSON 文件）
        // ★ append 后必须同步更新内存态。只写文件不更新 _history 会有两个问题：
        //   1) 同一次会话内 _history 停留在旧数据，依赖「进页时重新 load」才能看到新记录；
        //   2) writeHistory 与 reloadHistory 都在 Dispatchers.IO 上异步执行，
        //      两者无顺序保证 —— reload 先于 append 完成时，历史页会少一条（窗口极小但真实）。
        viewModelScope.launch(Dispatchers.IO) {
            TransferHistoryStore.append(appCtx, record)
            // 直接前置插入，不必重读整个文件（append 已做上限裁剪）
            _history.update { prev ->
                (listOf(record) + prev).take(HISTORY_MAX)
            }
        }
    }

    /** 与 `TransferHistoryStore.MAX`(200) 保持一致 */
    private val HISTORY_MAX = 200

    // ============ 传输历史 ============
    // ⚠️ 声明必须放在 init 之前：Kotlin 按声明顺序初始化属性，
    // init 块里读_history 会报「Unresolved reference」。

    fun reloadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            _history.value = TransferHistoryStore.load(appCtx)
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            TransferHistoryStore.clear(appCtx)
            _history.value = emptyList()
        }
    }

    fun deleteHistory(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            TransferHistoryStore.delete(appCtx, id)
            _history.value = TransferHistoryStore.load(appCtx)
        }
    }

    /**
     * 重发某条历史记录。
     *
     * ⚠️ 与设计文档 §4.4 的**有意出入**：文档设想「重新 probe host:port 后复用冻结的
     * 文件名」即可重发。但文件名不等于文件内容 —— 历史刻意不存 Uri
     * （分享 Uri 的临时授权早已失效），因此无法凭空重发字节。
     * 这里诚实降级为：切回原目标并提示用户重新选择文件（这才是用户真正需要的动作）。
     */
    fun retryHistory(recordId: String) {
        val r = _history.value.firstOrNull { it.id == recordId } ?: return
        // ★ 提示必须放在 selectDevice 之后，且在同一个协程内。
        // 原因：selectDevice 内部会写 _error（如「正在发送中，完成后再切换」，
        // 见下方 Running 分支）；若本函数先无条件写「已切回」，会把那个真实原因覆盖掉，
        // 用户看到成功提示但其实没切回。这是 QA 在 P1-3 里抓到的。
        viewModelScope.launch {
            // ★ 快照必须在 selectDevice **之前**取。放在之后取的话，
            // selectDevice 已把新文案写进去了，before == after 恒成立，判据永远失效。
            val before = _error.value
            val device = _devices.value.firstOrNull { it.id == r.deviceId }
            if (device != null) {
                selectDevice(device.id)
            } else {
                // 设备条目已被删除：按历史快照的地址重建条目
                val added = SenderDeviceStore.upsertDevice(
                    appCtx,
                    SenderDevice(id = "", name = r.deviceLabel, host = r.targetHost, port = r.targetPort),
                )
                reloadDevices()
                selectDevice(added.id)
            }
            // ★ 用「调用前后的值是否变化」判断，而不是「现在是否为空」。
            //
            // 上一版用 `_error.value.isNullOrBlank()` 是错的：_error 全局只有一个，
            // 18 处写入但只有 2 处清空，残留上一次的失败文案是常态。
            // 那样判据会永久为 false → 「已切回」提示被静默吞掉，用户零反馈，
            // 且界面上还挂着上次的旧错误文案（DevicePickerScreen 会持续渲染它）。
            //
            // 正确判据：selectDevice 若因「发送中」被拒会写入新文案，值必然变化；
            // 没变化说明它成功切了，这时才提示。
            if (_error.value == before) {
                _error.value = "已切回「${r.deviceLabel}」，请重新选择要发送的文件"
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
            // 下载用当前目标的会话凭据；跨设备时守卫确保不会用别处的 token
            val s = _sessions.value[_activeDeviceId.value] ?: return@launch
            val ok = ShareApi.downloadFile(s.baseUrl, transfer.id, tmpFile, s.token) { sent, total ->
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
        if (connState.value != ConnState.Connected) return
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

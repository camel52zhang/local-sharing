package com.localsharing.app.receiver

import android.content.Context
import android.provider.MediaStore
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 电视接收模式的内嵌 HTTP/WS 服务器。
 *
 * 实现与桌面端一致的 API 契约（手机端零改动即可连接推送）：
 * - GET  /health          -> 200 ok
 * - GET  /api/info        -> { name, port, lanIp, connectUrl, requiresCode:false }
 * - POST /api/devices     -> urlencoded(name,type,code,clientId) -> { deviceId, token, wsUrl:"" }
 * - POST /api/upload      -> multipart(files[], asFolder)，头 x-device-id / x-token
 *                            响应 { ok, count, transferId, files:[{name,size}] }
 *                            （count/transferId/files 为加法式扩展，旧版手机不读响应体故不受影响）
 * - WS   /ws?token=xxx    -> 只握手保活，不推业务消息（手机端 WS 断开会触发重连报错）
 *
 * 接收的文件保存到公共 Downloads/local-sharing/（API 29+ 走 MediaStore，无需权限）。
 */
class ReceiverServer(
    private val context: Context,
    private val port: Int,
    private val deviceName: String,
    private val onReceived: (ReceivedItem) -> Unit,
    private val onLog: (String) -> Unit,
) : NanoWSD(port) {

    init {
        // NanoHTTPD 默认的 DefaultTempFileManager 依赖 System.getProperty("java.io.tmpdir")，
        // 部分电视 ROM 上该目录不可写，File.createTempFile 会抛 IOException ->
        // 被 getTmpBucket()/saveTmpFile() 包成 Error 抛出，直接杀死处理线程。
        // 这里显式指定到应用私有缓存目录，必定可写，且不依赖系统属性。
        val dir = File(context.cacheDir, "nanohttpd-tmp").apply { mkdirs() }
        setTempFileManagerFactory(
            object : NanoHTTPD.TempFileManagerFactory {
                override fun create(): NanoHTTPD.TempFileManager =
                    object : NanoHTTPD.TempFileManager {
                        private val created = java.util.concurrent.CopyOnWriteArrayList<java.io.File>()

                        override fun clear() {
                            created.forEach { runCatching { it.delete() } }
                            created.clear()
                        }

                        override fun createTempFile(filenameHint: String?): NanoHTTPD.TempFile {
                            val f = File.createTempFile("ls-", ".tmp", dir)
                            created.add(f)
                            return object : NanoHTTPD.TempFile {
                                override fun delete() {
                                    f.delete()
                                }

                                override fun getName(): String = f.absolutePath

                                override fun open(): java.io.OutputStream =
                                    java.io.FileOutputStream(f)
                            }
                        }
                    }
            },
        )
    }

    /** 防重复提交的简单互斥 */
    private val saveLock = Any()

    /** 当前存活的长连接（用于服务端保活 ping；NanoWSD 2.3.1 无自动心跳） */
    private val liveSockets = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
    private var pinger: Thread? = null

    // ------------------------------------------------------------------

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        // 手机端以 /ws?token=xxx 建连，token 反查 clientId 以标记在线设备
        val token = handshake.parameters["token"]?.firstOrNull() ?: ""
        val clientId = ReceiverStore.clientIdForToken(context, token)
        return object : WebSocket(handshake) {
            override fun onOpen() {
                liveSockets.add(this)
                ensurePinger()
                clientId?.let { DevicePresence.setOnline(it, true) }
                onLog("[ws] phone connected (${clientId?.take(8) ?: "unknown"})")
            }

            override fun onClose(
                code: CloseCode?,
                reason: String?,
                initiatedByRemote: Boolean,
            ) {
                liveSockets.remove(this)
                clientId?.let { DevicePresence.setOnline(it, false) }
                onLog("[ws] phone closed: $code")
            }

            override fun onMessage(message: NanoWSD.WebSocketFrame) { /* 忽略手机端消息 */ }
            override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
            override fun onException(exception: IOException?) {
                onLog("[ws] error: ${exception?.message}")
            }
        }
    }

    /**
     * 保活 ping：NanoWSD 2.3.1 源码中没有任何自动心跳（仅提供手动 ping 帧 API），
     * 若不主动 ping，长时间空闲的连接会被中间设备/系统回收；同时 ping 写失败可及时
     * 发现半开连接并清理。每 20s 一轮。
     */
    private fun ensurePinger() {
        if (pinger != null) return
        pinger = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(20_000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                val dead = mutableListOf<WebSocket>()
                liveSockets.forEach { ws ->
                    try {
                        if (ws.isOpen) ws.ping("k".toByteArray()) else dead += ws
                    } catch (e: Exception) {
                        dead += ws
                    }
                }
                dead.forEach { ws ->
                    liveSockets.remove(ws)
                    try {
                        ws.close(WebSocketFrame.CloseCode.NormalClosure, "ping failed", false)
                    } catch (_: Exception) {
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "ls-ws-ping"
            start()
        }
    }

    override fun stop() {
        pinger?.interrupt()
        pinger = null
        liveSockets.clear()
        DevicePresence.clear()
        super.stop()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        onLog("[http] ${session.method} $uri")
        return try {
            when {
                session.method == Method.OPTIONS -> newFixedLengthResponse("")
                uri == "/health" -> json(Response.Status.OK, """{"status":"ok"}""")
                uri == "/api/info" -> serveInfo()
                uri == "/api/devices" && session.method == Method.POST -> serveRegister(session)
                uri == "/api/upload" && session.method == Method.POST -> serveUpload(session)
                uri == "/ws" -> super.serve(session) // WebSocket 升级交由 openWebSocket
                else -> json(Response.Status.NOT_FOUND, """{"error":"not_found"}""")
            }
        } catch (t: Throwable) {
            // 必须捕获 Throwable 而非 Exception：NanoHTTPD 2.3.1 的 getTmpBucket()/saveTmpFile()
            // 在临时文件创建失败时抛的是 Error（如 java.io.tmpdir 不可写），
            // HTTPSession.execute() 的 catch 链也只抓 SocketException/IOException/ResponseException，
            // Error 会直接冒泡杀死工作线程并关闭 socket —— 手机端只会看到「连接被重置」。
            // upload 的 body > MEMORY_STORE_LIMIT(1024B) 必然走临时文件分支，因此这条路径必须兜住。
            onLog("[http] error(${t.javaClass.simpleName}): ${t.message ?: t.cause?.message}")
            json(Response.Status.INTERNAL_ERROR, """{"error":"${t.message?.replace("\"", "'")}"}""")
        }
    }

    private fun serveInfo(): Response {
        // 手机端会把 base 规范化为 http://ip:port，connectUrl 仅作展示
        val obj = JSONObject()
            .put("name", deviceName)
            .put("port", port)
            .put("lanIp", "")
            .put("connectUrl", "http://$port")
            .put("requiresCode", false)
        return json(Response.Status.OK, obj.toString())
    }

    private fun serveRegister(session: IHTTPSession): Response {
        session.parseBody(mutableMapOf())
        // NanoHTTPD 2.3.1 语义（源码实证）：parseBody(map) 传入的 map 只会被 multipart 填充；
        // application/x-www-form-urlencoded 的字段一律 decodeParms 进 session.parameters。
        // 因此手机端 register（FormBody: name/type/clientId）必须从 session.parameters 读，
        // 否则 clientId 为空 → 设备不登记 + token 不固定 → 上传 401 + WS 无法标记在线。
        fun form(key: String): String = session.parameters[key]?.firstOrNull().orEmpty()
        val name = form("name").ifBlank { "Device" }
        val clientId = form("clientId")
        val token = ReceiverStore.tokenFor(context, clientId)
        // 登记设备名，供电视端「已连接设备」列表展示
        ReceiverStore.upsertDevice(context, clientId, name)
        val obj = JSONObject()
            .put("deviceId", if (clientId.isEmpty()) "tv-dev-${System.currentTimeMillis()}" else clientId)
            .put("token", token)
            .put("wsUrl", "") // 手机端会回退为 ws://host:port/ws?token=xxx，由本服务器 /ws 承接
        onLog("[register] $name clientId=${clientId.ifEmpty { "(空)" }.take(12)}")
        return json(Response.Status.OK, obj.toString())
    }

    private fun serveUpload(session: IHTTPSession): Response {
        // 鉴权：x-token 必须是已注册的 token
        val token = session.headers["x-token"] ?: ""
        val clientId = ReceiverStore.clientIdForToken(context, token)
            ?: run {
                onLog("[upload] 401 unauthorized")
                return json(Response.Status.UNAUTHORIZED, """{"error":"unauthorized"}""")
            }
        val fromDevice = session.headers["x-device-id"]?.take(12) ?: clientId.take(12)

        val files = mutableMapOf<String, String>()
        // body > MEMORY_STORE_LIMIT(1024B) 时 NanoHTTPD 会走临时文件分支，
        // 这里记录关键节点，便于从电视端日志判断卡在哪一步
        onLog("[upload] start body=${session.headers["content-length"] ?: "?"}B tmpdir=${context.cacheDir.name}")
        session.parseBody(files) // multipart 落到临时文件（大文件由 NanoHTTPD 自动落盘缓冲，不占堆内存）
        onLog("[upload] parsed keys=${files.keys.sorted()}")

        // NanoHTTPD 2.3.1 实证语义（官方源码 decodeMultipartFormData + 桌面 JVM 实测确认，
        // 见 .build-tmp/tv_verify5/Probe.java）：
        //  - 计数器 pcount 只在「解析到非空 filename 属性」时递增（源码 :754-763 的 if (!fileName.isEmpty())），
        //    所以普通字段（如 asFolder）不会占用文件序号。
        //  - 同名 part 的 partName 会被就地改写为 name + pcount，故实际 key 依次是
        //    files / files1 / files2 ...；若客户端自己传了 files2/files3，则改写成 files21/files32
        //    （实测 S2），即 key 名不可预测，不能用循环递增下标去猜。
        //  - 关键：files map 的 key 与 parameters 的 key 是同一套命名，一一对应
        //    （files[key] = 临时文件路径，parameters[key] = [该 part 的原始文件名]），
        //    而 parameters["files"] 只含第 1 个 part，长度恒为 1。
        // 因此这里直接遍历 files map 并按同 key 取原始文件名，不再用自增下标索引两套序列。
        val asFolder = (session.parameters["asFolder"]?.firstOrNull() ?: "0") == "1"

        val saved = mutableListOf<Pair<String, Long>>()
        var partIndex = 0
        for ((key, tmpPath) in files) {
            val tmp = File(tmpPath)
            val declared = session.parameters[key]?.firstOrNull { it.isNotBlank() }
            // try/finally：saveToDownloads 抛 MediaStore 异常时也要删临时文件，
            // 否则反复失败会在 cacheDir/nanohttpd-tmp 累积残留。
            try {
                if (!tmp.exists()) {
                    onLog("[upload] WARN part#$partIndex key=$key 临时文件不存在，跳过: $tmpPath")
                } else {
                    // 客户端确实没传 filename 时才退化命名，且记日志便于排查
                    val original = declared ?: run {
                        onLog("[upload] WARN part#$partIndex key=$key 缺少 filename，退化为占位名")
                        "file-${partIndex + 1}"
                    }
                    val (savedName, size) = saveToDownloads(original, tmp, asFolder)
                    saved += savedName to size
                    onLog("[upload] saved part#$partIndex key=$key as=$savedName ($size B)")
                }
            } finally {
                tmp.delete()
            }
            partIndex++
        }
        if (partIndex > 0 && saved.size != partIndex) {
            onLog("[upload] WARN 解析到 $partIndex 个文件 part，实际落盘 ${saved.size} 个，存在丢失")
        }
        if (saved.isEmpty()) {
            return json(Response.Status.BAD_REQUEST, """{"error":"no_files"}""")
        }

        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        //顺带收集已落盘的 ReceivedItem.id，作为回执里的 transferId（与电视端接收记录对账的钥匙）
        val savedIds = mutableListOf<String>()
        synchronized(saveLock) {
            saved.forEachIndexed { idx, (name, size) ->
                val item = ReceivedItem(
                    id = "rcv-${fmt.format(Date(now))}-$idx",
                    name = name,
                    size = size,
                    fromDevice = fromDevice,
                    time = now,
                    isApk = name.lowercase(Locale.US).endsWith(".apk"),
                )
                ReceiverStore.appendReceived(context, item)
                savedIds.add(item.id)
                onReceived(item)
            }
        }
        // 加法式扩展：ok/count 语义与改前逐字节不变，仅追加 transferId 与 files[]。
        // 旧版手机的成功分支根本不读响应体（ShareApi.uploadFiles 丢弃 body），
        // 因此新增字段对它们完全不可见 —— 这是协议向后兼容的基石。
        val filesJson = JSONArray()
        saved.forEach { (name, size) ->
            // name 取 saveToDownloads 回读的真实落盘名（actualName），
            // 含 MediaStore 同名自动改名（photo.jpg -> photo (1).jpg），
            // 这样手机历史里显示的名字与电视上实际看到的文件一致。
            filesJson.put(JSONObject().put("name", name).put("size", size))
        }
        val obj = JSONObject()
            .put("ok", true)
            .put("count", saved.size)
            .put("transferId", savedIds.firstOrNull() ?: "")
            .put("files", filesJson)
        return json(Response.Status.OK, obj.toString())
    }

    /** 保存到公共 Downloads/local-sharing/，返回 (显示名, 实际大小) */
    private fun saveToDownloads(displayName: String, tmp: File, asFolder: Boolean): Pair<String, Long> {
        val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val finalName = if (asFolder && !safeName.lowercase(Locale.US).endsWith(".zip")) {
            safeName + ".zip"
        } else safeName

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // 尊重电视端「保存位置」设置：Download/local-sharing[/子目录]
            val sub = ReceiverStore.getSaveSubdir(context)
            val relative = if (sub.isEmpty()) {
                "Download/${ReceiverStore.SAVE_ROOT}"
            } else {
                "Download/${ReceiverStore.SAVE_ROOT}/$sub"
            }
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                put(MediaStore.Downloads.MIME_TYPE, guessMime(finalName))
                put(MediaStore.Downloads.RELATIVE_PATH, relative)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
            ) ?: throw IllegalStateException("MediaStore insert failed")
            // 不能用 ?.use —— openOutputStream 返回 null 时整段会被静默跳过、不抛任何异常，
            // 随后却按 tmp.length() 上报大小，表现为「记录显示 12MB、实际 0 字节」且无任何日志。
            // 必须显式抛，且上报真实写入字节数（部分写入时也能如实反映）。
            val out = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("openOutputStream returned null for $uri")
            var written: Long
            out.use { o -> written = FileInputStream(tmp).use { it.copyTo(o) } }
            // MediaStore 遇到同名文件会自动改名（如 photo.jpg -> photo (1).jpg）。
            // 必须回读真实落盘名并记录，否则「打开/安装」按 DISPLAY_NAME 反查会命中旧文件。
            val actualName = try {
                context.contentResolver.query(
                    uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else finalName } ?: finalName
            } catch (e: Exception) {
                finalName
            }
            return actualName to written
        }
        // API 28-：落专属顶层目录 /storage/emulated/0/local-sharing[/sub]（不走 Download）。
        // ★ 2026-10-09 借鉴小白文件管理器：TCL ROM 上 Download 与 download（小写，ROM 建）
        //   存在大小写孪生，往 Download 写文件会在两个视图重复出现、删一处两处同消失。
        //   顶层 local-sharing 无孪生目录，碰撞根除（详见 ReceiverStore.savePathLabel 注释）。
        //   依旧可能因平台限制失败（进程拿不到 sdcard_rw 组 → Permission denied），
        //   此时自动降级到应用私有外部目录（无需任何权限，必定可写），并回报真实路径。
        val publicDir = ReceiverStore.legacyPublicDir(context)
        if (publicDir.canWrite() || publicDir.mkdirs()) {
            try {
                val (name, size) = writeToDir(publicDir, finalName, tmp)
                onLog("[save] 落盘公共目录: ${publicDir.absolutePath}/$name")
                return name to size
            } catch (e: Exception) {
                // 不能让单次失败把整次上传打成 500：降级继续
                onLog("[save] WARN 公共目录写入失败，降级到应用目录: ${e.message}")
            }
        } else {
            onLog("[save] WARN 公共目录不可写（mkdirs 未成功），降级到应用目录")
        }
        // 兜底：getExternalFilesDir 属于应用私有，无需任何运行时权限，API 24+ 均可写
        val privBase = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("getExternalFilesDir 返回 null，无法落盘")
        val sub = ReceiverStore.getSaveSubdir(context)
        val privDir = File(privBase, if (sub.isEmpty()) ReceiverStore.SAVE_ROOT else "${ReceiverStore.SAVE_ROOT}/$sub")
        val (name, size) = writeToDir(privDir, finalName, tmp)
        onLog("[save] 已降级落盘到应用目录: ${privDir.absolutePath}/$name")
        // 真实路径回报给上层，用于「打开文件」时定位
        lastFallbackPath = privDir.absolutePath
        ReceiverStore.markFallbackDir(context, privDir.absolutePath)
        return name to size
    }

    /**
     * 上一次落盘是否走了降级目录（应用私有外部目录）及其绝对路径。
     * API 24~28 上公共目录可能不可写，此时「打开/安装」必须用私有路径而非公共路径反查。
     */
    @Volatile
    var lastFallbackPath: String? = null

    /** 写入指定目录，同名自动加 (1) 后缀；返回 (实际文件名, 实际字节数) */
    private fun writeToDir(dir: File, finalName: String, tmp: File): Pair<String, Long> {
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("mkdirs failed: ${dir.absolutePath}")
        }
        var f = File(dir, finalName)
        var i = 1
        while (f.exists()) {
            val dot = finalName.lastIndexOf('.')
            f = File(
                dir,
                if (dot > 0) "${finalName.substring(0, dot)} ($i).${finalName.substring(dot + 1)}"
                else "$finalName ($i)",
            )
            i++
        }
        var written: Long
        f.outputStream().use { input -> written = FileInputStream(tmp).use { it.copyTo(input) } }
        return f.name to written
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json", body)
}

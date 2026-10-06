package com.localsharing.app.receiver

import android.content.Context
import android.provider.MediaStore
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode
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

        // NanoHTTPD 2.3.1 实证语义（源码 decodeMultipartFormData）：
        // - 同名控件不覆盖：第 1 个 -> "files"，第 2 个 -> "files2"，第 3 个 -> "files3"...
        // - 原始文件名按部分顺序存入 parameters["files"] 列表
        val filenames = session.parameters["files"] ?: emptyList()
        val asFolder = (session.parameters["asFolder"]?.firstOrNull() ?: "0") == "1"

        val saved = mutableListOf<Pair<String, Long>>()
        var i = 0
        while (true) {
            val key = if (i == 0) "files" else "files$i"
            val tmpPath = files[key] ?: break
            val tmp = File(tmpPath)
            if (tmp.exists()) {
                val original = filenames.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "file-$i"
                val (savedName, size) = saveToDownloads(original, tmp, asFolder)
                saved += savedName to size
                onLog("[upload] saved $savedName ($size B)")
            }
            tmp.delete()
            i++
        }
        if (saved.isEmpty()) {
            return json(Response.Status.BAD_REQUEST, """{"error":"no_files"}""")
        }

        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
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
                onReceived(item)
            }
        }
        return json(Response.Status.OK, """{"ok":true,"count":${saved.size}}""")
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
            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(tmp).use { it.copyTo(out) }
            }
            // MediaStore 遇到同名文件会自动改名（如 photo.jpg -> photo (1).jpg）。
            // 必须回读真实落盘名并记录，否则「打开/安装」按 DISPLAY_NAME 反查会命中旧文件。
            val actualName = try {
                context.contentResolver.query(
                    uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else finalName } ?: finalName
            } catch (e: Exception) {
                finalName
            }
            return actualName to tmp.length()
        }
        // API 28-：直接写公共 Downloads 目录（已有 WRITE_EXTERNAL_STORAGE 权限）
        val sub = ReceiverStore.getSaveSubdir(context)
        val dir = File(
            android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            if (sub.isEmpty()) ReceiverStore.SAVE_ROOT else "${ReceiverStore.SAVE_ROOT}/$sub",
        )
        dir.mkdirs()
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
        FileInputStream(tmp).use { input -> f.outputStream().use { input.copyTo(it) } }
        return f.name to f.length()
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

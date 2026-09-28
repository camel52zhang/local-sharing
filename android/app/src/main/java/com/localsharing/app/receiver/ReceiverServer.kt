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

    /** 防重复提交的简单互斥 */
    private val saveLock = Any()

    // ------------------------------------------------------------------

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return object : WebSocket(handshake) {
            override fun onOpen() { onLog("[ws] phone connected") }
            override fun onClose(
                code: CloseCode?,
                reason: String?,
                initiatedByRemote: Boolean,
            ) { onLog("[ws] phone closed: $code") }
            override fun onMessage(message: NanoWSD.WebSocketFrame) { /* 忽略手机端消息 */ }
            override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
            override fun onException(exception: IOException?) {
                onLog("[ws] error: ${exception?.message}")
            }
        }
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
        } catch (e: Exception) {
            onLog("[http] error: ${e.message}")
            json(Response.Status.INTERNAL_ERROR, """{"error":"${e.message?.replace("\"", "'")}"}""")
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
        val form = mutableMapOf<String, String>()
        session.parseBody(form)
        val name = form["name"] ?: "Device"
        val clientId = form["clientId"] ?: ""
        val token = ReceiverStore.tokenFor(context, clientId)
        val obj = JSONObject()
            .put("deviceId", if (clientId.isEmpty()) "tv-dev-${System.currentTimeMillis()}" else clientId)
            .put("token", token)
            .put("wsUrl", "") // 手机端会回退为 ws://host:port/ws?token=xxx，由本服务器 /ws 承接
        onLog("[register] $name (clientId=${clientId.take(8)}...)")
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
        session.parseBody(files) // multipart 落到临时文件（大文件由 NanoHTTPD 自动落盘缓冲，不占堆内存）

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
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                put(MediaStore.Downloads.MIME_TYPE, guessMime(finalName))
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
            ) ?: throw IllegalStateException("MediaStore insert failed")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(tmp).use { it.copyTo(out) }
            }
            return finalName to tmp.length()
        }
        // API 28-：直接写公共 Downloads 目录（已有 WRITE_EXTERNAL_STORAGE 权限）
        val dir = File(
            android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "local-sharing",
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

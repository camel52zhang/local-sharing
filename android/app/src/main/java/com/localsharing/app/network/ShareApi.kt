package com.localsharing.app.network

import android.content.Context
import android.net.Uri
import com.localsharing.app.model.OutgoingItem
import com.localsharing.app.model.PcInfo
import com.localsharing.app.model.RegisterResult
import com.localsharing.app.model.UploadReceipt
import com.localsharing.app.model.parseReceipt
import com.localsharing.app.util.getSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object ShareApi {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS) // 大文件上传不限时
        .readTimeout(60, TimeUnit.SECONDS)
        // WebSocket 主动心跳：避免长时间空闲被中间设备（路由器/NAT/电视系统）回收连接；
        // 服务端（桌面 Node / 电视 NanoWSD）收到 Ping 均会自动回 Pong。
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    suspend fun getInfo(baseUrl: String): PcInfo = withContext(Dispatchers.IO) {
        val resp = client.newCall(Request.Builder().url("$baseUrl/api/info").build()).execute()
        val obj = JSONObject(resp.body?.string() ?: "{}")
        PcInfo(
            name = obj.optString("name", ""),
            port = obj.optInt("port", 0),
            lanIp = obj.optString("lanIp").takeIf { it.isNotEmpty() },
            connectUrl = obj.optString("connectUrl", baseUrl),
            requiresCode = obj.optBoolean("requiresCode", false),
        )
    }

    suspend fun register(
        baseUrl: String,
        name: String,
        type: String,
        code: String?,
        clientId: String = "",
    ): RegisterResult = withContext(Dispatchers.IO) {
        val body = okhttp3.FormBody.Builder()
            .add("name", name)
            .add("type", type)
            .apply { if (!code.isNullOrEmpty()) add("code", code) }
            .apply { if (clientId.isNotEmpty()) add("clientId", clientId) }
            .build()
        val resp = client.newCall(
            Request.Builder().url("$baseUrl/api/devices").post(body).build(),
        ).execute()
        val obj = JSONObject(resp.body?.string() ?: "{}")
        RegisterResult(
            deviceId = obj.optString("deviceId"),
            token = obj.optString("token"),
            wsUrl = obj.optString("wsUrl"),
        )
    }

    /**
     * 上传结果：成功与否 + 失败原因（HTTP 状态码 / 响应体 / 异常信息）+ 回执。
     * 失败原因会透传到手机 UI，避免只显示「发送失败，请检查连接」而无法定位。
     *
     * [receipt] 是接收端回报的落盘明细（`/api/upload` 同步返回即代表落盘完成，
     * 响应体本身就是回执）。旧版接收端不回报时为 [UploadReceipt] 空实例。
     */
    data class UploadResult(
        val ok: Boolean,
        val detail: String = "",
        /** 0 = 根本没拿到响应（连接失败） */
        val httpCode: Int = 0,
        val receipt: UploadReceipt = UploadReceipt(),
    )

    /**
     * 手机 → 电脑：上传一个或多个文件。
     * asFolder=true 时服务器会将其标记为“文件夹”（通常是客户端已打包的 zip）。
     */
    suspend fun uploadFiles(
        context: Context,
        baseUrl: String,
        token: String,
        deviceId: String,
        items: List<OutgoingItem>,
        asFolder: Boolean,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): UploadResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext UploadResult(false, "没有选中文件")
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        for (item in items) {
            val size = if (item.size > 0) item.size else getSize(context, item.uri)
            val rb = UriRequestBody(context, item.uri, size, onProgress)
            builder.addFormDataPart("files", item.displayName, rb)
        }
        builder.addFormDataPart("asFolder", if (asFolder) "1" else "0")
        val req = Request.Builder()
            .url("$baseUrl/api/upload")
            .addHeader("x-device-id", deviceId)
            .addHeader("x-token", token)
            .post(builder.build())
            .build()
        return@withContext try {
            client.newCall(req).execute().use { resp ->
                val bodyText = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                if (resp.isSuccessful) {
                    // 同步200 即代表接收端已落盘完成，响应体本身就是回执。
                    // 解析再包一层 runCatching：回执解析失败绝不能把成功的发送误判为失败。
                    val receipt = runCatching { parseReceipt(bodyText) }.getOrDefault(UploadReceipt())
                    UploadResult(true, "", resp.code, receipt)
                } else {
                    // 服务端返回 4xx/5xx 时把状态码与响应体带回去，是定位问题的第一手证据
                    UploadResult(false, "HTTP ${resp.code} ${bodyText.take(200)}".trim(), resp.code)
                }
            }
        } catch (e: Exception) {
            UploadResult(false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 手机拉取电脑推送的文件 */
    suspend fun downloadFile(
        baseUrl: String,
        transferId: String,
        destFile: java.io.File,
        token: String,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/transfer/$transferId")
            .header("X-Token", token)
            .build()
        return@withContext try {
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext false
            val total = resp.body?.contentLength() ?: -1L
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(destFile).use { out ->
                    val buf = ByteArray(8192)
                    var read: Int
                    var sent = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        sent += read
                        if (total > 0) onProgress(sent, total)
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 从连接 URL 解析 host:port */
    fun normalizeBaseUrl(raw: String): String? {
        val trimmed = raw.trim()
        val withScheme = if (trimmed.startsWith("http")) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        return "${url.scheme}://${url.host}:${url.port}"
    }
}

/** 以流方式读取 content Uri 并上报进度的 RequestBody */
class UriRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val declaredSize: Long,
    private val onProgress: (Long, Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = "application/octet-stream".toMediaType()

    // 必须有 -1 分支：declaredSize 来自 SAF 的 OpenableColumns.SIZE，provider 可能不提供该列
    // （见 FileUtil.getSize），此时长度未知。返回 0 会让 OkHttp 认为 part 长度为 0 却仍写入 N 字节，
    // 抛 ProtocolException；返回 -1 则走 chunked 编码，由服务端按实际字节数解析 boundary。
    override fun contentLength(): Long = if (declaredSize > 0) declaredSize else -1L

    override fun writeTo(sink: BufferedSink) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open uri: $uri")
        // 总量未知时如实传 -1，让 UI 切到不确定态；否则进度回调不触发，界面会一直停在 0%
        val total = if (declaredSize > 0) declaredSize else -1L
        input.use {
            val buf = ByteArray(8192)
            var read: Int
            var sent = 0L
            while (it.read(buf).also { r -> read = r } != -1) {
                sink.write(buf, 0, read)
                sent += read
                onProgress(sent, total)
            }
        }
    }
}

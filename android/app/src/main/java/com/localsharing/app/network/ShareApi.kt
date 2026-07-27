package com.localsharing.app.network

import android.content.Context
import android.net.Uri
import com.localsharing.app.model.OutgoingItem
import com.localsharing.app.model.PcInfo
import com.localsharing.app.model.RegisterResult
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
    ): RegisterResult = withContext(Dispatchers.IO) {
        val body = okhttp3.FormBody.Builder()
            .add("name", name)
            .add("type", type)
            .apply { if (!code.isNullOrEmpty()) add("code", code) }
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
    ): Boolean = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext false
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
            val resp = client.newCall(req).execute()
            resp.body?.string()
            resp.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /** 手机拉取电脑推送的文件 */
    suspend fun downloadFile(
        baseUrl: String,
        transferId: String,
        destFile: java.io.File,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/api/transfer/$transferId").build()
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
    override fun contentLength(): Long = declaredSize

    override fun writeTo(sink: BufferedSink) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open uri: $uri")
        input.use {
            val buf = ByteArray(8192)
            var read: Int
            var sent = 0L
            while (it.read(buf).also { r -> read = r } != -1) {
                sink.write(buf, 0, read)
                sent += read
                if (declaredSize > 0) onProgress(sent, declaredSize)
            }
        }
    }
}

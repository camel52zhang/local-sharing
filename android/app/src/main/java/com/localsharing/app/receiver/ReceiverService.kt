package com.localsharing.app.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 接收服务的运行状态（Activity 观察用） */
data class ReceiverStatus(
    val running: Boolean = false,
    val port: Int = 0,
    val lanIp: String = "",
    val log: List<String> = emptyList(),
)

/**
 * 电视接收模式前台服务：承载 ReceiverServer，保活期间通知栏显示运行状态。
 */
class ReceiverService : Service() {

    companion object {
        const val CHANNEL_ID = "receiver"
        const val NOTIF_ID = 1001
        /** 与桌面端一致的默认端口；被占用时依次 +1 尝试 */
        const val BASE_PORT = 8080

        private val _status = MutableStateFlow(ReceiverStatus())
        val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReceiverService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
        }
    }

    private var server: ReceiverServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("启动中…"))
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        server?.stop()
        server = null
        _status.value = _status.value.copy(running = false)
        super.onDestroy()
    }

    private fun startServer() {
        val ip = lanIp()
        // 端口探测：8080 起依次尝试 5 个
        var started: ReceiverServer? = null
        var usedPort = 0
        for (offset in 0 until 5) {
            val p = BASE_PORT + offset
            try {
                val s = ReceiverServer(
                    context = this,
                    port = p,
                    deviceName = "${android.os.Build.MODEL}（电视接收）",
                    onReceived = { /* 记录已由 Store 落盘，Activity 通过轮询/重启观察 */ },
                    onLog = { line -> appendLog(line) },
                )
                s.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                started = s
                usedPort = p
                break
            } catch (e: Exception) {
                appendLog("[port] $p 被占用：${e.message}")
            }
        }
        server = started
        _status.value = ReceiverStatus(
            running = started != null,
            port = usedPort,
            lanIp = ip,
            log = _status.value.log,
        )
        if (started != null) {
            appendLog("[server] listening on $ip:$usedPort")
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification("接收中 · http://$ip:$usedPort"))
        } else {
            appendLog("[server] 启动失败：所有候选端口均被占用")
        }
    }

    private fun appendLog(line: String) {
        val entry = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date()) + " $line"
        _status.value = _status.value.copy(log = (_status.value.log + entry).takeLast(100))
    }

    private fun lanIp(): String {
        return try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            var ip = ""
            while (en.hasMoreElements()) {
                val n = en.nextElement()
                val addrs = n.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) {
                        return a.hostAddress ?: ""
                    }
                }
            }
            ip
        } catch (e: Exception) {
            ""
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "电视接收", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("局域网互传 · 电视接收")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }
}

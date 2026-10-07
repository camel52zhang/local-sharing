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

        /**
         * 端口候选区间：38080~38085。
         *
         * 为什么不用 8080：局域网里 8080 是最常被占的端口（各种开发服务器、反向代理、
         * 路由器管理页、部分智能家居），抢到就用容易与用户已有服务冲突，且难以排查。
         * 38080 段基本只有本应用在用。
         * 被占用时依次 +1 尝试，共 6 个候选。
         */
        const val PORT_RANGE_START = 38080
        const val PORT_RANGE_END = 38085
        const val PORT_RANGE_COUNT = PORT_RANGE_END - PORT_RANGE_START + 1

        /** 与桌面端约定区间的首选端口 */
        const val BASE_PORT = PORT_RANGE_START

        private const val TAG = "ReceiverService"

        private val _status = MutableStateFlow(ReceiverStatus())
        val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

        /**
         * 追加一条服务日志，同时进两处：
         * ① 内存 StateFlow —— 电视 UI 的「服务日志」弹窗直接显示（[TvReceiverActivity] 里
         *    `showLogs` 那个AlertDialog）。电视上没有 logcat 可看，用户截图就能反馈。
         * ② logcat —— 便于 `adb logcat -s ReceiverService` 抓完整上下文。
         *
         * 放在 companion 里而不是实例方法上：[TvReceiverActivity] 的安装流程（不持有Service 实例，
         * Service 可能已stop）也需要写同一份日志。
         */
        fun appendLog(line: String) {
            val entry = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date()) + " $line"
            android.util.Log.i(TAG, line)
            _status.value = _status.value.copy(log = (_status.value.log + entry).takeLast(100))
        }

        fun start(context: Context) {
            val i = Intent(context, ReceiverService::class.java)
            // startForegroundService 是 API 26 才有的方法；在 Android 5.0/6.0 上直接调会
            // NoSuchMethodError（Error 而非 Exception，外层 catch 不住），必须做版本分支。
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                @Suppress("DEPRECATION")
                context.startService(i)
            }
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
        // 端口探测：38080 起依次尝试 38081~38085（共 6 个候选）
        var started: ReceiverServer? = null
        var usedPort = 0
        for (offset in 0 until PORT_RANGE_COUNT) {
            val p = PORT_RANGE_START + offset
            try {
                val s = ReceiverServer(
                    context = this,
                    port = p,
                    deviceName = "${android.os.Build.MODEL}（电视接收）",
                    onReceived = { /* 记录已由 Store 落盘，Activity 通过轮询/重启观察 */ },
                    onLog = { line -> appendLog(line) },
                )
                // 关键：timeout 传 0 = 不设 SO_TIMEOUT。
                // NanoHTTPD 仅在 timeout>0 时 setSoTimeout；NanoWSD 2.3.1 握手后不会重置该超时，
                // 若沿用 SOCKET_READ_TIMEOUT(5000ms)，空闲 WebSocket 会在 5s 后被
                // SocketTimeoutException 打断 → 手机端报 "websocket error" 并反复重连。
                // （桌面端 Node 服务无此超时，所以同一部手机连电脑正常。）
                s.start(0, false)
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
            // 同 createChannel：getSystemService(Class) 是 API 23+，这里用 API 1 的 String 重载
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification("接收中 · http://$ip:$usedPort"))
        } else {
            appendLog("[server] 启动失败：$PORT_RANGE_START~$PORT_RANGE_END 全部被占用")
        }
    }

    /**
     * 选择用于展示二维码的局域网 IP。
     * 电视常有多块网卡（有线 eth0 + 无线 wlan0）以及系统虚拟网卡（p2p0 / dummy / tun / rmnet），
     * 简单取「第一个非回环 IPv4」可能拿到虚拟网卡地址，导致手机扫码后连不上。
     * 策略：跳过虚拟/点对点接口，按 wlan > eth > 其它 的优先级取地址。
     */
    private fun lanIp(): String {
        return try {
            val candidates = mutableListOf<Pair<Int, String>>()
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val n = en.nextElement()
                if (!n.isUp || n.isLoopback) continue
                val name = (n.name ?: "").lowercase(java.util.Locale.US)
                if (name.startsWith("p2p") || name.startsWith("tun") || name.startsWith("rmnet") ||
                    name.startsWith("ppp") || name.contains("dummy")
                ) continue
                val priority = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    else -> 2
                }
                val addrs = n.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) {
                        val host = a.hostAddress ?: continue
                        candidates += priority to host
                    }
                }
            }
            candidates.minByOrNull { it.first }?.second ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun createChannel() {
        // 注意：必须用 getSystemService(String) 这个 API 1 写法。
        // Context.getSystemService(Class<T>) 是 API 23 才重载的，在 Android 5.0 上调用会
        // NoSuchMethodError（Error，不是 Exception），且此处是 onCreate 的第一行 → 启动即崩。
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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

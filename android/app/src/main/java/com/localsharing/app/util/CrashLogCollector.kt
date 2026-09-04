package com.localsharing.app.util

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内崩溃 / 日志收集器（纯标准 Android API，无第三方依赖）。
 *
 * 前提：本 App 为 debuggable 构建（build.gradle.kts -> debug { isDebuggable = true }），
 * 因此运行时可通过 Runtime.exec("logcat") 读取**整个系统**的 logcat，
 * 无需 root、无需 READ_LOGS 权限。
 *
 * 关于 native 崩溃：Java 层 UncaughtExceptionHandler 抓不到 signal 11；但只要本收集器的
 * logcat 进程在后台持续运行，debuggerd 吐到 logcat 的崩溃栈（"FATAL EXCEPTION"、
 * "signal 11 (SIGSEGV)"、"backtrace"）会被写入 app_log.txt，因此 **native 崩溃也能被收集**。
 * 这正是用户真机崩溃后，无需电脑 adb 也能拿到崩溃栈的原因。
 */
object CrashLogCollector {
    private const val TAG = "CrashLogCollector"
    private const val FILE_NAME = "app_log.txt"
    private const val CRASH_FLAG = "crash_flag"
    private const val MAX_BYTES = 2 * 1024 * 1024 // 环形缓冲上限 ~2MB
    private const val TRIM_RATIO = 0.5 // 超限后保留最近 50%
    private const val TRIM_EVERY = 256L // 每写 256 行检查一次文件大小

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var appContext: Context? = null
    private var writeCount = 0L

    /** 上次运行是否崩溃（由 App.onCreate 在启动时消费崩溃标记后填充，供主界面提示） */
    @Volatile
    var lastRunCrashed: Boolean = false

    private fun logFile() = File(appContext!!.filesDir, FILE_NAME)
    private fun flagFile() = File(appContext!!.filesDir, CRASH_FLAG)

    /** 启动后台 logcat 收集。重复调用安全（只会真正启动一次）。 */
    fun start(context: Context) {
        appContext = context.applicationContext
        if (running.compareAndSet(false, true)) {
            executor.execute {
                try {
                    // 持续跟踪模式（不带 -t/-T/-d），进程持续运行，崩溃后仍能收集 debuggerd 的崩溃栈。
                    // 注意：若带 -t/-T 会被 logcat 当作“dump N 行后退出”的非持续模式，收集器进程会提前结束、丢日志。
                    // 初始会 dump 当前 buffer 全部历史，trim() 会在超过 2MB 时只保留最近 50%。
                    val p = Runtime.getRuntime().exec(
                        arrayOf("logcat", "-v", "time", "*:V"),
                    )
                    BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                        reader.forEachLine { line -> writeRaw(line) }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "logcat collect io failed: ${e.message}")
                } catch (t: Throwable) {
                    Log.e(TAG, "logcat collector died: ${t.message}")
                }
            }
        }
    }

    /** 供 UncaughtExceptionHandler 与外部同步写入（崩溃栈标记等）。同步写入，确保进程死前落盘。 */
    @Synchronized
    fun append(text: String) {
        writeRaw(text)
    }

    @Synchronized
    private fun writeRaw(line: String) {
        try {
            val out = logFile()
            FileWriter(out, true).use { it.appendLine(line) }
            if (++writeCount % TRIM_EVERY == 0L) trim()
        } catch (_: Exception) {
            // 日志写入失败绝不影响主流程
        }
    }

    /** 环形缓冲：文件超过上限则只保留最近一部分，防止无限增长。 */
    @Synchronized
    private fun trim() {
        try {
            val out = logFile()
            if (!out.exists() || out.length() <= MAX_BYTES) return
            val lines = FileReader(out).use { it.readLines() }
            val keep = (lines.size * TRIM_RATIO).toInt().coerceAtLeast(1)
            FileWriter(out, false).use { w ->
                for (i in lines.size - keep until lines.size) w.appendLine(lines[i])
            }
        } catch (_: Exception) {
        }
    }

    /** 读取最近 n 行（供 UI 展示，限制行数避免 UI 卡顿）。 */
    fun readLastLines(n: Int): List<String> {
        return try {
            val out = logFile()
            if (!out.exists()) emptyList()
            else FileReader(out).use { it.readLines() }.takeLast(n)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 崩溃时由 UncaughtExceptionHandler 调用：写崩溃标记 + 落到日志文件。 */
    fun markCrash() {
        try { flagFile().writeText(System.currentTimeMillis().toString()) } catch (_: Exception) {}
        append("JAVA_CRASH marker written at ${System.currentTimeMillis()}")
    }

    /** 启动时调用：若上次运行崩溃返回 true 并清除标记。 */
    fun consumeCrashFlag(): Boolean {
        return try {
            val f = flagFile()
            val crashed = f.exists()
            if (crashed) f.delete()
            crashed
        } catch (_: Exception) { false }
    }
}

package com.localsharing.app

import android.app.Application
import android.util.Log
import com.localsharing.app.util.CrashLogCollector

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 日志收集功能绝不能拖垮 App 启动：所有调用整体 try/catch。
        try {
            // 必须先 start() 初始化 appContext，再读取任何日志文件。
            // 否则 consumeCrashFlag() 经 flagFile() 访问 appContext!! 会因未初始化抛 NPE，
            // 导致 Application.onCreate 崩溃、点击图标即闪退。
            CrashLogCollector.start(this)
            CrashLogCollector.lastRunCrashed = CrashLogCollector.consumeCrashFlag()

            // 挂全局未捕获异常处理器：抓 Java 层崩溃写入日志，再交还给系统，保证原有崩溃行为不变。
            // 注意：native 崩溃（signal 11）此处抓不到，但后台 logcat 进程会收集 debuggerd 输出。
            val def = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    CrashLogCollector.markCrash()
                    CrashLogCollector.append("JAVA_CRASH thread=${t.name}\n${e.stackTraceToString()}")
                } catch (_: Exception) {
                }
                def?.uncaughtException(t, e)
            }
        } catch (e: Exception) {
            Log.e("App", "CrashLogCollector init failed (non-fatal): ${e.message}")
        }
    }
}

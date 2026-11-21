package com.astrbot.lite

import android.app.Application
import com.astrbot.lite.core.AiPool
import com.astrbot.lite.core.ChatStore
import com.astrbot.lite.core.Config
import com.astrbot.lite.core.KnowledgeBase
import com.astrbot.lite.core.Logs
import com.astrbot.lite.core.MemoryStore
import com.astrbot.lite.core.Netdisk
import com.astrbot.lite.core.PersonaManager
import com.astrbot.lite.core.PluginManager
import com.astrbot.lite.core.PythonRuntime
import com.astrbot.lite.core.Storage
import com.astrbot.lite.core.TempMedia
import com.astrbot.lite.core.ThoughtEngine

class App : Application() {
    override fun onCreate() {
        writeMarker("App.onCreate() started")
        super.onCreate()
        installCrashLogger()
        writeMarker("crashLogger installed")
        // 1.7.190：统一存储根（Android/data/com.astrbot.lite/）+ 老数据迁移。
        // 必须最先执行：迁移须在各组件建目录之前完成，否则目标目录已存在会被跳过、迁移不生效。
        step("Storage.init") { Storage.init(this) }
        step("Logs.init") { Logs.init(this) }
        Logs.info("[存储] 数据目录：${Storage.root().absolutePath}")
        step("Config.init") { Config.init(this) }
        step("PersonaManager.init") { PersonaManager.init(this) }
        step("MemoryStore.init") { MemoryStore.init(this) }
        step("PluginManager.init") { PluginManager.init(this) }
        step("AiPool.init") { AiPool.init(this) }
        step("KnowledgeBase.init") { KnowledgeBase.init(this) }
        step("ThoughtEngine.init") { ThoughtEngine.init(this) }
        step("Netdisk.init") { Netdisk.init(this) }
        step("PythonRuntime.init") { PythonRuntime.init(this) }
        step("ChatStore.init") { ChatStore.init(this) }
        step("TempMedia.init") { TempMedia.init(this) }
        // 1.7.240 修复：旧版只有单一 qqAppId/qqSecret，必须迁移成第一条账号，
        // 否则升级后账号页 0 个账号、插件页显示“未配置账号”（迁移函数此前从未被调用）
        step("AccountManager.migrate") { com.astrbot.lite.core.AccountManager.migrateFromLegacy() }
        step("QQPanelSync.runOnce") { com.astrbot.lite.core.QQPanelSync.runOnce(this) }
        // WebView 内核升级（1.7.145）：必须在任何 WebView 实例化前执行（下方 warmup 是首个实例化点）。
        // 用 assets 里的 webview-upgrade.apk 把老内核升到 Chromium 70+，根治机顶盒白屏。
        // 三重兜底：文件不存在（用户未放 APK）/ 升级失败 / 类缺失 → 全部静默回退系统 WebView，绝不崩 App。
        // 1.7.224 止血：免安装升级在 Android 7+ linker 限制/14+ DCL 限制下会 native 崩溃白屏。
        // 默认关闭（Config.webViewUpgradeEnabled=false），App 用系统 WebView 正常启动。
        // WebView 免安装升级已彻底移除（1.7.224 止血：会 native 崩溃白屏；assets 已删）
        writeMarker("WebViewUpgrade REMOVED")
        step("WebView warmup") {
            try { android.webkit.WebView(this) } catch (_: Throwable) {}
        }
        writeMarker("App.onCreate() completed")
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
            writeMarker("$name OK")
        } catch (t: Throwable) {
            writeMarker("$name FAILED: ${t.message}")
            android.util.Log.e("AstrBotLite", "[$name] init failed", t)
        }
    }

    private fun writeMarker(msg: String) {
        try {
            val f = java.io.File(getExternalFilesDir(null) ?: filesDir, "startup_marker.txt")
            // 修复（1.7.179）：启动标记每次启动追加约 18 行且从不裁剪，长年累积会到 MB 级；超限截断重来
            if (f.isFile && f.length() > 128 * 1024L) f.writeText("")
            f.appendText("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())} $msg\n")
        } catch (_: Throwable) {}
    }

    /**
     * WebView 内核升级（1.7.145，反射式三重兜底）：
     * ① 类缺失（依赖被裁剪/未生效）→ 直接跳过，用系统 WebView
     * ② assets/webview-upgrade.apk 不存在（用户未放 APK）→ 跳过，用系统 WebView
     * ③ 升级过程异常 → catch 住记日志，绝不崩 App
     * 全部通过反射调用：即使 R8 把 WebViewUpgrade 优化掉，也只是"找不到类"，不会 NoClassDefFoundError 崩启动。
     */
    /**
     * WebView 内核升级（1.7.224 修复版）：
     * ① 包名修正：WebViewUpgrade 实际在 com.norman.webviewup.lib（不是 maven group id）
     * ② 放弃 UpgradeAssetSource：它内部用 AssetManager.openFd()，assets APK 压缩时会抛
     *    FileNotFoundException: ... probably compressed。改为 assets.open() 复制到内部目录，
     *    再用 UpgradeFileSource 从本地文件升级。
     * ③ 升级必须在任何 WebView 实例化之前完成（App.onCreate 里 warmup 之前）。
     */
    private fun upgradeWebView() {
        try {
            val upgradeCls = Class.forName("com.norman.webviewup.lib.WebViewUpgrade")
            val assetSrcCls = Class.forName("com.norman.webviewup.lib.source.UpgradeAssetSource")

            // 检查 assets 里是否有升级 APK
            try {
                assets.open("webview-upgrade.apk").close()
            } catch (_: Throwable) {
                Logs.info("[WebViewUpgrade] assets/webview-upgrade.apk 缺失，跳过升级")
                return
            }

            // 构造 UpgradeAssetSource(context, "webview-upgrade.apk")
            // 注意：build.gradle 里必须配置 aaptOptions { noCompress "apk" }，否则库内部读 Zip 会失败
            val source = assetSrcCls.getConstructor(android.content.Context::class.java, String::class.java)
                .newInstance(this, "webview-upgrade.apk")

            // 反射调用静态方法 upgrade(UpgradeSource)
            val m = upgradeCls.methods.firstOrNull { it.name == "upgrade" && it.parameterCount == 1 }
            if (m != null) {
                m.invoke(null, source)
                Logs.info("[WebViewUpgrade] 已请求升级 WebView 内核")
            } else {
                Logs.warn("[WebViewUpgrade] 未找到 upgrade 方法")
            }

            // 记录升级后的内核信息
            try {
                val pn = upgradeCls.getMethod("getUpgradeWebViewPackageName").invoke(null) as? String
                val ver = upgradeCls.getMethod("getUpgradeWebViewVersion").invoke(null) as? String
                Logs.info("[WebViewUpgrade] 升级后包名=$pn 版本=$ver")
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            Logs.warn("[WebViewUpgrade] 升级失败（回退系统 WebView）：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 崩溃兜底（1.7.127 彻底重写）：
     * - 通知栏弹第一优先（不依赖文件），截图即见
     * - 文件写第二（外部可读 crash_last.txt）
     * - logcat 第三（adb logcat 能捞）
     * - prev.uncaughtException() 仍会被调用（其抛出的异常被吞，防止二次崩溃）；
     *   注意系统默认 handler 可能仍弹系统崩溃 dialog，本兜底只能保证通知栏一定可见
     */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val text = "时间: $stamp\n线程: ${t.name}\n\n$sw"
            notifyCrashDirect(text)
            writeCrashDirect(text)
            android.util.Log.e("AstrBotLite", text)
            // Bug #2 修复：prev==null（无上级 handler）时直接杀进程，防止 App 僵死但用户看不见崩溃提示。
            if (prev != null) {
                try { prev.uncaughtException(t, e) } catch (_: Throwable) {}
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun writeCrashDirect(text: String) {
        val base = getExternalFilesDir(null) ?: filesDir
        try { java.io.File(base, "crash_last.txt").writeText(text) } catch (_: Throwable) {}
        try {
            @Suppress("DEPRECATION")
            val dl = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "AstrBotLite-crash.txt"
            )
            dl.writeText(text)
        } catch (_: Throwable) {}
    }

    private fun notifyCrashDirect(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val chId = "crash_log"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(chId, "崩溃日志", android.app.NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val b = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, chId)
            else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
            b.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("AstrBotLite 启动出错")
                .setStyle(android.app.Notification.BigTextStyle().bigText(text.take(2500)))
                .setAutoCancel(true)
            // Bug #3 修复：1001 与 BotService.NOTIF_ID 冲突，互相覆盖 → 崩溃通知改用 2001。
            nm.notify(2001, b.build())
        } catch (t: Throwable) {
            android.util.Log.e("AstrBotLite", "notifyCrashDirect failed: ${android.util.Log.getStackTraceString(t)}")
        }
    }
}

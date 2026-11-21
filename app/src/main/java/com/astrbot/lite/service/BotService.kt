package com.astrbot.lite.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.astrbot.lite.R
import com.astrbot.lite.core.AccountManager
import com.astrbot.lite.core.BotEngine
import com.astrbot.lite.core.Logs
import java.util.concurrent.ConcurrentHashMap

/**
 * 前台服务：保证机器人在后台/息屏持续运行（类似 base.apk 的常驻通知）
 *
 * 1.7.245 按 1.7.222 还原账号级引擎池：
 *  - 每个账号只允许一个 BotEngine（engineFor + putIfAbsent）；
 *  - ACTION_START 只启动「当前账号」，不再额外建一个全局 unbound 引擎；
 *  - 原来 startAccount() 会「服务里建一个全局引擎 + 账号池里再建一个」，两者都用同一套凭证
 *    连了两条 WebSocket，同一条群消息被两个引擎各处理一次（用户实测：一条消息两个 LLM 请求）。
 */
class BotService : Service() {

    companion object {
        const val ACTION_START = "com.astrbot.lite.START"
        const val ACTION_STOP = "com.astrbot.lite.STOP"
        const val ACTION_ACCOUNT = "com.astrbot.lite.ACCOUNT"
        const val CHANNEL_ID = "astrbot_bot_channel"
        const val NOTIF_ID = 1001

        /** 账号级引擎池：accountId → 引擎（同账号绝不允许并存两个） */
        private val engines = ConcurrentHashMap<String, BotEngine>()
        /** 没有任何账号时的兜底引擎（用全局 Config 凭证） */
        @Volatile private var unbound: BotEngine? = null
        @Volatile private var appCtx: Context? = null

        /** 只读快照，供账号页/插件页判断各账号是否在线 */
        fun engines(): Map<String, BotEngine> = engines

        /** 取/建某账号的引擎（幂等；同账号并发调用也只会有一个实例） */
        fun engineFor(accountId: String, ctx: Context): BotEngine? {
            if (accountId.isBlank()) return null
            val acc = AccountManager.get(accountId) ?: return null
            if (acc.appId.isBlank() || acc.secret.isBlank()) return null
            engines[accountId]?.let { return it }
            // 同一 AppID 只允许一个连接：两个账号填了同一个 AppID 时共用已运行的引擎，
            // 否则两条 WebSocket 会各自收到同一事件 → 一条消息处理两次
            val sameApp = engines.entries.firstOrNull { (id, _) ->
                AccountManager.get(id)?.appId == acc.appId
            }
            if (sameApp != null) {
                Logs.warn("[BotService] 账号「${acc.name}」与「${AccountManager.get(sameApp.key)?.name ?: sameApp.key}」使用同一 AppID，复用同一连接")
                return sameApp.value
            }
            val eng = BotEngine(ctx.applicationContext, accountId)
            val existing = engines.putIfAbsent(accountId, eng)
            return existing ?: eng
        }

        /** 当前激活账号的引擎（未初始化上下文时返回 null，调用方会转而 start()） */
        fun engine(): BotEngine? {
            val id = AccountManager.activeId()
            if (id.isBlank()) return unbound
            val c = appCtx ?: return null
            return engineFor(id, c)
        }

        fun start(ctx: Context) {
            appCtx = ctx.applicationContext
            val i = Intent(ctx, BotService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun startAccount(ctx: Context, accountId: String, enable: Boolean = true) {
            appCtx = ctx.applicationContext
            val i = Intent(ctx, BotService::class.java)
                .setAction(ACTION_ACCOUNT)
                .putExtra("account_id", accountId)
                .putExtra("enable", enable)
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BotService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        appCtx = applicationContext
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACCOUNT -> {
                // startForegroundService 要求 5s 内必须 startForeground，停用分支也要先满足（否则 ANR）
                startForeground(NOTIF_ID, buildNotification("正在运行"))
                val accountId = intent.getStringExtra("account_id").orEmpty()
                if (intent.getBooleanExtra("enable", true)) {
                    val e = engineFor(accountId, this)
                    if (e != null) {
                        acquireWakeLock()
                        e.start()
                    } else {
                        Logs.warn("[BotService] 账号未配置或不完整，无法启动：$accountId")
                    }
                } else {
                    stopEngineFor(accountId)
                }
                return START_STICKY
            }
            ACTION_STOP -> {
                stopAll()
                return START_NOT_STICKY
            }
        }
        startActive()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        stopAll()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 只启动当前账号；没有账号时才用全局兜底引擎 */
    private fun startActive() {
        startForeground(NOTIF_ID, buildNotification("正在运行"))
        acquireWakeLock()
        val id = AccountManager.activeId()
        if (id.isBlank()) {
            val e = unbound ?: BotEngine(this, "").also { unbound = it }
            e.start()
            return
        }
        val e = engineFor(id, this)
        if (e != null) {
            e.start()
        } else {
            Logs.warn("[BotService] 当前账号未配置完整，未启动（account=$id）")
        }
    }

    private fun stopAll() {
        releaseWakeLock()
        for (e in engines.values) {
            try {
                e.stop()
            } catch (_: Throwable) {
            }
        }
        engines.clear()
        try {
            unbound?.stop()
        } catch (_: Throwable) {
        }
        unbound = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopEngineFor(accountId: String) {
        val e = engines.remove(accountId) ?: return
        try {
            e.stop()
        } catch (_: Throwable) {
        }
        if (engines.isEmpty()) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** 息屏时保持网络长连接不被系统挂起 */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AstrBotLite:bot").apply { acquire() }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "机器人服务",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("AstrBot Lite")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .build()
    }
}

package com.astrbot.lite.core

import android.content.Context
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 机器人引擎：连接 QQ 官方机器人，收到消息后先匹配插件，再调用 AI（结合当前人设卡）回复
 */
class BotEngine(private val ctx: Context, private val accountId: String = "") {

    fun interface StatusListener {
        fun onStatus(running: Boolean, detail: String)
    }

    fun interface MessageListener {
        fun onChatEntry(entry: ChatEntry)
    }

    /** 测试页回复回调：测试模式下把引擎产生的回复回传给测试界面（带上会话标识，好区分是哪个会话） */
    fun interface TestReplyListener {
        fun onTestReply(convKey: String, text: String)
    }

    fun interface TestThoughtListener {
        fun onTestThought(convKey: String, thought: String)
    }

    private var client: QqBotClient? = null

    @Volatile private var running = false

    @Volatile private var lastStatus = "未启动"

    /**
     * 消息处理线程池（单线程保证按序处理）。
     * 关键：WebSocket 的 onMessage 回调是串行的，若直接在回调里跑 AI 调用（最长 120s）
     * 或同步发送，会阻塞后续所有消息的接收；这里把处理丢进队列立即返回，回调不再卡。
     *
     * 注意：若某条消息的同步处理被一条“永不返回”的调用卡住（正常 AI 有 90s 绝对超时，
     * 只有异常情况才可能超时不返回），这个单线程 worker 会被永久占住，后续所有消息只排队、
     * 表现为“收到消息但不回复”。因此加了看门狗：长时间没有任何一条任务完成就重建 worker。
     */
    @Volatile private var msgExec: java.util.concurrent.ExecutorService = newMsgWorker()

    /** 最近一次 msgExec 任务完成的时间戳（看门狗判断 worker 是否卡死） */
    @Volatile private var lastMsgTaskDone = System.currentTimeMillis()

    /** msgExec 任务排队数（未消费任务的粗略计数，用于看门狗触发判断） */
    private val pendingMsg = java.util.concurrent.atomic.AtomicInteger(0)

    /** 超过该时长没有任何任务完成 → 认为 worker 卡死，重建（正常 AI 有 90s 绝对超时，100s 足够放过慢但正常的任务） */
    private val msgStuckMs = 100_000L

    /**
     * 群消息去重（1.7.242）：开了「群聊全量消息」权限后，同一条 @机器人 的消息会同时送达
     * GROUP_AT_MESSAGE_CREATE 与 GROUP_MESSAGE_CREATE 两个事件，且断线重连期间也可能重推同一个 id。
     * 原来两个事件都会被当成新消息 → 同一条消息跑两遍 AI（看起来像并发两个请求）。
     * 这里按 msgId 在 2 分钟内只处理一次。
     */
    private val seenMsgIds = ConcurrentHashMap<String, Long>()
    private val seenMsgIdsCleanAt = java.util.concurrent.atomic.AtomicLong(0)

    /** 兜底：两个事件的 id 不一致时，同群同人同内容 1.5s 内只处理一次 */
    private val seenGroupContent = ConcurrentHashMap<String, Long>()

    /** 兜底 2：同一群同一人 300ms 内到达的第二条，一律视为网关重复投递（真人不可能这么快连发两条） */
    private val lastGroupMemberAt = ConcurrentHashMap<String, Long>()

    private fun isDuplicateEvent(ev: QqMessageEvent): Boolean {
        val now = System.currentTimeMillis()
        // 低频清理，避免表无限增长
        if (now - seenMsgIdsCleanAt.get() > 60_000) {
            seenMsgIdsCleanAt.set(now)
            for (m in listOf(seenMsgIds, seenGroupContent, lastGroupMemberAt)) {
                val it = m.entries.iterator()
                while (it.hasNext()) {
                    if (now - it.next().value > 120_000) it.remove()
                }
            }
        }
        // ① 同一群同一人 300ms 内两条 → 网关把同一条消息投递了两次（不受 id/内容差异影响）
        if (ev.kind == "group") {
            val mk = ev.openid + "|" + ev.memberOpenid
            val prevM = lastGroupMemberAt.put(mk, now)
            if (prevM != null && now - prevM < 300) {
                Logs.info("[消息] 忽略重复事件（同群同人 ${now - prevM}ms 内二次投递）")
                return true
            }
        }
        // ② 同 msgId（两个事件类型共用同一条消息 id / 重连重推）
        val id = ev.msgId
        if (id.isNotBlank()) {
            val prev = seenMsgIds.put(id, now)
            if (prev != null && now - prev < 120_000) {
                Logs.info("[消息] 忽略重复事件（${ev.kind}，msgId=$id，距上次 ${now - prev}ms）")
                return true
            }
        }
        // ③ 同群同人同内容（两种事件的 id 或 @ 前缀清理结果不一致时兜底）
        if (ev.kind == "group" && ev.content.isNotBlank()) {
            val key = ev.openid + "|" + ev.memberOpenid + "|" + ev.content
            val prevC = seenGroupContent.put(key, now)
            if (prevC != null && now - prevC < 2000) {
                Logs.info("[消息] 忽略重复事件（group 同内容，距上次 ${now - prevC}ms）")
                return true
            }
        }
        return false
    }

    /**
     * 消息处理并发：对齐 AstrBot（每个事件一个 task），用 4 线程池。
     * 同一会话（同群/同私聊）仍加锁串行，保证回复顺序不乱；跨会话并行，
     * 不会再出现“一个群里的 AI 调用把其它消息全堵在队尾”的积压。
     */
    private fun newMsgWorker(): java.util.concurrent.ExecutorService = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "msg-worker").apply { isDaemon = true }
    }

    private val convLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun convLock(ev: QqMessageEvent): Any {
        if (convLocks.size > 256) convLocks.clear()
        return convLocks.computeIfAbsent(ev.kind + ":" + ev.openid) { Any() }
    }

    /** 看门狗：若现有 msgExec 单线程 worker 卡死（太久没完成任务且有消息在等），重建一个，避免永久不回复 */
    private fun ensureMsgWorkerHealthy() {
        if (!running) return
        if (pendingMsg.get() == 0) return // 没有排队中的消息，无需干预
        if (System.currentTimeMillis() - lastMsgTaskDone < msgStuckMs) return
        synchronized(this) {
            if (!running) return
            if (pendingMsg.get() == 0) return
            if (System.currentTimeMillis() - lastMsgTaskDone < msgStuckMs) return
            Logs.warn("检测到消息队列可能卡死（>${msgStuckMs / 1000}s 无任务完成），正在重建处理线程…")
            try {
                msgExec.shutdownNow()
            } catch (_: Exception) {
            }
            msgExec = newMsgWorker()
            lastMsgTaskDone = System.currentTimeMillis()
        }
    }

    /** 记忆增强线程池（单线程）：画像提取/上下文总结的 AI 调用移到这里，
     *  不阻塞消息队列（否则 AI 慢时后续消息全部排队，表现为“收到消息但不回复、实时消息不显示”）。
     *  注意：stop() 会 shutdownNow，start() 必须重建，否则重启后 execute 抛异常被吞、消息永不处理。 */
    /** 测试台远程图片下载专用（1.7.183：原来每次调用都 new 一个 OkHttpClient） */
    private val testImageHttp = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    @Volatile private var memoryExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "memory-worker").apply { isDaemon = true }
    }

    /** 最近活跃会话（kind, openid, sender）：主动消息发给最近聊过的人 */
    @Volatile private var lastConv: Triple<String, String, String>? = null

    /** 主动消息每日条数（凌晨重置） */
    @Volatile private var proactiveCount = 0
    private var proactiveDay = 0

    private val history = ConcurrentHashMap<String, ArrayDeque<ChatMsg>>()

    /** 最近一条消息事件（convKey -> 事件+时间戳）：后台异步任务（如生视频）完成后用它做被动回复，不占用主动消息额度 */
    private val lastEvent = ConcurrentHashMap<String, Pair<QqMessageEvent, Long>>()
    private val statusListeners = CopyOnWriteArrayList<StatusListener>()
    private val msgListeners = CopyOnWriteArrayList<MessageListener>()
    private val testReplyListeners = CopyOnWriteArrayList<TestReplyListener>()
    private val testThoughtListeners = CopyOnWriteArrayList<TestThoughtListener>()

    /**
     * 测试模式（测试页专用）：模拟 QQ 环境，跑完整链路但不碰真实数据 ——
     * 不写聊天记录、不写用户画像/分层摘要、不写知识库与内心思考、不参与主动消息；
     * 产生的回复通过 TestReplyListener 回传给测试界面显示。
     */
    @Volatile var testMode = false

    val isRunning: Boolean get() = running
    fun lastStatusDetail(): String = lastStatus

    // ---------------- 启动 / 停止 ----------------
    fun start() {
        if (running) return
        running = true
        // 关键修复：stop() 已对 msgExec/memoryExec 执行 shutdownNow()；重启必须重建，
        // 否则 execute 抛 RejectedExecutionException 被 onMessage 吞掉 → “收到消息([收到]日志正常)但不回复、无后续日志”
        msgExec = newMsgWorker()
        memoryExec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "memory-worker").apply { isDaemon = true }
        }
        unhealthySince = 0L // 每次启动给看门狗一个干净的计时窗口
        wirePluginDelivery() // 插件异步结果（生视频等）的投递通道
        startWatchdog() // 心跳看门狗（幂等：只在首次真正启动线程）
        notifyStatus("正在启动...")
        Thread {
            runLoop()
        }.apply {
            isDaemon = true
            name = "bot-engine"
            start()
        }
    }

    fun stop() {
        running = false
        client?.stop()
        client = null
        msgExec.shutdownNow()
        memoryExec.shutdownNow()
        notifyStatus("已停止")
    }

    fun restart() {
        stop()
        Thread.sleep(500)
        start()
    }

    // ---------------- 插件异步结果投递 ----------------
    /**
     * 给插件注入“后台任务完成后直接发出”的能力。
     * 生视频这类任务耗时长，不能在消息队列里同步等；生成完成后由这里解析会话地址（sessionKey = "kind:openid"）并发送。
     */
    private fun wirePluginDelivery() {
        PluginManager.videoDeliver = { sessionKey, bytes, ext ->
            val kind = sessionKey.substringBefore(':')
            val openid = sessionKey.substringAfter(':')
            if (kind.isBlank() || openid.isBlank() || !kind.startsWith("group") && !kind.startsWith("c2c")) {
                Logs.warn("[异步投递] 会话地址异常，丢弃：$sessionKey")
                false
            } else {
                emitChat(ChatEntry("bot", "🎬（视频）", System.currentTimeMillis(), sessionKey))
                client?.sendVideo(kind, openid, bytes, ext) ?: false
            }
        }
        PluginManager.videoUrlDeliver = { sessionKey, url ->
            val kind = sessionKey.substringBefore(':')
            val openid = sessionKey.substringAfter(':')
            if (kind.isBlank() || openid.isBlank() || !kind.startsWith("group") && !kind.startsWith("c2c")) {
                Logs.warn("[异步投递] 会话地址异常，丢弃：$sessionKey")
                false
            } else {
                emitChat(ChatEntry("bot", "🎬（视频链接）", System.currentTimeMillis(), sessionKey))
                client?.sendText(kind, openid, "🎬 视频生成好了，点击下方链接下载：\n$url") != null
            }
        }
        PluginManager.videoFailNotify = { sessionKey, text ->
            asyncNotice(sessionKey, text)
        }
        // 1.7.237：gal/游戏等插件在后台线程查完后回调这里（可带封面图），不再阻塞消息线程
        PluginManager.asyncReply = { sessionKey, text, imageUrl ->
            asyncReplyWith(sessionKey, text, imageUrl)
        }
    }

    /** 后台查询类插件的统一回复：可选先发一张封面图直链，再发正文 */
    private fun asyncReplyWith(sessionKey: String, text: String, imageUrl: String?) {
        PluginManager.testNotify?.invoke(sessionKey, text)
        val pair = lastEvent[sessionKey]
        if (pair == null) {
            Logs.warn("[异步投递] 没有可回复的消息事件，只记日志：$text")
            return
        }
        val (ev, ts) = pair
        val ageMin = (System.currentTimeMillis() - ts) / 60000
        if (ageMin > 50) {
            Logs.warn("[异步投递] 最近消息已过 $ageMin 分钟（msg_id 失效），不再发送：$text")
            return
        }
        if (!imageUrl.isNullOrBlank()) {
            if (testMode) {
                emitTestImageFromUrl(ev, imageUrl)
            } else {
                emitTestReply(ev, "📷（发图片）")
                val ok = client?.sendImageFromUrl(ev.kind, ev.openid, imageUrl)
                if (ok != true) Logs.warn("[异步投递] 图片发送失败：$imageUrl")
            }
        }
        emitChat(ChatEntry("bot", text, System.currentTimeMillis(), sessionKey))
        client?.sendReply(ev, text)
    }

    /** 异步任务失败时用最近一条消息做被动回复（被动回复不占主动消息额度；事件过旧则只记日志不再发） */
    private fun asyncNotice(sessionKey: String, text: String) {
        // 测试台观察者先行：控制台开着时同步看到异步插件结果
        // （旧版测试模式 lastEvent 为空 + client 为 null → 异步结果只进日志 = “无法触发插件”）
        PluginManager.testNotify?.invoke(sessionKey, text)
        val pair = lastEvent[sessionKey]
        if (pair == null) {
            Logs.warn("[异步投递] 没有可回复的消息事件，只记日志：$text")
            return
        }
        val (ev, ts) = pair
        val ageMin = (System.currentTimeMillis() - ts) / 60000
        if (ageMin > 50) {
            Logs.warn("[异步投递] 最近消息已过 $ageMin 分钟（msg_id 失效），不再发送：$text")
            return
        }
        emitChat(ChatEntry("bot", text, System.currentTimeMillis(), sessionKey))
        client?.sendReply(ev, text)
    }

    // ---------------- 心跳看门狗（整机级卡死自愈） ----------------
    /** 软恢复阈值：连接不健康持续该时长 → 重启机器人引擎（“重启机器人”） */
    private val softWatchdogMs = 5 * 60 * 1000L

    /** 硬恢复阈值：连接不健康持续该时长 → 重启整个应用（进程级），随后机器人自动拉起 */
    private val hardWatchdogMs = 15 * 60 * 1000L

    /** 软恢复冷却：两次软重启的最小间隔（避免刚重启又立刻再重启） */
    private val softCooldownMs = 3 * 60 * 1000L

    /** 硬恢复冷却：两次“重启应用”的最小间隔（防 crash-loop） */
    private val hardCooldownMs = 30 * 60 * 1000L

    @Volatile private var watchdogStarted = false

    /** 曾经连接成功过（READY/ACK 过）——只有“曾健康”才做升级重启，避免凭证错误时反复杀进程 */
    @Volatile private var wasEverHealthy = false

    /** 连接不健康开始的时刻（0=健康） */
    @Volatile private var unhealthySince = 0L

    @Volatile private var lastSoftRestart = 0L

    /** 启动心跳看门狗线程（幂等）。线程常驻，仅随 running 开关状态判断是否升级重启。 */
    private fun startWatchdog() {
        if (watchdogStarted) return
        synchronized(this) {
            if (watchdogStarted) return
            watchdogStarted = true
        }
        Thread {
            watchLoop@ while (true) {
                try {
                    Thread.sleep(60_000L) // 心跳看门狗检查间隔：每 1 分钟查一次是否卡死
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) {
                    // 引擎未运行：重置计时（下一次 start() 会重新计时）
                    unhealthySince = 0L
                    continue
                }
                val healthy = client?.heartbeatHealthy() ?: false
                if (healthy) {
                    wasEverHealthy = true
                    unhealthySince = 0L
                    continue
                }
                if (!wasEverHealthy) {
                    // 从未连接成功（凭证错误/网关不可达等）：不升级重启，避免把进程反复杀掉
                    continue
                }
                val now = System.currentTimeMillis()
                if (unhealthySince == 0L) unhealthySince = now
                val dur = now - unhealthySince
                if (dur >= hardWatchdogMs && canHardRestart()) {
                    Logs.warn("心跳确认机器人卡死 ${dur / 60000} 分钟（不健康），正在重启应用…")
                    triggerAppRelaunch()
                    break@watchLoop
                }
                if (dur >= softWatchdogMs && now - lastSoftRestart >= softCooldownMs) {
                    Logs.warn("心跳不健康已 ${dur / 60000} 分钟，正在重启机器人引擎…")
                    lastSoftRestart = now
                    unhealthySince = now // 给新引擎一个干净的计时窗口
                    try {
                        restart()
                    } catch (e: Exception) {
                        Logs.err("看门狗软重启失败：${e.message}")
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "bot-watchdog"
            start()
        }
    }

    private fun canHardRestart(): Boolean {
        val last = Config.lastHardRestart
        return last == 0L || System.currentTimeMillis() - last >= hardCooldownMs
    }

    /** 硬恢复：重启整个应用（进程级）。先用 AlarmManager 预排“重新拉起前台服务 + 打开主界面”，
     *  再杀掉当前进程；新进程启动后 BotService（STICKY）+ Config.botEnabled=true 会自动把机器人拉起来。 */
    private fun triggerAppRelaunch() {
        try {
            Config.botEnabled = true // 保证重启后机器人自动拉起
            Config.lastHardRestart = System.currentTimeMillis()
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val now = System.currentTimeMillis()

            val svc = android.content.Intent(ctx, com.astrbot.lite.service.BotService::class.java)
                .setAction(com.astrbot.lite.service.BotService.ACTION_START)
            val svcPi = android.app.PendingIntent.getService(
                ctx.applicationContext,
                2001,
                svc,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            am.cancel(svcPi)
            am.set(android.app.AlarmManager.RTC_WAKEUP, now + 8000L, svcPi)

            val act = android.content.Intent(ctx, com.astrbot.lite.ui.InitActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val actPi = android.app.PendingIntent.getActivity(
                ctx.applicationContext,
                2002,
                act,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            am.cancel(actPi)
            am.set(android.app.AlarmManager.RTC_WAKEUP, now + 9000L, actPi)
        } catch (e: Exception) {
            Logs.err("调度应用重启失败：${e.message}")
        }
        try {
            Thread.sleep(1000L) // 让 AlarmManager 完成调度再杀进程
        } catch (_: InterruptedException) {
        }
        Process.killProcess(Process.myPid())
        Runtime.getRuntime().exit(0)
    }

    // ---------------- 监听 ----------------
    fun addStatusListener(l: StatusListener) = statusListeners.add(l)
    fun removeStatusListener(l: StatusListener) = statusListeners.remove(l)
    fun addMessageListener(l: MessageListener) = msgListeners.add(l)
    fun removeMessageListener(l: MessageListener) = msgListeners.remove(l)
    fun addTestReplyListener(l: TestReplyListener) = testReplyListeners.add(l)
    fun removeTestReplyListener(l: TestReplyListener) = testReplyListeners.remove(l)
    fun addTestThoughtListener(l: TestThoughtListener) = testThoughtListeners.add(l)
    fun removeTestThoughtListener(l: TestThoughtListener) = testThoughtListeners.remove(l)

    /**
     * 测试入口：把模拟消息交给引擎处理（测试页用；不开线程池、不连 QQ，回复走 TestReplyListener）。
     * 与真实消息走同一条处理链路，所以插件、内置命令、AI 对话的行为都能测出来。
     */
    fun submitTestMessage(ev: QqMessageEvent) = processMessage(ev)

    /** 清空某个会话的上下文历史（测试页「清空上下文」用） */
    fun clearHistory(convKey: String) {
        synchronized(history) { history.remove(convKey) }
        Logs.info("[测试] 已清空会话上下文：$convKey")
    }

    // ---------------- 主循环 ----------------
    private fun runLoop() {
        Logs.info("启动机器人引擎...")
        BotState.init(ctx)
        if (!Config.isQqConfigured()) {
            Logs.err("未配置 QQ 机器人 AppID / Token，请先到设置中配置")
            notifyStatus("未配置 QQ 机器人")
            running = false
            return
        }
        if (!Config.isAiConfigured()) {
            Logs.warn("未配置 AI 服务，插件型固定回复仍可工作")
        }
        val persona = PersonaManager.active()
        Logs.info("当前人设卡：${persona.name}")

        // 1.7.245：引擎绑定账号 → 用该账号自己的 AppID/Secret/环境，多账号不再共用同一套凭证
        val acc = if (accountId.isNotBlank()) AccountManager.get(accountId) else null
        val appId = acc?.appId?.takeIf { it.isNotBlank() } ?: Config.qqAppId
        val secret = acc?.secret?.takeIf { it.isNotBlank() } ?: Config.qqSecret
        val apiBase = acc?.apiBase?.takeIf { it.isNotBlank() } ?: Config.qqApiBase
        val env = acc?.env?.takeIf { it.isNotBlank() } ?: Config.qqEnv
        client = QqBotClient(
            appId,
            secret,
            apiBase,
            env,
            object : QqBotClient.Listener {
                override fun onStatus(text: String) {
                    Logs.info(text)
                    notifyStatus(text)
                }

                override fun onInteraction(ev: QqInteraction) {
                    try {
                        // 先回应 QQ（否则客户端一直 loading）；再跑插件回调
                        client?.ackInteraction(ev.id)
                        val pyIds = PluginManager.installed()
                            .filter { it.enabled && (it.type == "python" || PythonRuntime.hasPlugin(it.id)) }
                            .map { it.id }
                        if (pyIds.isEmpty()) {
                            Logs.info("[按钮] 没有可处理的 Python 插件，已回应空结果")
                            return
                        }
                        val payload = JSONObject()
                            .put("action_id", ev.buttonId)
                            .put("data", ev.buttonData)
                            .put("session", "${ev.kind}:${ev.openid}")
                            .put("user_id", ev.memberOpenid)
                            .put("message_id", ev.messageId)
                        val r = PythonRuntime.handleButtonInteraction(pyIds, payload.toString())
                        if (r == null) {
                            Logs.info("[按钮] 插件没有匹配的回调处理器：${ev.buttonId}")
                            return
                        }
                        client?.sendInteractionReply(
                            ev.kind,
                            ev.openid,
                            ev.id,
                            r.reply ?: "",
                            r.buttons,
                        )
                    } catch (e: Exception) {
                        Logs.warn("[按钮] 处理失败: ${e.message}")
                    }
                }

                override fun onMessage(ev: QqMessageEvent) {
                    // 1.7.242：同一条群消息可能以「@消息」和「全量消息」两个事件送达（或重连重推）→ 去重
                    if (isDuplicateEvent(ev)) return
                    // 收到消息先“立即显示/记录”（在接收回调线程直接做，不进 msgExec 队列）：
                    // 单线程队列若正被上一条消息的 AI 调用占用，会把后续所有消息都堵在队尾，
                    // 表现就是“日志显示收到、但实时页面不更新、回复也迟迟不来”。即时显示保证实时页永远能动。
                    try {
                        emitReceived(ev)
                    } catch (_: Exception) {
                    }
                    // AI/插件回复逻辑仍丢进单线程队列，保证同一会话回复有序、不让 WS 回调线程被 AI 卡住
                    // 入队前先跑看门狗：若 worker 已被某条卡死的任务占住，重建它，让本消息能被处理、不会永远排不到
                    try {
                        ensureMsgWorkerHealthy()
                        pendingMsg.incrementAndGet()
                        msgExec.execute {
                            try {
                                // 同一会话串行（保证回复顺序），不同会话并行
                                synchronized(convLock(ev)) {
                                    val t0 = System.currentTimeMillis()
                                    try {
                                        processMessage(ev)
                                    } catch (t: Throwable) {
                                        // 关键：把回复链路里的异常打到日志页（否则静默失败=“收到但不回复”且无任何线索）
                                        Logs.err("处理消息异常（${ev.kind}/${ev.openid.takeLast(6)}）：${t.javaClass.simpleName}: ${t.message}")
                                    } finally {
                                        // 诊断：处理超 30s 说明 AI 服务慢，日志定位“收不到消息”的根因
                                        val cost = System.currentTimeMillis() - t0
                                        if (cost > 30000) {
                                            Logs.warn("消息处理耗时 ${cost / 1000}s（AI 服务响应慢）")
                                        }
                                        lastMsgTaskDone = System.currentTimeMillis()
                                    }
                                }
                            } finally {
                                pendingMsg.decrementAndGet()
                            }
                        }
                    } catch (e: Exception) {
                        // 修复（1.7.179）：原来完全静默——stop() 与回调竞态时消息在这里被丢掉且无任何痕迹，补日志
                        Logs.warn("消息入队失败（处理线程已停？）：${e.javaClass.simpleName}: ${e.message}")
                        pendingMsg.decrementAndGet()
                    }
                }
            },
        )
        client?.start()
        // 主动消息循环：隔一段时间主动找对方聊天（分享梦/脑补/日常），像真人有自己的生活
        Thread {
            while (running) {
                try {
                    val minM = pnum("proactiveMinMin", 30).coerceAtLeast(5)
                    val maxM = pnum("proactiveMaxMin", 90).coerceAtLeast(minM)
                    val span = (maxM - minM).coerceAtLeast(0)
                    val wait = (minM + if (span > 0) kotlin.random.Random.nextInt(span + 1) else 0) * 60 * 1000L
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    break
                }
                try {
                    sendProactive()
                } catch (_: Exception) {
                }
            }
        }.apply {
            isDaemon = true
            name = "proactive"
            start()
        }
    }

    // ---------------- 消息处理 ----------------
    /** 人格化回复引擎（1.0.55+ 新加，内置插件 personality，默认关闭）是否开启。
     *  含：去AI化/语言多样、心情(情绪)、做梦、脑补、群聊背景“现实世界→QQ群”。
     *  仅当插件存在且被启用才注入；否则回复走 1.0.54 的干净路径（人设+记忆+历史+当前消息）。 */
    private fun personalityOn(): Boolean = try {
        PluginManager.installed("personality")?.enabled == true
    } catch (_: Exception) {
        false
    }

    // ---------------- 人格化子功能配置读取 ----------------
    /** 读插件某配置项；缺失/异常返回默认值 */
    private fun pcfg(key: String, def: String = ""): String = try {
        PluginManager.configMap("personality")[key]?.trim() ?: def
    } catch (_: Exception) {
        def
    }

    /** 子功能开关（"1"/"0"，默认开） */
    private fun pflag(key: String, def: Boolean = true): Boolean {
        val v = pcfg(key, if (def) "1" else "0")
        return v == "1" || v == "true" || v == "开" || v == "开_"
    }

    /** 数值配置（解析失败回退默认） */
    private fun pnum(key: String, def: Int): Int {
        val v = pcfg(key, def.toString()).toIntOrNull()
        return v ?: def
    }

    /** 情绪子功能是否开（人格化总开关 + 情绪子开关） */
    private fun moodOn(): Boolean = personalityOn() && pflag("sub_enableMood", true)

    /** 做梦子功能是否开 */
    private fun dreamOn(): Boolean = personalityOn() && pflag("sub_enableDream", true)

    /** 脑补子功能是否开 */
    private fun imaginationOn(): Boolean = personalityOn() && pflag("sub_enableImagination", true)

    /** 去AI味子功能是否开 */
    private fun deaiOn(): Boolean = personalityOn() && pflag("sub_enableDeai", true)

    /** 现实世界人设子功能是否开 */
    private fun realityOn(): Boolean = personalityOn() && pflag("sub_enableReality", true)

    /** 群聊背景子功能是否开 */
    private fun groupCtxOn(): Boolean = personalityOn() && pflag("sub_enableGroupCtx", true)

    /** 主动消息子功能是否开（人格化总开关 + 主动子开关；proactiveOn() 仅判总开关） */
    private fun proactiveSubOn(): Boolean = personalityOn() && pflag("sub_enableProactive", true)

    /** 做梦间隔（小时） */
    private fun dreamMinHours(): Int = pnum("dreamMinHours", 6)

    /** 文本是否真的聊到「睡眠梦」：修复（1.7.179）——原 contains("梦") 会被“梦想”等词误触发，
     *  误标后当天不再聊梦；收紧为睡眠梦相关词组，宁可漏标不可误标 */
    private fun talkedAboutDream(text: String): Boolean = Regex("做梦|梦到|梦见|梦里|梦醒|噩梦|美梦|梦境|梦话|梦游").containsMatchIn(text)

    /** 情绪敏感度 1-10（越高越容易随对方情绪变化；默认 5） */
    private fun moodSensitivity(): Int = pnum("sub_moodReactivity", 5).coerceIn(1, 10)

    /** 发送者显示名：优先真实昵称；没有（QQ 未给 username）时用通用称呼，避免日志/提示显示成空白 */
    private fun senderDisplay(s: String?, kind: String): String {
        if (!s.isNullOrBlank()) return s
        return if (kind == "group") "群友" else "单聊用户"
    }

    /** 收到消息即时显示/记录（在接收回调线程执行，不进 msgExec 队列，避免被 AI 排队拖住实时页） */
    private fun emitReceived(ev: QqMessageEvent) {
        val disp = senderDisplay(ev.sender, ev.kind)
        val label = if (ev.kind == "group") "[群] $disp" else "[私聊] $disp"
        emitChat(
            ChatEntry(
                "user",
                ev.content,
                System.currentTimeMillis(),
                "${ev.kind}:${ev.openid}",
            ),
        )
        Logs.info("[收到] $label: ${ev.content.take(80)}")
        // 记录最近活跃会话：主动消息发给最近聊过的人（测试模式不记，免得主动消息发给测试用的假会话）
        if (!testMode) lastConv = Triple(ev.kind, ev.openid, disp)
    }

    private fun processMessage(rawEv: QqMessageEvent) {
        // v1.7.23 图生图支持：本条消息若引用了图片消息（replyMsgId 非空），
        // 拉取被引用消息的 attachments 提取图片 URL，并入 imageUrls（去重）。
        // 这样图生图/文生图命令即使没在当条消息带图、而是"回复"一张图，也能识别到参照图。
        var ev = rawEv
        val convKey = "${ev.kind}:${ev.openid}"
        // 记住最近一条消息：后台异步任务（生视频等）完成后用它做被动回复
        if (!testMode) {
            val now = System.currentTimeMillis()
            lastEvent.entries.removeAll { it.value.second < now - 3600_000 }
            history.entries.removeAll { (_, hist) ->
                synchronized(hist) { hist.isEmpty() }
            }
            lastEvent[convKey] = ev to now
        }

        if (rawEv.replyMsgId.isNotBlank() && !testMode) {
            val refUrls = try {
                client?.fetchReferencedImageUrls(rawEv.kind, rawEv.openid, rawEv.replyMsgId) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            if (refUrls.isNotEmpty() && !rawEv.imageUrls.containsAll(refUrls)) {
                ev = rawEv.copy(imageUrls = (rawEv.imageUrls + refUrls).distinct())
                Logs.info("[引用图] 回复引用的图片 ${refUrls.size} 张已并入 imageUrls")
            }
        }

        // v1.5.0 白名单 / 黑名单检查（测试模式跳过——测试用户 ID 不在名单中会被误拦截）
        val uid = resolveUid(ev)
        if (!testMode) {
            if (!Config.isWhitelisted(uid)) {
                Logs.info("[名单] $uid 不在白名单中，忽略消息")
                return
            }
            if (Config.isBlacklisted(uid)) {
                Logs.info("[名单] $uid 在黑名单中，忽略消息")
                return
            }
        }

        // v1.5.0 内置命令处理（在名单检查之后、插件匹配之前）
        if (handleBuiltinCommand(ev, uid)) return

        // v1.5.0 好感度：按话语信号加减（本地判定不调 AI；放 memoryExec 与 doMemory 串行，避免读改写并发丢更新）
        if (!testMode) memoryExec.execute { ThoughtEngine.adjustFavorabilityFromText(uid, ev.content) }

        // v1.5.0 群聊仅@回复检查（1.7.119 移除 UI 开关，逻辑保留但默认关闭——群消息全量接收）

        // 记忆增强（画像提取/上下文总结）异步执行：其 AI 调用不阻塞消息队列（测试模式不写记忆）
        if (!testMode) memoryExec.execute { doMemory(ev, convKey) }

        // 人格化(情绪反应，1.0.55+，插件默认关)：仅在情绪子功能开启时对消息做情绪反应
        if (moodOn()) {
            try {
                val before = BotState.moodDesc()
                BotState.react(ev.content, moodSensitivity())
                if (before != BotState.moodDesc()) {
                    Logs.info("[情绪] 由「$before」变为「${BotState.moodDesc()}」（触发词：${ev.content.take(30)}）")
                }
            } catch (_: Exception) {
            }
        }

        // 纯@无文字：给 AI 一个占位提示，避免空消息导致 AI 困惑
        val userMsg = ev.content.ifBlank { "（对方@了你，没有说话，在等你开口）" }

        // 真正生成回复的这段包上保护：万一里面某个环节异常，也要记日志并尽量回一句，
        // 不能像现在这样“收到却不回复、日志里还什么都没有”（那样最难排查）。
        try {
            buildAndReply(ev, convKey, userMsg, uid)
        } catch (t: Throwable) {
            Logs.err("生成回复异常：${t.javaClass.simpleName}: ${t.message}")
            val fallback = "抱歉，我刚卡了一下，没反应过来。请再说一次好吗？（已记录错误：${t.javaClass.simpleName}）"
            try {
                deliver(ev, fallback)
            } catch (_: Exception) {
            }
        }
    }

    /** 生成并发送回复：插件匹配 → 固定回复 或 AI 对话 */
    private fun buildAndReply(ev: QqMessageEvent, convKey: String, userMsg: String, uid: String) {
        val hist = history.getOrPut(convKey) { ArrayDeque() }
        val persona = PersonaManager.active()
        // 注入用画像：同步读已保存的最新画像（文件读很快），异步更新不影响本次注入
        val profile = if (ContextManager.enabled() && ContextManager.profileEnabled()) {
            try {
                MemoryStore.load(resolveUid(ev))
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        // 1. 被动触发插件（命令）
        val hit = PluginManager.handle(ev.content, convKey, ev)
        if (hit.handled) {
            // 交互按钮（AstrBot 4.x ActionRow/Button）：走 markdown+keyboard，失败自动降级纯文本
            if (hit.buttons != null) {
                val txt = hit.reply ?: ""
                emitChat(ChatEntry("bot", txt.ifBlank { "（按钮消息）" }, System.currentTimeMillis(), convKey))
                if (testMode) {
                    emitTestReply(ev, txt)
                    return
                }
                client?.sendButtonsReply(ev, txt, hit.buttons)
                return
            }
            // 本地渲染（render 型插件）：举牌/文字图已在本地画好，直接把 PNG 作为图片消息发出
            if (hit.rendered != null && hit.rendered.isNotEmpty()) {
                emitChat(ChatEntry("bot", "📷（举牌图）", System.currentTimeMillis(), convKey))
                if (testMode) {
                    emitTestImageBytes(ev, hit.rendered, "png")
                    return
                }
                emitTestReply(ev, "📷（举牌图）")
                val ok = client?.sendImage(ev.kind, ev.openid, hit.rendered)
                if (ok != true) {
                    if (client?.heartbeatHealthy() == true) {
                        client?.sendReply(ev, "举牌图发送失败（连接正常），请再试一次。")
                    } else {
                        Logs.warn("举牌图发送失败且连接不健康，交由心跳看门狗恢复")
                    }
                }
                return
            }
            // 生成的/本地图片字节（localimage 型插件）：直接作为图片消息发出
            if (hit.media != null && hit.media.isNotEmpty()) {
                emitChat(ChatEntry("bot", "📷（图片）", System.currentTimeMillis(), convKey))
                if (testMode) {
                    emitTestImageBytes(ev, hit.media, hit.mediaExt)
                    return
                }
                emitTestReply(ev, "📷（图片）")
                val ok = client?.sendImage(ev.kind, ev.openid, hit.media, hit.mediaExt)
                if (ok != true) {
                    if (client?.heartbeatHealthy() == true) {
                        client?.sendReply(ev, "图片发送失败，请稍后再试一次。")
                    } else {
                        Logs.warn("图片发送失败且连接不健康，交由心跳看门狗恢复")
                    }
                }
                return
            }
            // 图片直链（image 型插件）：先“拉图”下载，再作为“图片消息”发出（发图）。
            // 图发完若同一条回复还带正文（如 Gal 详情：封面图+资源列表文字），必须继续发正文——
            // 旧版图后直接 return 把正文吞了（用户实测：详情只剩封面图、看不到资源列表）。
            if (!hit.image.isNullOrBlank()) {
                emitChat(ChatEntry("bot", "📷（发图片）", System.currentTimeMillis(), convKey))
                if (testMode) {
                    emitTestImageFromUrl(ev, hit.image)
                    return
                }
                emitTestReply(ev, "📷（发图片）")
                val ok = client?.sendImageFromUrl(ev.kind, ev.openid, hit.image)
                if (ok != true) {
                    // 连接健康 → 图片链接问题；有正文时正文马上会发，只在无正文时提示图片失败
                    // 连接不健康 → 多半是机器人卡死：不刷屏提示，交给心跳看门狗确认并重启
                    if (client?.heartbeatHealthy() == true) {
                        if (hit.reply == null) {
                            client?.sendReply(ev, "图片发送失败，直链：${hit.image}")
                        } else {
                            Logs.warn("图片发送失败（正文照常发送）：${hit.image}")
                        }
                    } else {
                        Logs.warn("图片发送失败且连接不健康，交由心跳看门狗恢复（不刷屏提示）：${hit.image}")
                    }
                }
                if (hit.reply != null) {
                    deliver(ev, hit.reply)
                }
                return
            }
            if (hit.reply != null) {
                deliver(ev, hit.reply)
                return
            }
            val msgs = buildMessages(persona, hist, ev.content, ev.sender, ev.kind, convKey, profile, hit.extraPrompt, null)
            val ans = chatLoop(hist, ev.content, ev.sender, msgs)
            deliver(ev, ans)
            return
        }

        // 2. 普通 AI 对话（含总是触发 hook + LLM 技能）：所有回复都走 AI，带人格化（心情/思考/生活）
        if (!Config.isAiConfigured()) {
            val tip = "AI 服务尚未配置，请到设置中填写 API 地址/密钥/模型。"
            deliver(ev, tip)
            return
        }

        // 知识库只入库、不影响本次回复 → 扔异步（AI 调用别占消息队列）
        // 1.7.224：默认关闭自动入库（Config.knowledgeAutoIngest），避免群消息污染知识库
        if (!testMode && Config.knowledgeAutoIngest) memoryExec.execute { doKnowledge(ev) }
        // 内心思考要注入本次回复，只能在这里同步生成（测试模式不新建、不落库）
        if (!testMode) thinkNow(ev, uid)
        val thought = ThoughtEngine.getThought(ev)

        val msgs = buildMessages(persona, hist, userMsg, ev.sender, ev.kind, convKey, profile, null, thought)
        val ans = chatLoop(hist, userMsg, ev.sender, msgs)
        deliver(ev, ans)
    }

    /** 统一发送回复：先记录文字（实时页/历史）→ TTS 插件开启则转语音发送，失败自动回退文字 */
    private fun deliver(ev: QqMessageEvent, text: String) {
        var cleaned = text
        val rules = PluginManager.cleanRules()
        if (rules.isNotEmpty()) {
            for ((regex, replacement) in rules) {
                cleaned = regex.replace(cleaned, replacement)
            }
        }
        // 1.7.252：空回复不能直接发（QQ 会 400 且界面上就是“收到消息但不回”），换成提示文案
        if (cleaned.isBlank()) {
            Logs.warn("[发送] 回复内容为空（AI 返回空或剥离思考后为空），改为提示文案")
            cleaned = "（这次没想好怎么回…你再说一遍？）"
        }
        emitChat(
            ChatEntry(
                "bot",
                cleaned,
                System.currentTimeMillis(),
                "${ev.kind}:${ev.openid}",
            ),
        )
        emitTestReply(ev, cleaned)
        if (personalityOn() && talkedAboutDream(cleaned) && !BotState.dreamMentionedToday()) {
            BotState.markDreamMentionedToday()
        }
        if (ttsOn()) {
            try {
                val audio = TTSEngine.synth(cleaned)
                if (audio != null) {
                    sendVoiceOrTest(ev, audio)
                    Logs.info("[TTS] 已转语音发送（${ev.kind}${if (testMode) "·测试台" else ""}）")
                    return
                }
                Logs.warn("[TTS] 合成失败，回退文字发送")
            } catch (e: Exception) {
                Logs.warn("[TTS] 异常，回退文字发送：${e.message}")
            }
        }
        val c = client
        if (c == null) {
            Logs.warn("[发送] 机器人未连接（client 为空），本次回复没有发出：${cleaned.take(40)}")
            return
        }
        Logs.info("[发送] 提交回复 ${cleaned.length} 字：${cleaned.take(30)}…")
        c.sendReply(ev, cleaned)
    }

    /** 发 TTS 语音：真实 QQ 直发；测试台存临时文件、以 🔊 voice:// 标记回传（控制台渲染为可播放气泡） */
    private fun sendVoiceOrTest(ev: QqMessageEvent, audio: ByteArray) {
        if (testMode) {
            val f = TempMedia.save(audio, "mp3")
            if (f != null) {
                emitTestReply(ev, "🔊 voice://${f.absolutePath}")
            } else {
                emitTestReply(ev, "🔊（语音已生成，但测试台暂存失败）")
            }
            return
        }
        client?.sendVoice(ev.kind, ev.openid, audio)
    }

    /** TTS 语音回复插件是否开启 */
    private fun ttsOn(): Boolean = try {
        PluginManager.installed("tts")?.enabled == true
    } catch (_: Exception) {
        false
    }

    /** 记忆增强（异步）：画像提取与上下文总结的 AI 调用在独立线程执行，且绝不持 hist 锁跨 AI，不阻塞消息队列 */
    private fun doMemory(ev: QqMessageEvent, convKey: String) {
        try {
            if (!ContextManager.enabled()) return
            val hist = history.getOrPut(convKey) { ArrayDeque() }
            val uid = resolveUid(ev)
            if (ContextManager.profileEnabled()) {
                val profile = MemoryStore.load(uid)
                if (ev.sender.isNotBlank()) profile.name = ev.sender
                profile.msgCount++
                profile.lastSeen = System.currentTimeMillis()
                // 内部短锁取快照 → 无锁 AI → 返回；不会再持锁阻塞回复线程
                ContextManager.maybeExtractProfile(profile, hist)
                MemoryStore.save(profile)
            }
            // 累计消息数 +1，按数量触发分层总结（内部同样不跨 AI 持锁）
            ContextManager.recordMessage(convKey)
            ContextManager.condense(convKey, hist)
        } catch (_: Exception) {
        }
    }

    // ---------------- 主动消息 + 做梦（更像人类：有生活、会主动、会做梦） ----------------
    /** 主动给最近聊过的人发一条消息（1.0.58 新加；归入人格化插件，默认关）。
     *  实际是否发：主动消息子开关 + 触发概率（proactiveChance）共同决定。 */
    private fun sendProactive() {
        if (!proactiveSubOn()) return // 人格化总开关 + 主动子开关都开才走
        // 触发概率：100=每次醒来都发；设低=随机跳过（模拟"有时想发有时不想"）
        val chance = pnum("proactiveChance", 100).coerceIn(0, 100)
        if (chance < 100 && kotlin.random.Random.nextInt(100) >= chance) return
        sendProactiveImpl()
    }
    private fun sendProactiveImpl() {
        val conv = lastConv ?: return
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour >= 23 || hour < 6) return // 深夜：睡觉，不发
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        if (today != proactiveDay) {
            proactiveDay = today
            proactiveCount = 0
        }
        val dailyMax = pnum("proactiveDailyMax", 3).coerceAtLeast(0)
        if (proactiveCount >= dailyMax) return
        val (kind, openid, sender) = conv
        // 修复（1.7.179）：原 noDreamToday 把「做梦功能关闭」和「今天已提过梦」混在一起，
        // 关掉做梦时也会注入“今天已经聊过梦了”（假话，误导模型）。拆开：关闭→不注入；已提过→提示别提。
        val dreamMentionedToday = BotState.dreamMentionedToday()
        // 先“做个梦”（按配置的梦间隔以上没做才做新的；做梦子开关关则跳过）
        if (dreamOn() && !dreamMentionedToday && BotState.dreamNeeded(dreamMinHours())) {
            val newDream = makeDream() ?: "记不清了，好像是个很长的梦"
            BotState.setDream(newDream)
            Logs.info("[做梦] 新梦：${newDream.take(50)}")
        }
        val dreamPart = when {
            !dreamOn() -> ""
            dreamMentionedToday -> "（今天已经聊过梦了，别再提）"
            else -> "、最近做的梦（${BotState.dreamDesc()}）"
        }
        val msgs = mutableListOf<ChatMsg>()
        msgs.add(ChatMsg("system", personaSystem()))
        msgs.add(ChatMsg("system", "你现在想主动给${sender ?: "对方"}发一条消息（不是等对方@你才回）。结合你的心情、当前生活状态$dreamPart，发一条自然简短、有人味的话：可以是分享刚做的梦、脑补的画面、突然想起对方、日常碎碎念。不要解释“我在发消息”，也不要太肉麻太长，50字以内。"))
        msgs.add(ChatMsg("user", "给对方发一条主动消息吧"))
        val reply = try {
            AiPool.chatFor("proactive", msgs)
        } catch (e: Exception) {
            Logs.warn("[主动消息] 生成失败：${e.message}")
            return
        }
        if (reply.isBlank()) return
        Logs.info("[主动消息] → ${sender ?: "对方"}（${if (kind == "group") "群" else "私聊"}）：${reply.take(60)}")
        client?.sendText(kind, openid, reply)
        // 发的是梦 → 今天标记已提过，后续回复/主动消息不再注入梦境（用收紧判定，防“梦想”等误标）
        if (dreamOn() && !dreamMentionedToday && talkedAboutDream(reply)) BotState.markDreamMentionedToday()
        proactiveCount++
    }

    /** 做梦：AI 生成一个简短、有点荒诞又生活化的梦 */
    private fun makeDream(): String? = try {
        AiPool.chatFor(
            "dream",
            listOf(
                ChatMsg("system", "你是梦境生成器。生成一个简短（一两句话）的梦，要有点荒诞又有生活感，比如“梦见我们在一个会飞的公交车上吃西瓜”。不要解释，只输出梦的内容。"),
                ChatMsg("user", "帮我编一个梦"),
            ),
        )
    } catch (e: Exception) {
        Logs.warn("生成梦境失败：${e.message}")
        null
    }

    /** 用户唯一 ID：群聊用 memberOpenid（空则用 g:{群openid}:{sender} 复合键防重名），单聊用 c2c openid */
    private fun resolveUid(ev: QqMessageEvent): String = when (ev.kind) {
        "group" -> ev.memberOpenid.ifBlank {
            // 修复（1.7.183）：原回落 "${ev.openid}:${ev.sender}"，当 memberOpenid 与 sender 双空时
            // 得到 "g:{群openid}:" —— 该群所有无名成员共用同一 uid（画像/好感度串号）。
            // sender 非空时保留原复合键；双空时用 msgId（全局唯一）兜底，保证每人一个稳定键。
            val suffix = if (ev.sender.isBlank()) ev.msgId.ifBlank { "anon" } else ev.sender
            "g:${ev.openid}:$suffix"
        }
        else -> ev.openid
    }

    /**
     * on_llm_request 钩子（1.7.88）：已装 Python 插件可改写要发给模型的消息。
     * 返回改写后的列表；无 Python 插件/失败/超时返回 null（调用方保持原消息）。
     */
    private fun hookLlmRequest(msgs: List<ChatMsg>): List<ChatMsg>? {
        return try {
            val ids = JSONArray()
            PluginManager.installed()
                .filter { it.enabled && (it.type == "python" || PythonRuntime.hasPlugin(it.id)) }
                .forEach { ids.put(it.id) }
            if (ids.length() == 0) return null
            val arr = JSONArray()
            msgs.forEach { arr.put(it.toJson()) }
            val payload = JSONObject().put("ids", ids).put("msgs", arr).toString()
            val out = PythonRuntime.runLlmHooks("on_llm_request", payload) ?: return null
            val o = JSONObject(out)
            if (o.optString("ok") != "true") return null
            val newMsgs = o.optJSONObject("payload")?.optJSONArray("msgs") ?: return null
            val list = mutableListOf<ChatMsg>()
            for (i in 0 until newMsgs.length()) {
                val m = newMsgs.optJSONObject(i) ?: continue
                list.add(ChatMsg(m.optString("role", "user"), m.optString("content", "")))
            }
            if (list.isEmpty()) null else list
        } catch (e: Exception) {
            Logs.debug("[hook] on_llm_request 桥接失败（保持原消息）: ${e.message}")
            null
        }
    }

    /** on_llm_response / on_agent_done / on_decorating_result：插件可逐级改写模型回复；失败/未改写返回原文 */
    private fun hookLlmResponse(reply: String, session: String): String {
        var text = reply
        // 上游 4.x：on_agent_done 在 on_llm_response 之后、on_decorating_result 在发送前；
        // 三者都可能改写最终文本，这里按顺序串起来（未注册的 kind 在垫片里 ran=0，无副作用）
        for (kind in listOf("on_llm_response", "on_agent_done", "on_decorating_result")) {
            text = runResponseHook(kind, text, session)
        }
        return text
    }

    private fun runResponseHook(kind: String, reply: String, session: String): String {
        return try {
            val ids = JSONArray()
            PluginManager.installed()
                .filter { it.enabled && (it.type == "python" || PythonRuntime.hasPlugin(it.id)) }
                .forEach { ids.put(it.id) }
            if (ids.length() == 0) return reply
            val payload = JSONObject()
                .put("ids", ids)
                .put("text", reply)
                .put("session", session ?: "")
                .toString()
            val out = PythonRuntime.runLlmHooks(kind, payload) ?: return reply
            val o = JSONObject(out)
            if (o.optString("ok") != "true") return reply
            val t = o.optJSONObject("payload")?.optString("text")
            if (t.isNullOrBlank()) reply else t
        } catch (e: Exception) {
            Logs.debug("[hook] $kind 桥接失败（保持原回复）: ${e.message}")
            reply
        }
    }

    /**
     * 调用 AI；若模型请求了 LLM 技能（调用技能：/xxx 参数），
     * 则把技能说明交给模型再生成一次，得到最终回答。
     * 1.7.88：调用前同步 LLM 桥配置（插件 context.llm_generate 用）+ on_llm_request 改写消息；
     * 得到回复后经 on_llm_response 改写。
     */
    private fun chatLoop(hist: ArrayDeque<ChatMsg>, userMsg: String, sender: String?, msgs: List<ChatMsg>): String {
        PythonRuntime.syncAiBridge()
        val llmReq = hookLlmRequest(msgs)
        val effMsgs = llmReq ?: msgs
        var reply = try {
            AiPool.chatFor("chat", effMsgs)
        } catch (e: Exception) {
            Logs.warn("AI 调用失败 -> ${e.message}")
            val msg = e.message.orEmpty()
            return if (msg.contains("429")) {
                "AI 服务请求太频繁或额度已用完（HTTP 429）。请稍等片刻再试，或检查 API 套餐/余额。"
            } else {
                "抱歉，调用 AI 失败：$msg"
            }
        }
        reply = hookLlmResponse(reply, sender ?: "")
        val inv = parseSkillInvocation(reply)
        if (inv != null) {
            val (name, arg) = inv
            val skill = PluginManager.findSkill(name)
            if (skill != null) {
                Logs.info("LLM 调用技能 ${skill.name}（参数：${arg.take(60)}）")
                Logs.debug("[技能] 改写前(原始回复): ${reply.take(300)}")
                val skillInstr = "【使用技能 ${skill.name}】\n请求内容：${arg.ifBlank { "（无参数，按技能说明执行）" }}\n技能执行说明：\n${skill.prompt}\n请按技能说明完成该技能，并直接输出最终回答。"
                Logs.debug("[技能] 注入指令: ${skillInstr.take(500)}")
                val msgs2 = effMsgs.toMutableList()
                msgs2.add(ChatMsg("assistant", reply))
                msgs2.add(ChatMsg("user", skillInstr))
                val rewritten = try {
                    AiPool.submit("chat", msgs2, 30_000) ?: run {
                        Logs.warn("[技能] ${skill.name} 调用超时（30s），回复保持原文")
                        reply
                    }
                } catch (e: Exception) {
                    Logs.warn("[技能] ${skill.name} 调用失败：${e.message}")
                    reply
                }
                Logs.debug("[技能] 改写后(最终): ${rewritten.take(300)}")
                if (rewritten != reply) {
                    Logs.info("[技能] ${skill.name} 改写生效（回复变化 ${reply.length}→${rewritten.length} 字）")
                }
                reply = rewritten
            } else {
                Logs.warn("模型请求了不存在的技能 $name，已忽略（回复保持原文）")
                Logs.debug("[技能] 未命中技能，原始回复保留: ${reply.take(200)}")
            }
        }
        // 对外输出时应用总是触发(hook)插件的正则清理（如 clean_response 删除括号内容）。
        // 注意：历史记录保存“原始”回复，避免 AI 上下文里全是清理后的干净文本，
        // 导致关闭过滤插件后依然不输出之前被过滤的内容（如括号、表情）。
        var output = PluginManager.cleanReply(reply)
        if (output != reply) {
            Logs.debug("[Hook] 正则清理改写（${reply.length}→${output.length} 字）")
        }
        // 若清理后为空（或模型本就返回空内容），绝不能发空消息（QQ 会当作没回/报错），
        // 改用一句兜底，避免“收到却不回复”。
        if (output.isBlank()) {
            Logs.warn("AI 返回空内容，已用兜底文案回复")
            output = "嗯…我这边刚没组织好语言，再说一次好不好？"
        }
        // 1.7.259：AI 文本完整后再去空行，所有换行/连续空白合并成一个空格。
        // 只作用于 AI 最终回复；内置插件与 AstrBot 插件的固定回复走 deliver，不经过这里。
        val merged = mergeWhitespace(output)
        if (merged != output) {
            Logs.debug("[发送] 去空行：${output.length}→${merged.length} 字")
        }
        output = merged
        append(hist, userMsg, sender, reply)
        return output
    }

    /** 把换行和连续空白压成一个空格（AI 完整回复用；插件固定回复不要调用） */
    private fun mergeWhitespace(text: String): String = text.replace(Regex("[\\s\u3000]+"), " ").trim()

    /** 识别模型回复末尾的“调用技能：/技能名 参数” */
    private fun parseSkillInvocation(reply: String): Pair<String, String>? {
        val re = Regex("""调用技能\s*[:：]\s*(/\S+)(?:\s+(.*))?""")
        val m = re.findAll(reply).lastOrNull() ?: return null
        return m.groupValues[1] to m.groupValues.getOrElse(2) { "" }.trim()
    }

    /** 人格化 system 指令：核心是"像真人发微信"——短、口语、带当下状态。
     *  刻意不写成规则清单：长串编号规则本身就会把模型带向又长又"AI"的输出（越强调去AI味，越端着）。
     *  各子段按子功能开关条件拼接；支持 personality 插件「配置」里填自定义指令完全覆盖。 */
    private fun personaSystem(): String {
        BotState.decay()
        // 用户自定义人格化指令：填了则完全覆盖内置（细调人格/口吻用）
        val custom = PluginManager.configMap("personality")["customPrompt"]?.trim()
        if (!custom.isNullOrBlank()) return custom
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeDesc = when (hour) {
            in 5..8 -> "清晨"
            in 9..11 -> "上午"
            12 -> "中午"
            in 13..17 -> "下午"
            in 18..19 -> "傍晚"
            in 20..22 -> "晚上"
            else -> "深夜"
        }
        val sb = StringBuilder()
        // 身份段
        sb.append(
            if (realityOn()) {
                "你是个真人，在QQ上跟朋友闲聊。你有自己的日子要过，会困会饿、要上班、会追剧，不是AI，也从不提AI/助手/模型。\n"
            } else {
                "你是个有自己生活的QQ群友，在跟朋友闲聊。\n"
            },
        )
        // 当下状态（人格化最有价值的部分，保留）
        sb.append("现在是").append(timeDesc).append("，你").append(BotState.lifeState)
        if (moodOn()) sb.append("，心情").append(BotState.moodDesc()).append("，心情会影响你怎么说话")
        sb.append("。\n")
        if (dreamOn()) {
            sb.append(
                if (BotState.dreamMentionedToday()) {
                    "（梦今天聊过了，别再提）\n"
                } else {
                    "（你最近梦到：${BotState.dreamDesc()}；除非正好聊到，不然不用提，提也就一次）\n"
                },
            )
        }
        if (imaginationOn()) sb.append("对方说话时你常顺带想到些具体画面，想说就随口带一句，别硬凑。\n")
        // 说话方式：短 + 口语 + 不端着（合并原「聊天风格/情绪控制/用词语境/去AI味」四段，大幅精简）
        sb.append("说话像发微信：一般就一两句，不写小作文，别铺陈别解释。用日常词（吃饭、上班、睡觉、逛街），朋友怎么聊你就怎么聊。")
        if (deaiOn()) {
            sb.append("别用「不是…而是…」「首先其次最后」这种腔调，别用「值得注意的是」「总的来说」这类套话，别在结尾总结升华，同一件事别反复念叨。")
        }
        sb.append("一条消息里情绪就一个调子，别又傲娇又撒娇又嫌弃。可以有口癖、跑题、说到一半，不知道就说不知道。")
        return sb.toString()
    }

    private fun buildMessages(
        persona: Persona,
        hist: ArrayDeque<ChatMsg>,
        userMsg: String,
        sender: String?,
        kind: String,
        convKey: String,
        profile: UserProfile?,
        extraSystem: String?,
        thought: String? = null,
    ): List<ChatMsg> {
        val msgs = mutableListOf<ChatMsg>()
        msgs.add(ChatMsg("system", persona.prompt))
        // 人格化回复引擎（1.0.55+ 功能，插件 personality 默认关）：开启才注入群聊背景 + 人格化提示。
        // 默认关时下面整块跳过，回复= 人设 + 记忆 + 历史 + 当前消息（即 1.0.54 的干净路径）。
        if (personalityOn()) {
            // 群聊背景：把对话场景从“现实世界”改成“群聊”，让 AI 知道自己在群里、按昵称区分每个人。
            // 群聊背景子开关关闭时跳过（用 realityOn 的现实世界身份段替代）。
            if (kind == "group" && groupCtxOn()) {
                msgs.add(ChatMsg("system", "你正在一个QQ群里聊天。你看到的每条发言都是「昵称 说：消息内容」的格式：冒号前（“说”字前）的是发言者的名字/昵称，用来区分是谁说的、以及你称呼他时用；它可能是不像人名的任意短语（例如“我喜欢猫”“开心就好”），那也是他的名字，一律只当名字、绝不把它当成发言内容或据此推断含义、也不要复述它。冒号之后才是这个人真正说的话。回应别人时直接用对方昵称称呼，但你的回复正文不要带“昵称 说：”前缀。"))
            }
            // 人格化：有生活、会思考、心情影响回复（去AI化/语言多样/做梦/脑补）
            msgs.add(ChatMsg("system", personaSystem()))
        }
        // 记忆增强：注入对话摘要 + 用户画像（独立于历史，不参与总结、不被总结覆盖）
        ContextManager.summaryOf(convKey)?.let { msgs.add(ChatMsg("system", "【对话摘要】\n$it")) }
        ContextManager.renderProfileSystem(profile)?.let { msgs.add(ChatMsg("system", it)) }
        // 总是触发（hook）插件：指令注入每条消息
        PluginManager.alwaysInstructions().forEach { msgs.add(ChatMsg("system", it)) }
        // LLM 触发（技能）：把技能列表和调用语法告诉模型
        val skills = PluginManager.llmSkills()
        if (skills.isNotEmpty()) {
            val sb = StringBuilder()
            sb.append("你可以使用以下技能。当用户的需求适合用某个技能完成时，在回复末尾另起一行单独输出，不要与正文混在同一行：\n")
            sb.append("调用技能：/技能名 参数\n\n技能列表：\n")
            skills.forEach { sb.append("- ${it.name}：${it.desc}\n") }
            msgs.add(ChatMsg("system", sb.toString().trim()))
        }
        extraSystem?.takeIf { it.isNotBlank() }?.let { msgs.add(ChatMsg("system", it)) }
        // v1.5.0 内心思考注入
        thought?.takeIf { it.isNotBlank() }?.let { msgs.add(ChatMsg("system", "【内心思考】$it（这是你的内心独白，不会发给对方，用来让回复更自然连贯）")) }
        // v1.5.0 知识库注入
        if (Config.knowledgeEnabled) {
            try {
                val kbEntries = KnowledgeBase.search(userMsg.take(50), 3)
                val kbText = KnowledgeBase.formatForInjection(kbEntries)
                if (kbText.isNotBlank()) msgs.add(ChatMsg("system", kbText))
            } catch (_: Exception) {
            }
        }
        // 历史 + 当前消息统一渲染（群聊标注说话人）；与 memory-worker 的总结互斥，避免并发修改
        val finalUser = ChatMsg("user", userMsg, sender)
        synchronized(hist) { msgs.addAll(ContextManager.renderRecent(hist, kind, finalUser)) }
        return msgs
    }

    private fun append(hist: ArrayDeque<ChatMsg>, user: String, sender: String?, bot: String) {
        synchronized(hist) {
            hist.addLast(ChatMsg("user", user, sender))
            hist.addLast(ChatMsg("assistant", bot))
            // 上限放宽到 120 条：用户要求历史消息尽量保留，旧消息由记忆总结压缩而非直接丢弃
            while (hist.size > 120) hist.removeFirst()
        }
    }

    private fun notifyStatus(detail: String) {
        lastStatus = detail
        statusListeners.forEach { it.onStatus(running, detail) }
    }

    private fun emitChat(entry: ChatEntry) {
        // 测试模式不写聊天记录：测试页的对话不能混进真实聊天记录（实时页/历史都不该出现）
        if (!testMode) ChatStore.append(entry)
        msgListeners.forEach { it.onChatEntry(entry) }
    }

    /** 测试台：图片字节落临时文件回传（控制台渲染 🖼 气泡）；不再往真实 QQ 发无效 openid */
    private fun emitTestImageBytes(ev: QqMessageEvent, bytes: ByteArray, ext: String) {
        val f = TempMedia.save(bytes, ext.ifBlank { "png" })
        emitTestReply(ev, if (f != null) "🖼 image://${f.absolutePath}" else "🖼（图片暂存失败）")
    }

    /** 测试台：远程图片先下载落地再回传（失败给可见提示，不再出现“图片发送失败”静默） */
    private fun emitTestImageFromUrl(ev: QqMessageEvent, url: String) {
        try {
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            testImageHttp.newCall(req).execute().use { r ->
                val bytes = if (r.isSuccessful) r.body?.bytes() else null
                if (bytes == null || bytes.isEmpty()) {
                    emitTestReply(ev, "🖼（图片加载失败：HTTP ${r.code}）")
                    return
                }
                val ext = when {
                    url.contains(".png") -> "png"
                    url.contains(".gif") -> "gif"
                    else -> "jpg"
                }
                val f = TempMedia.save(bytes, ext)
                emitTestReply(ev, if (f != null) "🖼 image://${f.absolutePath}" else "🖼（图片暂存失败）")
            }
        } catch (e: Exception) {
            emitTestReply(ev, "🖼（图片加载失败：${e.message?.take(60)}）")
        }
    }

    /** 测试模式：把引擎产生的回复回传给测试页显示（非测试模式什么都不做） */
    private fun emitTestReply(ev: QqMessageEvent, text: String) {
        if (!testMode) return
        testReplyListeners.forEach { it.onTestReply("${ev.kind}:${ev.openid}", text) }
    }

    // ---------------- v1.5.0 内置命令 ----------------
    private fun handleBuiltinCommand(ev: QqMessageEvent, uid: String): Boolean {
        val text = ev.content.trim()
        // "查看xxx画像"
        val profileMatch = Regex("^查看(.+)画像$").find(text)
        if (profileMatch != null) {
            val target = profileMatch.groupValues[1].trim()
            val isSelf = target == "自己" || target == "我的" || target == "我"
            if (isSelf) {
                val profile = MemoryStore.load(uid)
                val view = ThoughtEngine.formatProfileView(profile, true)
                deliver(ev, view)
                return true
            }
            val leaked = ThoughtEngine.checkPrivacyLeak(text, uid, target)
            if (leaked) {
                deliver(ev, "抱歉，我无法透露其他人的私人信息。")
                return true
            }
            deliver(ev, "暂时只能查看自己的画像。发送「查看自己画像」试试？")
            return true
        }
        // 撤回机器人上一条消息（QQ 官方接口，2 分钟内有效；群聊需机器人有「撤回消息」权限）
        if (text == "/撤回" || text == "撤回上一条" || text == "撤销上一条") {
            val (ok, msg) = try {
                client?.recallLast(ev.kind, ev.openid) ?: (false to "连接未就绪")
            } catch (e: Exception) {
                false to "撤回失败：${e.message}"
            }
            Logs.info("[撤回] ${if (ok) "成功" else "失败"}：$msg")
            deliver(ev, msg)
            return true
        }
        // "我的ID"
        if (text == "我的ID" || text == "/我的ID") {
            deliver(ev, "你的 UID：$uid")
            return true
        }
        // 好感度查看
        if (text == "好感度" || text == "/好感度" || text == "我的好感" || text == "亲密度" || text == "/亲密度") {
            val profile = MemoryStore.load(uid)
            val favor = profile.favorability
            val desc = when {
                favor >= 90 -> "超级喜欢！"
                favor >= 75 -> "很喜欢你～"
                favor >= 60 -> "对你有好感"
                favor >= 40 -> "普通朋友"
                favor >= 25 -> "有点警惕"
                else -> "不太想理你……"
            }
            deliver(ev, "💗 好感度：$favor/100（$desc）")
            return true
        }
        return false
    }

    /** 知识库入库：判断是否新闻类型 → 存标题/出处。不参与本次回复，所以可以异步 */
    private fun doKnowledge(ev: QqMessageEvent) {
        try {
            if (Config.knowledgeEnabled) {
                KnowledgeBase.processMessage(ev.content, ev.sender, ev.imageUrls)
            }
        } catch (e: Exception) {
            Logs.debug("[知识库] 处理异常: ${e.message}")
        }
    }

    /** 内心思考：要注入到本次回复里，必须在回复前同步生成 */
    private fun thinkNow(ev: QqMessageEvent, uid: String) {
        try {
            if (Config.thoughtEnabled) {
                val profile = try {
                    MemoryStore.load(uid)
                } catch (_: Exception) {
                    null
                }
                val thought = ThoughtEngine.think(ev, ev.content, profile)
                if (thought != null && testMode) {
                    val convKey = "${ev.kind}:${ev.openid}"
                    testThoughtListeners.forEach { it.onTestThought(convKey, thought) }
                }
            }
        } catch (e: Exception) {
            Logs.debug("[内心思考] 处理异常: ${e.message}")
        }
    }
}

package com.astrbot.lite.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * QQ 官方机器人客户端（群聊 / 单聊 v2 API）
 * - 获取 access_token（bots.qq.com/app/getAppAccessToken）
 * - WebSocket 接入事件（wss://api.sgroup.qq.com/websocket/）
 * - REST 发送消息（/v2/groups|users/{openid}/messages）
 */
class QqBotClient(
    private val appId: String,
    private val secret: String,
    private val apiBase: String,
    /** 环境标识（prod / sandbox）：仅用于区分同一 AppID 的不同接入环境 */
    private val env: String = "prod",
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String)
        fun onMessage(ev: QqMessageEvent)

        /** 按钮点击（INTERACTION_CREATE type=11）；默认忽略，BotEngine 可覆写 */
        fun onInteraction(ev: QqInteraction) {}
    }

    companion object {
        /** 仅测试：用 AppID/AppSecret 换取 access_token，返回成功提示或抛异常 */
        fun testToken(appId: String, secret: String, apiBase: String): String {
            if (appId.isBlank()) throw Exception("AppID 不能为空")
            if (secret.isBlank()) throw Exception("AppSecret 不能为空")
            val c = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val body = JSONObject().put("appId", appId).put("clientSecret", secret).toString()
            val urls = arrayOf(
                "https://bots.qq.com/app/getAppAccessToken",
                "$apiBase/app/getAppAccessToken",
            )
            var lastErr: Exception? = null
            for (u in urls) {
                try {
                    val req = Request.Builder()
                        .url(u)
                        .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), body))
                        .build()
                    val resp = c.newCall(req).execute()
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val token = JSONObject(text).optString("access_token")
                        if (token.isNotBlank()) {
                            return "✅ 获取凭证成功（${token.take(8)}...，有效期 ${JSONObject(text).optString("expires_in")} 秒）"
                        }
                        throw Exception("返回缺少 access_token: ${text.take(120)}")
                    }
                    lastErr = Exception("HTTP ${resp.code}: ${text.take(120)}")
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            throw lastErr ?: Exception("获取 Token 失败")
        }

        const val OP_DISPATCH = 0
        const val OP_HEARTBEAT = 1
        const val OP_IDENTIFY = 2
        const val OP_RECONNECT = 7
        const val OP_INVALID_SESSION = 9
        const val OP_HELLO = 10
        const val OP_HEARTBEAT_ACK = 11

        // 群聊@ + 单聊（GROUP_AND_C2C_EVENT）。仅订阅需要的权限，避免“intent 无权限”被拒
        const val INTENTS = (1 shl 25) or (1 shl 26) // 群/单聊消息 + 互动事件（按钮回调）
    }

    private val json = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 注意：不要用 pingInterval —— QQ 网关不响应 OkHttp 的 ping 帧，
        // 会误判掉线。应用层心跳（op1）已能保持连接。
        .build()

    @Volatile private var running = false
    private var ws: WebSocket? = null

    @Volatile private var accessToken: String? = null
    private var tokenExpireAt = 0L
    private var sessionId: String? = null
    private var seq = 0L
    private var heartbeatMs = 45000L
    private var heartbeatThread: Thread? = null

    /** 当前机器人自己的 openid（READY 的 d.user.id）：用于识别群消息里@的是不是自己 */
    @Volatile private var botOpenid = ""

    /** 机器人自己的昵称（READY 的 d.user.username）：全量消息模式下 content 里 @ 标记是昵称形态，用昵称兜底判 atMe */
    @Volatile private var botNameCache = ""

    /** 最近一次收到心跳 ACK 的时间：用于检测网关“假死”（TCP 未断但不回 ACK） */
    @Volatile private var lastAckAt = 0L

    /** 鉴权成功(READY)时间 / 最近一次收到任何事件的时间：用于“已连接但收不到事件”的诊断 */
    @Volatile private var readyAt = 0L

    @Volatile private var lastEventAt = 0L

    // 鉴权令牌格式：v2 用 "QQBot {access_token}"；若不支持自动切换为 "Bot {appid}.{secret}"
    @Volatile private var useAccessTokenIdentify = true

    // 鉴权连续失败计数（用于切换鉴权格式）
    @Volatile private var invalidCount = 0

    // 当前生效的 REST 地址（与所选环境保持一致）
    @Volatile private var restBase = apiBase

    /** 同一 msg_id 的回复次数（平台要求 msg_id+msg_seq 唯一；不填默认 1，重复回复同一消息会被判重拒绝） */
    private val replySeq = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 每个会话最近一条机器人消息：会话键 → (message_id, 发送时间)。用于「/撤回」 */
    private val lastSent = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    fun start() {
        if (running) return
        running = true
        Thread { connectLoop() }.apply {
            isDaemon = true
            name = "qqbot-ws"
            start()
        }
    }

    fun stop() {
        running = false
        ws?.close(1000, "stop")
        ws = null
        heartbeatThread?.interrupt()
    }

    /** 心跳健康度：近 3 个心跳周期内收到过 ACK/事件，或刚 READY（宽限期内）→ 连接是活的。
     *  用于看门狗判断“机器人是否卡死”：空闲但已连接也算健康（心跳每 ~45s 刷新 lastAckAt）。 */
    fun heartbeatHealthy(): Boolean {
        val now = System.currentTimeMillis()
        val window = 3 * heartbeatMs
        val last = maxOf(lastAckAt, lastEventAt)
        if (last > 0 && now - last <= window) return true
        if (readyAt > 0 && now - readyAt <= window) return true
        return false
    }

    /** 最近一次连接层活动（ACK/事件/READY）时间，用于诊断与看门狗计时 */
    fun lastConnActivity(): Long = maxOf(lastAckAt, lastEventAt, readyAt)

    // ---------------- 连接循环 ----------------
    private fun connectLoop() {
        var backoff = 1500L
        while (running) {
            try {
                val token = getToken()
                val env = if (Config.qqEnv == "sandbox") "沙箱" else "正式"
                restBase = restBaseFor()
                val url = discoverGateway(token) ?: fallbackWsUrl()
                Logs.info("网关地址: $url（$env 环境）")
                listener.onStatus("正在连接网关（$env 环境）：$url")
                connectOnce(url, token)
            } catch (e: Exception) {
                listener.onStatus("连接异常: ${e.message}")
            }
            if (!running) break
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                break
            }
            backoff = minOf(backoff * 2, 15000L)
        }
    }

    private fun connectOnce(url: String, token: String) {
        val latch = CountDownLatch(1)
        val req = Request.Builder().url(url).build()
        val socket = http.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onStatus("WebSocket 已建立")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleFrame(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (ws === webSocket) webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (ws === webSocket) ws = null
                    listener.onStatus("连接已关闭（$code $reason）")
                    latch.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (ws === webSocket) ws = null
                    listener.onStatus("连接断开: ${t.message}")
                    latch.countDown()
                }
            },
        )
        ws = socket
        try {
            latch.await()
        } catch (_: InterruptedException) {
        }
    }

    // ---------------- Token / 网关 ----------------
    private fun getToken(): String {
        val now = System.currentTimeMillis() / 1000
        if (!accessToken.isNullOrEmpty() && now < tokenExpireAt - 60) return accessToken!!
        val body = JSONObject().put("appId", appId).put("clientSecret", secret).toString()
        val urls = arrayOf(
            "https://bots.qq.com/app/getAppAccessToken",
            "$apiBase/app/getAppAccessToken",
        )
        var lastErr: Exception? = null
        for (u in urls) {
            try {
                val req = Request.Builder()
                    .url(u)
                    .post(RequestBody.create(json, body))
                    .build()
                Logs.debug("[Token] POST $u  appId=$appId")
                val resp = http.newCall(req).execute()
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val obj = JSONObject(text)
                    val token = obj.optString("access_token")
                    if (token.isNotBlank()) {
                        accessToken = token
                        tokenExpireAt = now + obj.optLong("expires_in", 7200)
                        Logs.debug("[Token] 获取成功 token=${token.take(6)}… expires_in=${obj.optLong("expires_in", 0)}s")
                        listener.onStatus("已获取 access_token")
                        return token
                    }
                    Logs.debug("[Token] 响应缺少 access_token: ${text.take(200)}")
                    throw Exception("返回缺少 access_token: ${text.take(200)}")
                }
                Logs.debug("[Token] 失败 HTTP ${resp.code}：${text.take(200)}")
                lastErr = Exception("HTTP ${resp.code}: ${text.take(200)}")
            } catch (e: Exception) {
                lastErr = e
            }
        }
        Logs.warn("[Token] 全部地址获取失败，最后错误：${lastErr?.message}")
        throw lastErr ?: Exception("获取 access_token 失败")
    }

    /** REST 地址：与所选环境保持一致（正式用 v2 官方域名 api.sgroup.qq.com） */
    private fun restBaseFor(): String = if (Config.qqEnv == "sandbox") {
        "https://sandbox.api.sgroup.qq.com"
    } else {
        "https://api.sgroup.qq.com"
    }

    /**
     * 从官方 /gateway 接口获取真正的 WebSocket 网关地址。
     * 官方 v2 事件只通过该网关推送；不要硬编码旧版 api.bot.qq.com（不会推 v2 事件）。
     */
    private fun discoverGateway(token: String): String? {
        val base = restBaseFor()
        val endpoints = listOf("$base/gateway", "$base/gateway/bot")
        for (ep in endpoints) {
            try {
                val req = Request.Builder()
                    .url(ep)
                    .header("Authorization", "QQBot $token")
                    .get()
                    .build()
                val resp = http.newCall(req).execute()
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) continue
                val obj = JSONObject(text)
                val data = obj.optJSONObject("data")
                val url = data?.optString("url")
                    ?.takeIf { it.startsWith("wss://") }
                    ?: obj.optString("url").takeIf { it.startsWith("wss://") }
                if (url != null) return url
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** 兜底网关地址（发现接口失败时使用） */
    private fun fallbackWsUrl(): String = if (Config.qqEnv == "sandbox") {
        "wss://sandbox.api.sgroup.qq.com/websocket/"
    } else {
        "wss://api.sgroup.qq.com/websocket/"
    }

    // ---------------- 收发 ----------------
    /** 发送回复消息（被动回复，带 msg_id 保持会话上下文）。同一条消息多次回复需递增 msg_seq，否则平台判重拒绝 */
    fun sendReply(ev: QqMessageEvent, content: String) {
        val body = JSONObject().put("content", content).put("msg_type", 0)
        if (ev.msgId.isNotBlank()) {
            body.put("msg_id", ev.msgId)
            body.put("msg_seq", nextSeq(ev.msgId))
        }
        sendRaw(ev.kind, ev.openid, body.toString(), "回复")
    }

    /** 取该 msg_id 的下一个回复序号（第一次为 1） */
    private fun nextSeq(msgId: String): Int {
        if (replySeq.size > 500) replySeq.clear()
        return synchronized(replySeq) {
            val n = (replySeq[msgId] ?: 0) + 1
            replySeq[msgId] = n
            n
        }
    }

    /**
     * 发送带按钮的消息（markdown + keyboard）。QQ 要求按钮挂在 markdown 消息上；
     * 没有 markdown/按钮权限时自动降级为纯文本，避免插件命令直接失联。
     */
    fun sendButtonsReply(ev: QqMessageEvent, content: String, rowsJson: String): Boolean {
        val body = JSONObject()
            .put("msg_type", 2)
            .put("markdown", JSONObject().put("content", content.ifBlank { " " }))
            .put("keyboard", JSONObject().put("content", JSONObject().put("rows", JSONArray(rowsJson))))
        if (ev.msgId.isNotBlank()) {
            body.put("msg_id", ev.msgId)
            body.put("msg_seq", nextSeq(ev.msgId))
        }
        val ok = sendRaw(ev.kind, ev.openid, body.toString(), "按钮消息") != null
        if (!ok) {
            Logs.warn("[按钮] 带按钮消息发送失败（多为机器人无 markdown/按钮权限），降级纯文本")
            sendReply(ev, content)
        }
        return ok
    }

    /** 回应按钮互动（PUT /interactions/{id}）：不回应客户端会一直转圈 */
    fun ackInteraction(interactionId: String, code: Int = 0): Boolean {
        if (interactionId.isBlank()) return false
        return try {
            val req = Request.Builder()
                .url(restBase + "/interactions/$interactionId")
                .header("Authorization", "QQBot ${getToken()}")
                .header("Content-Type", "application/json")
                .put(RequestBody.create(json, JSONObject().put("code", code).toString()))
                .build()
            val resp = http.newCall(req).execute()
            if (resp.isSuccessful) {
                true
            } else {
                val t = try {
                    resp.body?.string().orEmpty()
                } catch (_: Exception) {
                    ""
                }
                Logs.warn("[按钮] 回应互动失败 HTTP ${resp.code}：${t.take(160)}")
                false
            }
        } catch (e: Exception) {
            Logs.warn("[按钮] 回应互动异常: ${e.message}")
            false
        }
    }

    /** 按钮点击后的被动回复（event_id 来自 INTERACTION_CREATE），同样支持继续挂按钮 */
    fun sendInteractionReply(
        kind: String,
        openid: String,
        eventId: String,
        content: String,
        rowsJson: String?,
    ): Boolean {
        val body = JSONObject().put("event_id", eventId)
        if (rowsJson != null) {
            body.put("msg_type", 2)
                .put("markdown", JSONObject().put("content", content.ifBlank { " " }))
                .put("keyboard", JSONObject().put("content", JSONObject().put("rows", JSONArray(rowsJson))))
        } else {
            body.put("msg_type", 0).put("content", content)
        }
        val ok = sendRaw(kind, openid, body.toString(), "按钮回调回复") != null
        if (!ok && rowsJson != null) sendText(kind, openid, content)
        return ok
    }

    /** 主动发送消息（机器人主动找对方聊天，无事件来源、不带 msg_id） */
    fun sendText(kind: String, openid: String, content: String) {
        sendRaw(kind, openid, JSONObject().put("content", content).put("msg_type", 0).toString(), "主动发送")
    }

    /** 发送语音消息：上传 mp3 素材（srv_send_msg=true 直接发送），失败时日志记录 */
    fun sendVoice(kind: String, openid: String, audio: ByteArray) {
        try {
            val token = getToken()
            val path = if (kind == "group") {
                "/v2/groups/$openid/files"
            } else {
                "/v2/users/$openid/files"
            }
            val json = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
            val body = JSONObject()
                .put("file_type", 3)
                .put("file_data", json)
                .put("srv_send_msg", true)
                .toString()
            val req = Request.Builder()
                .url(restBase + path)
                .header("Authorization", "QQBot $token")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            val text = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                Logs.info("[发送] 语音成功（$kind）")
            } else {
                Logs.warn("[发送] 语音失败 HTTP ${resp.code}：${text.take(150)}")
            }
        } catch (e: Exception) {
            Logs.warn("[发送] 语音异常: ${e.message}")
        }
    }

    /** 拉图：从 url 下载图片字节（带 UA/超时/大小上限），失败返回 null。供发图前先取图用 */
    fun fetchImageBytes(url: String): ByteArray? {
        if (url.isBlank()) return null
        return try {
            // 带 Referer（同源）降低常见图床「防盗链」拦截概率，避免下载到占位图/HTML
            val referer = try {
                val u = java.net.URL(url)
                "${u.protocol}://${u.host}"
            } catch (_: Exception) {
                null
            }
            val rb = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (AstrBotLite)")
                .get()
            if (referer != null) rb.header("Referer", referer)
            val req = rb.build()
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) {
                Logs.warn("[拉图] HTTP ${resp.code}：$url")
                return null
            }
            // 先看 Content-Length 再读：body.bytes() 会一次性进内存，机顶盒上超大响应容易 OOM
            val len = resp.body?.contentLength() ?: -1L
            if (len > 10 * 1024 * 1024) {
                Logs.warn("[拉图] Content-Length 超过 10MB（${len / 1024}KB），已放弃：$url")
                return null
            }
            val bytes = resp.body?.bytes()
            when {
                bytes == null || bytes.isEmpty() -> {
                    Logs.warn("[拉图] 空内容：$url")
                    null
                }
                bytes.size > 10 * 1024 * 1024 -> {
                    Logs.warn("[拉图] 超过 10MB，已放弃（${bytes.size / 1024}KB）：$url")
                    null
                }
                else -> bytes
            }
        } catch (e: Exception) {
            Logs.warn("[拉图] 下载异常: ${e.message}（$url）")
            null
        }
    }

    /**
     * 发图：作为“图片消息”（file_type=1，srv_send_msg=true）上传并发出。
     * - GIF / PNG / JPEG：原样上传（GIF 保留动图动画，PNG/JPG 本身干净不损失画质）。
     * - WEBP / BMP / 未知格式：先重编码为干净的 PNG 再发（规避“富媒体文件格式不支持 HTTP 400”）。
     * - 首选被拒（常见于 GIF 动图不被接受）：自动降级为“首帧 PNG”再重试一次。
     * 返回是否成功。
     */
    fun sendImage(kind: String, openid: String, bytes: ByteArray, ext: String = "png"): Boolean {
        var tmp: java.io.File? = null
        var delReason = "图片发送失败"
        try {
            if (bytes.isEmpty()) {
                Logs.warn("[发图] 图片为空，跳过")
                return false
            }
            val sniff = sniffImageKind(bytes)
            val isClean = sniff == ImgKind.GIF || sniff == ImgKind.PNG || sniff == ImgKind.JPEG
            // 首选发送体：干净的 GIF/PNG/JPG 原样发（GIF 保留动图）；其余（webp/bmp/未知/非图）重编码为 PNG。
            val first = if (isClean) {
                bytes
            } else {
                val png = reencodeToPng(bytes)
                if (png == null) {
                    Logs.warn("[发图] 下载的内容无法解码为有效图片（可能是防盗链占位图/HTML/不支持的格式，ext=$ext），无法作为图片发送")
                    listener.onStatus("图片 发送失败: 图片格式无法识别")
                    return false
                }
                png
            }
            // 生成/取到的图片先落到临时文件，发完立刻删除（本机不留存；存与删都进日志）
            tmp = TempMedia.save(first, if (isClean) ext else "png")
            if (postImageFile(kind, openid, first)) {
                delReason = "图片已发送"
                Logs.info("[发图] 图片发送成功（$kind，${first.size / 1024}KB，${if (isClean) ext else "重编码 PNG"}）")
                listener.onStatus("图片 发送成功")
                return true
            }
            // 降级兜底：首选（多为 GIF 动图）被拒 → 退成首帧 PNG 再试一次
            if (isClean) {
                val png = reencodeToPng(bytes)
                if (png != null && !png.contentEquals(first)) {
                    Logs.info("[发图] 原格式（$ext）被拒，降级为首帧 PNG 重试")
                    if (postImageFile(kind, openid, png)) {
                        delReason = "图片已发送"
                        listener.onStatus("图片 发送成功")
                        return true
                    }
                }
            }
            // 体积兜底：大 PNG 多次失败（间歇性 500/体积敏感）→ 压成 JPEG（通常缩到 1/4）再试
            if (sniff == ImgKind.PNG && first.size > 800 * 1024) {
                val jpg = reencodeToJpeg(first, 90)
                if (jpg != null) {
                    Logs.info("[发图] PNG（${first.size / 1024}KB）重试仍失败，转 JPEG（${jpg.size / 1024}KB）再试")
                    if (postImageFile(kind, openid, jpg)) {
                        delReason = "图片已发送"
                        Logs.info("[发图] 图片发送成功（JPEG 兜底，$kind，${jpg.size / 1024}KB）")
                        listener.onStatus("图片 发送成功")
                        return true
                    }
                }
            }
            listener.onStatus("图片 发送失败")
            return false
        } catch (e: Exception) {
            Logs.warn("[发图] 图片异常: ${e.message}")
            listener.onStatus("图片 发送失败: ${e.message}")
            return false
        } finally {
            TempMedia.delete(tmp, delReason)
        }
    }

    /** 上传富媒体（QQ 官方富媒体接口）：file_type 1=图片 2=视频 3=语音；srv_send_msg=true 上传完直接作为消息发出。返回是否成功。
     *  v1.7.46：对 5xx/「系统繁忙」做指数退避重试（最多 3 次：立即、+2s、+4s）——群发图实测会间歇性 500。 */
    private fun postMediaFile(kind: String, openid: String, fileType: Int, data: ByteArray, tag: String): Boolean {
        val token = getToken()
        val path = if (kind == "group") "/v2/groups/$openid/files" else "/v2/users/$openid/files"
        val body = JSONObject()
            .put("file_type", fileType)
            .put("file_data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
            .put("srv_send_msg", true)
        var attempt = 0
        while (true) {
            attempt++
            val req = Request.Builder()
                .url(restBase + path)
                .header("Authorization", "QQBot $token")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json, body.toString()))
                .build()
            val (code, text) = http.newCall(req).execute().use { resp ->
                resp.code to (resp.body?.string().orEmpty())
            }
            if (code in 200..299) return true
            val msg = try {
                JSONObject(text).optString("message", text.take(150))
            } catch (_: Exception) {
                text.take(150)
            }
            val retryable = code in 500..599 || msg.contains("系统繁忙")
            if (retryable && attempt < 3) {
                val delayMs = if (attempt == 1) 2000L else 4000L
                Logs.warn("$tag 发送失败 HTTP $code：$msg（第 $attempt/3 次，${delayMs / 1000}s 后重试）")
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return false
                }
                continue
            }
            Logs.warn("$tag 发送失败 HTTP $code：$msg")
            return false
        }
    }

    /** 真正上传图片：base64 后 POST 到富媒体接口（file_type=1 直接作为图片消息发出）。返回是否成功 */
    private fun postImageFile(kind: String, openid: String, data: ByteArray): Boolean = postMediaFile(kind, openid, 1, data, "[发图]")

    /** 用魔数（magic bytes）嗅探图片真实格式，比直链的后缀/文件名更可靠 */
    private enum class ImgKind { GIF, PNG, JPEG, WEBP, BMP, UNKNOWN, NOT_IMAGE }

    private fun sniffImageKind(b: ByteArray): ImgKind {
        if (b.size < 4) return ImgKind.NOT_IMAGE
        val at = { i: Int -> if (i < b.size) (b[i].toInt() and 0xFF) else -1 }
        return when {
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> ImgKind.GIF // GIF87a / GIF89a
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> ImgKind.PNG // \x89PNG
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> ImgKind.JPEG // JPEG/JFIF
            at(0) == 0x52 &&
                at(1) == 0x49 &&
                at(2) == 0x46 &&
                at(3) == 0x46 &&
                at(8) == 0x57 &&
                at(9) == 0x45 &&
                at(10) == 0x42 &&
                at(11) == 0x50 -> ImgKind.WEBP // RIFF....WEBP
            at(0) == 0x42 && at(1) == 0x4D -> ImgKind.BMP // 'BM'
            else -> {
                // 开头像 HTML/JSON 文本的多半是防盗链占位或报错页，不是图
                val head = String(b.copyOfRange(0, minOf(b.size, 512)), Charsets.US_ASCII)
                val t = head.trimStart()
                if (t.startsWith("<", true) || t.startsWith("{") || head.contains("DOCTYPE", true) || head.contains("<html", true)) {
                    ImgKind.NOT_IMAGE
                } else {
                    ImgKind.UNKNOWN
                }
            }
        }
    }

    /** 解码并重新编码为 PNG（保留透明通道；GIF 只取首帧）。内容不是有效图片时返回 null。线程安全，可在子线程调用。 */
    private fun reencodeToPng(src: ByteArray): ByteArray? {
        try {
            val bmp = BitmapFactory.decodeByteArray(src, 0, src.size) ?: return null
            if (bmp.width <= 0 || bmp.height <= 0) {
                bmp.recycle()
                return null
            }
            val out = java.io.ByteArrayOutputStream()
            val ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            return if (ok) out.toByteArray() else null
        } catch (e: Exception) {
            Logs.warn("[发图] PNG 重编码失败: ${e.message}")
            return null
        }
    }

    /** 解码并压缩为 JPEG（体积兜底用；透明通道先白底合成，避免透明区变黑）。失败返回 null。 */
    private fun reencodeToJpeg(src: ByteArray, quality: Int): ByteArray? {
        try {
            val bmp = BitmapFactory.decodeByteArray(src, 0, src.size) ?: return null
            if (bmp.width <= 0 || bmp.height <= 0) {
                bmp.recycle()
                return null
            }
            val toEncode = if (bmp.hasAlpha()) {
                val flat = android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, android.graphics.Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(flat)
                cv.drawColor(android.graphics.Color.WHITE)
                cv.drawBitmap(bmp, 0f, 0f, null)
                bmp.recycle()
                flat
            } else {
                bmp
            }
            val out = java.io.ByteArrayOutputStream()
            val ok = toEncode.compress(Bitmap.CompressFormat.JPEG, quality, out)
            toEncode.recycle()
            return if (ok) out.toByteArray() else null
        } catch (e: Exception) {
            Logs.warn("[发图] JPEG 重编码失败: ${e.message}")
            return null
        }
    }

    /** 发图（拉图+发图一体）：先从 url 下载图片（拉图），成功再以“图片消息”发出（发图）。返回是否成功 */
    fun sendImageFromUrl(kind: String, openid: String, url: String): Boolean {
        val bytes = fetchImageBytes(url) ?: return false
        val raw = url.substring(url.lastIndexOf('/') + 1)
        val q = raw.indexOf('?')
        val ext = if (q >= 0) raw.substring(0, q) else raw
        return sendImage(kind, openid, bytes, ext)
    }

    /**
     * 发视频（QQ 官方富媒体 file_type=2，平台要求 mp4）：base64 上传后直接作为视频消息发出。返回是否成功。
     * 视频体积远大于图片；过大（base64 后≈1.33 倍）容易被平台拒，这里 >25MB 先警告但仍尝试。
     */
    fun sendVideo(kind: String, openid: String, bytes: ByteArray, ext: String = "mp4"): Boolean {
        var tmp: java.io.File? = null
        var delReason = "视频发送失败"
        try {
            if (bytes.isEmpty()) {
                Logs.warn("[发视频] 视频为空，跳过")
                return false
            }
            val mb = bytes.size / 1024.0 / 1024.0
            val mbText = String.format("%.2f", mb)
            if (mb > 25.0) {
                Logs.warn("[发视频] 视频偏大（$mbText MB），QQ 富媒体可能拒收，仍尝试发送")
            }
            // 生成的视频先落到临时文件，发完立刻删除（本机不留存；存与删都进日志）
            tmp = TempMedia.save(bytes, ext)
            Logs.info("[发视频] 开始发送：$kind，$mbText MB，格式 $ext")
            if (postMediaFile(kind, openid, 2, bytes, "[发视频]")) {
                delReason = "视频已发送"
                Logs.info("[发视频] 视频发送成功（$mbText MB）")
                listener.onStatus("视频 发送成功")
                return true
            }
            listener.onStatus("视频 发送失败")
            return false
        } catch (e: Exception) {
            Logs.warn("[发视频] 异常: ${e.message}")
            listener.onStatus("视频 发送失败: ${e.message}")
            return false
        } finally {
            TempMedia.delete(tmp, delReason)
        }
    }

    /**
     * 文本消息发送（回复/主动）。1.7.255 起补齐三条稳定性：
     *  1) 5xx / 429 /「系统繁忙」/ 网络异常 → 退避重试 3 次（原来直接丢弃，表现为“AI 回了但 QQ 没收到”）；
     *  2) 401 → 清掉 access_token 缓存重新获取再发（token 过期不再整条丢失）；
     *  3) 被动回复（带 msg_id）被拒（AI 处理超过 5 分钟窗口 / msg_id 失效）→ 去掉 msg_id 改主动消息兜底重发一次。
     *     内容违规（40034006）不做兜底，同样的文字重发也必被拦。
     */
    private fun sendRaw(kind: String, openid: String, bodyJson: String, tag: String): String? {
        val path = if (kind == "group") {
            "/v2/groups/$openid/messages"
        } else {
            "/v2/users/$openid/messages"
        }
        val url = restBase + path
        var body = bodyJson
        var attempt = 0
        var activeFallback = false
        while (true) {
            attempt++
            try {
                val token = getToken()
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "QQBot $token")
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(json, body))
                    .build()
                Logs.debug("[QQ REST] POST $url  body=${body.take(300)}")
                val resp = http.newCall(req).execute()
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    Logs.debug("[QQ REST] 响应 200：${text.take(200)}")
                    Logs.info("[发送] $tag 成功（$kind${if (activeFallback) "·主动兜底" else ""}）")
                    listener.onStatus(tag + " 成功")
                    val mid = try {
                        JSONObject(text).optString("id")
                    } catch (_: Exception) {
                        ""
                    }
                    if (mid.isNotBlank()) {
                        lastSent["$kind:$openid"] = mid to System.currentTimeMillis()
                        // 只保留最近一小时，避免长期运行堆积
                        val cutoff = System.currentTimeMillis() - 3600_000
                        lastSent.entries.removeAll { it.value.second < cutoff }
                    }
                    return mid.ifBlank { null }
                }
                val code = resp.code
                val msg = try {
                    JSONObject(text).optString("message", text.take(200))
                } catch (_: Exception) {
                    text.take(200)
                }
                Logs.debug("[QQ REST] 失败响应体: ${text.take(300)}")
                // 401：access_token 失效 → 清缓存重新获取后重试
                if (code == 401 && attempt < 4) {
                    Logs.warn("[发送] $tag 401 鉴权失效，重新获取 token 后重试")
                    accessToken = ""
                    continue
                }
                // 5xx / 429 / 系统繁忙：退避重试
                val retryable = code in 500..599 || code == 429 || msg.contains("系统繁忙")
                if (retryable && attempt < 3) {
                    val delayMs = if (attempt == 1) 1000L else 2500L
                    Logs.warn("[发送] $tag 失败 HTTP $code：$msg（第 $attempt/3 次，${delayMs}ms 后重试）")
                    Thread.sleep(delayMs)
                    continue
                }
                // 被动回复过期/无效 → 主动消息兜底（内容违规除外）
                val violation = msg.contains("内容违规") || msg.contains("40034006")
                if (!activeFallback && !violation && code in 400..499 && body.contains("\"msg_id\"")) {
                    activeFallback = true
                    attempt = 0
                    body = JSONObject(body).apply {
                        remove("msg_id")
                        remove("msg_seq")
                    }.toString()
                    Logs.warn("[发送] $tag 被动回复失败（HTTP $code：$msg），改用主动消息兜底重发")
                    continue
                }
                Logs.warn("[发送] $tag 失败 HTTP $code：$msg")
                listener.onStatus(tag + " 失败: HTTP $code $msg")
                return null
            } catch (e: InterruptedException) {
                return null
            } catch (e: Exception) {
                if (attempt < 3) {
                    Logs.warn("[发送] $tag 异常：${e.message}（第 $attempt/3 次，1s 后重试）")
                    try {
                        Thread.sleep(1000L)
                    } catch (_: InterruptedException) {
                        return null
                    }
                    continue
                }
                Logs.warn("[发送] $tag 异常: ${e.message}")
                listener.onStatus(tag + " 异常: ${e.message}")
                return null
            }
        }
        return null
    }

    /** 撤回本会话最近一条机器人消息（2 分钟内有效）。返回 (是否成功, 提示文案) */
    fun recallLast(kind: String, openid: String): Pair<Boolean, String> {
        val rec = lastSent["$kind:$openid"] ?: return false to "没有可撤回的消息（机器人还没在这个会话发过言）"
        val (mid, ts) = rec
        if (System.currentTimeMillis() - ts > 120_000) {
            lastSent.remove("$kind:$openid")
            return false to "上一条消息已超过 2 分钟，QQ 不允许撤回了"
        }
        return if (recallMessage(kind, openid, mid)) {
            lastSent.remove("$kind:$openid")
            true to "已撤回上一条消息"
        } else {
            false to "撤回失败（可能需要机器人有「撤回消息」权限，或消息已过期）"
        }
    }

    /** 撤回指定消息：DELETE /v2/groups|users/{openid}/messages/{message_id} */
    fun recallMessage(kind: String, openid: String, messageId: String): Boolean {
        if (messageId.isBlank()) return false
        val path = if (kind == "group") {
            "/v2/groups/$openid/messages/$messageId"
        } else {
            "/v2/users/$openid/messages/$messageId"
        }
        return try {
            val token = getToken()
            val req = Request.Builder()
                .url(restBase + path)
                .header("Authorization", "QQBot $token")
                .delete()
                .build()
            val resp = http.newCall(req).execute()
            val code = resp.code
            if (code in 200..299) {
                Logs.info("[撤回] 成功（$kind，${messageId.take(12)}…）")
                true
            } else {
                val body = try {
                    resp.body?.string().orEmpty()
                } catch (_: Exception) {
                    ""
                }
                Logs.warn("[撤回] 失败 HTTP $code：${body.take(200)}")
                false
            }
        } catch (e: Exception) {
            Logs.warn("[撤回] 异常: ${e.message}")
            false
        }
    }

    private fun handleFrame(text: String) {
        val p = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        when (p.optInt("op", -1)) {
            OP_HELLO -> {
                // optLong 缺省返回 0（不是 null），?: 不会生效：必须做范围校验，否则 heartbeatMs=0
                // 会导致心跳包狂发 + 看门狗健康窗口为 0 而反复误判重启
                val hb = p.optJSONObject("d")?.optLong("heartbeat_interval", 0L) ?: 0L
                heartbeatMs = if (hb in 1000..300000) hb else 45000L
                lastAckAt = System.currentTimeMillis()
                identify()
                startHeartbeat()
            }
            OP_HEARTBEAT_ACK -> {
                lastAckAt = System.currentTimeMillis()
            }
            OP_DISPATCH -> {
                seq = p.optLong("s", seq)
                lastEventAt = System.currentTimeMillis()
                when (p.optString("t")) {
                    "READY" -> {
                        sessionId = p.optJSONObject("d")?.optString("session_id")
                        val user = p.optJSONObject("d")?.optJSONObject("user")
                        botOpenid = user?.optString("id").orEmpty()
                        val name = user?.optString("username") ?: "QQ机器人"
                        botNameCache = name
                        useAccessTokenIdentify = true
                        invalidCount = 0
                        readyAt = System.currentTimeMillis()
                        listener.onStatus("✅ 鉴权成功，已连接：$name")
                    }
                    "RESUMED" -> listener.onStatus("连接已恢复")
                    "C2C_MESSAGE_CREATE" -> parseC2C(p.optJSONObject("d"))?.let {
                        listener.onMessage(it)
                    }
                    "GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE" -> parseGroup(p.optJSONObject("d"), p.optString("t"))?.let {
                        listener.onMessage(it)
                    }
                    "GROUP_ADD_ROBOT", "GROUP_DEL_ROBOT" -> listener.onStatus("收到事件: ${p.optString("t")}")
                    "INTERACTION_CREATE" -> parseInteraction(p.optJSONObject("d"))?.let {
                        listener.onInteraction(it)
                    }
                    else -> Logs.info("收到其他事件: ${p.optString("t")}")
                }
            }
            OP_RECONNECT -> {
                listener.onStatus("服务端要求重连")
                ws?.close(4000, "reconnect")
            }
            OP_INVALID_SESSION -> {
                // 打印错误码，便于定位：4013/4014=intent/权限问题，4914=未发布只能连沙箱
                val d = p.optJSONObject("d")
                val code = d?.optInt("code", -1) ?: -1
                val msg = d?.optString("message", "").orEmpty()
                listener.onStatus("会话无效，错误码 $code $msg")
                invalidCount++
                if (invalidCount % 2 == 0) {
                    useAccessTokenIdentify = !useAccessTokenIdentify
                    listener.onStatus("已切换鉴权格式（${if (useAccessTokenIdentify) "QQBot" else "Bot"}）")
                }
                sessionId = null
                ws?.close(4000, "invalid")
            }
        }
    }

    private fun identify() {
        val token = if (useAccessTokenIdentify) {
            "QQBot ${accessToken ?: ""}"
        } else {
            "Bot $appId.$secret"
        }
        val d = JSONObject()
        d.put("token", token)
        d.put("intents", INTENTS.toLong())
        d.put("shard", JSONArray().put(0).put(1))
        ws?.send(JSONObject().put("op", OP_IDENTIFY).put("d", d).toString())
        listener.onStatus("已发送鉴权（${if (useAccessTokenIdentify) "QQBot" else "Bot"} 格式）")
    }

    private fun startHeartbeat() {
        synchronized(this) {
            heartbeatThread?.interrupt()
            heartbeatThread = Thread {
                while (running) {
                    try {
                        Thread.sleep(heartbeatMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (!running) break
                    val w = ws ?: break
                    // 网关“假死”检测：连续 3 个心跳周期（默认 135s）没收到 ACK，
                    // 说明 TCP 还连着但网关不再推消息/回心跳，主动断开触发重连，
                    // 否则会出现“显示在线但实际收不到消息”。
                    if (lastAckAt > 0 && System.currentTimeMillis() - lastAckAt > 3 * heartbeatMs) {
                        listener.onStatus("网关心跳无响应（疑似掉线），正在自动重连…")
                        try {
                            w.close(4000, "heartbeat-timeout")
                        } catch (_: Exception) {
                        }
                        break
                    }
                    // 发送心跳
                    val p = JSONObject().put("op", OP_HEARTBEAT)
                    if (seq > 0) p.put("d", seq) else p.put("d", JSONObject.NULL)
                    w.send(p.toString())
                }
            }.apply {
                isDaemon = true
                name = "qqbot-heartbeat"
                start()
            }
        }
    }

    // ---------------- 事件解析 ----------------
    /**
     * 发送者显示名：用 QQ 事件里给的真实昵称（author.username）当名字。
     * 注意 QQ 官方在 C2C/群推送里该字段常为空字符串；为空时返回空串、
     * 不捏造“QQ用户·xxxx”之类的假名字——那种假名被喂给 AI 会被当真名，反而更乱
     * （例如 AI 误以为对方就叫“QQ用户·a1b2c3d4”）。没名字时让上层用通用称呼即可。
     */
    private fun displayName(username: String?): String = username?.trim().orEmpty()

    private fun parseC2C(d: JSONObject?): QqMessageEvent? {
        if (d == null) return null
        val author = d.optJSONObject("author")
        val openid = d.optString("c2c_openid").ifEmpty { author?.optString("user_openid").orEmpty() }
        val sender = displayName(author?.optString("username"))
        val content = cleanContent(d.optString("content"))
        val images = extractImageUrls(d)
        cacheImages(d.optString("id"), images)
        val replyMsgId = extractReplyMsgId(d)
        if (openid.isBlank() || (content.isBlank() && images.isEmpty())) return null
        return QqMessageEvent(
            "c2c",
            openid,
            "",
            content,
            d.optString("id"),
            sender,
            imageUrls = images,
            replyMsgId = replyMsgId,
        )
    }

    private fun parseGroup(d: JSONObject?, eventType: String = "GROUP_MESSAGE_CREATE"): QqMessageEvent? {
        if (d == null) return null
        val groupOpenid = d.optString("group_openid")
        val author = d.optJSONObject("author")
        val memberOpenid = author?.optString("member_openid").orEmpty()
        val sender = displayName(author?.optString("username"))
        val raw = d.optString("content")
        // 解析所有被@的 openid：<@!openid>（含多次@自己/@他人），供逻辑层判断“@的是不是我”
        val atIds = parseAtIds(raw)
        // 1.7.117：全量消息模式（GROUP_MESSAGE_CREATE）下 content 里 @ 标记是昵称形态
        // <@昵称>（不含 openid），parseAtIds 拿不到。改从官方 mentions 字段兜底取被@用户。
        val mentions = d.optJSONArray("mentions") ?: d.optJSONArray("at")
        val mentionOpenids = mutableListOf<String>()
        if (mentions != null) {
            for (i in 0 until mentions.length()) {
                val m = mentions.optJSONObject(i) ?: continue
                val oid = m.optString("member_openid")
                    .ifEmpty { m.optString("id") }
                    .ifEmpty { m.optString("user_openid") }
                    .ifEmpty { m.optString("user_id") }
                if (oid.isNotBlank()) mentionOpenids.add(oid)
            }
        }
        val allAtOpenids = (atIds + mentionOpenids).distinct()
        val content = cleanContent(raw)
        val images = extractImageUrls(d)
        cacheImages(d.optString("id"), images)
        val replyMsgId = extractReplyMsgId(d)
        if (groupOpenid.isBlank()) return null
        // 1.7.117：atMe 判定加第④层——mentions 里含 botOpenid（全量模式下 @ 机器人在 mentions 里）
        //   若 mentions 字段网关未下发，退回昵称文本匹配（raw 含 "<@机器人昵称" 也判中）
        var atMe = isAtMe(eventType, botOpenid, raw, allAtOpenids, memberOpenid)
        if (!atMe && eventType == "GROUP_MESSAGE_CREATE" && allAtOpenids.isEmpty()) {
            // 昵称兜底：全量模式下 content 里的 @ 标记形如 <@茂密>（昵称不含 openid），
            // 用 READY 拿到的机器人昵称匹配 raw 里的 <@昵称
            val botName = botNameCache
            if (botName.isNotBlank() && raw.contains("<@$botName")) {
                atMe = true
                Logs.debug("[群聊@] 昵称兜底命中 atMe：raw 含 <$botName")
            }
        }
        // 诊断日志：全量模式下未命中 atMe 时输出 mentions 原文，方便定位格式差异
        if (eventType == "GROUP_MESSAGE_CREATE" && !atMe && mentionOpenids.isNotEmpty()) {
            Logs.debug("[群聊@诊断] mentions=$mentions raw前80=${raw.take(80)} bot=$botOpenid")
        }
        if (atMe) Logs.debug("[群聊@] 命中 atMe（eventType=$eventType, atIds=$allAtOpenids, bot=$botOpenid, author=$memberOpenid）")
        return QqMessageEvent(
            "group", groupOpenid, memberOpenid, content, d.optString("id"), sender,
            atMe = atMe,
            atOpenids = allAtOpenids,
            imageUrls = images,
            replyMsgId = replyMsgId,
        )
    }

    /**
     * 本地"带图消息"缓存（message_id → 图片 URL 列表，LRU 上限 64 条，仅内存）。
     * 用途：用户**引用群里某条图片消息**发「图生图 xxx」时，QQ 的 get-message 群接口
     * 实测不可用/结构不定，本地缓存不依赖任何接口即可命中（机器人在线期间收到过的图都能查到）。
     * 机器人重启后失效，届时退回官方接口兜底。
     */
    private val imageMsgCache = object : LinkedHashMap<String, List<String>>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>): Boolean = size > 64
    }
    private val imgCacheLock = Any()

    private fun cacheImages(msgId: String, images: List<String>) {
        if (msgId.isBlank() || images.isEmpty()) return
        synchronized(imgCacheLock) { imageMsgCache[msgId] = images }
    }

    /** INTERACTION_CREATE（type=11 消息按钮）→ 统一事件。非按钮互动返回 null（无需回应） */
    private fun parseInteraction(d: JSONObject?): QqInteraction? {
        if (d == null) return null
        if (d.optInt("type", -1) != 11) {
            Logs.info("收到其他互动事件 type=${d.optInt("type", -1)}（忽略）")
            return null
        }
        val resolved = d.optJSONObject("data")?.optJSONObject("resolved") ?: JSONObject()
        val scene = d.optString("scene", "")
        val isGroup = scene == "group" || d.optInt("chat_type", -1) == 1
        val groupOpenid = d.optString("group_openid", "")
        val userOpenid = d.optString("user_openid", "")
        val openid = if (isGroup) groupOpenid else userOpenid
        if (openid.isBlank()) return null
        val ev = QqInteraction(
            id = d.optString("id", ""),
            kind = if (isGroup) "group" else "c2c",
            openid = openid,
            memberOpenid = if (isGroup) d.optString("group_member_openid", "") else userOpenid,
            buttonId = resolved.optString("button_id", ""),
            buttonData = resolved.optString("button_data", ""),
            messageId = resolved.optString("message_id", ""),
        )
        Logs.info("[按钮] 收到点击：id=${ev.buttonId} data=${ev.buttonData.take(60)}（${ev.kind}）")
        return ev
    }

    private fun extractImageUrls(d: JSONObject): List<String> {
        val urls = mutableListOf<String>()
        val arr = d.optJSONArray("attachments") ?: return urls
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("content_type", "").startsWith("image", true) || item.optString("filename", "").contains(Regex("\\.(png|jpg|jpeg|gif|webp|bmp)", RegexOption.IGNORE_CASE))) {
                val url = item.optString("url", "").ifEmpty { item.optString("src", "") }
                if (url.isNotBlank()) urls.add(url)
            }
        }
        return urls
    }

    private fun extractReplyMsgId(d: JSONObject): String {
        // 官方字段 reference.message_id；兼容个别网关的 message_reference / id 写法
        val ref = d.optJSONObject("reference")
            ?: d.optJSONObject("message_reference")
            ?: return ""
        val id = ref.optString("message_id").ifBlank { ref.optString("id") }
        if (id.isNotBlank()) Logs.debug("[引用图] 消息引用 id=$id")
        return id
    }

    /** 提取消息文本里所有 <@!openid> / <@openid> 标记的 openid */
    private fun parseAtIds(raw: String): List<String> = Regex("<@!?([^>]*)>").findAll(raw).mapNotNull { m ->
        m.groupValues[1].trim().takeIf { it.isNotBlank() }
    }.toList()

    /**
     * 1.7.117 强化：判定"这条群消息是否@了机器人自己"。
     * 四层判定（任一命中即 true）：
     * ① 官方事件类型 GROUP_AT_MESSAGE_CREATE——QQ 只在"机器人被@到"时才发此事件；
     * ② content 里 <@!botOpenid> 标记命中（GROUP_AT_MESSAGE_CREATE 的常规路径）；
     * ③ mentions / at 字段里含 botOpenid（全量消息模式 GROUP_MESSAGE_CREATE 下的权威标志）；
     * ④ 兜底：作者 = 机器人自己（机器人@自己测试场景）。
     * 旧版只走 ②，全量消息模式下 @ 标记是昵称形态、mentions 字段未被解析，导致 atMe 恒 false。
     */
    private fun isAtMe(eventType: String, botOpenidNow: String, raw: String, atIds: List<String>, authorOpenid: String): Boolean {
        if (eventType == "GROUP_AT_MESSAGE_CREATE") return true
        if (botOpenidNow.isNotBlank() && atIds.contains(botOpenidNow)) return true
        return botOpenidNow.isNotBlank() && authorOpenid == botOpenidNow
    }

    /**
     * 拉取被引用消息里的图片 URL（v1.7.23；v1.7.46 群聊加固）：
     * 用户"回复"一条图片消息再发命令时，当前消息的 attachments 里只有引用（reference.message_id），
     * 原图在被引用的那条消息里。两级取图：
     * ① 本地缓存（机器人在线期间收到过的图消息，不依赖任何接口——群聊引用图主路径）；
     * ② 官方 get-message 接口兜底（兼容 data.message / data / message / 根级 多种返回结构）。
     * 失败/无图返回空列表，不影响主流程。
     */
    fun fetchReferencedImageUrls(kind: String, openid: String, msgId: String): List<String> {
        if (msgId.isBlank()) return emptyList()
        // ① 本地缓存命中 → 直接用（无需 token/接口）
        synchronized(imgCacheLock) { imageMsgCache[msgId] }?.let {
            if (it.isNotEmpty()) {
                Logs.debug("[引用图] 本地缓存命中 msgId=$msgId（${it.size} 张）")
                return it
            }
        }
        // ② 官方 get-message 接口兜底
        return try {
            val token = getToken()
            val path = if (kind == "group") "/v2/groups/$openid/messages/$msgId" else "/v2/users/$openid/messages/$msgId"
            val req = Request.Builder()
                .url(restBase + path)
                .header("Authorization", "QQBot $token")
                .get()
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Logs.debug("[引用图] get-message HTTP ${r.code}：$msgId（本地缓存未命中，已无其它来源）")
                    return emptyList()
                }
                val obj = JSONObject(r.body?.string().orEmpty())
                // get-message 返回结构不统一：依次尝试 data.message / data / message / 根对象
                val candidates = listOf(
                    obj.optJSONObject("data")?.optJSONObject("message"),
                    obj.optJSONObject("data"),
                    obj.optJSONObject("message"),
                    obj,
                )
                for (c in candidates) {
                    if (c == null) continue
                    val imgs = extractImageUrls(c)
                    if (imgs.isNotEmpty()) return imgs
                    // 有的结构把 attachments 再包一层 message
                    val inner = c.optJSONObject("message")
                    if (inner != null) {
                        val imgs2 = extractImageUrls(inner)
                        if (imgs2.isNotEmpty()) return imgs2
                    }
                }
                Logs.debug("[引用图] get-message 无图片字段：$msgId")
                emptyList()
            }
        } catch (e: Exception) {
            Logs.debug("[引用图] 拉取引用消息异常：${e.message}")
            emptyList()
        }
    }

    private fun cleanContent(s: String): String = s.replace(Regex("<@!?[^>]*>"), " ")
        .replace("&nbsp;", " ")
        .trim()
}

package com.astrbot.lite.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI 对话引擎：OpenAI 兼容 /chat/completions 接口
 * 兼容 OpenAI / DeepSeek / 智谱(z.ai) / 火山方舟 / 自定义 等
 *
 * 1.7.237 内核修复（z.ai 时好时坏 / 动不动 timeout）：
 *  1. 改用 SSE 流式请求（stream=true）——首包很快到达，不会像非流式那样整段等生成完，
 *     readTimeout 只作用于“两次数据之间”，长回复不再整体超时（AstrBot 稳定也是因为它走流式）；
 *  2. 服务端若忽略 stream 直接回 JSON，自动回退按非流式解析；
 *  3. 瞬时失败（连接被重置 / 408 / 429 / 5xx）按 0.6s→1.8s 退避重试 3 次；
 *  4. 真·兜底：流式已收到部分内容后断流，直接返回已收到的部分，不再判为失败。
 */
object ChatEngine {
    private val json = "application/json; charset=utf-8".toMediaType()

    private val UA = "AstrBotLite/${com.astrbot.lite.BuildConfig.VERSION_NAME} (Android)"

    /** 浏览器 UA：部分 Cloudflare 站点会直接拦“非浏览器”UA，列模型时用它兜底重试 */
    private val UA_BROWSER =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 流式：两段数据之间的最长等待（首包/思考停顿），不是整段回复时长。
        // 1.7.246：60s → 120s。z.ai 过载时首包可能要排队 60s+，60s 会在“还没开始吐字”时判超时
        .readTimeout(120, TimeUnit.SECONDS)
        // 整段硬上限，防止持续吐脏数据的流把线程永久占住
        .callTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private class HttpException(
        val code: Int,
        val bodyText: String,
        /** 服务端 Retry-After（毫秒，0=未提供） */
        val retryAfterMs: Long = 0,
    ) : Exception(messageOf(code, bodyText)) {
        companion object {
            fun messageOf(code: Int, body: String): String {
                val obj = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    null
                }
                var msg = obj?.optString("message", "").orEmpty()
                // Cloudflare 的错误格式：{"success":false,"errors":[{"message":"..."}]}
                if (msg.isBlank()) {
                    val errs = obj?.optJSONArray("errors")
                    if (errs != null && errs.length() > 0) {
                        msg = errs.optJSONObject(0)?.optString("message", "").orEmpty()
                    }
                }
                return "HTTP $code: ${msg.ifBlank { body.take(160) }}"
            }
        }
    }

    /** 发起一次对话，返回模型回答文本 */
    fun chat(msgs: List<ChatMsg>): String {
        val base = Config.aiBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw Exception("未配置 API 地址")
        val apiKey = Config.aiApiKey.trim()
        if (apiKey.isEmpty()) throw Exception("未配置 API Key")
        val model = Config.aiModel.trim()
        if (model.isEmpty()) throw Exception("未配置模型名")
        Logs.info("[LLM] 请求 $model（消息 ${msgs.size} 条，temperature=${Config.aiTemperature}）")
        return complete(base, apiKey, model, sanitizeMsgs(msgs), Config.aiTemperature)
    }

    /** 快捷测试 */
    fun testChat(question: String): String = chat(listOf(ChatMsg("user", question)))

    fun chatWith(
        baseUrl: String,
        apiKey: String,
        model: String,
        msgs: List<ChatMsg>,
        temperature: Float = 0.8f,
    ): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw Exception("未配置 API 地址")
        if (apiKey.trim().isEmpty()) throw Exception("未配置 API Key")
        if (model.trim().isEmpty()) throw Exception("未配置模型名")
        Logs.info("[LLM] 请求 ${model.trim()}（chatWith，消息 ${msgs.size} 条）")
        return complete(base, apiKey.trim(), model.trim(), sanitizeMsgs(msgs), temperature)
    }

    /**
     * 对齐 AstrBot 的 `_sanitize_assistant_messages`：严格网关（GLM/DeepSeek Reasoner 等）
     * 遇到「content 为空的 assistant 消息」会直接 400。历史里如果混进上一次失败产生的空回复，
     * 会导致后续每一条消息都报错（表现就是“请求必定失败”）。这里发送前把空 assistant 过滤掉。
     */
    private fun sanitizeMsgs(msgs: List<ChatMsg>): List<ChatMsg> {
        val out = msgs.filterNot { it.role == "assistant" && it.content.isBlank() }
        return out.ifEmpty { msgs }
    }

    // ---------------- 内部：请求 + 重试 + 流式解析 ----------------

    /**
     * 重试策略对齐 AstrBot（astrbot/core/provider/sources/request_retry.py）：
     *  - 最多 5 次
     *  - 指数退避 wait_exponential(multiplier=1, min=0.2, max=30) → 1s、2s、4s、8s、16s（封顶 30s）
     *  - 可重试状态码 {408,409,429,500,502,503,504,529} 及全部 5xx
     *  - 连接类异常同样重试
     */
    private const val MAX_ATTEMPTS = 5

    private fun complete(
        base: String,
        apiKey: String,
        model: String,
        msgs: List<ChatMsg>,
        temperature: Float,
    ): String {
        var last: Exception? = null
        var stream = true
        var triedNonStream = false
        var altBase: String? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return attemptOnce(altBase ?: base, apiKey, model, msgs, temperature, stream)
            } catch (e: HttpException) {
                last = e
                // 个别网关不接受 stream —— 400/422 时改用非流式再试一次，避免“原本能用被流式改坏”
                if (stream && (e.code == 400 || e.code == 422) && !triedNonStream) {
                    Logs.warn("[LLM] 服务端拒绝流式（HTTP ${e.code}），改用非流式重试")
                    stream = false
                    triedNonStream = true
                    continue
                }
                // z.ai 的 Coding Plan 必须用 /api/coding/paas/v4，普通 /api/paas/v4 在套餐 key 上
                // 会表现为持续 429/1305（服务过载）。过载时自动改用 Coding 端点再试。
                if (e.code == 429 && altBase == null) {
                    val alt = zaiCodingBase(base)
                    if (alt != null) {
                        altBase = alt
                        Logs.warn("[LLM] z.ai 持续 429，改用 Coding Plan 端点重试：$alt")
                        continue
                    }
                }
                if (!retriable(e.code) || attempt == MAX_ATTEMPTS) throw e
                backoff(attempt, e.code, e.message ?: "", e.retryAfterMs)
            } catch (e: IOException) {
                last = e
                if (attempt == MAX_ATTEMPTS) throw e
                backoff(attempt, null, e.message ?: e.javaClass.simpleName)
            }
        }
        throw last ?: Exception("未知错误")
    }

    /** z.ai 普通端点 → Coding Plan 端点（仅当 base 是 api.z.ai/api/paas/v4 时） */
    private fun zaiCodingBase(base: String): String? {
        if (!base.contains("api.z.ai")) return null
        if (base.contains("/api/coding/")) return null
        if (!base.contains("/api/paas/v4")) return null
        return base.replace("/api/paas/v4", "/api/coding/paas/v4")
    }

    private fun retriable(code: Int): Boolean = code in setOf(408, 409, 429, 500, 502, 503, 504, 529) || code in 500..599

    private fun backoff(attempt: Int, code: Int?, reason: String, retryAfterMs: Long = 0) {
        // AstrBot 的 wait_exponential：multiplier=1, min=0.2, max=30 → 1s/2s/4s/8s/16s
        var ms = minOf(30_000L, 1_000L * (1L shl (attempt - 1)))
        // 服务端给了 Retry-After 时按它来（最多 30s）
        if (retryAfterMs > 0) ms = maxOf(ms, retryAfterMs.coerceAtMost(30_000L))
        // 429：给全 App 设一段冷却，其它功能（内心思考/总结…）也会在 attemptOnce 入口等待
        if (code == 429) pauseUntil = System.currentTimeMillis() + ms
        Logs.warn("[LLM] 第 $attempt 次请求失败（$reason），${ms}ms 后重试")
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 1.7.252：去掉全局串行化。
     * 之前用 Semaphore(1) 把请求排队，一旦前一个请求卡住（思考模型首包很慢/连接挂住），
     * 后一条消息会**静默等锁**——日志停在「[LLM] 请求 …」之后再无任何输出（用户实测）。
     * 现在请求不再排队（与 AstrBot 一致）；只保留 429 的短冷却，并且等待时会打日志。
     */
    @Volatile private var pauseUntil = 0L

    private fun attemptOnce(
        base: String,
        apiKey: String,
        model: String,
        msgs: List<ChatMsg>,
        temperature: Float,
        stream: Boolean,
    ): String {
        val wait = pauseUntil - System.currentTimeMillis()
        if (wait > 0) {
            Logs.info("[LLM] 上次 429 冷却中，等待 ${wait / 1000}s 后再发请求")
            try {
                Thread.sleep(minOf(wait, 15_000L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return attemptOnceInner(base, apiKey, model, msgs, temperature, stream)
    }

    private fun attemptOnceInner(
        base: String,
        apiKey: String,
        model: String,
        msgs: List<ChatMsg>,
        temperature: Float,
        stream: Boolean,
    ): String {
        val arr = JSONArray()
        msgs.forEach { arr.put(it.toJson()) }
        val body = JSONObject()
            .put("model", model)
            .put("messages", arr)
            .put("temperature", temperature.toDouble())
            .put("stream", stream)
        // 对齐 AstrBot：流式时要求附带 usage 帧（stream_options.include_usage）
        // Workers AI 的兼容层不认这个字段，跳过
        if (stream && !isCfAiBase(base)) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }

        val endpoint = if (base.contains("/chat/completions")) base else "$base/chat/completions"
        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream, application/json")
            .header("User-Agent", UA)
            .post(RequestBody.create(json, body.toString()))
            .build()

        Logs.debug("[LLM] POST ${endpoint.take(100)}")
        val t0 = System.currentTimeMillis()
        val resp = http.newCall(req).execute()
        resp.use { r ->
            if (!r.isSuccessful) {
                val text = try {
                    r.body?.string().orEmpty()
                } catch (_: Exception) {
                    ""
                }
                Logs.debug("[LLM] 失败响应体(HTTP ${r.code}): ${text.take(500)}")
                val ra = r.header("Retry-After")?.trim()?.toLongOrNull()?.let { it * 1000L } ?: 0L
                throw HttpException(r.code, text, ra)
            }
            val source = r.body?.source() ?: throw IOException("响应为空")
            val firstLine = try {
                source.readUtf8Line() ?: ""
            } catch (e: IOException) {
                throw e
            }
            if (firstLine.isNotBlank()) {
                // 有这一行说明服务端确实开始回包了；停在「[LLM] 请求」之后没有它 = 卡在等首包/连接
                Logs.info("[LLM] 首包已到（${(System.currentTimeMillis() - t0) / 1000}s）")
            }
            val trimmedFirst = firstLine.trim()
            Logs.debug("[LLM] 首行：${trimmedFirst.take(200)}")
            val sseLike = trimmedFirst.startsWith("data:") ||
                trimmedFirst.startsWith("event:") ||
                trimmedFirst.startsWith(":")
            // 有的网关不给 "data:" 前缀，直接一行一个 JSON（NDJSON）；按里面有没有 delta 识别
            val ndjsonLike = trimmedFirst.startsWith("{") && trimmedFirst.contains("\"delta\"")
            if (!sseLike && !ndjsonLike) {
                // 服务端没走流式（或首行就是 JSON 开头）：整体按非流式解析
                val rest = try {
                    source.readUtf8()
                } catch (_: IOException) {
                    ""
                }
                val text = firstLine + (if (rest.isEmpty()) "" else "\n" + rest)
                val content = parseNonStream(text)
                val cost = (System.currentTimeMillis() - t0) / 1000
                Logs.info("[LLM] 响应 ${cost}s（非流式），输出 ${content.length} 字")
                return content
            }

            val sb = StringBuilder()
            var lines = 0
            var finish = ""
            var reasoningChars = 0
            var gotContent = false
            var line: String? = firstLine
            try {
                while (line != null) {
                    val l = (line as String).trim()
                    val data = when {
                        l.startsWith("data:") -> l.removePrefix("data:").trim()
                        l.startsWith("{") -> l
                        else -> ""
                    }
                    var done = false
                    if (data == "[DONE]") break
                    if (data.isNotEmpty()) {
                        val rsn = IntArray(1)
                        val fr = collectDelta(data, sb, rsn)
                        reasoningChars += rsn[0]
                        // 收到 finish_reason 就已经收齐了：立即收尾，不等 [DONE]
                        // （有的网关不发 [DONE]/不主动关流，干等会一直卡到 120s 读超时）
                        if (fr.isNotBlank()) {
                            finish = fr
                            done = true
                        }
                    }
                    lines++
                    val cost = (System.currentTimeMillis() - t0) / 1000
                    if (!gotContent && sb.isNotEmpty()) {
                        gotContent = true
                        Logs.info("[LLM] 正文首字已到（${cost}s，此前思考 $reasoningChars 字）")
                    }
                    // 思考阶段每 10 帧报一次进度：让“首包已到之后没动静”能看出是在思考还是真卡住
                    if (!gotContent && lines > 0 && lines % 10 == 0) {
                        Logs.info("[LLM] 思考中：$lines 帧 / $reasoningChars 字（${cost}s）")
                    }
                    if (done) break
                    line = source.readUtf8Line()
                }
            } catch (e: IOException) {
                // 已收到部分内容：当作成功返回（断流重试会重复已输出的文字）
                if (sb.isNotEmpty()) {
                    Logs.warn("[LLM] 流式断流，已收到 ${sb.length} 字，按部分结果返回：${e.message}")
                } else {
                    throw e
                }
            }
            val rawText = sb.toString()
            val content = stripThinking(rawText)
            if (rawText.length - content.length > 20) {
                Logs.debug("[LLM] 已剥离思考内容 ${rawText.length - content.length} 字（<think> 标签）")
            }
            // 200 但内容为空（服务端只回了 role/心跳帧，或 reasoning 用尽）→ 当可重试失败，
            // 否则上层会回“AI 返回空内容”的兜底文案（用户实测：glm 偶发一次空包后直接兜底）
            if (content.isEmpty()) {
                throw IOException("模型返回空内容（流式 $lines 帧，finish=${finish.ifBlank { "无" }}）")
            }
            val cost = (System.currentTimeMillis() - t0) / 1000
            Logs.info("[LLM] 响应 ${cost}s（流式 $lines 帧），输出 ${content.length} 字")
            return content
        }
    }

    private fun collectDelta(data: String, sb: StringBuilder, reasoning: IntArray): String {
        try {
            val o = JSONObject(data)
            val err = o.optJSONObject("error")
            if (err != null) {
                throw HttpException(o.optInt("code", 500), err.toString())
            }
            val choices = o.optJSONArray("choices") ?: return ""
            var fr = ""
            for (i in 0 until choices.length()) {
                val choice = choices.optJSONObject(i) ?: continue
                if (!choice.isNull("finish_reason")) {
                    val f = choice.optString("finish_reason", "")
                    if (f.isNotBlank() && f != "null") fr = f
                }
                val delta = choice.optJSONObject("delta") ?: continue
                // 思考流（GLM/DeepSeek/MiniMax 等）：只统计长度用于进度日志，不拼进回复
                val rc = delta.optString("reasoning_content", "")
                if (rc.isNotEmpty()) reasoning[0] += rc.length
                if (!delta.isNull("content")) {
                    val c = delta.optString("content", "")
                    if (c.isNotEmpty()) sb.append(c)
                }
            }
            return fr
        } catch (e: HttpException) {
            throw e
        } catch (_: Exception) {
            // 心跳帧 / 不完整分片：忽略
            return ""
        }
    }

    private fun parseNonStream(text: String): String {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("响应不是合法 JSON：${text.take(120)}")
        }
        val choices = obj.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            val err = obj.optString("error", "")
            if (err.isNotBlank()) throw IOException("模型返回错误：${err.take(200)}")
            throw IOException("模型返回为空：${text.take(160)}")
        }
        val content = stripThinking(
            (choices.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: ""),
        )
        if (content.isEmpty()) throw IOException("模型返回空内容（非流式）")
        return content
    }

    // ---------- 思考标签剥离（对齐 AstrBot 的 <think>/<thinking> 处理） ----------
    private val THINK_BLOCK = Regex(
        "<(?:think|thinking)>.*?</(?:think|thinking)>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val THINK_TAIL = Regex("</(?:think|thinking)>\\s*$", RegexOption.IGNORE_CASE)
    private val THINK_OPEN = Regex("<(?:think|thinking)>", RegexOption.IGNORE_CASE)

    /**
     * 有些模型（GLM/DeepSeek 等）把思考过程直接写在 content 里，形如
     * `<think>…</think>正文`。QQ 里不能把思考发出去，发送前统一剥掉：
     *  - 成对的 `<think>…</think>` 整段删除；
     *  - 结尾残留的 `</think>` 删除；
     *  - 只有开标签没有闭标签（被 max_tokens 截断）→ 从开标签起到结尾都当思考丢弃。
     */
    private fun stripThinking(raw: String): String {
        var t = THINK_BLOCK.replace(raw, "")
        t = THINK_TAIL.replace(t, "")
        val open = THINK_OPEN.find(t)
        if (open != null) t = t.substring(0, open.range.first)
        return t.trim()
    }

    /** Cloudflare Workers AI 的 OpenAI 兼容地址 */
    private fun isCfAiBase(base: String): Boolean = base.contains("api.cloudflare.com/client/v4/accounts/") && base.contains("/ai/v1")

    private fun cfErrorText(o: JSONObject): String {
        val errs = o.optJSONArray("errors")
        if (errs != null && errs.length() > 0) {
            return errs.optJSONObject(0)?.optString("message", "").orEmpty()
        }
        return o.optString("message", "")
    }

    /** Workers AI 的模型列表不走 /models，而是 /ai/models/search；模型名是 @cf/... */
    private fun cloudflareModels(base: String, key: String): String {
        val root = base.substringBefore("/ai/v1")
        val url = "$root/ai/models/search?per_page=100"
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .header("User-Agent", UA_BROWSER)
                .get()
                .build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    return "获取失败: HTTP ${r.code}: ${text.take(160)}"
                }
                val obj = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    return "获取失败: 返回不是 JSON（${text.take(80)}）"
                }
                if (!obj.optBoolean("success", true)) {
                    return "获取失败: ${cfErrorText(obj).ifBlank { text.take(120) }}"
                }
                val result = obj.opt("result")
                val arr = when (result) {
                    is JSONArray -> result
                    is JSONObject -> result.optJSONArray("data") ?: JSONArray()
                    else -> JSONArray()
                }
                val models = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val name = m.optString("name")
                    val id = m.optString("id")
                    val model = when {
                        name.startsWith("@cf/") -> name
                        id.startsWith("@cf/") -> id
                        name.isNotBlank() -> name
                        else -> id
                    }
                    if (model.isBlank()) continue
                    // 只保留文本生成类，避免把 embedding/图像模型也列出来
                    val task = m.optJSONObject("task")
                    val taskText = task?.let {
                        (it.optString("id") + " " + it.optString("name")).lowercase()
                    }.orEmpty()
                    if (task != null &&
                        taskText.isNotBlank() &&
                        !taskText.contains("text generation") &&
                        !taskText.contains("text-generation")
                    ) {
                        continue
                    }
                    models.add(model)
                }
                if (models.isEmpty()) {
                    "无可用模型"
                } else {
                    models.distinct().sorted().joinToString("\n") +
                        "\n\n提示：Workers AI 的对话模型名必须以 @cf/ 开头"
                }
            }
        } catch (e: Exception) {
            "获取失败: ${e.message}"
        }
    }

    /** Cloudflare 拦截的特征：cf-mitigated 头，或 text/html 的验证页 */
    private fun looksLikeCloudflare(r: Response, text: String): Boolean {
        if (r.header("cf-mitigated") != null) return true
        val ct = r.header("Content-Type").orEmpty().lowercase()
        if (!ct.contains("text/html")) return false
        val t = text.take(2000).lowercase()
        return t.contains("just a moment") ||
            t.contains("cloudflare") ||
            t.contains("attention required")
    }

    fun listModels(baseUrl: String, apiKey: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return "无"
        val key = apiKey.trim()
        // Cloudflare Workers AI：模型列表在 /ai/models/search，不在 /models
        if (isCfAiBase(base)) return cloudflareModels(base, key)
        var lastErr = ""
        // 第一次用 APP UA；若被 Cloudflare 拦，换浏览器头再试一次
        val uas = listOf(UA, UA_BROWSER)
        for ((i, ua) in uas.withIndex()) {
            val req = Request.Builder()
                .url("$base/models")
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", ua)
                .apply {
                    if (i > 0) {
                        header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        header("Cache-Control", "no-cache")
                        header("Pragma", "no-cache")
                    }
                }
                .get()
                .build()
            try {
                http.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (looksLikeCloudflare(r, text)) {
                        lastErr = "被 Cloudflare 拦截/验证（HTTP ${r.code}）"
                        return@use
                    }
                    if (!r.isSuccessful) {
                        return "获取失败: HTTP ${r.code}: ${text.take(120)}"
                    }
                    val obj = try {
                        JSONObject(text)
                    } catch (_: Exception) {
                        return "获取失败: 返回不是 JSON（${text.take(80)}）"
                    }
                    val arr = obj.optJSONArray("data") ?: return "无可用模型"
                    val models = mutableListOf<String>()
                    for (k in 0 until arr.length()) {
                        val id = arr.optJSONObject(k)?.optString("id") ?: continue
                        models.add(id)
                    }
                    return if (models.isEmpty()) "无可用模型" else models.joinToString("\n")
                }
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
        }
        return "获取失败：$lastErr\n该接口被 Cloudflare 保护，无法自动列取，请手动填写模型名"
    }
}

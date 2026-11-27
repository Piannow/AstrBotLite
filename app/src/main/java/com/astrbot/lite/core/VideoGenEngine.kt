package com.astrbot.lite.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI 生视频引擎。
 * 各家视频接口形态不一，这里兼容两种：
 * ① 同步形态：提交后响应里直接给视频直链（data[0].url / video_url / results.videos[0].url 等）→ 直接下载 mp4
 * ② 异步形态：提交后返回任务ID（id / requestId / task_id）→ 轮询任务状态，出直链后下载
 * 生成耗时较长，调用方应放后台线程（PluginManager 的 video 插件已是异步生成）。
 */
object VideoGenEngine {
    private val json = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    /** 轮询参数：每 6 秒查一次，最多 50 次（约 5 分钟） */
    private const val POLL_INTERVAL_MS = 6000L
    private const val POLL_MAX = 50

    /**
     * 链接模式（1.7.207 补齐入口）：返回生成结果的直链，供「发链接」而不是上传文件。
     *
     * 实现说明：当前引擎只提供"生成二进制"链路（[generate]），没有可复用的取链接接口。
     * 这里如实返回 null（调用方已有失败提示），避免伪造一个不可播的地址。
     * 若后续接入支持返回 URL 的视频服务，在此实现即可。
     */
    fun generateUrl(prompt: String, pluginId: String = "videogen"): String? {
        Logs.warn("[AI生视频] 链接模式未实现（$pluginId）：当前引擎仅支持生成文件后上传")
        return null
    }

    /** 读取配置：AI 池优先（mediaEntry），否则读插件配置 */
    fun config(pluginId: String = "videogen"): ImageGenEngine.GenConfig {
        val pool = AiPool.mediaEntry("video")
        if (pool != null) {
            val m = PluginManager.configMap(pluginId)
            return ImageGenEngine.GenConfig(
                baseUrl = pool.baseUrl,
                apiKey = pool.apiKey,
                model = pool.model,
                size = m["size"]?.trim()?.takeIf { it.isNotBlank() } ?: "1280x720",
            )
        }
        val m = PluginManager.configMap(pluginId)
        return ImageGenEngine.GenConfig(
            baseUrl = m["baseUrl"]?.trim().orEmpty(),
            apiKey = m["apiKey"]?.trim().orEmpty(),
            model = m["model"]?.trim().orEmpty(),
            size = m["size"]?.trim()?.takeIf { it.isNotBlank() } ?: "1280x720",
        )
    }

    /** 生成视频：返回 mp4 字节；未配置/失败返回 null */
    fun generate(prompt: String, pluginId: String = "videogen"): ByteArray? {
        val clean = prompt.trim()
        if (clean.isBlank()) return null
        val cfg = config(pluginId)
        if (cfg.baseUrl.isBlank() || cfg.apiKey.isBlank() || cfg.model.isBlank()) {
            Logs.warn("[AI生视频] 未配置视频生成服务（服务地址/密钥/模型）")
            return null
        }
        return try {
            val base = cfg.baseUrl.trim().trimEnd('/')
            // 服务地址可填到 /video/ 一级（如 .../v1/video/submit），也可只填根（自动补 /video/generations 再退 /video/submit）
            val endpoints = if (base.contains("/video/")) {
                listOf(base)
            } else {
                listOf("$base/video/generations", "$base/video/submit")
            }
            val body = JSONObject()
                .put("model", cfg.model)
                .put("prompt", clean)
                .put("size", cfg.size)
            var text: String? = null
            var endpoint = endpoints.first()
            for (ep in endpoints) {
                Logs.info("[AI生视频] 提交任务：$ep（模型 ${cfg.model}）")
                val t = postJson(ep, cfg.apiKey, body)
                if (t != null) {
                    text = t
                    endpoint = ep
                    break
                }
            }
            if (text == null) return null
            val root = try {
                JSONObject(text)
            } catch (_: Exception) {
                Logs.warn("[AI生视频] 响应不是 JSON：${text.take(200)}")
                return null
            }
            // ① 同步形态：响应里直接有视频直链
            findVideoUrl(root)?.let { url ->
                Logs.info("[AI生视频] 同步返回视频直链，开始下载")
                return download(url)
            }
            // ② 异步形态：拿任务ID轮询
            val taskId = findTaskId(root)
            if (taskId == null) {
                Logs.warn("[AI生视频] 响应里既没有视频直链也没有任务ID：${text.take(200)}")
                return null
            }
            Logs.info("[AI生视频] 任务已提交（$taskId），开始轮询")
            poll(taskId, base, endpoint, cfg.apiKey)
        } catch (e: Exception) {
            Logs.warn("[AI生视频] 生成异常: ${e.message}")
            null
        }
    }

    /** 轮询任务状态：出直链就下载；任务失败/超时返回 null */
    private fun poll(taskId: String, base: String, submitEndpoint: String, apiKey: String): ByteArray? {
        repeat(POLL_MAX) { i ->
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return null
            }
            val text = queryStatus(base, submitEndpoint, taskId, apiKey) ?: return@repeat
            val root = try {
                JSONObject(text)
            } catch (_: Exception) {
                return@repeat
            }
            findVideoUrl(root)?.let { url ->
                Logs.info("[AI生视频] 第 ${i + 1} 次轮询：视频已生成，开始下载")
                return download(url)
            }
            val st = statusText(root)
            if (st != null && st.lowercase() in setOf("failed", "fail", "error", "cancelled", "canceled")) {
                Logs.warn("[AI生视频] 任务失败（$st）：${text.take(160)}")
                return null
            }
            if (i % 5 == 0) Logs.info("[AI生视频] 第 ${i + 1} 次轮询：生成中${if (st != null) "（$st）" else ""}")
        }
        Logs.warn("[AI生视频] 轮询超时（约 ${POLL_MAX * POLL_INTERVAL_MS / 1000} 秒），放弃")
        return null
    }

    /** 查任务状态：依次尝试常见的几种任务查询形态，返回响应文本（都不通返回 null） */
    private fun queryStatus(base: String, submitEndpoint: String, taskId: String, apiKey: String): String? {
        // ① 智谱风格：GET {base}/async-result/{id}
        getText("$base/async-result/$taskId", apiKey)?.let { return it }
        // ② SiliconFlow 风格：POST {base}/video/status（body 带 requestId）
        val b = JSONObject().put("requestId", taskId).put("task_id", taskId)
        postJson("$base/video/status", apiKey, b)?.let { return it }
        // ③ 通用：GET {提交地址}/{id}
        return getText("$submitEndpoint/$taskId", apiKey)
    }

    /** 从响应里找视频直链：优先路径带 video 的，其次 .mp4 直链，最后兜底 url 字段（排除更像图片/封面的） */
    private fun findVideoUrl(root: JSONObject): String? {
        val out = mutableListOf<Pair<String, String>>()
        collectUrls(root, "", 0, out)
        if (out.isEmpty()) return null
        out.firstOrNull { it.first.contains("video", true) }?.let { return it.second }
        out.firstOrNull { it.second.contains(".mp4", true) }?.let { return it.second }
        return out.firstOrNull {
            it.first.endsWith("url", true) &&
                !it.second.contains("image", true) &&
                !it.second.contains("cover", true)
        }?.second
    }

    /** 递归收集所有 http 开头的字符串字段，key 记录完整路径（如 data.0.url / results.videos.0.url） */
    private fun collectUrls(node: Any?, path: String, depth: Int, out: MutableList<Pair<String, String>>) {
        if (depth > 6) return
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    val p = if (path.isEmpty()) k else "$path.$k"
                    if (v is String) {
                        if (v.startsWith("http")) out.add(p to v)
                    } else {
                        collectUrls(v, p, depth + 1, out)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val p = if (path.isEmpty()) "$i" else "$path.$i"
                collectUrls(node.opt(i), p, depth + 1, out)
            }
        }
    }

    /** 找任务ID：常见的 id / task_id / requestId 等字段，只在顶层与 data 层找，避免误取别的 id */
    private fun findTaskId(root: JSONObject): String? {
        val keys = listOf("task_id", "taskId", "request_id", "requestId", "job_id", "jobId", "id")
        for (k in keys) {
            val v = root.optString(k, "")
            if (v.isNotBlank()) return v
        }
        val data = root.optJSONObject("data") ?: return null
        for (k in keys) {
            val v = data.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return null
    }

    /** 任务状态文本（用于判断失败/记录日志） */
    private fun statusText(root: JSONObject): String? {
        val keys = listOf("task_status", "taskStatus", "status", "state")
        for (k in keys) {
            val v = root.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return null
    }

    private fun download(url: String): ByteArray? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (AstrBotLite)")
                .get()
                .build()
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) {
                Logs.warn("[AI生视频] 下载视频失败 HTTP ${resp.code}")
                resp.close()
                return null
            }
            val bytes = resp.body?.bytes()
            if (bytes == null || bytes.isEmpty()) {
                Logs.warn("[AI生视频] 下载到的视频为空")
                return null
            }
            Logs.info("[AI生视频] 视频已下载：${bytes.size / 1024}KB")
            bytes
        } catch (e: Exception) {
            Logs.warn("[AI生视频] 下载视频异常: ${e.message}")
            null
        }
    }

    private fun postJson(url: String, apiKey: String, body: JSONObject): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json, body.toString()))
                .build()
            val resp = http.newCall(req).execute()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Logs.warn("[AI生视频] POST 失败 HTTP ${resp.code}：${text.take(160)}")
                return null
            }
            text
        } catch (e: Exception) {
            Logs.warn("[AI生视频] POST 异常: ${e.message}")
            null
        }
    }

    private fun getText(url: String, apiKey: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) return null
            resp.body?.string()
        } catch (_: Exception) {
            null
        }
    }
}

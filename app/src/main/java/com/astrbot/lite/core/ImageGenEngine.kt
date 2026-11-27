package com.astrbot.lite.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 联网文生图引擎（OpenAI 兼容 /images/generations）：
 * 支持 Agnes 中国站及任何提供 OpenAI 兼容出图接口的服务。
 * 参数来自内置 text2image 插件配置（baseUrl / apiKey / model / size / responseFormat）。
 * 出图较慢，读超时放宽到 120s。
 */
object ImageGenEngine {
    private val json = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(140, TimeUnit.SECONDS)
        .build()

    data class GenConfig(
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = "",
        val size: String = "1024x1024",
        val responseFormat: String = "",
    ) {
        val valid: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    }

    /** 读取配置：AI 池优先，但池条目缺字段时逐项回落插件配置（避免“池里只填一半”导致整条链路不可用） */
    fun config(pluginId: String = "text2image"): GenConfig {
        val m = PluginManager.configMap(pluginId)
        val pluginCfg = GenConfig(
            baseUrl = m["baseUrl"]?.trim().orEmpty(),
            apiKey = m["apiKey"]?.trim().orEmpty(),
            model = m["model"]?.trim().orEmpty(),
            size = m["size"]?.trim()?.takeIf { it.isNotBlank() } ?: "1024x1024",
            responseFormat = m["responseFormat"]?.trim().orEmpty(),
        )
        val pool = AiPool.mediaEntry("image") ?: return pluginCfg
        return GenConfig(
            baseUrl = pool.baseUrl.ifBlank { pluginCfg.baseUrl },
            apiKey = pool.apiKey.ifBlank { pluginCfg.apiKey },
            model = pool.model.ifBlank { pluginCfg.model },
            size = pluginCfg.size,
            responseFormat = pluginCfg.responseFormat,
        )
    }

    /** 端点拼装：用户把完整端点贴进 baseUrl（…/chat/completions、…/images/generations）时先剥掉再拼 */
    private fun endpointFor(baseUrl: String, path: String): String {
        var base = baseUrl.trim().trimEnd('/')
        for (tail in listOf("/chat/completions", "/images/generations", "/images/edits")) {
            val i = base.indexOf(tail)
            if (i >= 0) base = base.substring(0, i)
        }
        return base + path
    }

    /** 发 JSON 请求：瞬时失败（429/5xx/网络异常）重试 3 次，返回响应体原文 */
    private fun executeJson(url: String, apiKey: String, body: JSONObject): String? {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(RequestBody.create(json, body.toString()))
            .build()
        for (attempt in 1..3) {
            var retry = false
            try {
                http.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        Logs.warn("[文生图] HTTP ${r.code}: ${text.take(160)}")
                        retry = r.code == 429 || r.code >= 500
                    } else {
                        return text
                    }
                }
            } catch (e: Exception) {
                Logs.warn("[文生图] 请求异常: ${e.message}")
                retry = true
            }
            if (!retry || attempt == 3) return null
            try {
                Thread.sleep(if (attempt == 1) 500L else 1500L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    /** 文生图：返回 (图片字节, 扩展名)；失败返回 null */
    fun generate(prompt: String, pluginId: String = "text2image"): Pair<ByteArray, String>? {
        val clean = prompt.trim()
        if (clean.isBlank()) return null
        val cfg = config(pluginId)
        if (!cfg.valid) {
            Logs.warn("[文生图] 未配置文生图服务（需在 $pluginId 插件配置中填 baseUrl / apiKey / model）")
            return null
        }
        return try {
            val body = JSONObject()
                .put("model", cfg.model)
                .put("prompt", clean)
                .put("n", 1)
                .put("size", cfg.size)
            // 服务端 response_format 只接受返回方式 b64/url；
            // 仅当用户把 responseFormat 填成 b64/url 时才下发（强制返回方式）；
            // 填的是 png/jpg 等输出格式时不下发——避免 400，服务端按其默认返回（通常给 url，我们照常下载）。
            val rf = cfg.responseFormat.trim().lowercase()
            when (rf) {
                "b64", "b64_json", "url" -> body.put("response_format", if (rf == "b64_json") "b64" else rf)
                else -> { }
            }

            val endpoint = endpointFor(cfg.baseUrl, "/images/generations")
            val text = executeJson(endpoint, cfg.apiKey, body) ?: return null
            val r = parseImageBody(text, cfg.responseFormat)
            if (r == null) {
                Logs.warn("[文生图] 响应里没有图（b64/url 都缺）：${text.take(200)}")
            } else {
                Logs.info("[文生图] 出图成功 ${r.first.size} 字节 ${r.second}")
            }
            r
        } catch (e: Exception) {
            Logs.warn("[文生图] 出图异常: ${e.message}")
            null
        }
    }

    /**
     * 图生图：三级级联，逐个尝试（4xx 快速失败换下一种，日志逐步记录）：
     * ① /images/edits —— OpenAI 标准图生图（multipart 带参考图）
     * ② /images/generations + image 参数 —— Agnes 等网关对图模型指定的端点（实测400提示"请使用 /v1/images/generations"）
     * ③ /chat/completions 多模态 —— 聊天式出图模型（带 image_url 内容）
     */
    fun generateFromImage(imageBytes: ByteArray, prompt: String, pluginId: String = "image2image"): Pair<ByteArray, String>? {
        val clean = prompt.trim()
        if (clean.isBlank() || imageBytes.isEmpty()) return null
        val cfg = config(pluginId)
        if (!cfg.valid) {
            Logs.warn("[图生图] 未配置图生图服务（需在 $pluginId 插件配置中填 baseUrl / apiKey / model）")
            return null
        }
        // 级联顺序（1.7.68）：generations 优先——语义是“按参考图生成全新一张图”，也是 agnes 等网关指定的
        // image 模型入口；images/edits 是“编辑原图”语义，容易把参考图原样重画出来（用户要的是换内容保画风）。
        tryGenerationsWithImage(cfg, imageBytes, clean)?.let { return it }
        tryImagesEdits(cfg, imageBytes, clean)?.let { return it }
        return tryChatWithImage(cfg, imageBytes, clean, pluginId)
    }

    /** 图生图一站式（1.7.91）：参考图 URL → 下载字节 → 三级级联（画图带图/残留 image2image 共用） */
    fun generateFromImageUrl(imageUrl: String, prompt: String, pluginId: String, ctx: android.content.Context? = null): Pair<ByteArray, String>? {
        val url = imageUrl.trim()
        if (url.isBlank()) return null
        val bytes = download(url)
        if (bytes == null || bytes.isEmpty()) {
            Logs.warn("[图生图] 参照图下载失败: ${url.take(100)}")
            return null
        }
        Logs.info("[图生图] 参照图下载成功 ${bytes.size} 字节 → 三级级联")
        return generateFromImage(bytes, prompt, pluginId)
    }

    /** ① /images/edits（multipart：image 文件 + prompt） */
    private fun tryImagesEdits(cfg: GenConfig, imageBytes: ByteArray, clean: String): Pair<ByteArray, String>? {
        return try {
            val base = cfg.baseUrl.trim().trimEnd('/')
            val endpoint = endpointFor(base, "/images/edits")
            val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("model", cfg.model)
                .addFormDataPart("image", "ref.png", RequestBody.create("image/png".toMediaType(), imageBytes))
                .addFormDataPart("prompt", clean)
                .addFormDataPart("n", "1")
                .addFormDataPart("size", cfg.size)
                .build()
            val resp = http.newCall(
                Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer ${cfg.apiKey}")
                    .post(body).build(),
            ).execute()
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Logs.warn("[图生图] ①images/edits HTTP ${resp.code}: ${bodyStr.take(160)}")
                return null
            }
            val r = parseImageBody(bodyStr, cfg.responseFormat)
            if (r == null) Logs.warn("[图生图] ①images/edits 响应无图：${bodyStr.take(160)}")
            r
        } catch (e: Exception) {
            Logs.warn("[图生图] ①images/edits 异常: ${e.message}")
            null
        }
    }

    /** ② /images/generations + image 参数（data URL 形式的参考图） */
    private fun tryGenerationsWithImage(cfg: GenConfig, imageBytes: ByteArray, clean: String): Pair<ByteArray, String>? {
        return try {
            val b64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
            val body = JSONObject()
                .put("model", cfg.model)
                .put("prompt", clean)
                .put("n", 1)
                .put("size", cfg.size)
                .put("image", "data:image/png;base64,$b64Image")
            val endpoint = endpointFor(cfg.baseUrl, "/images/generations")
            val bodyStr = executeJson(endpoint, cfg.apiKey, body) ?: return null
            val r = parseImageBody(bodyStr, cfg.responseFormat)
            if (r == null) Logs.warn("[图生图] ②generations 响应无图：${bodyStr.take(160)}")
            r
        } catch (e: Exception) {
            Logs.warn("[图生图] ②generations 异常: ${e.message}")
            null
        }
    }

    /** ③ /chat/completions 多模态（text + image_url 内容，回复里找图；找不到图走两段式兜底） */
    private fun tryChatWithImage(cfg: GenConfig, imageBytes: ByteArray, clean: String, pluginId: String): Pair<ByteArray, String>? {
        return try {
            val b64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
            val b64 = JSONObject()
                .put("b64_json", b64Image)
                .put("url", "data:image/png;base64,$b64Image")
            val contentArr = org.json.JSONArray()
            contentArr.put(org.json.JSONObject().put("type", "text").put("text", clean))
            contentArr.put(org.json.JSONObject().put("type", "image_url").put("image_url", b64))
            val body = JSONObject()
                .put("model", cfg.model)
                .put(
                    "messages",
                    org.json.JSONArray().put(
                        org.json.JSONObject().put("role", "user").put("content", contentArr),
                    ),
                )
                .put("max_tokens", 4096)
            val endpoint = endpointFor(cfg.baseUrl, "/chat/completions")
            val req = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json, body.toString()))
                .build()
            val resp = http.newCall(req).execute()
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Logs.warn("[图生图] ③chat/completions HTTP ${resp.code}: ${bodyStr.take(160)}")
                return null
            }
            val root = JSONObject(bodyStr)
            val text = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "").orEmpty()
            val imageUrlRegex = Regex("https?://[^\\s)\"]+\\.(?:png|jpg|jpeg|gif|webp|bmp)", RegexOption.IGNORE_CASE)
            val match = imageUrlRegex.find(text)
            if (match != null) {
                val bytes = download(match.value) ?: return null
                Logs.info("[图生图] ③从回复URL下载成功 ${bytes.size} 字节")
                return bytes to imageExt(cfg.responseFormat, match.value)
            }
            // 从回复里找 b64_json 图（部分多模态模型直接回 base64）
            val b64Match = Regex("b64_json\"?\\s*[:=]\\s*\"?([A-Za-z0-9+/=]{50,})").find(text)
            if (b64Match != null) {
                val bytes: ByteArray? = try {
                    android.util.Base64.decode(b64Match.groupValues[1], android.util.Base64.DEFAULT)
                } catch (_: Exception) {
                    null
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    Logs.info("[图生图] ③从 b64_json 解码成功 ${bytes.size} 字节")
                    return bytes to "png"
                }
            }
            // 1.7.91 两段式兜底：多模态模型读懂参考图后常回“文字描述”而非图——
            // 把其描述与用户创作要求拼成文生图 prompt 再出图（不依赖图生图专用端点，成功率最高）
            Logs.warn("[图生图] ③模型回复（无图URL）：${text.take(200)}")
            val desc = text.replace(Regex("[\\r\\n]+"), " ").trim()
            val refusal = Regex("^(无法|抱歉|不能|对不起|我无法|很抱歉)").containsMatchIn(desc)
            if (desc.length >= 20 && !refusal) {
                Logs.info("[图生图] ③两段式兜底：vision 描述 ${desc.take(60)}… → 文生图")
                val twoStage = generate("${desc.take(500)}；创作要求：$clean", pluginId)
                if (twoStage != null) {
                    Logs.info("[图生图] ③两段式兜底出图成功 ${twoStage.first.size} 字节")
                    return twoStage
                }
                Logs.warn("[图生图] ③两段式兜底文生图失败")
            }
            return null
        } catch (e: Exception) {
            Logs.warn("[图生图] ③chat/completions 异常: ${e.message}")
            null
        }
    }

    /** 解析 images 接口标准响应：data[0].b64_json / data[0].url（兼容根级 url） */
    private fun parseImageBody(bodyStr: String, responseFormat: String): Pair<ByteArray, String>? {
        return try {
            val root = JSONObject(bodyStr)
            val item = root.optJSONArray("data")?.optJSONObject(0)
            val b64 = (item?.optString("b64_json", "") ?: "").ifBlank { item?.optString("b64", "") ?: "" }
            if (b64.isNotBlank()) {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                if (bytes.isNotEmpty()) return bytes to imageExt(responseFormat)
            }
            val url = (item?.optString("url", "") ?: "").ifBlank { root.optString("url", "") }
            if (url.isNotBlank()) {
                val bytes = download(url) ?: return null
                return bytes to imageExt(responseFormat, url)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun imageExt(responseFormat: String, url: String = ""): String {
        val rf = responseFormat.trim().lowercase()
        if (rf in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")) return if (rf == "jpg") "jpeg" else rf
        if (url.isNotBlank()) {
            val raw = url.substringAfterLast('/').substringBefore('?').lowercase()
            if (raw in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")) return if (raw == "jpg") "jpeg" else raw
        }
        return "png"
    }

    /** 下载图片字节；超过 10MB 放弃，避免内存问题 */
    private fun download(url: String): ByteArray? {
        return try {
            val r = http.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (AstrBotLite)")
                    .get()
                    .build(),
            ).execute()
            if (!r.isSuccessful) {
                Logs.warn("[文生图] 图片下载 HTTP ${r.code}：$url")
                r.close()
                return null
            }
            val b = r.body?.bytes()
            when {
                b == null || b.isEmpty() -> {
                    Logs.warn("[文生图] 图片下载为空：$url")
                    null
                }
                b.size > 10 * 1024 * 1024 -> {
                    Logs.warn("[文生图] 图片超过 10MB，已放弃（${b.size / 1024}KB）：$url")
                    null
                }
                else -> b
            }
        } catch (e: Exception) {
            Logs.warn("[文生图] 图片下载异常: ${e.message}")
            null
        }
    }
}

package com.astrbot.lite.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 在线 TTS 引擎（OpenAI 兼容 /audio/speech）：
 * 支持 OpenAI、硅基流动、火山等提供 OpenAI 兼容 TTS 接口的服务。
 * 参数来自内置 tts 插件配置（baseUrl / apiKey / voice / model）。
 */
object TTSEngine {
    private val json = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(70, TimeUnit.SECONDS)
        .build()

    data class TtsConfig(
        val baseUrl: String = "",
        val apiKey: String = "",
        val voice: String = "alloy",
        val model: String = "tts-1",
    ) {
        val valid: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()
    }

    /** 读取配置：AI 池优先（mediaEntry），否则读插件配置 */
    fun config(): TtsConfig {
        val pool = AiPool.mediaEntry("tts")
        if (pool != null) {
            val m = PluginManager.configMap("tts")
            return TtsConfig(
                baseUrl = pool.baseUrl,
                apiKey = pool.apiKey,
                voice = m["voice"]?.trim()?.takeIf { it.isNotBlank() } ?: "alloy",
                model = pool.model.ifBlank { m["model"]?.trim()?.takeIf { it.isNotBlank() } ?: "tts-1" },
            )
        }
        val m = PluginManager.configMap("tts")
        return TtsConfig(
            baseUrl = m["baseUrl"]?.trim().orEmpty(),
            apiKey = m["apiKey"]?.trim().orEmpty(),
            voice = m["voice"]?.trim()?.takeIf { it.isNotBlank() } ?: "alloy",
            model = m["model"]?.trim()?.takeIf { it.isNotBlank() } ?: "tts-1",
        )
    }

    /** 文本合成语音，返回 mp3 字节；失败返回 null */
    fun synth(text: String, cfg: TtsConfig = config()): ByteArray? {
        val clean = text.trim()
        if (clean.isBlank()) return null
        if (!cfg.valid) {
            Logs.warn("[TTS] 未配置 TTS 服务（需在 tts 插件配置中填 baseUrl / apiKey）")
            return null
        }
        return try {
            val body = JSONObject()
                .put("model", cfg.model)
                .put("voice", cfg.voice)
                .put("input", clean)
                .put("response_format", "mp3")
            val req = Request.Builder()
                .url(cfg.baseUrl.trimEnd('/') + "/audio/speech")
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json, body.toString()))
                .build()
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty().take(120)
                Logs.warn("[TTS] HTTP ${resp.code}: $err")
                return null
            }
            resp.body?.bytes()
        } catch (e: Exception) {
            Logs.warn("[TTS] 合成异常: ${e.message}")
            null
        }
    }
}

package com.astrbot.lite.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

object EmbeddingEngine {
    private const val DIR_NAME = "vectors"
    private const val CACHE_MAX = 50
    private lateinit var dir: File

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val httpQuick = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    private val queryCache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
            size > CACHE_MAX
    }

    private fun ensureDir(): File {
        if (!::dir.isInitialized) {
            dir = File(Storage.dir("state/knowledge"), DIR_NAME).apply { mkdirs() }
        }
        return dir
    }

    fun configured(): Boolean =
        Config.knowledgeEmbedBaseUrl.isNotBlank() &&
        Config.knowledgeEmbedApiKey.isNotBlank() &&
        Config.knowledgeEmbedModel.isNotBlank()

    fun hasAnyVector(): Boolean {
        val d = ensureDir()
        return d.listFiles()?.any { it.name.endsWith(".json") } == true
    }

    fun embed(text: String): FloatArray? = embedInternal(text, http)

    fun embedQuery(text: String): FloatArray? {
        queryCache[text]?.let { return it }
        val v = embedInternal(text, httpQuick) ?: return null
        queryCache[text] = v
        return v
    }

    private fun embedInternal(text: String, client: OkHttpClient): FloatArray? {
        if (!configured()) return null
        val base = Config.knowledgeEmbedBaseUrl.trim().trimEnd('/')
        val url = base + "/embeddings"
        val body = JSONObject()
            .put("model", Config.knowledgeEmbedModel.trim())
            .put("input", text.take(8000))
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + Config.knowledgeEmbedApiKey.trim())
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logs.warn("[嵌入] HTTP " + resp.code + " " + (resp.body?.string()?.take(200) ?: ""))
                    return null
                }
                val s = resp.body?.string() ?: return null
                val arr = JSONObject(s).optJSONArray("data") ?: return null
                if (arr.length() == 0) return null
                val vec = arr.optJSONObject(0)?.optJSONArray("embedding") ?: return null
                val out = FloatArray(vec.length())
                for (i in 0 until vec.length()) out[i] = vec.optDouble(i, 0.0).toFloat()
                if (out.isEmpty()) return null
                out
            }
        } catch (e: Exception) {
            Logs.warn("[嵌入] 请求失败: " + e.message)
            null
        }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return (dot / (sqrt(na) * sqrt(nb)))
    }

    fun loadVector(id: String): FloatArray? {
        val d = ensureDir()
        val f = File(d, id + ".json")
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("v") ?: return null
            val out = FloatArray(arr.length())
            for (i in 0 until arr.length()) out[i] = arr.optDouble(i, 0.0).toFloat()
            out
        } catch (_: Exception) { null }
    }

    fun saveVector(id: String, v: FloatArray) {
        try {
            val d = ensureDir()
            val arr = JSONArray()
            for (x in v) arr.put(x.toDouble())
            val o = JSONObject().put("id", id).put("dim", v.size).put("v", arr)
            File(d, id + ".json").writeText(o.toString())
        } catch (e: Exception) {
            Logs.warn("[嵌入] 保存向量失败: " + e.message)
        }
    }

    fun deleteVector(id: String) {
        try { File(ensureDir(), id + ".json").delete() } catch (_: Exception) {}
    }

    fun deleteAll() {
        try { ensureDir().listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }
}

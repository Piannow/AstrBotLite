package com.astrbot.lite.core

import android.content.Context
import com.chaquo.python.internal.Common
import okhttp3.internal.ws.WebSocketProtocol
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

object AiPool {
    private const val FILE = "ai_pool.json"
    private const val MIGRATION_FILE = "pool_migration_done"

    val FEATURES: List<Feature> = listOf(
        Feature("chat", "主对话回复（含技能改写）", false),
        Feature("summary", "消息总结（上下文分层摘要）", false),
        Feature("thought", "内心思考", false),
        Feature("review", "审核 AI（防注入 / 隐私判断）", false),
        Feature("knowledge", "知识库（新闻判断 / 标题提取）", false),
        Feature("embedding", "嵌入检索（知识库向量化）", false),
        Feature("dream", "做梦（梦境生成）", false),
        Feature("proactive", "主动消息（主动找人聊天）", false),
        Feature("image", "生成图片（文生图 / 图生图）", true),
        Feature("video", "生成视频", true),
        Feature("tts", "语音合成（TTS）", true)
    )

    private val entries = CopyOnWriteArrayList<AiPoolEntry>()
    private val assign = ConcurrentHashMap<String, String>()
    private val warnedMissing = ConcurrentHashMap.newKeySet<String>()
    private val submitPool: ExecutorService = Executors.newCachedThreadPool()
    private var dir: File? = null

    private val FEATURE_PLUGINS: Map<String, List<String>> = mapOf(
        "image" to listOf("text2image", "imageengine", "image2image"),
        "video" to listOf("videogen"),
        "tts" to listOf("tts")
    )

    private val PLUGIN_FEATURES: Map<String, String> = buildMap {
        for ((feature, plugins) in FEATURE_PLUGINS) {
            for (p in plugins) put(p, feature)
        }
    }

    /** baseUrl/model 含以下关键词的条目回落时跳过（防止生图模型被误用于聊天） */
    private val IMAGE_URL_SIGNS = listOf(
        "dalle", "dall-e", "midjourney", "stable", "stability", "flux",
        "ideogram", "recraft", "pollinations", "pexels", "pixai", "novelai",
        "pixiv", "waifu", "tiam", "images/generations", "/image"
    )
    private val IMAGE_MODEL_SIGNS = listOf(
        "dall-e", "dalle", "sd-", "sdxl", "stable-diffusion", "midjourney",
        "flux", "ideogram", "recraft", "pollinations", "kolors", "seedream",
        "wanx", "cogview", "image"
    )

    private fun isImageModel(e: AiPoolEntry): Boolean {
        return isImageUrl(e.baseUrl) || isImageModelName(e.model)
    }
    private fun isImageUrl(s: String?): Boolean {
        val l = s?.lowercase() ?: ""
        return IMAGE_URL_SIGNS.any { l.contains(it) }
    }
    private fun isImageModelName(s: String?): Boolean {
        val l = s?.lowercase() ?: ""
        return IMAGE_MODEL_SIGNS.any { l.contains(it) }
    }

    data class Feature(
        val key: String,
        val label: String,
        val media: Boolean
    )

    fun init(ctx: Context) {
        val d = File(Storage.dir("state"), "pool")
        d.mkdirs()
        dir = d
        load()
        migratePluginConfigs()
        syncMediaPluginConfigs()
    }

    private fun migratePluginConfigs() {
        val d = dir ?: return
        val marker = File(d, MIGRATION_FILE)
        if (marker.exists()) return

        val mediaPlugins = mapOf(
            "image" to listOf("text2image", "imageengine", "image2image"),
            "video" to listOf("videogen"),
            "tts" to listOf("tts")
        )
        var migrated = false
        for ((feature, pluginIds) in mediaPlugins) {
            if (assign[feature].isNullOrBlank()) {
                for (pid in pluginIds) {
                    val mapConfigMap = PluginManager.configMap(pid)
                    val baseUrl = mapConfigMap["baseUrl"]?.trim()?.toString() ?: ""
                    val apiKey = mapConfigMap["apiKey"]?.trim()?.toString() ?: ""
                    val model = mapConfigMap["model"]?.trim()?.toString() ?: ""
                    if (!baseUrl.isBlank() && !apiKey.isBlank()) {
                        val name = "$feature-migrated"
                        val entry = AiPoolEntry(
                            "migrated_${feature}_${System.currentTimeMillis()}",
                            name, baseUrl, apiKey, model
                        )
                        entries.add(entry)
                        assign[feature] = entry.id
                        migrated = true
                        Logs.info("[AI池] 自动迁移：" + pid + " → 特性 " + feature + "（条目 " + entry.name + "）")
                        break
                    }
                }
            }
        }
        if (migrated) save()
        marker.writeText("done")
    }

    fun list(): List<AiPoolEntry> = entries.toList()

    fun get(id: String): AiPoolEntry? {
        return entries.find { it.id == id }
    }

    fun add(entry: AiPoolEntry) {
        entries.removeAll { it.id == entry.id }
        entries.add(entry)
        save()
        syncMediaPluginConfigs()
    }

    fun remove(id: String) {
        entries.removeAll { it.id == id }
        for (k in assign.keys.toList()) {
            if (assign[k] == id) assign[k] = ""
        }
        save()
        syncMediaPluginConfigs()
    }

    fun assignedId(feature: String): String = assign[feature] ?: ""

    fun setAssignments(map: Map<String, String>) {
        for ((k, v) in map) {
            assign[k] = v
        }
        save()
        syncMediaPluginConfigs()
    }

    fun getFeaturePlugins(): Map<String, List<String>> = FEATURE_PLUGINS

    fun pullFromPluginConfig(pluginId: String, config: List<PluginConfigField>) {
        try {
            val feature = PLUGIN_FEATURES[pluginId] ?: return
            val nb = pluginConfigValue(config, "baseUrl")
            val ak = pluginConfigValue(config, "apiKey")
            val md = pluginConfigValue(config, "model")
            if (nb.isEmpty() && ak.isEmpty() && md.isEmpty()) return

            val curId = assign[feature] ?: ""
            val cur = if (curId.isNotBlank()) get(curId) else null

            if (cur == null) {
                val entry = AiPoolEntry(
                    "sync_${feature}_${System.currentTimeMillis()}",
                    "$feature·插件配置",
                    nb, ak, md
                )
                entries.add(entry)
                assign[feature] = entry.id
                save()
                Logs.info("[AI池] 插件「" + pluginId + "」配置已新建并绑定条目「" + entry.name + "」（feature=" + feature + "）")
                return
            }

            val baseUrl = nb.ifBlank { cur.baseUrl }
            val apiKey = ak.ifBlank { cur.apiKey }
            val model = md.ifBlank { cur.model }

            val updated = cur.copy(baseUrl = baseUrl, apiKey = apiKey, model = model)
            if (updated == cur) return

            val idx = entries.indexOfFirst { it.id == cur.id }
            if (idx >= 0) entries[idx] = updated
            save()
            Logs.info("[AI池] 插件「" + pluginId + "」配置已回写条目「" + cur.name + "」（feature=" + feature + "）")
        } catch (e: Exception) {
            Logs.warn("[AI池] 插件配置回写失败: " + e.message)
        }
    }

    private fun pluginConfigValue(list: List<PluginConfigField>, k: String): String {
        val field = list.find { it.key == k }
        return field?.value?.trim()?.toString() ?: ""
    }

    fun syncMediaPluginConfigs() {
        if (!Config.aiPoolEnabled) return
        try {
            for ((feature, value) in FEATURE_PLUGINS) {
                try {
                    val entry = mediaEntry(feature)
                    if (entry != null) {
                        for (pid in value) {
                            val p = PluginManager.installed(pid) ?: continue
                            val config = p.config
                            val isEmpty = config !is Collection<*> || config.isEmpty() ||
                                    config.filterIsInstance<PluginConfigField>().none {
                                        it.key in listOf("baseUrl", "apiKey", "model")
                                    }
                            if (!isEmpty) continue

                            val updated = p.config.filterIsInstance<PluginConfigField>().map { f ->
                                when (f.key) {
                                    "baseUrl" -> f.copy(value = entry.baseUrl)
                                    "apiKey" -> f.copy(value = entry.apiKey)
                                    "model" -> f.copy(value = entry.model)
                                    else -> f
                                }
                            }
                            PluginManager.setConfig(pid, updated)
                        }
                        Logs.info("[AI池] 特性「" + feature + "」→「条目「" + entry.name + "」已同步到插件配置")
                    }
                } catch (e: Exception) {
                    Logs.warn("[AI池] 同步插件配置失败: " + e.message)
                    return
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    fun mediaEntry(feature: String): AiPoolEntry? {
        if (!Config.aiPoolEnabled) return null
        return get(assignedId(feature))
    }

    fun chatConfigured(feature: String): Boolean {
        if (!Config.aiPoolEnabled) return Config.isAiConfigured()
        val e = get(assign[feature] ?: "") ?: entries.firstOrNull()
        if (e != null) {
            return !e.baseUrl.isBlank() && !e.apiKey.isBlank() && !e.model.isBlank()
        }
        return Config.isAiConfigured()
    }

    fun usable(): Boolean {
        return entries.any { e ->
            !e.baseUrl.isBlank() && !e.apiKey.isBlank() && !e.model.isBlank()
        }
    }

    fun readyOrWarn(source: String, feature: String): Boolean {
        if (chatConfigured(feature)) return true
        if (warnedMissing.add("$source|$feature")) {
            Logs.warn("[" + source + "] AI 未配置（特性「" + feature + "」），已自动跳过本功能——填好 设置 的 API 或在 AI 池分配条目后自动恢复")
        }
        return false
    }

    private fun entryFor(feature: String): AiPoolEntry? {
        if (!Config.aiPoolEnabled) return null
        val e = get(assignedId(feature))
        if (e != null) return e
        // 1.7.230: 回落时跳过疑似生图模型，防止生图模型被误用于聊天
        return entries.firstOrNull { !isImageModel(it) }
    }

    fun chatFor(feature: String, msgs: List<ChatMsg>): String {
        val entry = entryFor(feature)
        return entry?.let { chatWithEntry(it, msgs) } ?: ChatEngine.chat(msgs)
    }

    fun submit(feature: String, msgs: List<ChatMsg>, timeoutMs: Long = 30000): String? {
        val future = submitPool.submit(Callable {
            chatFor(feature, msgs)
        })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            try { future.cancel(true) } catch (_: Exception) {}
            null
        }
    }

    @Throws(JSONException::class)
    fun bridgeChatConfig(): JSONObject {
        val o = JSONObject()
        try {
            val e = entryFor("chat")
            if (e != null && !e.baseUrl.isBlank() && !e.model.isBlank()) {
                o.put("enabled", true)
                o.put("baseUrl", e.baseUrl)
                o.put("apiKey", e.apiKey)
                o.put("model", e.model)
                return o
            }
            if (Config.isAiConfigured()) {
                o.put("enabled", true)
                o.put("baseUrl", Config.aiBaseUrl)
                o.put("apiKey", Config.aiApiKey)
                o.put("model", Config.aiModel)
                return o
            }
        } catch (_: Exception) {}
        o.put("enabled", false)
        return o
    }

    fun chatWithEntry(entry: AiPoolEntry, msgs: List<ChatMsg>): String {
        return try {
            ChatEngine.chatWith(entry.baseUrl, entry.apiKey, entry.model, msgs)
        } catch (e: Exception) {
            Logs.warn("[AI池] " + entry.name + " 主模型失败：" + e.message + "，尝试回退模型")
            val fb = entry.fallbackModel.trim().toString()
            if (!fb.isBlank()) {
                try {
                    return ChatEngine.chatWith(entry.baseUrl, entry.apiKey, fb, msgs)
                } catch (e3: Exception) {
                    Logs.warn("[AI池] 回退模型也失败：" + e3.message + "，使用默认配置")
                    return ChatEngine.chat(msgs)
                }
            }
            ChatEngine.chat(msgs)
        }
    }

    private fun load() {
        val d = dir ?: return
        val f = File(d, FILE)
        if (!f.exists()) return
        try {
            val obj = JSONObject(f.readText())
            val arr = obj.optJSONArray("entries") ?: JSONArray()
            entries.clear()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                try {
                    entries.add(AiPoolEntry.fromJson(e.toString()))
                } catch (ex: Exception) {
                    Logs.warn("AI池跳过坏条目 #$i: " + ex.message)
                }
            }
            assign.clear()
            val a = obj.optJSONObject("assign")
            if (a == null) {
                assign["summary"] = obj.optString("summaryEntryId", "")
                assign["thought"] = obj.optString("thoughtEntryId", "")
                assign["knowledge"] = obj.optString("knowledgeEntryId", "")
            } else {
                val it = a.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    assign[k] = a.optString(k, "")
                }
            }
        } catch (e: Exception) {
            Logs.err("AI池加载失败: " + e.message)
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(JSONObject(e.toJson()))
            }
            val a = JSONObject()
            for ((k, v) in assign) {
                a.put(k, v)
            }
            val obj = JSONObject()
                .put("entries", arr)
                .put("assign", a)
                .put("summaryEntryId", assignedId("summary"))
                .put("thoughtEntryId", assignedId("thought"))
                .put("knowledgeEntryId", assignedId("knowledge"))
            val d = dir ?: return
            File(d, FILE).writeText(obj.toString(2))
        } catch (e: Exception) {
            Logs.err("AI池保存失败: " + e.message)
        }
    }

    @Throws(JSONException::class)
    fun exportSnapshot(): JSONObject {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject(e.toJson()))
        }
        val a = JSONObject()
        for ((k, v) in assign) {
            a.put(k, v)
        }
        return JSONObject()
            .put("entries", arr)
            .put("assign", a)
            .put("summaryEntryId", assignedId("summary"))
            .put("thoughtEntryId", assignedId("thought"))
            .put("knowledgeEntryId", assignedId("knowledge"))
    }

    fun importSnapshot(obj: JSONObject) {
        try {
            entries.clear()
            assign.clear()
            val arr = obj.optJSONArray("entries") ?: JSONArray()
            val seenIds = mutableSetOf<String>()
            var fixedId = 0
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                try {
                    val entry = AiPoolEntry.fromJson(e.toString())
                    val finalEntry = if (entry.id.isBlank() || entry.id in seenIds) {
                        val newId = "imported_${System.currentTimeMillis()}_$i"
                        fixedId++
                        entry.copy(id = newId)
                    } else entry
                    seenIds.add(finalEntry.id)
                    entries.add(finalEntry)
                } catch (_: Exception) {}
            }
            val a = obj.optJSONObject("assign")
            if (a != null) {
                val it = a.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    assign[k] = a.optString(k, "")
                }
            }
            var clearedAssign = 0
            for (k in assign.keys.toList()) {
                val v = assign[k]
                if (!v.isNullOrBlank() && v !in seenIds) {
                    assign[k] = ""
                    clearedAssign++
                }
            }
            save()
            try {
                syncMediaPluginConfigs()
            } catch (_: Exception) {}
            Logs.info("[AI池] 已恢复：" + entries.size + " 条，" + assign.size + " 个分配，补全 id " + fixedId + "，清理虚空 " + clearedAssign)
        } catch (e: Exception) {
            Logs.err("[AI池] 恢复失败: " + e.message)
        }
    }
}

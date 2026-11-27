package com.astrbot.lite.core

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

object KnowledgeBase {

    private const val DIR_NAME = "knowledge"
    private const val FILE = "entries.json"
    private const val MAX_ENTRIES = 500

    private lateinit var dir: File
    private val entries = CopyOnWriteArrayList<KnowledgeEntry>()

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, DIR_NAME).apply { mkdirs() }
        load()
    }

    fun list(): List<KnowledgeEntry> = entries.toList()

    fun add(entry: KnowledgeEntry) {
        entries.removeAll { it.id == entry.id }
        entries.add(0, entry)
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        save()
    }

    fun remove(id: String) {
        entries.removeAll { it.id == id }
        save()
    }

    fun clear() {
        entries.clear()
        save()
    }

    fun search(query: String, limit: Int = 5): List<KnowledgeEntry> {
        if (query.isBlank()) return entries.take(limit)
        // 向量检索优先：已配置嵌入模型且有条目向量时走语义检索
        try {
            if (EmbeddingEngine.configured() && EmbeddingEngine.hasAnyVector()) {
                val qv = EmbeddingEngine.embedQuery(query)
                if (qv != null) {
                    val scored = mutableListOf<Pair<KnowledgeEntry, Float>>()
                    for (e in entries) {
                        val ev = EmbeddingEngine.loadVector(e.id) ?: continue
                        val s = EmbeddingEngine.cosine(qv, ev)
                        if (s >= 0.25f) scored.add(e to s)
                    }
                    if (scored.isNotEmpty()) {
                        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
                    }
                }
            }
        } catch (e: Exception) {
            Logs.warn("[知识库] 向量检索失败，回退字面: " + e.message)
        }
        // 字面匹配回退（无向量/未配模型/API失败）
        val q = query.lowercase()
        return entries.filter {
            it.title.lowercase().contains(q) ||
                it.content.lowercase().contains(q) ||
                it.tags.lowercase().contains(q) ||
                it.sourcePerson.lowercase().contains(q)
        }.take(limit)
    }

    fun formatForInjection(entries: List<KnowledgeEntry>): String {
        if (entries.isEmpty()) return ""
        val sb = StringBuilder("【知识库】\n")
        for (e in entries) {
            sb.append("- [${e.title}] ${e.content.take(200)}")
            if (e.sourcePerson.isNotBlank()) sb.append("（来源：${e.sourcePerson}）")
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    fun processMessage(content: String, sender: String, imageUrls: List<String>) {
        if (!Config.knowledgeEnabled) return
        // 未配置 AI：新闻判断/标题提取都要模型——直接跳过并警告一次
        // （旧行为：judgeNews 捕获异常后只打 debug，知识库“静默为空”，用户以为坏了）
        if (!AiPool.readyOrWarn("知识库", "knowledge")) return
        try {
            val isNews = judgeNews(content, imageUrls)
            if (!isNews) return
            val title = extractTitle(content)
            val entry = KnowledgeEntry(
                id = "k_${System.currentTimeMillis()}",
                title = title,
                content = content.take(1000),
                source = if (imageUrls.isNotEmpty()) "图文消息(${imageUrls.size}张图)" else "文字消息",
                sourcePerson = sender,
                createdAt = System.currentTimeMillis(),
            )
            add(entry)
            Logs.info("[知识库] 新增条目「${title.take(30)}」（来源：$sender）")
        } catch (e: Exception) {
            Logs.warn("[知识库] 处理消息失败: ${e.message}")
        }
    }

    private fun judgeNews(content: String, imageUrls: List<String>): Boolean {
        if (content.length < 20 && imageUrls.isEmpty()) return false
        try {
            val msgs = listOf(
                ChatMsg("system", "你是新闻判断助手。判断以下消息是否为「新闻类型」消息（需要关注的新信息、事件、事实，而非日常闲聊/问候/表情/简单问答）。如果消息含多个图片且有上下文联系，更可能是新闻。只回复 是 或 否。"),
                ChatMsg(
                    "user",
                    buildString {
                        append("消息内容：${content.take(500)}")
                        if (imageUrls.isNotEmpty()) append("\n附带了${imageUrls.size}张图片")
                    },
                ),
            )
            val r = AiPool.chatFor("knowledge", msgs).trim().lowercase()
            return r.startsWith("是") || r.startsWith("yes")
        } catch (e: Exception) {
            Logs.debug("[知识库] 新闻判断失败，保守跳过: ${e.message}")
            return false
        }
    }

    private fun extractTitle(content: String): String {
        try {
            val msgs = listOf(
                ChatMsg("system", "你是标题提取助手。给下面这段消息生成一个简短精炼的标题（10字以内），只输出标题，不要解释。"),
                ChatMsg("user", content.take(500)),
            )
            val t = AiPool.chatFor("knowledge", msgs).trim()
            if (t.isNotBlank() && t.length <= 30) return t
        } catch (_: Exception) {
        }
        return content.take(15).replace("\n", " ") + if (content.length > 15) "…" else ""
    }

    private fun load() {
        val f = File(dir, FILE)
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            entries.clear()
            for (i in 0 until arr.length()) {
                entries.add(KnowledgeEntry.fromJson(arr.optJSONObject(i).toString()))
            }
        } catch (e: Exception) {
            Logs.err("知识库加载失败: ${e.message}")
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            entries.forEach { arr.put(org.json.JSONObject(it.toJson())) }
            File(dir, FILE).writeText(arr.toString(2))
        } catch (e: Exception) {
            Logs.err("知识库保存失败: ${e.message}")
        }
    }
}

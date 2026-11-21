package com.astrbot.converter

import org.json.JSONArray
import org.json.JSONObject

/** 转换后的一个命令 */
data class ConvCommand(
    var match: String,
    var desc: String,
    var reply: String,
    var prompt: String,
    var note: String
)

/** 转换结果（可导出为 AstrBot Lite 的 plugin.json） */
data class ConvResult(
    var id: String,
    var name: String,
    var version: String,
    var author: String,
    var desc: String,
    var commands: MutableList<ConvCommand>,
    val log: MutableList<String>,
    var trigger: String = "passive" // always=总是(hook) / passive=被动(命令) / llm=LLM技能 / never=从不
) {
    fun toLiteJson(): String {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("version", version)
        o.put("author", author)
        o.put("description", desc)
        o.put("type", "prompt")
        o.put("trigger", trigger)
        val arr = JSONArray()
        commands.forEach { c ->
            val co = JSONObject()
            co.put("match", c.match)
            if (c.prompt.isNotBlank()) co.put("prompt", c.prompt)
            if (c.reply.isNotBlank()) co.put("reply", c.reply)
            arr.put(co)
        }
        o.put("commands", arr)
        o.put("enabled", true)
        return o.toString(2)
    }

    /** 读取已经是 AstrBot Lite 格式的 plugin.json */
    fun fromLiteJson(json: String): Boolean {
        return try {
            val o = JSONObject(json)
            id = o.optString("id", id)
            name = o.optString("name", name)
            version = o.optString("version", version)
            author = o.optString("author", author)
            desc = o.optString("description", desc)
            trigger = o.optString("trigger", "passive")
            commands.clear()
            val arr = o.optJSONArray("commands")
            if (arr != null) for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                commands.add(ConvCommand(
                    match = c.optString("match", "/"),
                    desc = "",
                    reply = if (c.has("reply")) c.optString("reply") else "",
                    prompt = c.optString("prompt", ""),
                    note = ""
                ))
            }
            if (commands.isEmpty()) return false
            log.add("✅ 已读取现成的 Lite 插件（plugin.json），触发方式：${triggerName(trigger)}，可直接导出或继续编辑")
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        val TRIGGER_OPTIONS = arrayOf("总是触发(hook)", "被动触发(命令)", "LLM触发(技能)", "从不触发")
        val TRIGGER_VALUES = arrayOf("always", "passive", "llm", "never")

        fun triggerName(t: String): String {
            val i = TRIGGER_VALUES.indexOfFirst { it == t }
            return if (i >= 0) TRIGGER_OPTIONS[i] else t
        }
    }
}

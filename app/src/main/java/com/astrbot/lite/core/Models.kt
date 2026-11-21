package com.astrbot.lite.core

import org.json.JSONArray
import org.json.JSONObject

/** 对话消息（OpenAI 兼容格式）；sender 为发送者显示名（群聊标注说话人用，不进入 API payload） */
data class ChatMsg(val role: String, val content: String, val sender: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("role", role).put("content", content)
}

/** 人设卡 */
data class Persona(
    val id: String,
    val name: String,
    val avatar: String,
    val description: String,
    val prompt: String,
    val greeting: String,
    val createdAt: Long
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("avatar", avatar)
        o.put("description", description)
        o.put("prompt", prompt)
        o.put("greeting", greeting)
        o.put("createdAt", createdAt)
        return o.toString(2)
    }

    companion object {
        fun fromJson(s: String): Persona {
            val o = JSONObject(s)
            return Persona(
                id = o.optString("id", "p" + System.currentTimeMillis()),
                name = o.optString("name", "未命名"),
                avatar = o.optString("avatar", "🤖"),
                description = o.optString("description", ""),
                prompt = o.optString("prompt", "你是一个乐于助人的AI助手，请用中文简洁友好地回答问题。"),
                greeting = o.optString("greeting", "你好呀~有什么可以帮你？"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}

/** 插件命令定义 */
data class PluginCommand(
    val match: String,
    val prompt: String,
    val reply: String?,
    val regex: String? = null,        // 正则清理规则（总是触发 hook 插件用）：对回复按正则匹配
    val replaceWith: String? = null   // 匹配后替换为的内容；默认 null=删除（等价替换为空串）
) {
    companion object {
        fun fromJson(o: JSONObject): PluginCommand = PluginCommand(
            match = o.optString("match", "/"),
            prompt = o.optString("prompt", ""),
            reply = if (o.has("reply")) o.optString("reply") else null,
            regex = if (o.has("regex")) o.optString("regex") else null,
            replaceWith = if (o.has("replaceWith")) o.optString("replaceWith") else null
        )
    }
}

/** 插件配置项（客户端可编辑）。
 *  1.7.87 类型化（对齐 AstrBot _conf_schema）：
 *  - string：单行输入框；text：多行；number：数字（int/float）
 *  - bool：开关；select：下拉框（options 候选）
 *  - list：行编辑器（每行一条，可新增/删除 = "新建框"，值存 JSON 数组）
 *  - json：结构化降级（object/dict/template_list/file → 多行 JSON 编辑，控制容量与复杂度） */
data class PluginConfigField(
    val key: String,
    val label: String,
    val value: String,
    val secret: Boolean = false,
    val multiLine: Boolean = false,  // 长文本（如自定义人格化指令）用多行输入框
    val type: String = "string",     // string|text|number|bool|select|list|json
    val options: List<String> = emptyList() // select/list 的候选项
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("label", label)
        .put("value", value)
        .put("secret", secret)
        .put("multiLine", multiLine)
        .put("type", type)
        .let { o ->
            if (options.isNotEmpty()) o.put("options", JSONArray(options)) else o
        }

    companion object {
        fun fromJson(o: JSONObject): PluginConfigField {
            val opts = o.optJSONArray("options")
            val optList = if (opts != null) (0 until opts.length()).map { opts.optString(it) } else emptyList()
            return PluginConfigField(
                key = o.optString("key"),
                label = o.optString("label", o.optString("key")),
                value = o.optString("value"),
                secret = o.optBoolean("secret", false),
                multiLine = o.optBoolean("multiLine", false),
                type = o.optString("type", "string").ifBlank { "string" },
                options = optList
            )
        }
    }
}

/** 轻量插件（兼容 AstrBot 插件市场的结构：名称/版本/作者/描述/指令） */
data class PluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val type: String,          // "prompt" | "webhook"
    val commands: List<PluginCommand>,
    val webhookUrl: String,
    val downloadUrl: String,
    val enabled: Boolean,
    val trigger: String = "passive",  // always=总是(hook) / passive=被动(命令) / llm=LLM技能 / never=从不
    val config: List<PluginConfigField> = emptyList(),
    val doc: String = ""       // 插件文档/使用说明（内置插件手写；AstrBot 转换插件取 README）
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("version", version)
        o.put("author", author)
        o.put("description", description)
        o.put("type", type)
        val arr = org.json.JSONArray()
        commands.forEach { c ->
            val co = JSONObject()
            co.put("match", c.match)
            if (c.prompt.isNotBlank()) co.put("prompt", c.prompt)
            if (c.reply != null) co.put("reply", c.reply)
            if (!c.regex.isNullOrBlank()) co.put("regex", c.regex)
            if (!c.replaceWith.isNullOrBlank()) co.put("replaceWith", c.replaceWith)
            arr.put(co)
        }
        o.put("commands", arr)
        if (webhookUrl.isNotBlank()) o.put("webhookUrl", webhookUrl)
        if (downloadUrl.isNotBlank()) o.put("downloadUrl", downloadUrl)
        o.put("enabled", enabled)
        o.put("trigger", trigger)
        if (config.isNotEmpty()) {
            val carr = org.json.JSONArray()
            config.forEach { carr.put(it.toJson()) }
            o.put("config", carr)
        }
        if (doc.isNotBlank()) o.put("doc", doc)
        return o.toString(2)
    }

    companion object {
        fun fromJson(s: String): PluginInfo = fromJson(JSONObject(s))

        fun fromJson(o: JSONObject): PluginInfo {
            val commands = mutableListOf<PluginCommand>()
            val arr = o.optJSONArray("commands")
            if (arr != null) for (i in 0 until arr.length()) {
                commands.add(PluginCommand.fromJson(arr.optJSONObject(i)))
            }
            return PluginInfo(
                id = o.optString("id", "p" + System.currentTimeMillis()),
                name = o.optString("name", "未命名插件"),
                version = o.optString("version", "1.0.0"),
                author = o.optString("author", ""),
                description = o.optString("description", ""),
                type = o.optString("type", "prompt"),
                commands = commands,
                webhookUrl = o.optString("webhookUrl", ""),
                downloadUrl = o.optString("downloadUrl", ""),
                enabled = o.optBoolean("enabled", true),
                trigger = o.optString("trigger", "passive"),
                config = run {
                    val c = mutableListOf<PluginConfigField>()
                    val ca = o.optJSONArray("config")
                    if (ca != null) for (i in 0 until ca.length()) {
                        c.add(PluginConfigField.fromJson(ca.optJSONObject(i)))
                    }
                    c
                },
                doc = o.optString("doc", "")
            )
        }
    }
}

/** 界面消息条目；session = "群/私聊:openid"，用于把记录按会话分组（旧记录为空） */
data class ChatEntry(val from: String, val content: String, val time: Long,
                     val session: String = "")

/** 日志条目 */
data class LogLine(val level: String, val text: String, val time: Long)

/** QQ 官方机器人收到的消息事件 */
data class QqMessageEvent(
    val kind: String,        // "c2c" | "group"
    val openid: String,      // 群 group_openid 或 单聊 c2c_openid
    val memberOpenid: String,
    val content: String,
    val msgId: String,
    val sender: String,
    val atMe: Boolean = false,              // 本消息是否@了当前机器人（含多次@自己）
    val atOpenids: List<String> = emptyList(), // 消息中@的所有 openid（含自己与他人）
    val imageUrls: List<String> = emptyList(), // v1.5.0 消息中的图片URL列表
    val replyMsgId: String = ""              // v1.5.0 引用回复的消息ID
)

/** QQ 官方机器人按钮点击（INTERACTION_CREATE，type=11 消息按钮） */
data class QqInteraction(
    val id: String,
    val kind: String,          // "c2c" | "group"
    val openid: String,        // 群 group_openid 或 单聊 user_openid
    val memberOpenid: String,
    val buttonId: String,
    val buttonData: String,
    val messageId: String
)

/** AI池条目（可复用于 消息总结/内心思考/知识库 等不同场景） */
data class AiPoolEntry(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val fallbackModel: String = "",
    val idleModel: String = ""
) {
    fun toJson(): String = org.json.JSONObject()
        .put("id", id)
        .put("name", name)
        .put("baseUrl", baseUrl)
        .put("apiKey", apiKey)
        .put("model", model)
        .put("fallbackModel", fallbackModel)
        .put("idleModel", idleModel)
        .toString(2)

    companion object {
        fun fromJson(s: String): AiPoolEntry {
            val o = org.json.JSONObject(s)
            return AiPoolEntry(
                id = o.optString("id", "pool_" + System.currentTimeMillis()),
                name = o.optString("name", "未命名"),
                baseUrl = o.optString("baseUrl", ""),
                apiKey = o.optString("apiKey", ""),
                model = o.optString("model", ""),
                fallbackModel = o.optString("fallbackModel", ""),
                idleModel = o.optString("idleModel", "")
            )
        }
    }
}

/** 知识库条目 */
data class KnowledgeEntry(
    val id: String,
    val title: String,
    val content: String,
    val source: String,
    val sourcePerson: String,
    val createdAt: Long,
    val tags: String = ""
) {
    fun toJson(): String = org.json.JSONObject()
        .put("id", id)
        .put("title", title)
        .put("content", content)
        .put("source", source)
        .put("sourcePerson", sourcePerson)
        .put("createdAt", createdAt)
        .put("tags", tags)
        .toString(2)

    companion object {
        fun fromJson(s: String): KnowledgeEntry {
            val o = org.json.JSONObject(s)
            return KnowledgeEntry(
                id = o.optString("id", "k_" + System.currentTimeMillis()),
                title = o.optString("title", ""),
                content = o.optString("content", ""),
                source = o.optString("source", ""),
                sourcePerson = o.optString("sourcePerson", ""),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                tags = o.optString("tags", "")
            )
        }
    }
}

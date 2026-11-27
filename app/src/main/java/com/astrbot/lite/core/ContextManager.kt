package com.astrbot.lite.core

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * 人格化核心逻辑：上下文自动清理/LLM 总结、群聊说话人标注、用户画像提取、好感度追踪。
 * 开关均来自内置插件 "memory" 的 config（enabled / ctxClean / ctxSummarize / profileEnable / speakerMark / sumThreshold / favorAdjust）。
 */
object ContextManager {

    private const val MEMORY_ID = "memory"
    private const val EXTRACT_EVERY = 8 // 画像不完整时，每间隔多少条消息提取一次

    /** convKey -> 分层摘要：layers[0]=消息级摘要列表，layers[1]=摘要的摘要，以此类推（每满 LAYER_SIZE 条提升一层） */
    private val layers = ConcurrentHashMap<String, MutableList<MutableList<String>>>()

    /** 累计消息数（按会话）：每满 sumThreshold 条触发一次总结 */
    private val msgCounters = ConcurrentHashMap<String, Int>()

    /** 上次已总结到的累计消息数（按会话） */
    private val lastCondensedAt = ConcurrentHashMap<String, Int>()

    /** 每层摘要达到该数量 → 整体再总结提升一层 */
    private const val LAYER_SIZE = 5

    // ---------------- 配置读取 ----------------
    fun enabled(): Boolean = PluginManager.isEnabled(MEMORY_ID)
    fun cleanEnabled(): Boolean = cfg("ctxClean", true)
    fun summarizeEnabled(): Boolean = cfg("ctxSummarize", true)
    fun profileEnabled(): Boolean = cfg("profileEnable", true)
    fun speakerMarkEnabled(): Boolean = cfg("speakerMark", true)
    fun favorAdjustEnabled(): Boolean = cfg("favorAdjust", true)
    fun sumThreshold(): Int = PluginManager.configMap(MEMORY_ID)["sumThreshold"]?.toIntOrNull() ?: 20

    private fun cfg(key: String, def: Boolean): Boolean = PluginManager.configMap(MEMORY_ID)[key]?.equals("true", ignoreCase = true) ?: def

    // ---------------- 上下文总结 / 清理 ----------------
    /** 注入用摘要：从最高层到最低层拼接（高层是低层的浓缩，AI 看全貌又不过载） */
    fun summaryOf(convKey: String): String? {
        val list = layers[convKey] ?: return null
        val parts = mutableListOf<String>()
        for (level in list.indices.reversed()) {
            if (list[level].isNotEmpty()) parts.add(list[level].joinToString("\n"))
        }
        return parts.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    /** 会话累计消息数 +1（按 convKey），用于按数量触发总结。
     *  注：不用 Map.merge（API24+，老机顶盒会 NoSuchMethodError），手写 get/put 兼容到底。 */
    fun recordMessage(convKey: String) {
        msgCounters[convKey] = (msgCounters[convKey] ?: 0) + 1
    }

    /**
     * 分层总结：会话累计消息数每满 sumThreshold（默认20）条，把历史中超出 AI 窗口（historyLen）的最旧部分
     * 总结成一条摘要；摘要每累积 LAYER_SIZE（5）条再整体总结提升一层，以此类推。
     * 用户要求“消息都不能删，至多总结”——总结成功才把旧消息压缩为摘要；失败则保留历史，等下次批次再试。
     */
    fun condense(convKey: String, hist: ArrayDeque<ChatMsg>) {
        if (!summarizeEnabled()) return
        val batch = sumThreshold()
        val keep = Config.historyLen.coerceAtLeast(6)
        val total = msgCounters[convKey] ?: return
        val last = lastCondensedAt.getOrPut(convKey) { total }
        if (total - last < batch) return // 未累积满一个批次
        // 只在锁内读取“最旧消息”快照，随后【释放锁】再调用 AI，避免记忆总结的慢 AI 卡住回复线程
        val oldest = synchronized(hist) {
            if (hist.size <= keep) {
                emptyList()
            } else {
                hist.toList().take(hist.size - keep)
            }
        }
        if (oldest.isEmpty()) return
        val chunk = summarizeChat(oldest)
        if (chunk == null || chunk.isBlank()) return // 总结失败：保留历史，等下次
        addLayer(convKey, chunk)
        // 重新加锁，仅把已总结的最旧 removeCount 条从历史里移除
        synchronized(hist) {
            rebuild(hist, oldest.size)
        }
        lastCondensedAt[convKey] = total
        Logs.info("[记忆] 已总结 ${oldest.size} 条历史（$convKey）")
    }

    /** 追加一条摘要到最低层；若该层满 LAYER_SIZE 条则整体再总结提升一层（递归） */
    private fun addLayer(convKey: String, text: String) {
        val list = layers.getOrPut(convKey) { mutableListOf(mutableListOf()) }
        list[0].add(text)
        promote(convKey, 0)
    }

    private fun promote(convKey: String, level: Int) {
        val list = layers.getOrPut(convKey) { mutableListOf(mutableListOf()) }
        while (list.size <= level) list.add(mutableListOf())
        if (list[level].size < LAYER_SIZE) return
        val chunk = summarizeText(list[level].joinToString("\n"))
        if (chunk == null || chunk.isBlank()) return // 提升失败：保留，等下次
        list[level].clear()
        while (list.size <= level + 1) list.add(mutableListOf())
        list[level + 1].add(chunk)
        promote(convKey, level + 1)
    }

    /** 把多条摘要进一步浓缩成一条更高层摘要（分层总结的“总结的总结”） */
    private fun summarizeText(text: String): String? {
        // 未配置 AI：跳过压缩（返回 null → 调用方保留历史原样），只警告一次
        if (!AiPool.readyOrWarn("消息总结", "summary")) return null
        val sys = "你是对话记忆整理助手。请把下面的多条摘要进一步压缩成更简洁的要点，保留关键事实、决策、约定、未完成事项和重要时间点，控制在150字以内，条目式。只输出摘要本身，不要任何解释。"
        return try {
            AiPool.chatFor("summary", listOf(ChatMsg("system", sys), ChatMsg("user", text)))
        } catch (e: Exception) {
            Logs.warn("摘要再总结失败：${e.message}")
            null
        }
    }

    private fun rebuild(hist: ArrayDeque<ChatMsg>, removeCount: Int) {
        val rest = hist.toList().drop(removeCount)
        hist.clear()
        hist.addAll(rest)
    }

    /** 把被总结的最旧消息渲染成带说话人的文本，供 LLM 压缩 */
    private fun summarizeChat(oldest: List<ChatMsg>): String? {
        if (!AiPool.readyOrWarn("消息总结", "summary")) return null
        if (oldest.isEmpty()) return null
        val sb = StringBuilder()
        for (m in oldest) {
            val who = if (m.role == "user") {
                m.sender?.takeIf { it.isNotBlank() } ?: "用户"
            } else {
                "AI"
            }
            // 用「X 说：内容」句式，明确冒号前是说话者名字（可能是任意短语），避免摘要模型把名字当内容
            sb.append("$who 说：${m.content}\n")
        }
        val sys = "你是对话记忆整理助手。请把下面的对话历史压缩成一段简洁的中文摘要，务必保留：\n" +
            "- 用户身份、称呼与明显偏好\n" +
            "- 关键决策、约定与事实\n" +
            "- 未完成的事项与待办\n" +
            "- 重要时间点\n" +
            "不要写入用户画像类信息（性别/年龄/城市等已单独维护）。控制在200字以内，条目式。只输出摘要本身，不要任何解释。"
        return try {
            AiPool.chatFor("summary", listOf(ChatMsg("system", sys), ChatMsg("user", sb.toString())))
        } catch (e: Exception) {
            Logs.warn("上下文总结失败：${e.message}")
            null
        }
    }

    // ---------------- 群聊说话人标注（读期渲染，不污染历史） ----------------
    fun renderForAi(msg: ChatMsg, kind: String): ChatMsg {
        if (kind == "group" && speakerMarkEnabled() && msg.role == "user") {
            // 用「名字 说：内容」的句式：让“说”明确标记名字只是说话者，
            // 避免纯“昵称：内容”时，AI 把不像人名的昵称（如“我喜欢猫”）误当成消息内容。
            val name = msg.sender?.takeIf { it.isNotBlank() }
            if (name != null) return msg.copy(content = "$name 说：" + msg.content)
        }
        return msg
    }

    /** 历史（最近 Config.historyLen 条）+ 当前消息统一渲染 */
    fun renderRecent(hist: ArrayDeque<ChatMsg>, kind: String, finalUser: ChatMsg): List<ChatMsg> = (hist.toList().takeLast(Config.historyLen) + finalUser).map { renderForAi(it, kind) }

    // ---------------- 用户画像 ----------------
    fun renderProfileSystem(profile: UserProfile?): String? {
        if (profile == null) return null
        val body = profile.render()
        if (body.isBlank()) return null
        val nameNote = if (profile.name.isNotBlank()) {
            // 让对方“知道自己叫什么”：QQ 昵称可能是不像人名的任意短语（如“我喜欢猫”），
            // 明确告诉 AI 那只是个名字/称呼，不代表它的字面意思，防止把名字当成对方在表达喜好或内容。
            "\n请注意：上面“姓名”一栏是对方的名字（QQ 昵称），即使它像“我喜欢猫”这样的任意短语，那也只是他的名字，不是他在描述喜好，请不要据此推断含义，只用它来称呼对方即可。"
        } else {
            "\n如果对方尚未提供姓名或昵称，就用“你”称呼，不要臆造名字。"
        }
        return "【用户画像】\n$body\n\n请记住并自然地运用以上用户信息（称呼、性别、偏好等），不要直接向用户复述这份画像。$nameNote"
    }

    /**
     * 用户画像提取（已禁用自动调用）：为节省 AI 调用（免费额度容易 429），
     * 不再每 N 条消息额外请求一次 LLM 做提取——普通对话每条消息只保留 1 次主对话请求。
     * 已保存的画像仍会注入上下文；需要时可手动重建（后续可加开关）。
     */
    fun maybeExtractProfile(profile: UserProfile, hist: ArrayDeque<ChatMsg>) {
        // 自动画像提取已移除（省调用）
    }

    private fun extractProfileText(input: String): String? {
        val sys = "你是用户画像提取助手。根据下面的用户发言提取关键信息，只输出以下键值行（每行一个“键: 值”，没有把握就不输出，不要编造）：\n" +
            "姓名:\n性别:\n期望称呼:\n年龄:\n城市:\n兴趣:\n其他:\n" +
            "不要输出解释或其他内容。"
        return try {
            ChatEngine.chat(listOf(ChatMsg("system", sys), ChatMsg("user", input)))
        } catch (e: Exception) {
            Logs.warn("用户画像提取失败：${e.message}")
            null
        }
    }

    /** 解析 "键: 值" 行（兼容中文冒号/英文冒号） */
    fun parseKeyValues(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val re = Regex("""^\s*(姓名|性别|期望称呼|年龄|城市|兴趣|其他)\s*[:：]\s*(.+?)\s*$""")
        for (line in text.lines()) {
            val m = re.find(line)
            if (m != null) out[m.groupValues[1]] = m.groupValues[2].trim()
        }
        return out
    }
}

package com.astrbot.lite.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * 机器人情绪系统（更接近人类）：
 * - 20+ 种情绪，含复杂情绪（愧疚/心疼/委屈/得意/甜蜜…），不止“开心/难过”这种粗粒度
 * - 情绪会流动：事件触发变化、随时间向平静衰减、生活状态随时间变化
 * - 情绪可混合：主情绪 + 次要情绪 + 强度（1~5）
 * 持久化到 filesDir/bot_state.json。
 */
object BotState {
    private val POSITIVE = listOf("开心", "兴奋", "得意", "满足", "感动", "期待", "感激", "甜蜜", "安心")
    private val NEUTRAL = listOf("平静", "无聊", "好奇", "犯困")
    private val NEGATIVE = listOf("难过", "低落", "烦躁", "生气", "委屈", "焦虑", "害怕", "疲惫", "麻木")
    private val COMPLEX = listOf("愧疚", "心疼", "害羞", "抱歉")

    @Volatile private var mood = "平静"

    // 主情绪
    @Volatile private var intensity = 3

    // 强度 1~5
    @Volatile private var secondary: String? = null

    // 次要情绪（混合情绪）
    @Volatile var lifeState = "闲着没事"

    // 生活状态（注入用）
    @Volatile private var dream: String? = null

    // 最近做的梦
    @Volatile private var dreamAt = 0L

    // 做梦时间
    @Volatile private var dreamDay = ""

    // 今天已主动提过梦（日期串，跨天重置）
    @Volatile private var lastChangeAt = 0L
    private lateinit var file: File

    private fun dayKey(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH)}-${c.get(Calendar.DAY_OF_MONTH)}"
    }

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, "bot_state.json")
        try {
            val o = JSONObject(file.readText())
            // 兼容旧版（旧文件只有 mood/life/t）：旧 mood 作为主情绪，默认强度 3
            mood = o.optString("mood", "平静")
            intensity = o.optInt("intensity", 3).coerceIn(1, 5)
            secondary = o.optString("secondary").takeIf { it.isNotBlank() }
            lifeState = o.optString("life", "闲着没事")
            dream = o.optString("dream").takeIf { it.isNotBlank() }
            dreamAt = o.optLong("dreamAt", 0L)
            dreamDay = o.optString("dreamDay")
            lastChangeAt = o.optLong("t", 0L)
        } catch (_: Exception) {
        }
    }

    /** 保存：消息线程与 proactive 线程可能同时改状态，加锁防止两次 writeText 交错写坏 JSON */
    @Synchronized
    private fun save() {
        try {
            file.writeText(
                JSONObject()
                    .put("mood", mood)
                    .put("intensity", intensity)
                    .put("secondary", secondary ?: "")
                    .put("life", lifeState)
                    .put("dream", dream ?: "")
                    .put("dreamAt", dreamAt)
                    .put("dreamDay", dreamDay)
                    .put("t", System.currentTimeMillis())
                    .toString(2),
            )
        } catch (_: Exception) {
        }
    }

    /**
     * 情绪流动：10 分钟无事件 → 强度 -1；强度到 0 → 回到平静。
     * 生活状态 30 分钟按时间段刷新（刚睡醒/忙自己的事/闲着/追剧/准备睡觉）。
     */
    fun decay() {
        if (!::file.isInitialized) return
        val now = System.currentTimeMillis()
        if (now - lastChangeAt >= 10 * 60 * 1000L) {
            if (intensity > 1) {
                intensity--
                lastChangeAt = now
                save()
            } else if (mood != "平静") {
                mood = "平静"
                secondary = null
                lastChangeAt = now
                save()
            }
        }
        if (now - lastChangeAt >= 30 * 60 * 1000L) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            lifeState = when (hour) {
                in 6..9 -> "刚睡醒，还有点迷糊"
                in 10..12 -> "在忙自己的事"
                in 13..17 -> "闲着没事"
                in 18..21 -> "在追剧刷手机"
                else -> "准备睡觉了，但还是没睡"
            }
            lastChangeAt = now
            save()
        }
    }

    /** 事件驱动情绪变化：根据对方的话产生人类式情绪（含愧疚/心疼/得意等复杂情绪）。
     *  sensitivity 1~10：越高越容易随对方情绪波动；低敏感度时只响应"强信号"（被夸/被怪/对方难过），
     *  弱信号（被找/被亲近/对方无聊）需要 sensitivity >= 6 才触发。 */
    fun react(text: String, sensitivity: Int = 5) {
        if (!::file.isInitialized || text.isBlank()) return
        when {
            // 被夸 → 得意（强信号，任何敏感度都响应）
            text.contains("真棒") ||
                text.contains("厉害") ||
                text.contains("聪明") ||
                text.contains("夸夸") ||
                text.contains("可爱") ||
                text.contains("好看") -> set("得意", 4, "开心")
            // 对方难过 → 心疼（强信号）
            text.contains("难过") ||
                text.contains("伤心") ||
                text.contains("好累") ||
                text.contains("哭") ||
                text.contains("崩溃") ||
                text.contains("难受") -> set("心疼", 4, "担心")
            // 被责备/失望 → 愧疚（强信号）
            text.contains("失望") ||
                text.contains("你变了") ||
                text.contains("敷衍") ||
                text.contains("不理我") ||
                text.contains("讨厌你") ||
                text.contains("没用") ||
                text.contains("烦你") ||
                text.contains("怪你") ||
                text.contains("生气") -> set("愧疚", 5, "难过")
            // 对方情绪激烈 → 害怕+委屈（强信号）
            text.contains("气死") || text.contains("发火") || text.contains("滚") || text.contains("烦死了") -> set("委屈", 3, "害怕")
            // 以下弱信号：需要敏感度 >= 6
            else -> if (sensitivity >= 6) {
                when {
                    text.contains("爱你") ||
                        text.contains("喜欢你") ||
                        text.contains("想你") ||
                        text.contains("抱抱") ||
                        text.contains("贴贴") ||
                        text.contains("亲亲") -> set("甜蜜", 4, "开心")
                    text.contains("谢谢") || text.contains("感谢") || text.contains("辛苦") -> set("开心", 3, "感激")
                    text.contains("在吗") || text.contains("出来") || text.contains("快出来") || text.contains("找你") -> set("期待", 3, "开心")
                    text.contains("无聊") || text.contains("没意思") || text.contains("好闲") -> set("无聊", 3, null)
                    else -> return
                }
            } else {
                return
            }
        }
    }

    private fun set(newMood: String, inten: Int, sec: String?) {
        mood = newMood
        intensity = inten
        secondary = sec
        lastChangeAt = System.currentTimeMillis()
        save()
    }

    /** 注入用心情描述：如「开心（比较开心，还有点得意）」 */
    fun moodDesc(): String {
        val strength = when (intensity) {
            in 1..2 -> "有点"
            3 -> ""
            4 -> "比较"
            else -> "非常"
        }
        val main = "$strength$mood"
        val sec = secondary?.let { "，还有点$it" } ?: ""
        return "「$main$sec」"
    }

    // ---------------- 梦境（更人类化的“做梦”） ----------------
    /** 需要做新梦：从没做过，或上次做梦已超过 minHours 小时（默认 6） */
    fun dreamNeeded(minHours: Int = 6): Boolean = dream == null || System.currentTimeMillis() - dreamAt > minHours.toLong() * 3600_000L

    /** 记录一个新梦 */
    fun setDream(d: String) {
        dream = d.takeIf { it.isNotBlank() } ?: return
        dreamAt = System.currentTimeMillis()
        save()
    }

    /** 今天是否已主动提过梦（跨天自动重置）。提过则回复/主动消息不再注入梦境 */
    fun dreamMentionedToday(): Boolean = dreamDay == dayKey()

    /** 标记：今天已经主动提过梦了 */
    fun markDreamMentionedToday() {
        dreamDay = dayKey()
        save()
    }

    /** 注入用梦境描述：如「昨晚梦见…」 */
    fun dreamDesc(): String = dream ?: "最近没怎么做梦"
}

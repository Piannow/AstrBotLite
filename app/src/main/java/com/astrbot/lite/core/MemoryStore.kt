package com.astrbot.lite.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 单个用户的画像：结构化已知字段 + 自由 notes。
 * 画像独立持久化、独立注入，不参与上下文总结（不会被总结覆盖）。
 */
data class UserProfile(
    val uid: String,
    var name: String = "", // 显示名 sender
    var gender: String = "", // 性别
    var nickname: String = "", // 期望称呼
    var age: String = "", // 年龄
    var city: String = "", // 城市
    var interests: String = "", // 兴趣
    var notes: String = "", // 其他事实（自由文本，追加）
    var msgCount: Int = 0, // 参与消息数（用于低频提取节流）
    var lastExtractAt: Int = 0, // 上次画像提取时的 msgCount
    var lastSeen: Long = 0L,
    var updatedAt: Long = 0L,
    var favorability: Int = 50, // v1.5.0 好感度 0-100
    var favorNote: String = "", // v1.5.0 好感度备注/综合评价
    var qqId: String = "", // v1.5.0 QQ号（用于名单判断与画像查看）
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("uid", uid)
        o.put("name", name)
        o.put("gender", gender)
        o.put("nickname", nickname)
        o.put("age", age)
        o.put("city", city)
        o.put("interests", interests)
        o.put("notes", notes)
        o.put("msgCount", msgCount)
        o.put("lastExtractAt", lastExtractAt)
        o.put("lastSeen", lastSeen)
        o.put("updatedAt", updatedAt)
        o.put("favorability", favorability)
        o.put("favorNote", favorNote)
        o.put("qqId", qqId)
        return o.toString(2)
    }

    /** 关键画像字段全空 → 认为画像不完整，需要继续提取 */
    fun isIncomplete(): Boolean = gender.isBlank() && nickname.isBlank() && age.isBlank() && city.isBlank() && interests.isBlank() && notes.isBlank()

    /** 渲染注入文本：跳过空字段；全部为空返回 ""（不注入） */
    fun render(): String {
        val sb = StringBuilder()
        fun add(label: String, v: String) {
            if (v.isNotBlank()) sb.append("$label：$v\n")
        }
        add("姓名", name)
        add("性别", gender)
        add("期望称呼", nickname)
        add("年龄", age)
        add("城市", city)
        add("兴趣", interests)
        add("其他", notes)
        if (favorability != 50 || favorNote.isNotBlank()) {
            sb.append("好感度：$favorability/100\n")
            if (favorNote.isNotBlank()) sb.append("综合评价：$favorNote\n")
        }
        return sb.toString().trimEnd()
    }

    /** 把提取到的字段合并进画像（非空则覆盖） */
    fun merge(extracted: Map<String, String>) {
        extracted["姓名"]?.takeIf { it.isNotBlank() }?.let { name = it }
        extracted["性别"]?.takeIf { it.isNotBlank() }?.let { gender = it }
        extracted["期望称呼"]?.takeIf { it.isNotBlank() }?.let { nickname = it }
        extracted["年龄"]?.takeIf { it.isNotBlank() }?.let { age = it }
        extracted["城市"]?.takeIf { it.isNotBlank() }?.let { city = it }
        extracted["兴趣"]?.takeIf { it.isNotBlank() }?.let { interests = it }
        extracted["其他"]?.takeIf { it.isNotBlank() }?.let { notes = it }
    }

    companion object {
        fun fromJson(s: String, uid: String): UserProfile {
            val o = try {
                JSONObject(s)
            } catch (_: Exception) {
                return UserProfile(uid)
            }
            return UserProfile(
                uid = uid,
                name = o.optString("name"),
                gender = o.optString("gender"),
                nickname = o.optString("nickname"),
                age = o.optString("age"),
                city = o.optString("city"),
                interests = o.optString("interests"),
                notes = o.optString("notes"),
                msgCount = o.optInt("msgCount", 0),
                lastExtractAt = o.optInt("lastExtractAt", 0),
                lastSeen = o.optLong("lastSeen", 0),
                updatedAt = o.optLong("updatedAt", 0),
                favorability = o.optInt("favorability", 50),
                favorNote = o.optString("favorNote"),
                qqId = o.optString("qqId"),
            )
        }
    }
}

/**
 * 用户画像存储：filesDir/profiles/{uid}.json（复用 PersonaManager 的文件模式）。
 */
object MemoryStore {
    private lateinit var dir: File
    private val cache = ConcurrentHashMap<String, UserProfile>()

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "profiles").apply { mkdirs() }
    }

    /** 文件名安全化：uid 可能含 QQ 昵称等外部文本，禁止 /、\ 与 .. 直接拼进路径（防越界/静默丢数据） */
    private fun safeName(uid: String): String {
        val s = uid.replace('/', '_').replace('\\', '_')
            .replace(Regex("\\.{2,}"), "_")
            .replace(Regex("[\\x00-\\x1f]"), "")
            .trim()
        return (s.ifBlank { "u_" + Integer.toHexString(uid.hashCode()) }).take(120)
    }

    /** 加载（内存缓存优先），无则读文件，无文件则新建默认 */
    fun load(uid: String): UserProfile {
        cache[uid]?.let { return it }
        val f = File(dir, safeName(uid) + ".json")
        val p = if (f.exists()) {
            try {
                UserProfile.fromJson(f.readText(), uid)
            } catch (_: Exception) {
                UserProfile(uid)
            }
        } else {
            UserProfile(uid)
        }
        cache[uid] = p
        return p
    }

    fun save(p: UserProfile) {
        cache[p.uid] = p
        try {
            File(dir, safeName(p.uid) + ".json").writeText(p.toJson())
        } catch (_: Exception) {
        }
    }

    fun clear(uid: String? = null) {
        if (uid == null) {
            cache.clear()
            dir.listFiles()?.forEach { it.delete() }
        } else {
            cache.remove(uid)
            File(dir, safeName(uid) + ".json").delete()
        }
    }
}

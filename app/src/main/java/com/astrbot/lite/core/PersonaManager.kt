package com.astrbot.lite.core

import android.content.Context
import java.io.File

/**
 * 人设卡管理：filesDir/personas/{id}.json，每张卡一个 JSON 文件
 */
object PersonaManager {
    private lateinit var dir: File

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "personas")
        dir.mkdirs()
    }

    /** 首次使用：一张人设卡都没有时，创建内置默认卡并设为当前生效 */
    fun seedDefaults(ctx: Context) {
        if (list().isNotEmpty()) return
        val p = Persona(
            id = "default",
            name = "默认助手",
            avatar = "🤖",
            description = "通用 AI 聊天助手",
            prompt = "你是 AstrBot Lite 内置的 AI 助手。请始终使用中文，语气自然友好，回答尽量简洁清晰。",
            greeting = "你好呀~我是你的 AI 助手，有什么可以帮你？",
            createdAt = System.currentTimeMillis()
        )
        save(p)
        Config.activePersonaId = p.id
        Logs.info("已创建默认人设卡：“${p.name}”")
    }

    /** 全部人设卡（按创建时间倒序） */
    fun list(): List<Persona> {
        val out = mutableListOf<Persona>()
        dir.listFiles { f -> f.extension == "json" }?.forEach { f ->
            runCatching { out.add(Persona.fromJson(f.readText())) }
        }
        out.sortByDescending { it.createdAt }
        return out
    }

    /** 当前生效的人设卡：配置指定 → 列表第一张 → 内置默认 */
    fun active(): Persona {
        return get(Config.activePersonaId) ?: list().firstOrNull() ?: defaultPersona()
    }

    fun get(id: String): Persona? {
        return list().firstOrNull { it.id == id }
    }

    fun save(p: Persona) {
        File(dir, p.id + ".json").writeText(p.toJson())
    }

    fun delete(id: String) {
        File(dir, id + ".json").delete()
        if (Config.activePersonaId == id) Config.activePersonaId = "default"
    }

    /** 导出为 JSON 文本（分享给别人导入） */
    fun exportJson(p: Persona): String {
        return p.toJson()
    }

    /** 从 JSON 文本导入；id 已存在时自动分配新 id，保存后返回 */
    fun importJson(text: String): Persona {
        var p = Persona.fromJson(text)
        if (get(p.id) != null) {
            p = p.copy(id = newPersonaId())
        }
        save(p)
        return p
    }

    fun setActive(id: String) {
        if (get(id) != null) {
            Config.activePersonaId = id
        }
    }

    fun newPersonaId(): String {
        return "p" + System.currentTimeMillis()
    }

    private fun defaultPersona(): Persona {
        return Persona(
            id = "default",
            name = "默认助手",
            avatar = "🤖",
            description = "",
            prompt = "你是乐于助人的AI助手，请用中文简洁回答。",
            greeting = "你好呀~",
            createdAt = System.currentTimeMillis()
        )
    }
}
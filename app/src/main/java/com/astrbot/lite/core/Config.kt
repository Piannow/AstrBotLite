package com.astrbot.lite.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用配置（SharedPreferences 持久化）
 */
object Config {
    private lateinit var sp: SharedPreferences

    const val PROVIDER_OPENAI = 0
    const val PROVIDER_DEEPSEEK = 1
    const val PROVIDER_ZHIPU = 2
    const val PROVIDER_VOLC = 3
    const val PROVIDER_CUSTOM = 4

    val PROVIDER_NAMES = arrayOf("OpenAI", "DeepSeek", "智谱 GLM", "火山方舟", "自定义")

    val PROVIDER_BASE = arrayOf(
        "https://api.openai.com/v1",
        "https://api.deepseek.com/v1",
        "https://open.bigmodel.cn/api/paas/v4",
        "https://ark.cn-beijing.volces.com/api/v3",
        ""
    )

    val PROVIDER_MODEL = arrayOf(
        "gpt-4o-mini",
        "deepseek-chat",
        "glm-4-flash",
        "doubao-1-5-lite-32k-250115",
        ""
    )

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("astrbot_lite", Context.MODE_PRIVATE)
    }

    // ---------- QQ 官方机器人 ----------
    var qqAppId: String
        get() = sp.getString("qq_appid", "") ?: ""
        set(v) = sp.edit().putString("qq_appid", v).apply()

    var qqSecret: String
        get() = sp.getString("qq_secret", "") ?: ""
        set(v) = sp.edit().putString("qq_secret", v).apply()

    var qqApiBase: String
        get() = sp.getString("qq_api_base", "https://api.sgroup.qq.com") ?: "https://api.sgroup.qq.com"
        set(v) = sp.edit().putString("qq_api_base", v).apply()

    /** 连接环境：prod=正式（默认），sandbox=沙箱 */
    var qqEnv: String
        get() = sp.getString("qq_env", "prod") ?: "prod"
        set(v) = sp.edit().putString("qq_env", v).apply()

    // ---------- AI 对话 ----------
    var aiProvider: Int
        get() = sp.getInt("ai_provider", PROVIDER_DEEPSEEK)
        set(v) = sp.edit().putInt("ai_provider", v).apply()

    var aiBaseUrl: String
        get() = sp.getString("ai_base_url", PROVIDER_BASE[PROVIDER_DEEPSEEK]) ?: ""
        set(v) = sp.edit().putString("ai_base_url", v).apply()

    var aiApiKey: String
        get() = sp.getString("ai_api_key", "") ?: ""
        set(v) = sp.edit().putString("ai_api_key", v).apply()

    var aiModel: String
        get() = sp.getString("ai_model", PROVIDER_MODEL[PROVIDER_DEEPSEEK]) ?: ""
        set(v) = sp.edit().putString("ai_model", v).apply()

    var aiTemperature: Float
        get() = sp.getFloat("ai_temperature", 0.8f)
        set(v) = sp.edit().putFloat("ai_temperature", v).apply()

    var historyLen: Int
        get() = sp.getInt("history_len", 12)
        set(v) = sp.edit().putInt("history_len", v).apply()

    // ---------- 人设卡 ----------
    var activePersonaId: String
        get() = sp.getString("active_persona", "default") ?: "default"
        set(v) = sp.edit().putString("active_persona", v).apply()

    // ---------- 插件 ----------
    var pluginCatalogUrl: String
        get() = sp.getString("plugin_catalog_url", "") ?: ""
        set(v) = sp.edit().putString("plugin_catalog_url", v).apply()

    // ---------- 状态 ----------
    var initialized: Boolean
        get() = sp.getBoolean("initialized", false)
        set(v) = sp.edit().putBoolean("initialized", v).apply()

    var botEnabled: Boolean
        get() = sp.getBoolean("bot_enabled", false)
        set(v) = sp.edit().putBoolean("bot_enabled", v).apply()

    /** 心跳看门狗：最近一次"硬重启应用（进程级）"的时间戳（用于防 crash-loop 冷却） */
    var lastHardRestart: Long
        get() = sp.getLong("last_hard_restart", 0L)
        set(v) = sp.edit().putLong("last_hard_restart", v).apply()

    // ---------- v1.5.0 白名单 / 黑名单 ----------
    var whitelist: String
        get() = sp.getString("whitelist", "") ?: ""
        set(v) = sp.edit().putString("whitelist", v).apply()

    var blacklist: String
        get() = sp.getString("blacklist", "") ?: ""
        set(v) = sp.edit().putString("blacklist", v).apply()

    /** 白名单启用 */
    var whitelistOn: Boolean
        get() = sp.getBoolean("whitelist_on", false)
        set(v) = sp.edit().putBoolean("whitelist_on", v).apply()

    /** 黑名单启用 */
    var blacklistOn: Boolean
        get() = sp.getBoolean("blacklist_on", false)
        set(v) = sp.edit().putBoolean("blacklist_on", v).apply()

    // ---------- v1.5.0 群聊仅@回复 ----------
    var groupOnlyAtReply: Boolean
        get() = sp.getBoolean("group_only_at_reply", false)
        set(v) = sp.edit().putBoolean("group_only_at_reply", v).apply()

    // ---------- v1.5.0 AI池（1.7.72 起移除开关、常驻启用） ----------
    /** 历史开关字段保留读写，但读取恒 true——开关曾把池同步挡在门外，造成“配置页一片空白” */
    var aiPoolEnabled: Boolean
        get() = true
        @Suppress("UNUSED_PARAMETER")
        set(v) { /* 已废弃：写入忽略，读取恒 true */ }

    // ---------- v1.7.37 GitHub 安装镜像 ----------
    /** GitHub 安装镜像名：""=自动（按延迟选最快）；"直连"=官方直连；其余见 PluginConverter.GH_MIRRORS */
    var ghMirror: String
        get() = sp.getString("gh_mirror", "") ?: ""
        set(v) = sp.edit().putString("gh_mirror", v).apply()

    // ---------- 指令面板自动同步（纯内部状态，无 UI） ----------
    /** 各场景 panel_id（JSON map：scope → id；更新失败转创建时用） */
    var qqPanelIds: String
        get() = sp.getString("qq_panel_ids", "") ?: ""
        set(v) = sp.edit().putString("qq_panel_ids", v).apply()
    /** 上次已同步的面板 items JSON（相同则零 API 调用） */
    var qqPanelItems: String
        get() = sp.getString("qq_panel_items", "") ?: ""
        set(v) = sp.edit().putString("qq_panel_items", v).apply()

    // ---------- v1.5.0 知识库 ----------
    var knowledgeEnabled: Boolean
        get() = sp.getBoolean("knowledge_enabled", false)
        set(v) = sp.edit().putBoolean("knowledge_enabled", v).apply()

    // 群消息自动入库开关（默认关；1.7.224 起取消自动入库，避免污染知识库）
    var knowledgeAutoIngest: Boolean
        get() = sp.getBoolean("knowledge_auto_ingest", false)
        set(v) = sp.edit().putBoolean("knowledge_auto_ingest", v).apply()

    // WebView 内核升级开关（默认关：Android 7+ linker 限制 + 14+ DCL 限制下，免安装升级方案会 native 崩溃白屏）
    var webViewUpgradeEnabled: Boolean
        get() = sp.getBoolean("webview_upgrade_enabled", false)
        set(v) = sp.edit().putBoolean("webview_upgrade_enabled", v).apply()

    // ---------- 向量检索（EmbeddingEngine 使用；留空则退化为纯关键词检索） ----------
    var knowledgeEmbedBaseUrl: String
        get() = sp.getString("knowledge_embed_base_url", "") ?: ""
        set(v) = sp.edit().putString("knowledge_embed_base_url", v).apply()

    var knowledgeEmbedApiKey: String
        get() = sp.getString("knowledge_embed_api_key", "") ?: ""
        set(v) = sp.edit().putString("knowledge_embed_api_key", v).apply()

    var knowledgeEmbedModel: String
        get() = sp.getString("knowledge_embed_model", "") ?: ""
        set(v) = sp.edit().putString("knowledge_embed_model", v).apply()

    // ---------- v1.5.0 内心思考 ----------
    var thoughtEnabled: Boolean
        get() = sp.getBoolean("thought_enabled", false)
        set(v) = sp.edit().putBoolean("thought_enabled", v).apply()

    fun isQqConfigured(): Boolean = qqAppId.isNotBlank() && qqSecret.isNotBlank()
    fun isAiConfigured(): Boolean = aiApiKey.isNotBlank() && aiBaseUrl.isNotBlank() && aiModel.isNotBlank()

    fun clearAll() {
        sp.edit().clear().apply()
    }

    fun isWhitelisted(qqId: String): Boolean {
        if (!whitelistOn) return true
        if (whitelist.isBlank()) return true
        return whitelist.split(Regex("[,\\s\\n]+")).any { it.trim() == qqId }
    }

    fun isBlacklisted(qqId: String): Boolean {
        if (!blacklistOn) return false
        if (blacklist.isBlank()) return false
        return blacklist.split(Regex("[,\\s\\n]+")).any { it.trim() == qqId }
    }
}

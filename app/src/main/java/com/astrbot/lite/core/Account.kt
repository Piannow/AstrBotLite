package com.astrbot.lite.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 机器人账号（1.7.232+）：支持同一 App 运行多个官方 QQ 机器人，各账号插件/状态完全隔离。
 *
 * 持久化：accounts.json（存储根 state/ 下），结构：
 * {
 *   "active": "acc_default",
 *   "list": [ { "id","name","appId","secret","apiBase","env","createdAt" }, ... ]
 * }
 *
 * 兼容：旧版只有一个 qqAppId/qqSecret，首次加载时若 accounts.json 不存在则
 * 从 SharedPreferences 迁移出第一条账号 "acc_default"。
 */
data class Account(
    val id: String,
    var name: String,
    var appId: String,
    var secret: String,
    var apiBase: String,
    var env: String,           // "prod" | "sandbox"
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("appId", appId)
        .put("secret", secret)
        .put("apiBase", apiBase)
        .put("env", env)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject): Account = Account(
            id = o.optString("id", "acc_" + System.currentTimeMillis()),
            name = o.optString("name", "机器人"),
            appId = o.optString("appId", ""),
            secret = o.optString("secret", ""),
            apiBase = o.optString("apiBase", "https://api.sgroup.qq.com"),
            env = o.optString("env", "prod"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
    }
}

/**
 * 账号管理器：多账号 CRUD + 激活态 + 单账号 SharedPreferences 视图。
 * 所有持久化集中在 accounts.json；Config 保留全局项（AI 池/知识库/人设），
 * 账号专属配置（appId/secret/env）以 Account 对象形式提供。
 */
object AccountManager {
    private const val MARKER = ".accounts"

    @Volatile private var activeAccountId: String = ""

    /** 加载全部账号（按 createdAt 排序） */
    fun loadAll(): List<Account> {
        val f = File(Storage.root(), "state/accounts.json")
        if (!f.isFile) return emptyList()
        return try {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("list") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Account.fromJson(it) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 当前激活账号 id（0 条账号时返回 ""） */
    fun activeId(): String = activeAccountId.ifBlank {
        // 兜底：从 accounts.json 读 active；没有就取第一条
        val f = File(Storage.root(), "state/accounts.json")
        if (f.isFile) {
            try {
                val o = JSONObject(f.readText())
                val id = o.optString("active", "")
                if (id.isNotBlank()) {
                    activeAccountId = id
                    return id
                }
            } catch (_: Exception) {}
        }
        val first = loadAll().firstOrNull() ?: return ""
        activeAccountId = first.id
        first.id
    }

    fun active(): Account? {
        val id = activeId()
        if (id.isBlank()) return null
        return loadAll().firstOrNull { it.id == id }
    }

    fun get(id: String): Account? = loadAll().firstOrNull { it.id == id }

    /** 切换激活账号（UI 调用） */
    fun setActive(id: String) {
        if (loadAll().none { it.id == id }) return
        activeAccountId = id
        persist()
        Logs.info("[账号] 切换到 ${get(id)?.name ?: id}")
    }

    /** 新增账号并立即激活 */
    fun add(name: String, appId: String, secret: String, apiBase: String, env: String): Account {
        val a = Account("acc_" + System.currentTimeMillis(), name, appId, secret, apiBase, env)
        val list = loadAll().toMutableList()
        list.add(a)
        activeAccountId = a.id
        persistList(list, activeAccountId)
        Logs.info("[账号] 新增 ${a.name}（appId=$appId, env=$env）")
        return a
    }

    /** 删除账号（不能删激活中且唯一的账号） */
    fun remove(id: String): Boolean {
        val list = loadAll().toMutableList()
        if (list.size <= 1) return false
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list.removeAt(idx)
        if (activeAccountId == id) activeAccountId = list.first().id
        persistList(list, activeAccountId)
        Logs.info("[账号] 删除 $id，剩余 ${list.size} 个")
        return true
    }

    /** 修改账号字段（不改变 id） */
    fun update(id: String, name: String, appId: String, secret: String, apiBase: String, env: String): Boolean {
        val list = loadAll().toMutableList()
        val target = list.firstOrNull { it.id == id } ?: return false
        target.name = name
        target.appId = appId
        target.secret = secret
        target.apiBase = apiBase
        target.env = env
        persistList(list, activeAccountId)
        return true
    }

    /** 是否已配置（有激活账号且其 appId/secret 非空） */
    fun isConfigured(): Boolean {
        val a = active() ?: return false
        return a.appId.isNotBlank() && a.secret.isNotBlank()
    }

    /** 当前账号的 apiBase/env（供 QqBotClient 构造使用） */
    fun activeApiBase(): String = active()?.apiBase ?: "https://api.sgroup.qq.com"
    fun activeEnv(): String = active()?.env ?: "prod"

    private fun persist() { persistList(loadAll(), activeAccountId) }

    /** 导出账号快照（供 ConfigExport 使用） */
    fun exportSnapshot(): JSONObject {
        val o = JSONObject()
        o.put("active", activeId())
        val arr = JSONArray()
        loadAll().forEach { arr.put(it.toJson()) }
        o.put("list", arr)
        return o
    }

    /** 从快照恢复账号（覆盖式写回，active 不存在时回落到第一条） */
    fun importSnapshot(obj: JSONObject) {
        try {
            val active = obj.optString("active", "")
            val arr = obj.optJSONArray("list") ?: JSONArray()
            val list = mutableListOf<Account>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                try { list.add(Account.fromJson(o)) } catch (_: Exception) {}
            }
            if (list.isEmpty()) return
            val newActive = if (list.any { it.id == active }) active else list.first().id
            persistList(list, newActive)
            Logs.info("[账号] 已恢复：${list.size} 个账号")
        } catch (e: Exception) {
            Logs.err("[账号] 恢复失败: ${e.message}")
        }
    }

    private fun persistList(list: List<Account>, activeId: String) {
        val o = JSONObject()
        o.put("active", activeId)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        o.put("list", arr)
        val f = File(Storage.root(), "state/accounts.json")
        f.parentFile?.mkdirs()
        f.writeText(o.toString(2))
    }

    /**
     * 首次升级迁移：旧版没有 accounts.json，但 SharedPreferences 里有 qqAppId/qqSecret，
     * 把这套旧凭证变成第一条账号 "acc_default" 并激活，保证老用户无感升级。
     * 同时在有旧版全局插件时，把 plugins/ 迁到 plugins/acc_default/，让默认账号能继续
     * 看到老设备装的插件。
     * 仅在没有任何账号（accounts.json 缺失/为空/损坏）且 Config.qqAppId 非空 时执行一次。
     */
    fun migrateFromLegacy() {
        // 1.7.240：原来只判断 accounts.json 是否存在——文件被空写/写坏（list 为空）时会永久跳过迁移，
        // 表现为“升级后账号信息没有迁移过来、账号页 0 个账号”。改为按实际账号数判断。
        if (loadAll().isNotEmpty()) return
        val appId = Config.qqAppId
        val secret = Config.qqSecret
        if (appId.isBlank()) return
        val a = Account(
            "acc_default", "默认机器人", appId, secret,
            Config.qqApiBase, Config.qqEnv
        )
        activeAccountId = a.id
        persistList(listOf(a), activeAccountId)
        Logs.info("[账号] 从旧版迁移出默认账号（appId=$appId）")
        migrateLegacyPlugins(a.id)
    }

    /**
     * 旧版全局 plugins/ 下的插件复制到 plugins/{accountId}/，让新账号作用域能继承老插件。
     * - 源目录 = 根 plugins/ 下所有「插件子目录」（排除 _backup_*）
     * - 目标 = plugins/{accountId}/同名子目录（已存在则跳过，避免覆盖）
     * - 只复制插件 JSON 与 asset 文件，不删源目录（备份保留）
     * 幂等：跑过一次后目标已存在，下次启动不再重复复制。
     */
    private fun migrateLegacyPlugins(accountId: String) {
        if (accountId.isBlank()) return
        val root = File(Storage.root(), "plugins")
        if (!root.isDirectory) return
        val targetRoot = File(root, accountId)
        if (!targetRoot.isDirectory && !targetRoot.mkdirs()) return
        val subs = root.listFiles { f ->
            f.isDirectory && !f.name.startsWith("_backup_") && f.name != accountId
        } ?: return
        var migrated = 0
        for (sub in subs) {
            val dst = File(targetRoot, sub.name)
            if (dst.exists()) continue   // 已存在=已迁移过/用户自装，跳过
            if (copyDirRecursive(sub, dst)) {
                migrated++
                Logs.info("[插件] 旧版插件迁移到 $accountId：${sub.name}")
            } else {
                Logs.warn("[插件] 旧版插件迁移失败：${sub.name}")
            }
        }
        if (migrated > 0) Logs.info("[插件] 旧版全局插件迁移完成：$migrated 项 → plugins/$accountId/")
    }

    /** 递归复制目录（保留相对路径结构；源目录保留作备份，与 Storage 迁移同策略） */
    private fun copyDirRecursive(src: File, dst: File): Boolean {
        return try {
            if (!dst.exists() && !dst.mkdirs()) return false
            src.walkTopDown().forEach { f ->
                if (f.isDirectory) {
                    File(dst, f.toRelativeString(src)).mkdirs()
                } else {
                    val target = File(dst, f.toRelativeString(src))
                    target.parentFile?.mkdirs()
                    f.copyTo(target, overwrite = false)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}

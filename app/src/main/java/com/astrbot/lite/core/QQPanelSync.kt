package com.astrbot.lite.core

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 指令面板自动同步（纯内部，无任何 UI 入口——用户明确不要手动设置）：
 * 启动后台把「已启用的被动触发插件命令」同步成 QQ 指令面板（/v2/panels），
 * 聊天输入框打 "/" 即弹出「快捷指令」窗口面板（官方 command-panel 示意）。
 *
 * 机制：
 * - 面板命令 = 插件第一条命令（去前导 /，补尾空格，≤14 字符单位；QQ 显示时自动加 "/"）
 *   描述 = 插件名（≤30）；仅被动触发、排除 AstrBot 插件（type=python / id 以 astrbot 开头）
 * - 期望 items JSON 与 Config.qqPanelItems 相同 → **零 API 调用**（无变化不打扰服务端）
 * - 有变化 → 四场景（c2c/group/channel/dm）逐个 更新(PUT，body 包 {"panel":...})/创建(POST)
 * - 每次启动同步一次；插件增删/开关后下次启动自动生效
 * - 1.7.44 的一次性遗留清理（清菜单+删旧面板）沿用其完成标记，已清理过的设备直接跳过
 * - 三道防线：方法级/线程级 catch Throwable + 文件显式判存在——绝不让启动闪退（1.7.43 教训）
 */
internal object QQPanelSync {

    /** 1.7.44 遗留清理的完成标记（同名沿用；设备上已存在则跳过清理） */
    private const val LEGACY_MARKER = "qq_menu_panel_cleaned"

    private val jsonMt = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 期望的面板元素（被动触发、非 AstrBot 插件；help 帮助菜单也进面板） */
    /**
     * 面板条目固定优先级：核心命令强制靠前。
     * 根因（1.7.57 实测日志）：服务端 8 条含「网盘、找」且四场景校验 ✓，但客户端只显示前几条——
     * 旧顺序按插件目录随机排，网盘被挤到第 7、8 位落在 QQ 面板可视区之外（或需滚动）。
     */
    private val PANEL_ORDER = listOf("netdisk", "anime", "galgame", "image2image", "text2image", "help")

    private fun desiredItems(): JSONArray {
        val items = JSONArray()
        val all = PluginManager.installed().filter { p ->
            p.type != "builtin" && p.type != "python" && !p.id.startsWith("astrbot") &&
                p.id != "image2image" && // 图生图已下线（1.7.70）：残留安装不出死条目
                p.enabled && p.trigger == "passive" &&
                p.commands.any { it.match.trim().isNotEmpty() }
        }
        // 固定顺序：网盘/看番/Gal 靠前（在任何 QQ 客户端的可视区），未列出的插件按名称靠后
        val ordered = all.sortedBy { p ->
            val i = PANEL_ORDER.indexOf(p.id)
            if (i >= 0) i else 100
        }
        for (p in ordered) {
            if (true) {
            val cmd = p.commands.firstOrNull()?.match?.trim().orEmpty()
            if (cmd.isEmpty()) continue
            // Galgame助手（合并要求）：面板不再出现裸 gal（发送只会出用法，属冗余），只留两个入口
            if (p.id == "galgame") {
                if (items.length() < 18) {
                    items.put(
                        JSONObject().put("type", "command")
                            .put("name", "gal推荐")
                            .put("desc", "Gal评分推荐")
                    )
                    items.put(
                        JSONObject().put("type", "command")
                            .put("name", "找galgame资源 ")
                            .put("desc", "搜Gal资源直链")
                    )
                }
                continue
            }
            val plain = if (cmd.startsWith("/")) cmd.substring(1) else cmd
            if (items.length() >= 20) break
            items.put(
                JSONObject().put("type", "command")
                    .put("name", clamp(plain + " ", 14))
                    .put("desc", clamp(p.name, 30))
            )
            // 网盘助手额外露出“找网盘资源”入口（其首条命令是 网盘:链接，全网搜是另一入口）
            if (p.id == "netdisk" && items.length() < 20) {
                items.put(
                    JSONObject().put("type", "command")
                        .put("name", "找 ")
                        .put("desc", "搜网盘资源")
                )
            }
            }
        }
        return items
    }

    /** 测试台「打 / 弹菜单」用：与 QQ 指令面板同一份数据的（名称, 描述）快照 */
    fun panelPairs(): List<Pair<String, String>> {
        val arr = desiredItems()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(o.optString("name").trim() to o.optString("desc"))
        }
        return out
    }

    private fun units(s: String): Int {
        var u = 0
        for (ch in s) u += if (ch.code >= 0x2E80) 2 else 1
        return u
    }

    private fun clamp(s: String, maxUnits: Int): String {
        if (units(s) <= maxUnits) return s
        val sb = StringBuilder()
        var u = 0
        for (ch in s) {
            val w = if (ch.code >= 0x2E80) 2 else 1
            if (u + w > maxUnits) break
            sb.append(ch)
            u += w
        }
        return sb.toString().ifBlank { s.take(1) }
    }

    @Volatile
    private var appDirRef: java.io.File? = null

    /** 面板同步互斥：启动与插件变化触发重叠时只跑一份 */
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    fun runOnce(context: Context) {
        appDirRef = context.applicationContext.filesDir
        requestSync()
        // 兜底：启动 15 秒后再同步一次——启动窗口期的安装/升级残留也能补上（幂等，无变化零请求）
        Thread {
            try {
                Thread.sleep(15_000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            requestSync()
        }.apply { isDaemon = true; name = "qq-panel-sync-delay"; start() }
    }

    /**
     * 面板同步请求：启动时 + 插件安装/卸载/启用/停用后自动调用（修“装了插件面板不更新”）。
     * items 与上次一致 → 零 API；有变化 → 重传四场景面板。
     */
    fun requestSync() {
        val dir = appDirRef ?: return
        if (!busy.compareAndSet(false, true)) return
        try {
            Thread {
                try {
                    syncAll(dir)
                } catch (t: Throwable) {
                    Logs.warn("[面板] 同步失败（已拦截，不影响启动）: ${t.message}")
                } finally {
                    busy.set(false)
                }
            }.apply { isDaemon = true; name = "qq-panel-sync"; start() }
        } catch (t: Throwable) {
            busy.set(false)
            Logs.warn("[面板] 启动同步任务失败（已拦截，不影响启动）: ${t.message}")
        }
    }

    private fun syncAll(appDir: java.io.File) {
        // 防线：整体 catch Throwable——任何异常只记日志，绝不杀进程
        try {
            if (Config.qqAppId.isBlank() || Config.qqSecret.isBlank()) return
                    val tok = token()
                    // ① 一次性遗留清理（1.7.44 语义）：清底部菜单 + 删光历史面板；完成过的设备跳过
                    val legacy = File(appDir, LEGACY_MARKER)
                    if (!legacy.exists()) {
                        try {
                            val cur = api(tok, "GET", "/v2/menu")
                            val menuItems = try {
                                JSONObject(cur).optJSONObject("menu")?.optJSONArray("items")?.length() ?: 0
                            } catch (_: Exception) { 0 }
                            if (menuItems > 0) {
                                api(tok, "PUT", "/v2/menu", JSONObject()
                                    .put("menu", JSONObject().put("items", JSONArray())).toString())
                                Logs.info("[面板] 遗留底部菜单已置空（原 ${menuItems} 项）")
                            }
                        } catch (e: Exception) {
                            Logs.warn("[面板] 遗留菜单清理失败: ${e.message}")
                        }
                        var deleted = 0
                        for (scope in listOf("c2c", "group", "channel", "dm")) {
                            try {
                                val resp = api(tok, "GET", "/v2/panels?scope=$scope&limit=50")
                                val records = JSONObject(resp).optJSONArray("records") ?: JSONArray()
                                for (i in 0 until records.length()) {
                                    val id = records.getJSONObject(i).optString("panel_id")
                                    if (id.isBlank()) continue
                                    try { api(tok, "DELETE", "/v2/panels/$id"); deleted++ } catch (_: Exception) { }
                                }
                            } catch (_: Exception) { }
                        }
                        legacy.writeText(System.currentTimeMillis().toString())
                        Logs.info("[面板] 遗留面板清理完成（删除 $deleted 个）")
                    }
                    // ② 期望面板同步：无变化 → 零调用
                    val desired = desiredItems()
                    if (desired.length() == 0) return
                    val desiredStr = desired.toString()
                    if (desiredStr == Config.qqPanelItems && Config.qqPanelIds.isNotBlank()) {
                        return // 与上次同步一致，不打扰服务端
                    }
                    val panelObj = JSONObject().put("items", desired).put("remark", "AstrBot Lite 自动同步")
                    val results = mutableListOf<String>()
                    for (scope in listOf("c2c", "group", "channel", "dm")) {
                        val id = panelId(scope)
                        if (id.isNotBlank()) {
                            try {
                                // 注意：PUT body 必须包一层 {"panel": ...}（1.7.41 的 40030016 就是漏了它）
                                api(tok, "PUT", "/v2/panels/$id", JSONObject().put("panel", panelObj).toString())
                                results.add("$scope(更新)")
                                continue
                            } catch (e: Exception) {
                                Logs.warn("[面板] 更新失败转创建 scope=$scope: ${e.message}")
                            }
                        }
                        val body = JSONObject()
                            .put("scope", scope)
                            .put("target_type", "all")
                            .put("panel", panelObj)
                            .toString()
                        val r = api(tok, "POST", "/v2/panels", body)
                        val pid = JSONObject(r).optString("panel_id")
                        if (pid.isBlank()) throw Exception("创建面板未返回 panel_id: ${r.take(160)}")
                        setPanelId(scope, pid)
                        results.add("$scope(新建)")
                    }
                    Config.qqPanelItems = desiredStr
                    Logs.info("[面板] ✅ 自动同步完成（${desired.length()} 条命令）：${results.joinToString("、")}")
                    // 条目名逐个列出：面板里到底有什么，日志一眼可见（排查“网盘未出现”）
                    val names = buildString {
                        for (i in 0 until desired.length()) {
                            if (i > 0) append("、")
                            append((desired.optJSONObject(i)?.optString("name") ?: "?").trim())
                        }
                    }
                    Logs.info("[面板] 条目：$names")
                    // 上传后逐场景 GET 校验：哪个场景没同步上/条目数不符，日志直接可见
                    val verify = mutableListOf<String>()
                    for (scope in listOf("c2c", "group", "channel", "dm")) {
                        try {
                            val resp = api(tok, "GET", "/v2/panels?scope=$scope&limit=50")
                            val records = JSONObject(resp).optJSONArray("records") ?: JSONArray()
                            var matched = 0
                            for (i in 0 until records.length()) {
                                val items2 = records.optJSONObject(i)?.optJSONObject("panel")?.optJSONArray("items")
                                if (items2 != null && items2.length() == desired.length()) matched++
                            }
                            verify.add("$scope:${if (matched > 0) "✓$matched 个" else "✗无匹配"}")
                        } catch (e: Exception) {
                            verify.add("$scope:校验失败(${e.message?.take(30)})")
                        }
                    }
                    Logs.info("[面板] 校验：${verify.joinToString("，")}")
        } catch (t: Throwable) {
            Logs.warn("[面板] 同步失败（已拦截，不影响启动）: ${t.message}")
        }
    }

    private fun panelId(scope: String): String {
        val map = try { JSONObject(Config.qqPanelIds) } catch (_: Exception) { JSONObject() }
        return map.optString(scope, "")
    }

    private fun setPanelId(scope: String, id: String) {
        val map = try { JSONObject(Config.qqPanelIds) } catch (_: Exception) { JSONObject() }
        map.put(scope, id)
        Config.qqPanelIds = map.toString()
    }

    private fun token(): String {
        val body = JSONObject().put("appId", Config.qqAppId).put("clientSecret", Config.qqSecret).toString()
        val req = Request.Builder()
            .url("https://bots.qq.com/app/getAppAccessToken")
            .post(body.toRequestBody(jsonMt))
            .build()
        http.newCall(req).execute().use { resp ->
            val t = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw Exception("token HTTP ${resp.code}: ${t.take(160)}")
            val tok = JSONObject(t).optString("access_token")
            if (tok.isBlank()) throw Exception("token 响应缺少 access_token: ${t.take(160)}")
            return tok
        }
    }

    private fun api(tok: String, method: String, path: String, body: String? = null): String {
        val base = if (Config.qqEnv == "sandbox") "https://sandbox.api.sgroup.qq.com" else "https://api.sgroup.qq.com"
        val builder = Request.Builder()
            .url(base + path)
            .header("Authorization", "QQBot $tok")
            .header("Content-Type", "application/json")
        val req = when (method.uppercase()) {
            "PUT" -> builder.put((body ?: "{}").toRequestBody(jsonMt))
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonMt))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }.build()
        http.newCall(req).execute().use { resp ->
            val t = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${t.take(300)}")
            return t
        }
    }
}

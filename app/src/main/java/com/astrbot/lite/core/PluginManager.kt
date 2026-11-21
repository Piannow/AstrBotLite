package com.astrbot.lite.core

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import com.astrbot.lite.core.AnimeDownloader
import com.astrbot.lite.core.AnimeDownloader.AnimeEpisode

/** 插件命中结果 */
data class PluginHit(
    val handled: Boolean,
    val reply: String?,       // 直接回复（非空则不再调用 AI）
    val extraPrompt: String?, // 追加的 AI 指令（prompt 型插件）
    val image: String? = null, // 图片直链（image 型插件）：非空则“拉图”下载后作为“图片消息”发出
    val rendered: ByteArray? = null, // 本地渲染出的 PNG 字节（render 型插件）：非空直接作为图片消息发出
    val media: ByteArray? = null, // 生成的/本地取出的图片字节（localimage 型）：非空作为图片消息发出
    val mediaExt: String = "png", // media 的图片扩展名
    val buttons: String? = null   // 交互按钮行（JSON 数组，QQ keyboard.content.rows 结构）
)

/** 可被 LLM 调用的技能（LLM 触发） */
data class LlmSkill(
    val name: String,   // 技能名，如 "/翻译"
    val desc: String,
    val prompt: String  // 执行说明（交给 AI 完成该技能）
)

/** 触发方式名称 */
val TRIGGER_NAMES = mapOf(
    "always" to "总是触发(hook)",
    "passive" to "被动触发(命令)",
    "llm" to "LLM触发(技能)",
    "never" to "从不触发"
)

/**
 * 轻量插件管理：
 * - 市场目录：内置 assets/plugins/catalog.json 或远程 URL（镜像 AstrBot 插件市场结构）
 * - 安装：下载 zip 解压到 plugins/{id}/（统一存储根下），或直接写 plugin.json
 * - 执行：prompt 型（给 AI 追加指令）/ reply 型（固定回复）/ webhook 型（调用外部接口）
 */
object PluginManager {
    private lateinit var ctx: Context
    /** 插件安装根目录（1.7.193：未初始化时不再 lateinit 崩——ext 型插件的
     *  BotEngine 调用可能早于 App.onCreate 的 PluginManager.init，走内部目录兜底，功能不中断） */
    @Volatile private var dir: File? = null
    private const val HELP_PAGE_SIZE = 4
    /** 帮助分页状态：convKey -> 当前页码（仅在 /help 后有效，输入无关内容即清除） */
    private val helpPages = ConcurrentHashMap<String, Int>()
    /** 文生图并发锁：convKey -> 是否已有请求在途。同一会话已有出图在途时，新请求不排队、直接提示"正在出图" */
    private val t2iBusy = ConcurrentHashMap<String, Boolean>()
    /** 生视频并发锁：convKey -> 是否已有生成在途（视频耗时长，异步生成，不阻塞消息队列） */
    private val vBusy = ConcurrentHashMap<String, Boolean>()
    /**
     * 异步出图/出视频的投递回调（由 BotEngine 注入，避免 PluginManager 直接依赖 QQ 客户端）：
     * sessionKey 形如 "group:openid" / "c2c:openid"，BotEngine 解析后调用对应发送接口。
     */
    var videoDeliver: ((sessionKey: String, bytes: ByteArray, ext: String) -> Boolean)? = null
    /** 发链接模式：把视频直链用文本消息发出（避免大文件上传失败），由 BotEngine 注入 */
    var videoUrlDeliver: ((sessionKey: String, url: String) -> Boolean)? = null
    /** 异步生成失败时的通知（BotEngine 注入，给用户回一句失败原因） */
    var videoFailNotify: ((sessionKey: String, text: String) -> Unit)? = null
    /**
     * 通用异步回复（1.7.237）：后台线程查完数据后回“文字 + 可选封面图直链”。
     * 解决 gal/游戏等插件在消息线程里同步发 HTTP、把整条消息队列堵住的问题。
     */
    var asyncReply: ((sessionKey: String, text: String, imageUrl: String?) -> Unit)? = null
    /** 测试台观察者：控制台打开时订阅异步插件结果（修复“测试配置无法触发插件”——
     *  旧版测试模式 lastEvent 为空/client 为 null，异步结果只进日志，控制台永远看不到） */
    var testNotify: ((sessionKey: String, text: String) -> Unit)? = null

    data class AnimeSession(
        val keyword: String = "",
        val animeId: String = "",
        val title: String = "",
        val episodes: List<AnimeEpisode> = emptyList(),
        /** 创建时间：与网盘会话并存时，纯数字按“谁最近活跃”仲裁归属 */
        val createdAt: Long = System.currentTimeMillis()
    )
    private val pendingAnime = ConcurrentHashMap<String, AnimeSession>()

    /** Galgame 待选状态（搜索单/推荐榜 → 作品详情+资源 → 网盘直链） */
    data class GalSession(
        val keyword: String = "",
        val page: Int = 1,
        val total: Int = 0,
        val gte: Boolean = false,
        /** list=作品列表(数字选作品) / work=作品详情(数字选资源) */
        val stage: String = "list",
        val items: List<GalEngine.GalItem> = emptyList(),
        val workId: String = "",
        val workName: String = "",
        val resources: List<GalEngine.GalRes> = emptyList(),
        /** 创建/最近活动时间：与网盘/番剧并存时，纯数字按“谁最近活跃”仲裁归属 */
        val createdAt: Long = System.currentTimeMillis()
    )
    private val pendingGal = ConcurrentHashMap<String, GalSession>()

    /** 游戏资源待选状态（搜索 → 详情+资源 → 网盘直链） */
    data class GameSession(
        val keyword: String = "",
        val page: Int = 1,
        val total: Int = 0,
        /** list=游戏列表(数字选游戏) / res=资源列表(数字选资源取直链) */
        val stage: String = "list",
        val items: List<GameEngine.GameItem> = emptyList(),
        val gameId: Int = 0,
        val gameName: String = "",
        val versionInfo: String = "",
        val resources: List<GameEngine.GameRes> = emptyList(),
        val createdAt: Long = System.currentTimeMillis()
    )
    private val pendingGame = ConcurrentHashMap<String, GameSession>()

    // ---------------- 图生图：会话最近图片（命令与图片分两条消息发也能关联） ----------------
    /** key=会话 → (图片URL列表, 收到时间)；用户先发图、再发命令时用上一张图做参照 */
    private val recentImages = ConcurrentHashMap<String, Pair<List<String>, Long>>()
    /** 最近图片有效期：10 分钟（QQ 图片 URL 有时效，且太久的图不该被当参照） */
    private const val RECENT_IMAGE_TTL_MS = 10 * 60 * 1000L

    private fun noteRecentImage(key: String?, urls: List<String>) {
        if (key.isNullOrBlank() || urls.isEmpty()) return
        val now = System.currentTimeMillis()
        recentImages.entries.removeAll { it.value.second < now - RECENT_IMAGE_TTL_MS }
        recentImages[key] = urls to now
        Logs.info("[图生图] 记住会话最近图片 ${urls.size} 张（10 分钟内有效）")
    }

    /** 取会话最近 10 分钟内的第一张图；无/过期返回 null */
    private fun recentImage(key: String?): String? {
        if (key.isNullOrBlank()) return null
        val e = recentImages[key] ?: return null
        if (System.currentTimeMillis() - e.second > RECENT_IMAGE_TTL_MS) {
            recentImages.remove(key)
            return null
        }
        return e.first.firstOrNull()
    }

    /** 该会话是否有待处理的番剧选集会话 */
    fun hasPendingAnime(sessionKey: String): Boolean = pendingAnime.containsKey(sessionKey)

    /** 处理番剧选集 pending 会话（纯数字/取消/重搜），命中返回 PluginHit，未命中(null) 表示穿透重搜 */
    private fun handleAnimePending(key: String, text: String): PluginHit? {
        val pending = pendingAnime[key] ?: return null
        // ① 纯数字 → 选集
        val idx = text.toIntOrNull()
        if (idx != null) {
            if (idx in 1..pending.episodes.size) {
                val ep = pending.episodes[idx - 1]
                val raw = ep.url ?: ""
                if (raw.isNotBlank()) {
                    val playUrl = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else try { AnimeDownloader.resolvePlayUrl(raw) } catch (_: Throwable) { null }
                    if (playUrl.isNullOrBlank()) {
                        return PluginHit(true, "❌ 解析播放链接失败，请重选或发「取消」重新搜索。", null)
                    }
                    pendingAnime.remove(key)
                    val reply = buildString {
                        append("▶️ ${pending.title} ${ep.title}\n")
                        append("☁️ 1080P 完整流（无需登录/大会员）\n\n")
                        append("📋 m3u8 链接（复制到 VLC/MX 播放）：\n")
                        append("```\n$playUrl\n```\n")
                        append("\n（不可播时发「取消」重新搜索。）")
                    }
                    Logs.info("[番剧] 选集 ${idx}/${pending.episodes.size} → ${ep.title}")
                    return PluginHit(true, reply, null)
                }
                return PluginHit(true, "\u274c 获取播放链接失败，请稍后重试。", null)
            }
            return PluginHit(true, "\u274c 无效编号，请回复 1-${pending.episodes.size} 的编号，或「取消」。", null)
        }
        // ② 取消
        if (text == "取消" || text == "cancel" || text == "取消选集" || text == "c") {
            pendingAnime.remove(key)
            Logs.info("[番剧] 用户取消选集")
            return PluginHit(true, "✅ 已取消选集。", null)
        }
        // ③ 带关键词重搜（如 "看番 鬼灭之刃"）→ 穿透到搜索流程
        return null
    }

    /** 云播搜索第 1 条结果 → 直接进入选集列表；titles=B 站补的集标题（可空，数量对不上则不拼） */
    private fun iyFirstPick(key: String, keyword: String, pick: AnimeDownloader.IyResult, titles: List<String>?): PluginHit {
        val sameSize = titles != null && titles.size == pick.eps.size
        val eps = pick.eps.mapIndexed { i, (n, s) ->
            val t = if (sameSize) titles?.getOrNull(i)?.trim() else null
            val label = if (t.isNullOrEmpty()) n else "$n $t"
            AnimeEpisode(i + 1, label, s, "", "iy")
        }
        pendingAnime[key] = AnimeSession(keyword = keyword, animeId = pick.name, title = pick.name, episodes = eps)
        val reply = buildString {
            append("🎬 ${pick.name}")
            if (pick.describe.isNotBlank()) append("（${pick.describe}）")
            append("\n☁️ 共 ${eps.size} 集 · 1080P 完整流\n\n")
            val show = eps.take(60)
            show.forEachIndexed { i, ep -> append("${i + 1}. ${ep.title}\n") }
            if (eps.size > 60) append("…（共 ${eps.size} 集，直接回复 1-${eps.size} 的编号即可）\n")
            when {
                titles == null -> append("\n⚠️ 集标题获取失败（B 站接口被风控），暂只显示集数，稍后重试可能恢复。")
                !sameSize -> append("\n⚠️ 集标题数量不匹配（B 站 ${titles.size} vs 云播 ${pick.eps.size}），暂只显示集数。")
            }
            append("\n请回复编号选择集数，或「取消」。")
        }
        Logs.info("[云播]「${pick.name}」源=${pick.typeId} ${eps.size}集 标题=${if (sameSize) "有" else "无"}")
        return PluginHit(true, reply, null)
    }

    /**
     * Galgame pending 处理：
     * - list 阶段：数字 → 拉详情+资源（同步两跳），转 work 阶段并回详情（附封面图）
     * - work 阶段：数字 → 免登录取网盘直链（提取码/解压密码）；「刷新」重拉资源
     * - 取消词 → 清状态；其它 → null 穿透（重新走搜索/推荐）
     */
    /** 后台线程统一出口：优先走 asyncReply（支持图片），没有注入时退回纯文本通知 */
    private fun asyncSend(key: String, text: String, imageUrl: String? = null) {
        val cb = asyncReply
        if (cb != null) {
            cb(key, text, imageUrl)
        } else {
            videoFailNotify?.invoke(key, text)
        }
    }

    private fun handleGalPending(key: String, text: String): PluginHit? {
        val g = pendingGal[key] ?: return null
        if (text == "取消" || text == "cancel" || text == "c") {
            pendingGal.remove(key)
            Logs.info("[Gal] 用户取消")
            return PluginHit(true, "✅ 已取消。", null)
        }
        if ((text == "刷新" || text == "更新") && (g.stage == "work" || g.stage == "reslist")) {
            // 1.7.237 内核修复：HTTP 全放后台线程，消息线程只回“处理中”，不再把整条队列堵死
            Thread {
                try {
                    if (g.stage == "work") {
                        val res = GalEngine.resources(g.workId)
                        if (res != null) {
                            pendingGal[key] = g.copy(resources = res, createdAt = System.currentTimeMillis())
                            asyncSend(key, galResourcesReply(g.workName, g.workId, res, true))
                        } else {
                            asyncSend(key, "❌ 资源列表刷新失败（站点繁忙），稍后再试。")
                        }
                    } else {
                        // 资源搜索态：按关键词重搜当前页
                        val pg = GalEngine.searchResources(g.keyword, g.page)
                        when {
                            pg == null -> asyncSend(key, "❌ 资源搜索刷新失败（站点繁忙），稍后再试。")
                            pg.items.isEmpty() -> asyncSend(key, "📄 「${g.keyword}」没有更多结果了。")
                            else -> {
                                pendingGal[key] = g.copy(
                                    total = pg.total, gte = pg.gte, resources = pg.items,
                                    createdAt = System.currentTimeMillis()
                                )
                                asyncSend(key, galResSearchReply(g.keyword, g.page, pg.total, pg.gte, pg.items))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logs.err("[Gal] 刷新异常: ${e.message}")
                    asyncSend(key, "❌ 刷新出错：${e.message}")
                }
            }.apply { isDaemon = true; name = "gal-refresh"; start() }
            return PluginHit(true, if (g.stage == "work") "🔄 正在刷新资源列表…" else "🔄 正在刷新资源搜索…", null)
        }
        val n = text.toIntOrNull() ?: return null
        if (g.stage == "list") {
            val item = g.items.getOrNull(n - 1)
                ?: return PluginHit(true, "❌ 编号超出范围（本页 ${g.items.size} 项），或「取消」。", null)
            Thread {
                try {
                    val det = GalEngine.detail(item.id)
                    if (det == null) {
                        asyncSend(key, "❌ 查询作品详情失败（网络或站点异常），稍后再试。")
                        return@Thread
                    }
                    val res = GalEngine.resources(item.id)
                    val resources = res ?: emptyList()
                    pendingGal[key] = g.copy(stage = "work", workId = item.id, workName = det.name, resources = resources)
                    Logs.info("[Gal] 详情 ${item.id}「${det.name}」 资源 ${resources.size} 条（ok=${res != null}）")
                    asyncSend(key, galDetailReply(det, item.id, resources, res != null), det.coverUrl.ifBlank { null })
                } catch (e: Exception) {
                    Logs.err("[Gal] 详情异常: ${e.message}")
                    asyncSend(key, "❌ 查询作品详情出错：${e.message}")
                }
            }.apply { isDaemon = true; name = "gal-detail"; start() }
            return PluginHit(true, "🔍 正在查询「${item.name}」…", null)
        }
        // work 阶段：数字取该资源直链
        val r = g.resources.getOrNull(n - 1)
            ?: return PluginHit(true, "❌ 编号超出范围（资源共 ${g.resources.size} 条）；发「刷新」重试，或「取消」。", null)
        Thread {
            try {
                val dl = GalEngine.downloadUrls(r.id)
                if (dl == null) {
                    asyncSend(key, "❌ 取直链失败：该资源${if (r.state != "valid") "已失效" else "暂时不可用"}（站点繁忙可稍后重试）。")
                    return@Thread
                }
                val reply = buildString {
                    val who = r.workName.ifBlank { g.workName }
                    append("⬇️ ${who.ifBlank { "资源" }}${if (r.title.isNotBlank()) " · ${r.title.take(30)}" else ""}［${GalEngine.typeLabel(r.type)}］\n")
                    dl.urls.forEach { append("🔗 $it\n") }
                    if (dl.extractionCode.isNotBlank()) append("🔑 提取码：${dl.extractionCode}\n")
                    if (dl.archivePassword.isNotBlank()) append("📦 解压密码：${dl.archivePassword}\n")
                    // 简介是站方标明密码/使用说明的地方——原样附出（无简介资源不显示此行）
                    if (r.note.isNotBlank()) append("📝 简介：${r.note.take(160)}\n")
                    append("📄 资源页：${GalEngine.PAGE_BASE}${g.workId}\n")
                }
                Logs.info("[Gal] 资源直链 ${r.id} → ${dl.urls.size} 条")
                asyncSend(key, reply)
            } catch (e: Exception) {
                Logs.err("[Gal] 取直链异常: ${e.message}")
                asyncSend(key, "❌ 取直链出错：${e.message}")
            }
        }.apply { isDaemon = true; name = "gal-dl"; start() }
        return PluginHit(true, "⏳ 正在取直链…", null)
    }

    // ---------------- 游戏资源 pending 处理 ----------------

    private fun handleGamePending(key: String, text: String): PluginHit? {
        val g = pendingGame[key] ?: return null
        if (text == "取消" || text == "cancel" || text == "c") {
            pendingGame.remove(key)
            Logs.info("[游戏] 用户取消")
            return PluginHit(true, "✅ 已取消。", null)
        }
        if ((text == "刷新" || text == "更新") && g.stage == "res") {
            val det = GameEngine.detail(g.gameId)
            if (det != null) {
                pendingGame[key] = g.copy(resources = det.resources, versionInfo = det.versionInfo, createdAt = System.currentTimeMillis())
                return PluginHit(true, gameResourcesReply(g.gameName, det.resources, det.versionInfo, true), null)
            }
            return PluginHit(true, "❌ 资源刷新失败（站点繁忙），稍后再试。", null)
        }
        val n = text.toIntOrNull() ?: return null
        if (g.stage == "list") {
            val item = g.items.getOrNull(n - 1)
                ?: return PluginHit(true, "❌ 编号超出范围（本页 ${g.items.size} 项），或「取消」。", null)
            val det = GameEngine.detail(item.id)
                ?: return PluginHit(true, "❌ 查询游戏详情失败（网络或站点异常），稍后再试。", null)
            val res = det.resources
            if (res.isEmpty()) {
                pendingGame.remove(key)
                return PluginHit(true, buildString {
                    append("🎮 ${det.item.title}\n")
                    append("📄 ${GameEngine.PAGE_BASE}${item.id}\n\n")
                    append("❌ 该页暂无网盘资源链接（可能仅含 Steam/官网下载）")
                }, null, image = null)
            }
            pendingGame[key] = g.copy(
                stage = "res",
                gameId = item.id,
                gameName = item.title,
                versionInfo = det.versionInfo,
                resources = res,
                createdAt = System.currentTimeMillis()
            )
            Logs.info("[游戏] 详情 ${item.id}「${item.title}」资源 ${res.size} 条")
            return PluginHit(true, gameResourcesReply(item.title, res, det.versionInfo, true), null)
        }
        // res 阶段：数字取该资源直链
        val r = g.resources.getOrNull(n - 1)
            ?: return PluginHit(true, "❌ 编号超出范围（资源共 ${g.resources.size} 条）；发「刷新」重试，或「取消」。", null)
        val reply = buildString {
            append("⬇️ ${g.gameName} ［${r.provider}］\n")
            if (r.version.isNotBlank()) append("📝 ${r.version.take(60)}\n")
            if (r.size.isNotBlank()) append("💾 ${r.size}\n")
            append("🔗 ${r.directUrl}\n")
            append("📄 资源页：${GameEngine.PAGE_BASE}${g.gameId}\n")
        }
        Logs.info("[游戏] 资源 ${r.panId} → ${r.directUrl.take(60)}")
        return PluginHit(true, reply, null)
    }

    private fun gameResourcesReply(gameName: String, res: List<GameEngine.GameRes>, versionInfo: String, ok: Boolean): String =
        buildString {
            if (!ok) {
                append("⚠️ 资源列表获取失败（站点繁忙），发「刷新」重试\n")
                return@buildString
            }
            if (res.isEmpty()) {
                append("📦 该游戏暂无网盘资源\n")
                return@buildString
            }
            append("🎮 $gameName\n")
            if (versionInfo.isNotBlank()) append("📝 版本：$versionInfo\n")
            append("\n📦 网盘资源（${res.size}）：\n")
            res.forEachIndexed { i, r ->
                append(i + 1).append(". [").append(r.provider).append("]")
                if (r.size.isNotBlank()) append(" · ${r.size}")
                append("\n")
            }
            append("\n回复编号取网盘直链；「刷新」重试；「取消」退出")
        }

    private fun galResLine(i: Int, r: GalEngine.GalRes): String {
        val sb = StringBuilder()
        sb.append(i).append(". [").append(GalEngine.typeLabel(r.type)).append("]")
        if (r.title.isNotBlank()) sb.append(" ").append(r.title.take(30))
        if (r.size.isNotBlank()) sb.append(" · ").append(r.size)
        if (r.providers.isNotEmpty()) sb.append(" · ").append(r.providers.joinToString("/"))
        if (r.languages.isNotBlank()) sb.append(" · ").append(r.languages)
        // 简介标明的密码状态（要密码=🔒并在取直链时附简介；写明无密码=🔓；没提密码不标）
        GalEngine.pwdHint(r.note)?.let { sb.append(" · ").append(it) }
        sb.append(" · ").append(if (r.state == "valid") "✅有效" else "⚠️已失效")
        return sb.toString()
    }

    /** 「找galgame资源」搜索结果页 */
    private fun galResSearchReply(kw: String, page: Int, total: Int, gte: Boolean, items: List<GalEngine.GalRes>): String =
        buildString {
            append("📦 资源搜索「$kw」共 ${total}${if (gte) "+" else ""} 条 · 第 $page 页\n\n")
            items.forEachIndexed { i, r ->
                append(i + 1).append(". [").append(GalEngine.typeLabel(r.type)).append("] ")
                append(r.workName.ifBlank { "未知作品" }.take(20))
                if (r.title.isNotBlank()) append(" · ${r.title.take(24)}")
                if (r.size.isNotBlank()) append(" · ${r.size}")
                if (r.providers.isNotEmpty()) append(" · ").append(r.providers.joinToString("/"))
                if (r.languages.isNotBlank()) append(" · ${r.languages}")
                GalEngine.pwdHint(r.note)?.let { append(" · ").append(it) }
                append(" · ").append(if (r.state == "valid") "✅有效" else "⚠️已失效")
                append("\n")
            }
            append("\n回复编号取网盘直链；同关键词再发一次翻下一页；「取消」退出")
        }

    private fun galResourcesReply(workName: String, workId: String, res: List<GalEngine.GalRes>, ok: Boolean): String =
        buildString {
            if (!ok) {
                append("⚠️ 资源列表获取失败（站点繁忙），发「刷新」重试\n")
                return@buildString
            }
            if (res.isEmpty()) {
                append("📦 该作品暂无资源\n")
                return@buildString
            }
            append("📦 资源（${res.size}）：\n")
            res.forEachIndexed { i, r -> append(galResLine(i + 1, r)).append("\n") }
            append("回复资源编号取网盘直链；「刷新」重拉资源；「取消」退出")
        }

    private fun galDetailReply(det: GalEngine.GalDetail, workId: String, res: List<GalEngine.GalRes>, ok: Boolean): String =
        buildString {
            append("🎮 ${det.name}")
            if (det.origin.isNotBlank()) append("（${det.origin}）")
            append("\n")
            if (det.aliases.isNotEmpty()) append("别名：${det.aliases.take(2).joinToString("、")}\n")
            if (det.date.isNotBlank()) append("📅 发售 ${det.date}")
            if (det.maker.isNotBlank()) append(if (det.date.isNotBlank()) " · 🏢 ${det.maker}" else "🏢 ${det.maker}")
            append("\n")
            val rating = det.rating?.let { r -> "⭐%.2f".format(r).trimEnd('0').trimEnd('.') }
            append(if (rating != null) "$rating（${det.ratingCount} 评）" else "⭐ 暂无评分")
            append(" · 👁 ${det.views} · ❤ ${det.likes}\n")
            if (det.platforms.isNotBlank()) append("🖥 ${det.platforms}")
            if (det.languages.isNotBlank()) {
                if (det.platforms.isNotBlank()) append(" · ")
                append("🌐 ${det.languages}")
            }
            if (det.platforms.isNotBlank() || det.languages.isNotBlank()) append("\n")
            append("🔗 ${GalEngine.PAGE_BASE}$workId\n")
            append(galResourcesReply(det.name, workId, res, ok))
        }

    /** 参照图取字节：http(s) 直连；content://（测试台选图）走 ContentResolver；file:// 读文件；上限 10MB */
    private fun fetchRefImage(url: String): ByteArray? = try {
        val bytes = when {
            url.startsWith("content://") ->
                ctx.contentResolver.openInputStream(android.net.Uri.parse(url))?.use { it.readBytes() }
            url.startsWith("file://") ->
                java.io.File(android.net.Uri.parse(url).path ?: "").readBytes()
            else -> http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.bytes()
            }
        }
        when {
            bytes == null -> null
            bytes.size > 10 * 1024 * 1024 -> {
                Logs.warn("[插件] 参照图超过 10MB（${bytes.size / 1024}KB），已放弃")
                null
            }
            else -> bytes
        }
    } catch (e: Exception) {
        Logs.warn("[插件] 参照图获取失败: ${e.message}")
        null
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        ctx = context.applicationContext
        // 1.7.190：统一存储根下 plugins/（Android/data/com.astrbot.lite/plugins）
        dir = Storage.dir("plugins")
        // AstrBot 市场 JSON 磁盘缓存目录（秒开 + 后台刷新）——1.7.190 移到统一存储根 market/
        PluginConverter.initCache(Storage.dir("market"))
        ensureBuiltinPlugins()
    }

    /** 插件安装根目录：未初始化时走 Storage（已迁移到外置存储根 plugins/，1.7.190 起统一存储）；
     *  Storage 尚未初始化时退回内部 filesDir（此时外置根还未解析，数据仍在内部旧位置） */
    private fun dir(): File = dir ?: Storage.dir("plugins").also { d -> dir = d }

    /** 已下线的内置插件：启动时清掉旧版本残留安装，避免它们继续出现在列表或生效 */
    private val RETIRED_PLUGIN_IDS = listOf("humanizer", "memory", "personality")

    /**
     * 启动时同步内置插件定义：**只更新「已安装」的插件**，绝不自动安装缺失的。
     *
     * 为什么不能再自动补装：catalog 里的内置插件全部会在「Lite 市场」展示，
     * 用户按需手动安装即可。若启动时把缺失的自动装回来，用户卸载过的插件会在
     * 下次启动/重装后"自己回来"，已安装列表里凭空多出一堆没手动装过的插件。
     *
     * 保留「更新已安装插件」这一步：catalog 里新增了配置项/命令时，
     * 老版本装过的插件也能跟着升级（用户的开关状态与已填配置值原样保留）。
     */
    private fun ensureBuiltinPlugins() {
        removeRetiredPlugins()
        val installedMap = installed().associateBy { it.id }
        val isFirstRun = installedMap["help"] == null
        for (entry in loadCatalog()) {
            // 远程插件（市场 zip）：定义以 zip 内自带的为准，不用 catalog 条目覆盖
            if (entry.downloadUrl.isNotBlank()) continue
            // 首次运行：只自动装 help 插件（核心必需），其余由用户在 Lite 市场手动安装
            if (isFirstRun && entry.id != "help") continue
            // 非首次运行：只同步已安装插件，不自动安装缺失的
            val existing = installedMap[entry.id] ?: continue
            val mergedCfg = mergeConfig(existing.config, entry.config)
            val updated = entry.copy(
                enabled = existing.enabled,
                // 文本替换升级：旧 rules（源→目标）一次性迁移进 find/replace 分对字段，老规则不丢
                config = if (entry.id == "wordreplace") migrateWordReplaceConfig(existing.config, mergedCfg) else mergedCfg
            )
            File(pluginDir(entry.id), "plugin.json").writeText(updated.toJson())
        }
    }

    /** 删除已下线插件的残留目录（连同配置）；不存在则跳过 */
    private fun removeRetiredPlugins() {
        for (id in RETIRED_PLUGIN_IDS) {
            val d = pluginDir(id)
            if (!d.exists()) continue
            try {
                if (d.deleteRecursively()) Logs.info("[插件] 已移除下线插件 $id")
            } catch (e: Exception) {
                Logs.warn("[插件] 移除下线插件 $id 失败：${e.message}")
            }
        }
    }

    /** 文本替换旧版单框 rules → 分对字段 find1..5/replace1..5（升级一次性迁移，旧规则原样带入） */
    private fun migrateWordReplaceConfig(old: List<PluginConfigField>, merged: List<PluginConfigField>): List<PluginConfigField> {
        val legacy = old.firstOrNull { it.key == "rules" }?.value?.trim().orEmpty()
        if (legacy.isBlank()) return merged
        if (merged.firstOrNull { it.key == "find1" }?.value?.isNotBlank() == true) return merged
        val pairs = legacy.lines()
            .mapNotNull { l ->
                val t = l.trim()
                val i = t.indexOf('→')
                if (t.isEmpty() || i <= 0) null else t.substring(0, i).trim() to t.substring(i + 1).trim()
            }
            .filter { it.first.isNotBlank() && it.first.length <= 1000 }
            .take(5)
        if (pairs.isEmpty()) return merged
        Logs.info("[插件] 文本替换：旧规则迁移 ${pairs.size} 条到分对字段")
        return merged.map { f ->
            val n = when {
                f.key.startsWith("find") -> f.key.removePrefix("find").toIntOrNull()
                f.key.startsWith("replace") -> f.key.removePrefix("replace").toIntOrNull()
                else -> null
            } ?: return@map f
            val pair = pairs.getOrNull(n - 1) ?: return@map f
            if (f.key.startsWith("find")) f.copy(value = pair.first) else f.copy(value = pair.second)
        }
    }

    /** 合并配置：保留用户已填的值，新增的 key 用默认值填充 */
    private fun mergeConfig(old: List<PluginConfigField>, new: List<PluginConfigField>): List<PluginConfigField> {
        val map = old.associateBy { it.key }.toMutableMap()
        for (f in new) {
            if (!map.containsKey(f.key)) map[f.key] = f
        }
        return new.map { f -> map[f.key] ?: f }
    }

    // ---------------- 已安装 ----------------
    fun installed(): List<PluginInfo> {
        val list = mutableListOf<PluginInfo>()
        val subs = dir().listFiles { f -> f.isDirectory } ?: return list
        for (sub in subs) {
            val f = File(sub, "plugin.json")
            if (f.exists()) {
                try {
                    list.add(PluginInfo.fromJson(f.readText()))
                } catch (_: Exception) {
                }
            }
        }
        list.sortBy { it.name }
        return list
    }

    fun installed(id: String): PluginInfo? = installed().firstOrNull { it.id == id }

    /** 插件是否启用（引擎读取开关用） */
    fun isEnabled(id: String): Boolean = installed(id)?.enabled == true

    /** 把插件的 config 读成 key→value（引擎读取配置用） */
    fun configMap(id: String): Map<String, String> =
        installed(id)?.config?.associate { it.key to it.value } ?: emptyMap()

    private fun pluginDir(id: String): File = File(dir(), id)

    /** 安全解析插件目录内的子路径：拒绝绝对路径与 .. 越界（zip 条目与资产路径不可信） */
    private fun safeChild(target: File, rel: String): File? {
        return try {
            val f = File(target, rel.replace('\\', '/'))
            val base = target.canonicalPath
            val path = f.canonicalPath
            if (path == base || path.startsWith(base + File.separator)) f else null
        } catch (_: Exception) {
            null
        }
    }

    /** 从市场条目安装（可下载 zip，也可直接写配置）；走断点续传，失败/取消清理残留不留半成品 */
    fun installFromCatalog(entry: PluginInfo): Boolean {
        // 1.7.201：安装前先备份已有同名插件，失败/取消时恢复，成功时丢弃
        val backup = backupExistingPlugin(entry.id)
        val target = pluginDir(entry.id)
        target.mkdirs()
        return try {
            if (entry.downloadUrl.isNotBlank()) {
                // 下载 zip（1.7.120：单源直接下载，无断点续传）
                InstallProgress.update("下载插件包…", 10)
                var lastTick = 0L
                var lastDone = 0L
                var speed = 0L
                val bytes = PluginConverter.downloadSingle(
                    entry.downloadUrl,
                    shouldCancel = { InstallProgress.isCancelled() },
                    onProgress = { done, total ->
                        val now = System.currentTimeMillis()
                        if (now - lastTick < 100 && total > 0 && done < total) return@downloadSingle
                        if (lastTick > 0 && now > lastTick) {
                            val dt = now - lastTick
                            if (dt > 0) speed = ((done - lastDone) * 1000L / dt)
                        }
                        lastTick = now
                        lastDone = done
                        val kbSpeed = if (speed >= 1024) "${speed / 1024} MB/s" else "$speed KB/s"
                        if (total > 0) {
                            val pct = (done * 45 / total).toInt().coerceIn(0, 45)
                            InstallProgress.update("下载 $kbSpeed ${done / 1024}/${total / 1024} KB", pct)
                        } else {
                            InstallProgress.update("下载 $kbSpeed ${done / 1024} KB", 10)
                        }
                    }
                )
                if (bytes == null) {
                    cleanupInstall(entry.id, backup)
                    if (InstallProgress.isCancelled()) {
                        InstallProgress.finish(false, "已取消安装（残留已清理）")
                    } else {
                        InstallProgress.finish(false, "下载失败（已清理残留）")
                    }
                    return false
                }
                if (InstallProgress.isCancelled()) {
                    cleanupInstall(entry.id, backup)
                    InstallProgress.finish(false, "已取消安装（残留已清理）")
                    return false
                }
                // 解压到插件目录
                InstallProgress.update("写入插件目录…", 60)
                val zis = ZipInputStream(java.io.ByteArrayInputStream(bytes))
                var ze = zis.nextEntry
                while (ze != null) {
                    if (InstallProgress.isCancelled()) {
                        zis.close()
                        cleanupInstall(entry.id, backup)
                        InstallProgress.finish(false, "已取消安装（残留已清理）")
                        return false
                    }
                    // 安全解压：zip 条目名不可信，拒绝 ../ 与绝对路径（Zip Slip 防护）
                    val outFile = safeChild(target, ze.name)
                    if (outFile == null) {
                        Logs.warn("插件包含越界条目，已跳过: ${ze.name}")
                    } else if (ze.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zis.copyTo(out) }
                    }
                    zis.closeEntry()
                    ze = zis.nextEntry
                }
                zis.close()
                // 解压后若没有 plugin.json，则写回市场条目
                if (!File(target, "plugin.json").exists()) {
                    File(target, "plugin.json").writeText(entry.copy(enabled = true).toJson())
                }
            } else {
                File(target, "plugin.json").writeText(entry.copy(enabled = true).toJson())
            }
            Logs.info("插件“${entry.name} v${entry.version}”安装成功")
            discardBackup(backup)
            QQPanelSync.requestSync()
            InstallProgress.finish(true, "安装成功")
            true
        } catch (e: Exception) {
            Logs.err("插件 ${entry.name} 安装失败: ${e.message}")
            cleanupInstall(entry.id, backup)
            InstallProgress.finish(false, "安装失败（已清理残留）")
            false
        }
    }

    fun uninstall(id: String) {
        // 无进度回调的旧入口（保持兼容）→ 走带进度的实现，丢弃进度
        uninstall(id) { _, _, _, _ -> }
    }

    /**
     * 卸载插件（1.7.147 进度化）：逐文件删除并回写进度，避免 deleteRecursively 同步阻塞卡死 UI。
     * @param onProgress (百分比 0-99, 当前文件相对路径, 已删文件数, 总文件数)——UI 弹进度框实时显示
     * @return 是否全部删除成功
     */
    fun uninstall(id: String, onProgress: (Int, String, Int, Int) -> Unit): Boolean {
        pluginSkillCache.remove(id)
        val liteDir = pluginDir(id)
        val pyDir = try { com.astrbot.lite.core.PythonRuntime.pluginDir(id) } catch (_: Exception) { null }
        // 收集全部待删文件（两目录树），用于百分比 + 明细
        val allFiles = ArrayList<java.io.File>()
        fun walk(root: java.io.File?) {
            root?.let { r ->
                val stack = ArrayDeque(r.listFiles()?.toList().orEmpty())
                while (stack.isNotEmpty()) {
                    val f = stack.removeFirst()
                    if (f.isDirectory) stack.addAll(f.listFiles()?.toList().orEmpty()) else allFiles.add(f)
                }
            }
        }
        walk(liteDir)
        if (pyDir != null) walk(pyDir)
        val total = allFiles.size
        Logs.info("[卸载] $id 共 $total 个文件待删（Lite 目录 ${if (liteDir.exists()) "在" else "无"}，Python 目录 ${if (pyDir != null && pyDir.exists()) "在" else "无"}）")
        var done = 0
        for (f in allFiles) {
            try {
                f.delete()
            } catch (e: Exception) {
                Logs.warn("[卸载] 删文件失败 ${f.name}: ${e.message}")
            }
            done++
            val pct = if (total > 0) done * 95 / total else 95
            onProgress(pct, f.name, done, total)   // 到 95%（留 5% 给删空目录 + Python 环境收尾）
        }
        // 收尾：删空目录（两棵树自下而上删目录节点）
        fun pruneDirs(root: java.io.File?) {
            root?.let { r ->
                r.walkBottomUp().forEach { d ->
                    try { d.delete() } catch (_: Exception) {}
                }
            }
        }
        pruneDirs(liteDir)
        if (pyDir != null) pruneDirs(pyDir)
        // Python 环境收尾（别名清理等）
        try {
            com.astrbot.lite.core.PythonRuntime.uninstall(id)
        } catch (_: Exception) {
        }
        Logs.info("[卸载] 插件已卸载: $id（$total 文件 + 空目录）")
        QQPanelSync.requestSync()
        onProgress(100, "完成", total, total)
        return true
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val p = installed(id) ?: return
        File(pluginDir(id), "plugin.json").writeText(p.copy(enabled = enabled).toJson())
        pluginSkillCache.remove(id)
        Logs.info("[插件] ${p.name} ${if (enabled) "启用" else "停用"}")
        QQPanelSync.requestSync()
    }

    /** 设置触发方式：always / passive / llm / never（帮助菜单不可修改） */
    fun setTrigger(id: String, trigger: String) {
        if (id == "help") return
        val p = installed(id) ?: return
        File(pluginDir(id), "plugin.json").writeText(p.copy(trigger = trigger).toJson())
        Logs.info("[插件] ${p.name} 触发方式改为 ${TRIGGER_NAMES[trigger] ?: trigger}")
    }

    /** 保存插件配置（保存后反向同步 AI 池：配置页 ↔ 池 双向一致，两边都不空白） */
    fun setConfig(id: String, config: List<PluginConfigField>) {
        val p = installed(id) ?: return
        File(pluginDir(id), "plugin.json").writeText(p.copy(config = config).toJson())
        AiPool.pullFromPluginConfig(id, config)
        val keyNames = config.map { it.key }.joinToString(", ")
        Logs.info("[插件] ${p.name} 配置已保存（$keyNames）")
    }

    /** 按 key 更新配置值（WebUI 交互写回用）：已有字段保留 label/type/secret 等元数据，仅改 value；
     *  未知 key 追加为 string 字段。返回是否有变更。 */
    fun setConfigValues(id: String, values: Map<String, String>): Boolean {
        val p = installed(id) ?: return false
        if (values.isEmpty()) return false
        val byKey = p.config.associateBy { it.key }.toMutableMap()
        var changed = false
        for ((k, v) in values) {
            if (k.isBlank()) continue
            val old = byKey[k]
            if (old == null) {
                val nf = PluginConfigField(key = k, label = k, value = v)
                byKey[k] = nf
                changed = true
            } else if (old.value != v) {
                byKey[k] = old.copy(value = v)
                changed = true
            }
        }
        if (!changed) return false
        // 保持原顺序（新 key 追加在后）
        val merged = p.config.mapNotNull { byKey.remove(it.key) } + byKey.values
        setConfig(id, merged)
        Logs.info("[插件] ${p.name} 配置已由 WebUI 更新（${values.keys.joinToString(", ")}）")
        return true
    }

    /**
     * 导入本地插件文件（Lite plugin.json / 含 plugin.json 或 main.py 的 zip / .py 源码）。
     * 若是 AstrBot Python 插件，自动转换为 Lite 插件后安装。
     */
    fun importLocal(name: String, bytes: ByteArray): Boolean {
        // pid 提前声明：失败/取消路径用它清理残留，不留半成品
        var pid = ""
        // 1.7.201：backup 提到 try 外，catch 块（兜底 cleanupInstall）也能访问
        var backup: File? = null
        return try {
            var jsonText: String? = null
            var logText = ""
            var assets: Map<String, ByteArray> = emptyMap()
            val isZip = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
            InstallProgress.start("识别插件内容…")
            Logs.info("[转换] 开始导入「$name」（${if (isZip) "zip 包 ${bytes.size} 字节" else "源码/JSON ${bytes.size} 字节"}）")
            if (isZip) {
                val cr = PluginConverter.convertZip(bytes)
                jsonText = cr.json
                logText = cr.log
                assets = cr.assets
            } else {
                val s = String(bytes, Charsets.UTF_8).trim()
                if (s.startsWith("{")) {
                    jsonText = s
                    logText = "已识别为现成 Lite 插件（plugin.json），直接安装"
                    Logs.info("[转换] 「$name」是现成 plugin.json，无需转换")
                } else {
                    val cr = PluginConverter.convertSource(s, null)
                    jsonText = cr.json
                    logText = cr.log
                }
            }
            // 转换日志：识别结论行用 INFO（给用户看），细节行用 DEBUG（排障用）
            logText.lines().filter { it.isNotBlank() }.forEach {
                val line = it.replace("⚠", "！").replace("✅", "✔")
                if (line.startsWith("✔") || line.startsWith("！") || line.contains("已识别") || line.contains("无法转换")) {
                    Logs.info("[转换]「$name」 $line")
                } else {
                    Logs.debug("[转换]「$name」 $line")
                }
            }
            if (jsonText.isNullOrBlank()) {
                Logs.err("[转换]「$name」失败：未找到可转换的内容")
                InstallProgress.finish(false, "转换失败：未找到可转换的内容")
                return false
            }
            pid = try { org.json.JSONObject(jsonText).optString("id") } catch (_: Exception) { "" }
            // 1.7.201：安装前备份已有同名插件，失败/取消时恢复，成功时丢弃
            backup = if (pid.isNotEmpty()) backupExistingPlugin(pid) else null
            if (InstallProgress.isCancelled()) {
                if (pid.isNotEmpty()) cleanupInstall(pid, backup)
                InstallProgress.finish(false, "已取消安装（残留已清理）")
                return false
            }
            InstallProgress.update("写入插件目录…", 60)
            if (!installPluginJson(jsonText, assets)) {
                if (pid.isNotEmpty()) cleanupInstall(pid, backup)
                InstallProgress.finish(false, "写入插件目录失败（残留已清理）")
                return false
            }
            // Web 离线转换：插件已进 Python 环境且带 web 视图时执行（离线可交互）
            val apyDir = if (pid.isNotEmpty()) PythonRuntime.pluginDir(pid) else null
            if (apyDir != null) {
                try {
                    InstallProgress.update("检测 Web 视图…", 70)
                    WebConverter.convert(apyDir) { stage, d, t ->
                        InstallProgress.update(stage, (70 + if (t > 0) d * 27 / t else 27).coerceIn(70, 97))
                    }
                } catch (e: Exception) {
                    Logs.warn("[Web转换] 收尾失败（不影响安装）: ${e.message}")
                }
            }
            if (InstallProgress.isCancelled()) {
                if (pid.isNotEmpty()) cleanupInstall(pid, backup)
                InstallProgress.finish(false, "已取消安装（残留已清理）")
                return false
            }
            discardBackup(backup)
            InstallProgress.finish(true, "安装成功")
            true
        } catch (e: Exception) {
            Logs.err("导入插件失败: ${e.message}")
            if (pid.isNotEmpty()) cleanupInstall(pid, backup)
            InstallProgress.finish(false, "导入失败（残留已清理）：${e.message}")
            false
        }
    }

    /** 把 plugin.json 文本安装为本地插件；assets 为随插件持久化的二进制资产（相对路径 → 字节） */
    fun installPluginJson(json: String, assets: Map<String, ByteArray> = emptyMap()): Boolean {
        return try {
            val p = PluginInfo.fromJson(json)
            val target = pluginDir(p.id)
            target.mkdirs()
            File(target, "plugin.json").writeText(p.copy(enabled = true).toJson())
            for ((rel, bytes) in assets) {
                if (rel.isBlank() || bytes.isEmpty()) continue
                val f = safeChild(target, rel)
                if (f == null) {
                    Logs.warn("插件资产路径越界，已跳过: $rel")
                    continue
                }
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
            }
            if (assets.isNotEmpty()) Logs.info("插件资产已持久化 ${assets.size} 项")
            pluginSkillCache.remove(p.id)
            Logs.info("插件“${p.name} v${p.version}”安装成功")
            QQPanelSync.requestSync()
            true
        } catch (e: Exception) {
            Logs.err("安装插件失败: ${e.message}")
            false
        }
    }

    /**
     * 安装失败/取消的残留清理（1.7.106）：删掉半成品的 Python 环境副本与 Lite 插件目录，
     * 不留残件。仅在"失败/取消"路径调用（成功路径是正常安装结果）。
     *
     * 1.7.201：新增 backup 参数——安装前已有同名插件时，先备份到 plugins/_backup_{pid}_{ts}/；
     * 失败/取消时把备份恢复到原插件目录（拷贝，不删备份直到恢复成功）；成功时调用方调
     * discardBackup 删除。backup 为 null（新装场景）时行为与旧版完全一致。
     */
    private fun cleanupInstall(pid: String, backup: File? = null) {
        try {
            PythonRuntime.pluginDir(pid)?.deleteRecursively()
        } catch (e: Exception) {
            Logs.warn("[安装] 清理 Python 半成品失败: ${e.message}")
        }
        try {
            pluginDir(pid).deleteRecursively()
        } catch (e: Exception) {
            Logs.warn("[安装] 清理插件目录失败: ${e.message}")
        }
        if (backup == null || !backup.isDirectory) {
            Logs.info("[安装] 已清理半成品残留: $pid")
            return
        }
        // 恢复备份（拷贝而非 rename：恢复中途异常时备份仍在磁盘上，可人工恢复）
        try {
            val liteBk = File(backup, "lite")
            if (liteBk.isDirectory) copyTree(liteBk, pluginDir(pid))
            val pyBk = File(backup, "py")
            if (pyBk.isDirectory) {
                val pyTarget = PythonRuntime.pluginDir(pid) ?: File(Storage.dir("plugins_python"), pid)
                copyTree(pyBk, pyTarget)
            }
            Logs.info("[安装] 已恢复原插件 $pid（来自 ${backup.name}）")
        } catch (e: Exception) {
            Logs.err("[安装] 恢复原插件失败 $pid: ${e.message}（备份保留在 ${backup.absolutePath}）")
            return
        }
        runCatching { backup.deleteRecursively() }
    }

    /** 1.7.201：安装前把已有同名插件备份到 plugins/_backup_{pid}_{ts}/。
     *  无原插件时返回 null（cleanupInstall 退化为普通清理，行为与旧版一致）。 */
    private fun backupExistingPlugin(pid: String): File? {
        val liteDir = pluginDir(pid)
        val pyDir = PythonRuntime.pluginDir(pid)
        if (!liteDir.isDirectory && pyDir?.isDirectory != true) return null
        val backup = File(dir(), "_backup_${pid}_${System.currentTimeMillis()}")
        return try {
            backup.mkdirs()
            if (liteDir.isDirectory) copyTree(liteDir, File(backup, "lite"))
            if (pyDir != null && pyDir.isDirectory) copyTree(pyDir, File(backup, "py"))
            Logs.info("[安装] 已备份原插件 $pid → ${backup.name}")
            backup
        } catch (e: Exception) {
            Logs.warn("[安装] 备份原插件失败 $pid: ${e.message}（继续安装，失败时尝试恢复）")
            backup.takeIf { it.isDirectory }
        }
    }

    /** 1.7.201：安装成功后丢弃备份 */
    private fun discardBackup(backup: File?) {
        if (backup == null) return
        runCatching { backup.deleteRecursively() }
    }

    /** 目录树拷贝（目标不存在则创建）。失败抛异常，由调用方决定回滚策略。 */
    private fun copyTree(src: File, dst: File) {
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) throw java.io.IOException("mkdirs 失败: ${dst.absolutePath}")
            src.listFiles()?.forEach { copyTree(it, File(dst, it.name)) }
        } else if (src.isFile) {
            dst.parentFile?.mkdirs()
            src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        }
    }

    /** 取消检查点：用户按❌后在下载/解压/阶段边界调用；true=已取消并完成清理+终态 */
    private fun cancelCheckpoint(pid: String, backup: File? = null): Boolean {
        if (!InstallProgress.isCancelled()) return false
        cleanupInstall(pid, backup)
        InstallProgress.finish(false, "已取消安装（残留已清理）")
        return true
    }

    /** 安装收尾：检测 web 视图 → Web 离线转换（外链资源本地化）→ 进度完成。失败不影响安装结果。
     *  1.7.201：新增 backup 参数——收尾阶段取消/失败时带上备份恢复；成功时丢弃备份。 */
    private fun finishAstrBotInstall(pid: String, name: String, pyOk: Boolean, backup: File? = null): Boolean {
        try {
            InstallProgress.update("检测 Web 视图…", 70)
            if (InstallProgress.isCancelled()) {
                cleanupInstall(pid, backup)
                InstallProgress.finish(false, "已取消安装（残留已清理）")
                return false
            }
            val apyDir = PythonRuntime.pluginDir(pid)
            val meta = apyDir?.let { dir ->
                WebConverter.convert(dir) { stage, d, t ->
                    if (InstallProgress.isCancelled()) return@convert
                    val pct = 70 + if (t > 0) d * 27 / t else 27
                    InstallProgress.update(stage, pct.coerceIn(70, 97))
                }
            }
            if (meta != null) {
                Logs.info("[Web转换]「$name」检测到 Web 视图，已执行离线转换（外链可离线交互）")
            }
            if (InstallProgress.isCancelled()) {
                cleanupInstall(pid, backup)
                InstallProgress.finish(false, "已取消安装（残留已清理）")
                return false
            }
            InstallProgress.finish(true, "安装成功")
            discardBackup(backup)
            if (InstallProgress.isCancelled()) return false
        } catch (e: Exception) {
            Logs.warn("[Web转换]「$name」收尾失败（不影响安装）: ${e.message}")
            InstallProgress.finish(true, "安装成功")
        }
        return true
    }

    /** 从 AstrBot 官方市场条目安装：下载 → 装进统一 Python 环境（不转换，直接跑原始 main.py）
     *  → 转换生成 plugin.json → Web 离线转换（检测到 web 视图时）。全程驱动 InstallProgress。 */
    fun installFromAstrBot(entry: AstrBotMarketEntry): Boolean {
        val zipUrl = entry.zipUrl
        if (zipUrl == null) {
            Logs.err("插件 ${entry.shownName} 没有可用的下载地址")
            InstallProgress.finish(false, "缺少下载地址")
            return false
        }
        // 1.7.201：安装前先备份已有同名插件，失败/取消时恢复，成功时丢弃
        val pid = entry.name
        val backup = backupExistingPlugin(pid)
        return try {
            InstallProgress.start("下载插件包…")
            // 1.7.171 修复"下载全程卡 0%"：分块下载的进度回调发生在下载线程池线程上，
            // 那里没有安装任务的 ThreadLocal 绑定（cur()==null）→ 旧 update() 会把每一条
            // 进度静默丢弃。这里先取到本线程的任务 id，回调里用 updateFor 显式带上。
            val taskId = InstallProgress.currentId()
            val lastTick = java.util.concurrent.atomic.AtomicLong(0L)
            var bytes = PluginConverter.downloadWithMirror(
                zipUrl,
                sourceLabel = "AstrBot",
                onProgress = { done, total ->
                    // 节流：~120ms 一次（跨线程用原子量，避免 4 线程互相覆盖导致时序错乱）
                    val now = System.currentTimeMillis()
                    val prev = lastTick.get()
                    if (now - prev < 120 && total > 0 && done < total) return@downloadWithMirror
                    lastTick.set(now)
                    if (total > 0) {
                        val pct = (done * 45 / total).toInt().coerceIn(0, 45)
                        InstallProgress.updateFor(taskId, "下载插件包 ${done / 1024}/${total / 1024} KB", pct)
                    } else {
                        InstallProgress.updateFor(taskId, "下载插件包 ${done / 1024} KB", 10)
                    }
                },
                shouldCancel = { InstallProgress.isCancelled() },
                // 只换阶段文案，不重置百分比（旧版 update(stage, 0) 会让进度条中途退回 0）
                onSourceChange = { stage -> InstallProgress.stageFor(taskId, stage) }
            )
            if (bytes == null) {
                Logs.err("插件 ${entry.shownName} 下载失败：$zipUrl")
                if (cancelCheckpoint(pid, backup)) return false
                cleanupInstall(pid, backup)
                InstallProgress.finish(false, "下载失败（已清理半成品）")
                return false
            }
            if (cancelCheckpoint(pid, backup)) return false
            // ① 原始 zip 装进统一 Python 环境（共享 site-packages，不转换）
            val pyOk = try {
                InstallProgress.update("写入 Python 统一环境…", 46)
                val ok = PythonRuntime.installZip(pid, bytes,
                    onEntry = { done, total ->
                        if (InstallProgress.isCancelled()) return@installZip
                        val pct = if (total > 0) 46 + done * 14 / total else 52
                        InstallProgress.update("写入 Python 统一环境 $done/$total", pct.coerceIn(46, 60))
                    },
                    shouldCancel = { InstallProgress.isCancelled() }
                )
                if (InstallProgress.isCancelled()) {
                    // 解压中途取消：installZip 循环已停，这里清理+终态
                    cleanupInstall(pid, backup)
                    InstallProgress.finish(false, "已取消安装（残留已清理）")
                    return false
                }
                if (ok) Logs.info("插件 ${entry.shownName} 已装入 Python 统一环境（共享 site-packages）")
                ok
            } catch (e: Exception) {
                Logs.err("插件 ${entry.shownName} Python 环境安装失败: ${e.message}")
                false
            }
            if (cancelCheckpoint(pid, backup)) return false
            // ② 仍走转换生成 plugin.json（命令匹配/触发）；转换失败不阻断——只要 Python 环境装上了，
            //    用户也能手动发命令触发（由垫片注册表分发）
            InstallProgress.update("转换生成插件配置…", 62)
            // lenient：真 Python 环境可跑多文件包——不因"相对导入"拒绝，保证 _conf_schema 配置与 README 文档迁移
            val cr = PluginConverter.convertZip(bytes, lenient = true)
            cr.log.lines().filter { it.isNotBlank() }.forEach { Logs.info(it) }
            val json = cr.json
            if (json.isNullOrBlank()) {
                if (!pyOk) {
                    Logs.err("插件 ${entry.shownName} 转换失败且 Python 环境未装成功：${cr.log}")
                    cleanupInstall(pid, backup)
                    InstallProgress.finish(false, "转换失败且 Python 环境未装成功（残留已清理）")
                    return false
                }
                // Python 装上了但转换失败：生成一个最小 plugin.json（被动触发、命令由垫片分发）
                val fallback = org.json.JSONObject().apply {
                    put("id", pid)
                    put("name", entry.shownName)
                    put("version", entry.version.ifBlank { "1.0.0" })
                    put("author", entry.author)
                    put("description", entry.desc)
                    put("type", "python")
                    put("trigger", "passive")
                    put("enabled", true)
                    put("commands", org.json.JSONArray().put(org.json.JSONObject().apply {
                        put("match", pid)
                        put("type", "prefix")
                    }))
                    put("config", org.json.JSONArray())
                    put("doc", "该插件以原生 Python 运行于统一 Python 环境；输入「$pid <参数>」触发。")
                }
                installPluginJson(fallback.toString(), emptyMap())
                // 1.7.147：fallback 分支的 id=pid（直接是目录名），别名登记防御性补上（幂等，同值跳过）
                try { PythonRuntime.putAlias(pid, pid) } catch (_: Exception) {}
                return finishAstrBotInstall(pid, entry.shownName, pyOk, backup)
            }
            // 转换成功：把 type 标为 python，Lite 目录 id 强制用 pid（让 Lite / Python / backup 键统一）。
            // 1.7.202：删旧 cid 目录（id 变更后旧目录残留）。
            val convertedJson = org.json.JSONObject(json)
            val cid = convertedJson.optString("id")
            if (pyOk) convertedJson.put("type", "python")
            convertedJson.put("id", pid)
            val patched = convertedJson.toString()
            if (cid.isNotBlank() && cid != pid) {
                try {
                    val oldDir = File(dir(), cid)
                    if (oldDir.isDirectory && oldDir != pluginDir(pid)) {
                        oldDir.deleteRecursively()
                        Logs.info("[安装] 已清除历史 Lite 目录 plugins/$cid（id 统一为 $pid）")
                    }
                } catch (e: Exception) {
                    Logs.warn("[安装] 清除历史 Lite 目录失败: ${e.message}")
                }
            }
            installPluginJson(patched, cr.assets)
            finishAstrBotInstall(pid, entry.shownName, pyOk, backup)
        } catch (e: Exception) {
            Logs.err("安装 ${entry.shownName} 失败: ${e.message}")
            cleanupInstall(pid, backup)
            InstallProgress.finish(false, "安装失败：${e.message}（残留已清理）")
            false
        }
    }

    /** 从粘贴的源码安装：转换 → 安装 */
    fun installFromSource(mainPy: String, metaYaml: String?): Boolean {
        val cr = PluginConverter.convertSource(mainPy, metaYaml)
        cr.log.lines().filter { it.isNotBlank() }.forEach { Logs.info(it) }
        val json = cr.json
        if (json.isNullOrBlank()) {
            Logs.err("转换失败：${cr.log}")
            return false
        }
        return installPluginJson(json)
    }

    /** 最近一次 loadCatalog() 的失败原因；成功/无错误为空串（Lite 市场 UI 展示诊断用） */
    @Volatile
    var catalogError: String = ""

    // ---------------- 市场目录 ----------------
    fun loadCatalog(): List<PluginInfo> {
        catalogError = ""
        val text = if (Config.pluginCatalogUrl.isBlank()) {
            // 内置目录
            try {
                ctx.assets.open("plugins/catalog.json").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                catalogError = "读取内置插件目录失败: ${e.message}"
                Logs.err(catalogError)
                return emptyList()
            }
        } else {
            // 远程目录
            try {
                val resp = http.newCall(
                    Request.Builder().url(Config.pluginCatalogUrl).build()
                ).execute()
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                resp.body?.string() ?: ""
            } catch (e: Exception) {
                catalogError = "拉取远程插件目录失败: ${e.message}"
                Logs.err(catalogError)
                return emptyList()
            }
        }
        val list = mutableListOf<PluginInfo>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                // 与已安装状态合并：enabled 以已安装为准
                val id = obj.optString("id")
                val installedP = installed(id)
                val merged = PluginInfo.fromJson(obj).copy(
                    enabled = installedP?.enabled ?: obj.optBoolean("enabled", true)
                )
                list.add(merged)
            }
        } catch (e: Exception) {
            catalogError = "解析插件目录失败: ${e.message}"
            Logs.err(catalogError)
        }
        return list
    }

    // ---------------- 消息处理 ----------------

    /**
     * 被动命令匹配（re: 正则命令除外）：**词边界匹配**——命令后必须是 空格/冒号/斜杠，
     * 或紧贴数字（如 打开2、选择3）。绝不能只按 startsWith(m) 匹配——"打开电视""选择困难症"
     * "帮助我"等日常话语会以"打开/选择/帮助"开头，被插件误拦截（这是网盘"用不了"的根因）。
     * AstrBot 转换的插件命令带 "/"（如 /翻译），同时认不带斜杠的写法（"翻译 你好"）。
     */
    private fun commandMatches(text: String, m: String): Boolean {
        val alt = if (m.startsWith("/")) m.substring(1) else null
        return text == m ||
            text.startsWith(m + " ") ||
            text.startsWith(m + ":") ||
            text.startsWith(m + "/") ||
            Regex("^" + Regex.escape(m) + "[0-9０-９][\\s\\S]*").matches(text) ||
            (alt != null && (text == alt || text.startsWith(alt + " ") || text.startsWith(alt + ":")))
    }

    /** 1.7.201：安全正则构造器——插件配置里手写的 `re:` 可能是非法模式（如 `re:[`），
     *  直接 `Regex(...)` 会抛 `PatternSyntaxException` 冒泡到 BotEngine 顶层 catch → 回复兜底文案。
     *  安全语义：非法正则 = 不匹配（与"命令没命中"一致），不阻塞其它合法命令。 */
    private fun safeRegex(pattern: String): Regex? = try {
        Regex(pattern)
    } catch (e: java.util.regex.PatternSyntaxException) {
        Logs.warn("[插件] 非法正则已忽略：${pattern.take(80)}（${e.message?.take(60)}）")
        null
    }

    /** 收集本条消息命中的全部被动插件（每插件取第一个匹配命令）。
     *  跳过：help（专门处理）/ builtin / **python 型**（python 只走垫片分发，不参与抢命令——
     *  旧版没跳过它，c.prompt 为空时 handled=true 且无回复 = 消息被静默吞掉）。 */
    private fun collectPassiveMatches(text: String): List<Pair<PluginInfo, PluginCommand>> {
        val out = mutableListOf<Pair<PluginInfo, PluginCommand>>()
        for (p in installed()) {
            if (p.id == "help") continue
            if (p.type == "builtin") continue
            if (p.type == "python") continue
            if (!p.enabled || p.trigger != "passive") continue
            for (c in p.commands) {
                val m = c.match.trim()
                if (m.isEmpty()) continue
                val ok = if (m.startsWith("re:")) safeRegex(m.substring(3))?.matches(text) ?: false else commandMatches(text, m)
                if (ok) {
                    out.add(p to c)
                    break // 每插件只取第一个匹配命令
                }
            }
        }
        return out
    }

    /** 执行型插件能真正干活（出图/网盘/番剧等）；prompt 型只把话交给 AI（撞车时最易“抢错”） */
    private fun execPriority(type: String): Int = when (type) {
        "netdisk", "anime", "image2image", "text2image", "imageengine",
        "video", "render", "localimage", "image", "webhook", "galgame", "game" -> 0
        "reply" -> 1
        else -> 2 // prompt 等
    }

    /** 选优：执行型优先 > 命令更长（更具体）> id 字典序（稳定，不再随目录顺序漂移） */
    private fun pickCandidate(cands: List<Pair<PluginInfo, PluginCommand>>): Pair<PluginInfo, PluginCommand>? =
        cands.minWithOrNull(
            compareBy(
                { execPriority(it.first.type) },
                { -it.second.match.trim().length },
                { it.first.id }
            )
        )

    /**
     * 被动触发（命令）命中检测：只匹配 trigger=passive 的已启用插件。
     * 命中 reply 型 → 直接回复；命中 prompt 型 → 返回 extraPrompt 交给 AI；
     * netdisk 型 → 交给网盘助手处理；webhook 型 → 调用外部接口。
     * help 插件（内置，不可卸载）由 handleHelp 专门处理：仅 /help 触发、+/− 翻页。
     */
    fun handle(message: String, sessionKey: String? = null, ev: QqMessageEvent? = null): PluginHit {
        var text = message.trim()
        // 图生图：每条带图消息都记住（用户常先发图、下一条再发「图生图 描述」）
        if (ev != null && ev.imageUrls.isNotEmpty()) noteRecentImage(sessionKey, ev.imageUrls)
        if (sessionKey != null) {
            handleHelp(sessionKey, text)?.let {
                Logs.info("[插件] help 命中：${text.take(20)}")
                return it
            }
        }
        // "/命令" 归一化：QQ 菜单按钮/用户习惯发「/看番 xxx」——去掉 / 后能命中插件命令则等价处理。
        // 只对不带 / 的命令归一化（/help、/anime 等自带斜杠的命令走原匹配，不受影响）。
        if (text.startsWith("/") && text.length > 1) {
            val stripped = text.substring(1)
            val hit = installed().any { p ->
                p.enabled && p.trigger == "passive" && p.type != "builtin" && p.commands.any { c ->
                    val m = c.match.trim()
                    when {
                        m.isEmpty() -> false
                        // 1.7.56：正则命令（网盘的 搜索/搜/找 等）也认 / 前缀写法——
                        // 旧版跳过 re: 导致「/找 关键词」既不归一化、正则又匹配不上，直接落空进 AI
                        m.startsWith("re:") -> safeRegex(m.substring(3))?.matches(stripped) ?: false
                        m.startsWith("/") -> false // 自带斜杠的命令走原生匹配
                        else -> commandMatches(stripped, m)
                    }
                }
            }
            if (hit) {
                Logs.info("[插件] /命令 归一化：「$text」→「$stripped」")
                text = stripped
            }
        }
        // 纯数字归属仲裁（1.7.54 升级：网盘×番剧×Galgame 三方）：
        // 三方都可能在等数字——网盘（全网搜结果/目录项）、番剧（选集）、Galgame（作品列表/资源列表）。
        // 按“谁最近活跃”归属：旧残留会话（如早前的网盘全网搜）不再抢走后来会话的数字
        // （用户实测：gal 资源列表输 3 被残留网盘会话抢走报“序号超出范围”）。仲裁结果打日志便于复诊。
        if (sessionKey != null && text.matches(Regex("""[1-9]\d*"""))) {
            val ndOn = Netdisk.hasSession(sessionKey) || Netdisk.hasGlobalSearch(sessionKey)
            val anime = pendingAnime[sessionKey]
            val gal = pendingGal[sessionKey]
            val game = pendingGame[sessionKey]
            val owner = listOfNotNull(
                if (ndOn) "网盘" to Netdisk.lastActiveAt else null,
                anime?.let { "番剧" to it.createdAt },
                gal?.let { "Galgame" to it.createdAt },
                game?.let { "游戏" to it.createdAt }
            ).maxByOrNull { it.second }?.first
            when (owner) {
                "网盘" -> {
                    Logs.info("[插件] 数字仲裁「$text」→ 网盘（最近活跃 ${Netdisk.lastActiveAt}）")
                    return PluginHit(true, Netdisk.handle(sessionKey, text, emptyList()), null)
                }
                "番剧" -> {
                    Logs.info("[插件] 数字仲裁「$text」→ 番剧选集")
                    val r = handleAnimePending(sessionKey, text)
                    if (r != null) return r
                    val pend = pendingAnime[sessionKey]
                    return PluginHit(true, "❌ 无效编号，请回复 1-${pend?.episodes?.size ?: 0} 的编号，或「取消」。", null)
                }
                "Galgame" -> {
                    Logs.info("[插件] 数字仲裁「$text」→ Galgame（创建 ${gal?.createdAt}）")
                    val r = handleGalPending(sessionKey, text)
                    if (r != null) return r
                    return PluginHit(true, "❌ 无效编号，或「取消」。", null)
                }
                "游戏" -> {
                    Logs.info("[插件] 数字仲裁「$text」→ 游戏资源（创建 ${game?.createdAt}）")
                    val r = handleGamePending(sessionKey, text)
                    if (r != null) return r
                    return PluginHit(true, "❌ 无效编号，或「取消」。", null)
                }
            }
        }
        // 番剧/Galgame/游戏 pending：取消词（"取消"/"cancel"）——不进 LLM
        if (sessionKey != null && (text == "取消" || text == "cancel" || text == "取消选集")) {
            if (pendingAnime.containsKey(sessionKey)) {
                pendingAnime.remove(sessionKey)
                Logs.info("[插件] 番剧助手：取消选集")
                return PluginHit(true, "✅ 已取消选集。", null)
            }
            if (pendingGal.containsKey(sessionKey)) {
                pendingGal.remove(sessionKey)
                Logs.info("[插件] Galgame助手：取消")
                return PluginHit(true, "✅ 已取消。", null)
            }
            if (pendingGame.containsKey(sessionKey)) {
                pendingGame.remove(sessionKey)
                Logs.info("[插件] 游戏助手：取消")
                return PluginHit(true, "✅ 已取消。", null)
            }
        }
        // 网盘会话内的控制词：% 回退 / 回退 / 返回 / 搜索xxx / 找xxx（全网搜索/翻页）。
        // 这些不是插件命令（不进 commands，免得污染帮助、误匹配日常话语），
        // 但也必须在这里放行——否则永远到不了 Netdisk，% 回退就是不触发（同一个坑）。
        if (sessionKey != null && Netdisk.isControlText(text) &&
            (Netdisk.hasSession(sessionKey) || Netdisk.hasGlobalSearch(sessionKey))
        ) {
            Logs.info("[插件] 网盘助手：会话控制 ${text.take(20)}")
            val ndCfg = installed("netdisk")?.config ?: emptyList()
            return PluginHit(true, Netdisk.handle(sessionKey, text, ndCfg), null)
        }
        // ── 冲突治理（1.7.47）：先收集全部命中，再按优先级选一个执行。
        // 旧版“先到先得”按目录顺序，别的插件命令撞车时会把消息抢走/吞掉（插件冲突根因）：
        // prompt 型命中只把话交给 AI（不出图）、python 型命中 c.prompt 为空时 handled=true 但无回复（消息被吃）。
        val cands = collectPassiveMatches(text)
        val chosen = pickCandidate(cands)
        if (chosen == null) {
            val passive = installed().count { it.enabled && it.trigger == "passive" }
            // Python 原生插件兜底：被动命令都没命中时，交给统一 Python 环境分发（AstrBot 市场插件）
            if (PythonRuntime.isReady()) {
                val pyIds = installed()
                    .filter { it.enabled && it.trigger == "passive" && (it.type == "python" || PythonRuntime.hasPlugin(it.id)) }
                    .map { it.id }
                if (pyIds.isNotEmpty()) {
                    val r = PythonRuntime.dispatchEx(pyIds, text, sessionKey ?: "*")
                    if (r != null) {
                        Logs.info("[插件] Python 原生插件命中（${pyIds.size} 个候选）→ ${(r.reply ?: "（按钮消息）").take(60)}")
                        return PluginHit(true, r.reply, null, buttons = r.buttons)
                    }
                }
            }
            Logs.info("[插件] 无被动命令命中（检查了 $passive 个被动插件）")
            return PluginHit(false, null, null)
        }
        if (cands.size > 1) {
            // 冲突可视化：一眼看出谁在抢这条消息、最终选了谁
            Logs.warn("[插件] 命令冲突「${text.take(20)}」：候选 [${cands.joinToString("、") { it.first.name }}] → 选用 ${chosen.first.name}")
        }
        run {
            val p = chosen.first
            val c = chosen.second
            val m = c.match.trim()
            if (c.match.trim().isNotEmpty()) {
                    // netdisk 型：网盘助手（列目录/进目录/取直链）
                    if (p.type == "netdisk") {
                        Logs.info("[插件] 命中 ${p.name} v${p.version}（网盘助手）命令: $m")
                        val reply = Netdisk.handle(sessionKey ?: "default", text, p.config)
                        return PluginHit(true, reply, null)
                    }
                    // anime 型：番剧搜索与播放（云播版）
                    if (p.type == "anime") {
                        Logs.info("[插件] 命中 ${p.name} v${p.version}（番剧）命令: $m")
                        val key = sessionKey ?: "default"
                        val argStr = text.removePrefix(m.trim()).trim()
                        val pending = pendingAnime[key]
                        if (pending != null) {
                            // 待选状态：数字=选集 / 取消 / 返回 null=新关键词重搜
                            val hit = handleAnimePending(key, argStr)
                            if (hit != null) return hit
                        }
                        if (argStr.isBlank() || argStr.length < 2) {
                            return PluginHit(true, "❌ 请输入番剧名称，例如：「看番 鬼灭之刃」", null)
                        }
                        val query = argStr
                        Thread {
                            // 云播搜索（isyour.love 官方接口），永远取第 1 条结果进选集
                            val iy = AnimeDownloader.isyourSearch(query)
                            if (iy.isEmpty()) {
                                videoFailNotify?.invoke(key, "❌ 未找到「$query」，换个关键词试试")
                            } else {
                                // 补集标题：云播源只有「第01集」，用 B 站接口（无需登录）按完整片名搜，集数对齐才采用；失败不阻塞
                                val first = iy.first()
                                val titles = AnimeDownloader.fetchEpisodeTitles(first.name.ifBlank { query }, first.eps.size)
                                val hit = iyFirstPick(key, query, first, titles)
                                videoFailNotify?.invoke(key, hit.reply ?: "")
                            }
                        }.apply { isDaemon = true; name = "anime-search"; start() }
                        return PluginHit(true, "🔍 搜索「$query」中...", null)
                    }
                    // webhook 型：调用外部接口
                    if (p.type == "webhook" && p.webhookUrl.isNotBlank()) {
                        Logs.info("[插件] 命中 ${p.name}（webhook）→ ${p.webhookUrl.take(60)}")
                        val reply = callWebhook(p.webhookUrl, text)
                        return PluginHit(true, reply, null)
                    }
                    // image 型：发图/拉图——随机取一张配置里的图片直链，先拉图下载再作为图片消息发出
                    if (p.type == "image") {
                        val src = p.config.firstOrNull { it.key == "imageUrl" || it.key == "images" }?.value?.trim().orEmpty()
                        val urls = src.split(Regex("[,\\n\\r]")).map { it.trim() }.filter { it.isNotBlank() }
                        val url = urls.randomOrNull()
                        if (url == null) {
                            Logs.info("[插件] 命中 ${p.name}（图片/发图拉图）但未配置图片直链")
                            return PluginHit(true, "还没配置图片直链：去插件页的「配置」里填图片地址（一行一个或用逗号分隔），我就能帮你发图啦。", null)
                        }
                        Logs.info("[插件] 命中 ${p.name}（图片/发图拉图）→ ${url.take(60)}（共 ${urls.size} 张，随机取一张）")
                        return PluginHit(true, null, null, url)
                    }
                    // render 型：本地渲染举牌/文字图——取命令后的文字，画到底图上渲染成图片消息发出
                    if (p.type == "render") {
                        val noSlash = if (m.startsWith("/")) m.substring(1) else m
                        val userText = when {
                            text.startsWith(m) -> text.substring(m.length)
                            text.startsWith(noSlash) -> text.substring(noSlash.length)
                            else -> ""
                        }.trim().removePrefix(":").trim()
                        if (userText.isEmpty()) {
                            Logs.info("[插件] 命中 ${p.name}（本地渲染）缺少文字，提示用法")
                            return PluginHit(true, "请输入要举牌的文字。用法：$noSlash <你的话>", null)
                        }
                        Logs.info("[插件] 命中 ${p.name}（本地渲染）→ ${userText.take(40)}")
                        val png = renderMeme(p.id, userText)
                        if (png == null) {
                            return PluginHit(true, "抱歉，举牌图没生成成功（多半是底图没随插件一起装进来）。可以先到插件页重装一次这个插件，再发一次试试。", null)
                        }
                        return PluginHit(true, null, null, null, png)
                    }
                    // localimage 型：自带图片——随机取一张随插件持久化的本地图片，直接作为图片消息发出（无需联网/配置）
                    if (p.type == "localimage") {
                        val picked = pickLocalImage(p.id)
                        if (picked == null) {
                            Logs.info("[插件] 命中 ${p.name}（自带图片）但暂无可用本地图片")
                            return PluginHit(true, "这个插件还没有可用的自带图片，先到插件页重装一次让它把图片带进来，再发一次试试。", null)
                        }
                        Logs.info("[插件] 命中 ${p.name}（自带图片）→ ${picked.second}（${picked.first.size} 字节）")
                        return PluginHit(true, null, null, null, null, picked.first, picked.second)
                    }
                    // text2image 型：联网文生图——取命令后的描述，调外部 AI 出图服务生成图片发出
                    if (p.type == "text2image") {
                        val noSlash = if (m.startsWith("/")) m.substring(1) else m
                        val prompt = when {
                            text.startsWith(m) -> text.substring(m.length)
                            text.startsWith(noSlash) -> text.substring(noSlash.length)
                            else -> ""
                        }.trim().removePrefix(":").trim()
                        if (prompt.isEmpty()) {
                            Logs.info("[插件] 命中 ${p.name}（文生图）缺少描述，提示用法")
                            return PluginHit(true, "请描述你想画的画面。用法：$noSlash <画面描述>", null)
                        }
                        // 并发锁：同一会话已有文生图请求在途（generate 同步阻塞期间），新请求不排队、不并发、直接提示
                        val key = sessionKey ?: "*"
                        if (t2iBusy.putIfAbsent(key, true) == true) {
                            Logs.info("[插件] 命中 ${p.name}（文生图）但本会话已有出图在途，忽略并提示")
                            return PluginHit(true, "正在出图，请稍候，出图完成前这条不再重复触发。", null)
                        }
                        Logs.info("[插件] 命中 ${p.name}（文生图）→ ${prompt.take(40)}")
                        val gen = try {
                            ImageGenEngine.generate(prompt, p.id)
                        } finally {
                            // 出图本身成功/失败都要释放锁（generate 返回后本次阻塞结束）
                            t2iBusy.remove(key)
                        }
                        if (gen == null) {
                            // 自带诊断：说清当前配置走的是哪一层、缺什么，下一次报错即可定位
                            val poolEntry = if (Config.aiPoolEnabled) AiPool.mediaEntry("image") else null
                            val diag = if (poolEntry != null) {
                                "配置来源=AI池条目「${poolEntry.name}」（地址${if (poolEntry.baseUrl.isBlank()) "空✗" else "✓"} " +
                                    "密钥${if (poolEntry.apiKey.isBlank()) "空✗" else "✓"} 模型${if (poolEntry.model.isBlank()) "空✗" else "✓"}）"
                            } else {
                                val cm = PluginManager.configMap(p.id)
                                "配置来源=插件配置（地址${if ((cm["baseUrl"] ?: "").isBlank()) "空✗" else "✓"} " +
                                    "密钥${if ((cm["apiKey"] ?: "").isBlank()) "空✗" else "✓"} " +
                                    "模型${if ((cm["model"] ?: "").isBlank()) "空✗" else "✓"}）"
                            }
                            Logs.warn("[文生图] 生成失败 · $diag")
                            return PluginHit(
                                true,
                                "文生图没成功。$diag\n请到 插件页→文生图→配置（或 设置→AI池）把空缺项补齐后重试；仍失败则看服务地址是否可访问。",
                                null
                            )
                        }
                        // 出图字节走内存（不落盘），PluginHit.media 承载；BotEngine 发送后 hit 局部变量释放，字节数组随之 GC 回收
                        return PluginHit(true, null, null, null, null, gen.first, gen.second)
                    }
                    // imageengine 型：统一图片引擎——按命令分发到 文生图/发图拉图/举牌
                    if (p.type == "imageengine") {
                        val key = sessionKey ?: "*"
                        val noSlash = if (m.startsWith("/")) m.substring(1) else m
                        val arg = when {
                            text.startsWith(m) -> text.substring(m.length)
                            text.startsWith(noSlash) -> text.substring(noSlash.length)
                            else -> ""
                        }.trim().removePrefix(":").trim()
                        val cmd = noSlash
                        // ① 发图拉图：随机取一张配置里的图片直链，先拉图下载再作为图片消息发出
                        if (cmd in setOf("表情包", "图片", "发图")) {
                            val src = p.config.firstOrNull { it.key == "imageUrl" || it.key == "images" }?.value?.trim().orEmpty()
                            val urls = src.split(Regex("[,\\n\\r]")).map { it.trim() }.filter { it.isNotBlank() }
                            val url = urls.randomOrNull()
                            if (url == null) {
                                Logs.info("[插件] 命中 ${p.name}（发图拉图）但未配置图片直链")
                                return PluginHit(true, "还没配置图片直链：去「图片引擎」插件的配置里填图片地址（一行一个或用逗号分隔），我就能帮你发图啦。", null)
                            }
                            Logs.info("[插件] 命中 ${p.name}（发图拉图）→ ${url.take(60)}（共 ${urls.size} 张，随机取一张）")
                            return PluginHit(true, null, null, url)
                        }
                        // ② 举牌/文字图：本地渲染——取命令后的文字画到底图上
                        if (cmd in setOf("举牌", "文字图")) {
                            if (arg.isEmpty()) {
                                Logs.info("[插件] 命中 ${p.name}（举牌）缺少文字，提示用法")
                                return PluginHit(true, "请输入要举牌的文字。用法：$noSlash <你的话>", null)
                            }
                            Logs.info("[插件] 命中 ${p.name}（举牌）→ ${arg.take(40)}")
                            val png = renderMeme(p.id, arg)
                            if (png == null) {
                                return PluginHit(true, "抱歉，举牌图没生成成功（多半是底图没配置）。可以到「图片引擎」插件的配置里填好底图（template），再发一次试试。", null)
                            }
                            return PluginHit(true, null, null, null, png)
                        }
                        // ③ 文生图 / 图生图：默认命令（画图/文生图/draw/画一张）
                        //    本条消息若附带了图片（或引用了图片）→ 走图生图（参照该图风格出图）；否则走文生图
                        if (arg.isEmpty()) {
                            Logs.info("[插件] 命中 ${p.name}（出图）缺少描述，提示用法")
                            return PluginHit(true, "请描述你想画的画面。用法：$noSlash <画面描述>（可附带图片做参照）", null)
                        }
                        if (t2iBusy.putIfAbsent(key, true) == true) {
                            Logs.info("[插件] 命中 ${p.name}（出图）但本会话已有出图在途，忽略并提示")
                            return PluginHit(true, "正在出图，请稍候，出图完成前这条不再重复触发。", null)
                        }
                        try {
                            // 1.7.91 图生图修复：恢复参照生成——附图/引用图/会话近图（10 分钟内）
                            // 任一存在即先走图生图三级级联；失败回退纯文生图（不阻断出图）
                            val i2iUrl = ev?.imageUrls?.firstOrNull() ?: recentImage(key)
                            if (!i2iUrl.isNullOrBlank()) {
                                Logs.info("[插件] 命中 ${p.name}（图生图）→ 参照 ${i2iUrl.take(48)} + 描述 ${arg.take(40)}")
                                val i2i = ImageGenEngine.generateFromImageUrl(i2iUrl, arg, p.id, ctx)
                                if (i2i != null) {
                                    return PluginHit(true, null, null, null, null, i2i.first, i2i.second)
                                }
                                Logs.warn("[图生图] 参照生成三级均失败，回退纯文生图")
                            }
                            Logs.info("[插件] 命中 ${p.name}（文生图）→ ${arg.take(40)}")
                            val gen = ImageGenEngine.generate(arg, p.id)
                            if (gen == null) {
                                return PluginHit(true, "出图没成功：请确认已在「图片引擎」插件配置里填好服务地址/密钥/模型，或稍后再试一次。", null)
                            }
                            return PluginHit(true, null, null, null, null, gen.first, gen.second)
                        } finally {
                            // 出图成功/失败/回退都要释放锁
                            t2iBusy.remove(key)
                        }
                    }
                    // reply 型：固定回复
                    if (c.reply != null) {
                        Logs.info("[插件] 命中 ${p.name}（固定回复）命令: $m")
                        return PluginHit(true, c.reply, null)
                    }
                    // image2image 型：图生图（1.7.91 恢复真执行——残留插件重新可用；
                    // 参照来源：本条附图/引用图 → 会话 10 分钟内最近一张图）
                    if (p.type == "image2image") {
                        val noSlash = if (m.startsWith("/")) m.substring(1) else m
                        val prompt = when {
                            text.startsWith(m) -> text.substring(m.length)
                            text.startsWith(noSlash) -> text.substring(noSlash.length)
                            else -> ""
                        }.trim().removePrefix(":").trim()
                        if (prompt.isEmpty()) {
                            return PluginHit(true, "请描述要生成的画面。用法：图生图 <描述>（先发图或引用图片消息作参照）", null)
                        }
                        val i2iUrl = ev?.imageUrls?.firstOrNull() ?: recentImage(sessionKey)
                        if (i2iUrl.isNullOrBlank()) {
                            return PluginHit(true, "没有找到参照图：请先发一张图片（或引用图片消息），再发「图生图 <描述>」。", null)
                        }
                        val lockKey = sessionKey ?: "*"
                        if (t2iBusy.putIfAbsent(lockKey, true) == true) {
                            return PluginHit(true, "正在出图，请稍候。", null)
                        }
                        val r = try {
                            Logs.info("[插件] 命中 ${p.name}（图生图）→ 参照 ${i2iUrl.take(48)} + 描述 ${prompt.take(40)}")
                            ImageGenEngine.generateFromImageUrl(i2iUrl, prompt, p.id, ctx)
                        } finally {
                            t2iBusy.remove(lockKey)
                        }
                        return if (r != null) {
                            PluginHit(true, null, null, null, null, r.first, r.second)
                        } else {
                            PluginHit(true, "图生图没成功：请确认「图片引擎」配置了可用服务（支持 images/generations 带 image 参数、images/edits 或多模态对话任一），或稍后再试。", null)
                        }
                    }
                    // video 型：AI生视频（异步生成——视频耗时长，不阻塞消息队列；生成完由回调直接发出）
                    if (p.type == "video") {
                        val noSlash = if (m.startsWith("/")) m.substring(1) else m
                        val prompt = when {
                            text.startsWith(m) -> text.substring(m.length)
                            text.startsWith(noSlash) -> text.substring(noSlash.length)
                            else -> ""
                        }.trim().removePrefix(":").trim()
                        if (prompt.isEmpty()) {
                            Logs.info("[插件] 命中 ${p.name}（AI生视频）缺少描述，提示用法")
                            return PluginHit(true, "请描述你想生成的视频画面。用法：$noSlash <画面描述>", null)
                        }
                        val key = sessionKey ?: "*"
                        if (vBusy.putIfAbsent(key, true) == true) {
                            Logs.info("[插件] 命中 ${p.name}（AI生视频）但本会话已有生成在途，忽略并提示")
                            return PluginHit(true, "正在生成视频，请稍候，完成前这条不再重复触发。", null)
                        }
                        val pid = p.id
                        val urlMode = videoUrlDeliver != null
                        Logs.info("[插件] 命中 ${p.name}（AI生视频）→ ${prompt.take(40)}（后台生成，${if (urlMode) "链接模式" else "上传模式"}）")
                        Thread {
                            try {
                                if (urlMode) {
                                    val url = VideoGenEngine.generateUrl(prompt, pid)
                                    if (url == null) {
                                        Logs.warn("[插件] AI生视频链接获取失败（$pid）")
                                        videoFailNotify?.invoke(key, "AI生视频没成功：请确认已在插件配置里填好视频生成服务地址/密钥/模型，或稍后再试一次。")
                                    } else if (videoUrlDeliver?.invoke(key, url) != true) {
                                        Logs.warn("[插件] AI生视频链接发送失败")
                                        videoFailNotify?.invoke(key, "视频生成好了但发送失败，请稍后再试一次。")
                                    } else {
                                        Logs.info("[插件] AI生视频链接已发送")
                                    }
                                } else {
                                    val bytes = VideoGenEngine.generate(prompt, pid)
                                    if (bytes == null || bytes.isEmpty()) {
                                        Logs.warn("[插件] AI生视频失败（$pid）")
                                        videoFailNotify?.invoke(key, "AI生视频没成功：请确认已在插件配置里填好视频生成服务地址/密钥/模型，或稍后再试一次。")
                                    } else if (videoDeliver == null) {
                                        Logs.warn("[插件] AI生视频完成但投递回调未注入，丢弃结果")
                                    } else {
                                        Logs.info("[插件] AI生视频完成，开始发送（${bytes.size / 1024}KB）")
                                        if (videoDeliver?.invoke(key, bytes, "mp4") != true) {
                                            Logs.warn("[插件] AI生视频发送失败")
                                            videoFailNotify?.invoke(key, "视频生成好了但发送失败，请稍后再试一次。")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Logs.warn("[插件] AI生视频异常: ${e.message}")
                                videoFailNotify?.invoke(key, "AI生视频出错了：${e.message}")
                            } finally {
                                vBusy.remove(key)
                            }
                        }.apply {
                            isDaemon = true
                            name = "video-gen"
                            start()
                        }
                        return PluginHit(true, if (urlMode) "🎬 收到，正在生成视频，完成后给你发下载链接。" else "🎬 收到，正在生成视频，完成后马上发给你（可能要等一会儿）。", null)
                    }
                    // galgame 型：Galgame 搜索与推荐（一体，数据源：鲲 Galgame 论坛 kungal.com）
                    if (p.type == "galgame") {
                        val key = sessionKey ?: "default"
                        val cmd = c.match.trim()
                        val arg = text.removePrefix(m.trim()).trim().removePrefix(":").trim()
                        // 待选状态：数字(详情/直链)/取消/刷新 → 交 pending 处理；其余穿透走搜索
                        val gHit = handleGalPending(key, arg)
                        if (gHit != null) return gHit
                        // 「找galgame资源 关键词」：资源全局直搜（备注+作品名模糊），结果直接是可取直链的资源
                        if (cmd == "找galgame资源") {
                            if (arg.isBlank()) {
                                return PluginHit(true, "用法：找galgame资源 关键词 —— 直接搜资源（备注/作品名模糊匹配），如：找galgame资源 汉化包", null)
                            }
                            val prevRes = pendingGal[key]
                            val rPage = if (prevRes != null && prevRes.stage == "reslist" && prevRes.keyword == arg) prevRes.page + 1 else 1
                            Thread {
                                try {
                                    val pg = GalEngine.searchResources(arg, rPage)
                                    if (pg == null) {
                                        videoFailNotify?.invoke(key, "❌ 资源搜索失败（网络或站点异常），稍后再试")
                                    } else if (pg.items.isEmpty()) {
                                        videoFailNotify?.invoke(
                                            key,
                                            if (rPage > 1) "📄 「$arg」没有更多结果了"
                                            else "🔍 没有找到与「$arg」相关的资源，换个关键词试试"
                                        )
                                    } else {
                                        pendingGal[key] = GalSession(
                                            keyword = arg, page = rPage, total = pg.total, gte = pg.gte,
                                            stage = "reslist", resources = pg.items
                                        )
                                        videoFailNotify?.invoke(key, galResSearchReply(arg, rPage, pg.total, pg.gte, pg.items))
                                    }
                                } catch (e: Exception) {
                                    Logs.err("[Gal] 资源搜索异常: ${e.message}")
                                    videoFailNotify?.invoke(key, "❌ 资源搜索出错：${e.message}")
                                }
                            }.apply { isDaemon = true; name = "gal-res-search"; start() }
                            return PluginHit(true, "🔍 资源搜索「$arg」中...", null)
                        }
                        // 合并（1.7.54）：推荐只走 gal推荐（gal 推荐/评分/热门等旧写法兼容）；裸 gal 不再出榜，给用法
                        val isRec = cmd == "gal推荐" || arg in setOf("推荐", "评分", "排行榜", "排行")
                        if (!isRec && arg.isBlank()) {
                            return PluginHit(
                                true,
                                "用法：gal 关键词 搜索作品 · gal推荐 看评分榜 · 找galgame资源 关键词 搜资源",
                                null
                            )
                        }
                        Thread {
                            try {
                                if (isRec) {
                                    val items = GalEngine.ranking("rating_desc", 10)
                                    if (items.isNullOrEmpty()) {
                                        videoFailNotify?.invoke(key, "❌ 推荐榜获取失败（站点繁忙或网络异常），稍后再试")
                                    } else {
                                        pendingGal[key] = GalSession(stage = "list", items = items)
                                        val reply = buildString {
                                            append("🏆 Galgame 评分推荐榜 Top${items.size}（鲲 Galgame 社区加权评分）\n\n")
                                            items.forEach { append(GalEngine.lineOf(0, it, true)).append("\n") }
                                            append("\n回复编号查看详情，或「取消」。")
                                        }
                                        videoFailNotify?.invoke(key, reply)
                                    }
                                } else {
                                    // 同关键词再发一次 = 翻下一页
                                    val prev = pendingGal[key]
                                    val page = if (prev != null && prev.keyword == arg && prev.stage == "list") prev.page + 1 else 1
                                    val pg = GalEngine.search(arg, page)
                                    if (pg == null) {
                                        videoFailNotify?.invoke(key, "❌ 搜索失败（网络或站点异常），稍后再试")
                                    } else if (pg.items.isEmpty()) {
                                        val pages = (pg.total + GalEngine.pageSize - 1) / GalEngine.pageSize
                                        videoFailNotify?.invoke(
                                            key,
                                            if (page > 1) "📄 「$arg」没有更多页了（共 $pages 页）"
                                            else "🔍 没有找到「$arg」相关作品，换个关键词试试"
                                        )
                                    } else {
                                        pendingGal[key] = GalSession(
                                            keyword = arg, page = page, total = pg.total, gte = pg.gte,
                                            stage = "list", items = pg.items
                                        )
                                        val pages = (pg.total + GalEngine.pageSize - 1) / GalEngine.pageSize
                                        val reply = buildString {
                                            append("🔍 「$arg」共 ${pg.total}${if (pg.gte) "+" else ""} 个作品 · 第 ${page}/${pages} 页\n\n")
                                            pg.items.forEachIndexed { i, it ->
                                                append(GalEngine.lineOf(i + 1, it, false)).append("\n")
                                            }
                                            append("\n回复编号查看详情；同关键词再发「gal $arg」翻下一页；「取消」退出")
                                        }
                                        videoFailNotify?.invoke(key, reply)
                                    }
                                }
                            } catch (e: Exception) {
                                Logs.err("[Gal] 查询异常: ${e.message}")
                                videoFailNotify?.invoke(key, "❌ Galgame 查询出错：${e.message}")
                            }
                        }.apply { isDaemon = true; name = "gal-query"; start() }
                        return PluginHit(true, if (isRec) "🎲 评分推荐榜加载中..." else "🔍 搜索「$arg」中...", null)
                    }
                    // game 型：游戏资源搜索（数据源：123资源库 www.123zyk.com）
                    if (p.type == "game") {
                        val key = sessionKey ?: "default"
                        val cmd = c.match.trim()
                        val arg = text.removePrefix(m.trim()).trim().removePrefix(":").trim()
                        // 待选状态：数字(详情/直链)/取消/刷新 → 交 pending 处理；其余穿透走搜索
                        val gHit = handleGamePending(key, arg)
                        if (gHit != null) return gHit
                        val isRec = cmd == "游戏推荐" || arg in setOf("推荐", "热门", "最新")
                        if (!isRec && arg.isBlank()) {
                            return PluginHit(
                                true,
                                "用法：游戏 关键词 搜索游戏 · 游戏推荐 看最新热门 · 回复编号取网盘直链",
                                null
                            )
                        }
                        Thread {
                            try {
                                if (isRec) {
                                    val items = GameEngine.ranking(10)
                                    if (items.isEmpty()) {
                                        videoFailNotify?.invoke(key, "❌ 游戏列表获取失败（站点繁忙或网络异常），稍后再试")
                                    } else {
                                        pendingGame[key] = GameSession(stage = "list", items = items)
                                        val reply = buildString {
                                            append("🎮 123资源库 最新/热门游戏（Top ${items.size}）\n\n")
                                            items.forEachIndexed { i, it ->
                                                append(GameEngine.lineOf(i + 1, it)).append("\n")
                                            }
                                            append("\n回复编号看详情与下载链接；「取消」退出")
                                        }
                                        videoFailNotify?.invoke(key, reply)
                                    }
                                } else {
                                    // 同关键词再发一次 = 翻下一页
                                    val prev = pendingGame[key]
                                    val page = if (prev != null && prev.keyword == arg && prev.stage == "list") prev.page + 1 else 1
                                    val pg = GameEngine.search(arg, page)
                                    if (pg == null) {
                                        videoFailNotify?.invoke(key, "❌ 搜索失败（网络或站点异常），稍后再试")
                                    } else if (pg.items.isEmpty()) {
                                        val pages = if (pg.total > 0) (pg.total + 7) / 8 else 0
                                        videoFailNotify?.invoke(
                                            key,
                                            if (page > 1) "📄 「$arg」没有更多页了（共 $pages 页）"
                                            else "🔍 没有找到「$arg」相关游戏，换个关键词试试"
                                        )
                                    } else {
                                        pendingGame[key] = GameSession(
                                            keyword = arg, page = page, total = pg.total,
                                            stage = "list", items = pg.items
                                        )
                                        val pages = (pg.total + 7) / 8
                                        val reply = buildString {
                                            append("🔍 「$arg」共 ${pg.total}${if (pg.gte) "+" else ""} 个游戏 · 第 ${page}/${if (pages < 1) 1 else pages} 页\n\n")
                                            pg.items.forEachIndexed { i, it ->
                                                append(GameEngine.lineOf(i + 1, it)).append("\n")
                                            }
                                            append("\n回复编号看详情与下载链接；同关键词再发一次翻下一页；「取消」退出")
                                        }
                                        videoFailNotify?.invoke(key, reply)
                                    }
                                }
                            } catch (e: Exception) {
                                Logs.err("[游戏] 查询异常: ${e.message}")
                                videoFailNotify?.invoke(key, "❌ 游戏搜索出错：${e.message}")
                            }
                        }.apply { isDaemon = true; name = "game-query"; start() }
                        return PluginHit(true, if (isRec) "🎮 游戏列表加载中..." else "🔍 搜索「$arg」中...", null)
                    }
                    // prompt 型：把命令后面的话 + 插件指令交给 AI
                    Logs.info("[插件] 命中 ${p.name}（prompt型/AI）命令: $m，参数: ${text.removePrefix(m).trim().take(30)}")
                    val rest = text.removePrefix(m).trim()
                    val prompt = c.prompt
                    return PluginHit(true, null, prompt)
            }
        }
        // 兜底（正常不可达：run 内每个分支都 return）——仅为满足非空返回
        return PluginHit(false, null, null)
    }

    // ---------------- 本地渲染（render 型插件） ----------------
    /**
     * 本地渲染举牌/文字图：读取插件配置里的底图引用/区域/颜色，取用户文字画到底图上，
     * 返回 PNG 字节。底图缺失或渲染失败返回 null。
     */
    fun renderMeme(pluginId: String, userText: String): ByteArray? {
        return try {
            val cfg = configMap(pluginId)
            val templateRef = listOfNotNull(cfg["template"], cfg["baseImage"])
                .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (templateRef.isBlank()) {
                Logs.err("[渲染] 插件 $pluginId 未配置底图（template）")
                return null
            }
            val regionArr = ImageRender.parseRegion(cfg["region"], 150, 150, 620, 470)
            val spec = ImageRender.Spec(
                regionX1 = regionArr[0],
                regionY1 = regionArr[1],
                regionX2 = regionArr[2],
                regionY2 = regionArr[3],
                textColor = ImageRender.parseColor(cfg["textColor"], "45,38,35,255"),
                bracketColor = ImageRender.parseColor(cfg["bracketColor"], "156,88,207,255"),
                maxFontHeight = cfg["maxFontHeight"]?.trim()?.toIntOrNull() ?: 200
            )
            val templateBytes = loadTemplate(pluginId, templateRef)
                ?: return null.also { Logs.err("[渲染] 底图不可用：$templateRef") }
            val fontRel = (cfg["font"] ?: "").trim().takeIf { it.isNotEmpty() }
            val fontFile = fontRel?.let { resolveTemplateFile(pluginId, it).takeIf { f -> f.isFile } }
            return ImageRender.render(templateBytes, userText, spec, fontFile)
                ?.also { Logs.info("[渲染] 生成举牌图成功 ${it.size} 字节") }
                ?.let { if (it.isEmpty()) null else it }
        } catch (e: Exception) {
            Logs.err("[渲染] 生成失败: ${e.message}")
            null
        }
    }

    /** 取渲染底图字节：本地文件优先（安装时随插件持久化），否则按直链下载 */
    private fun loadTemplate(pluginId: String, ref: String): ByteArray? {
        val local = resolveTemplateFile(pluginId, ref)
        if (local.isFile && local.length() > 0) {
            return local.readBytes()
        }
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            return try {
                val resp = http.newCall(Request.Builder().url(ref).build()).execute()
                if (!resp.isSuccessful) {
                    Logs.err("[渲染] 底图下载失败: HTTP ${resp.code} $ref")
                    null
                } else {
                    resp.body?.bytes()
                }
            } catch (e: Exception) {
                Logs.err("[渲染] 底图下载异常: ${e.message}")
                null
            }
        }
        return null
    }

    /** 把配置里相对路径的资产解析为插件目录下的绝对文件 */
    private fun resolveTemplateFile(pluginId: String, ref: String): File =
        File(pluginDir(pluginId), ref.trim())

    // ---------------- 自带图片（localimage 型插件） ----------------
    /**
     * 自带图片：随机取一张随插件持久化在插件目录下的本地图片（安装时从 zip 资产带入），
     * 返回 (图片字节, 扩展名)；找不到图片文件返回 null。
     */
    private fun pickLocalImage(pluginId: String): Pair<ByteArray, String>? {
        val root = pluginDir(pluginId)
        if (!root.isDirectory) return null
        val exts = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        val files = root.walkTopDown()
            .filter { it.isFile && exts.contains(it.extension.lowercase()) && it.length() > 0 }
            .toList()
        val f = files.randomOrNull() ?: return null
        val bytes = f.readBytes()
        if (bytes.isEmpty()) return null
        return bytes to f.extension.lowercase()
    }

    // ---------------- 内置帮助插件（/help 分页） ----------------
    /**
     * /help 帮助插件（内置、可关闭、可重装、不可卸载）：
     * 仅当输入 /help 或 /帮助 时输出插件文档（每页一个插件、顶部列出带序号的全插件清单）；
     * 其后输入 + 翻下页、- 翻上页、+N / -N 一次翻多页、或直接发序号（如「3」）直达第 3 个；
     * 输入与帮助无关的内容则清除分页状态，走正常对话。
     */
    private fun handleHelp(sessionKey: String, text: String): PluginHit? {
        val p = installed("help") ?: return null
        if (!p.enabled) return null
        val t = text.trim()
        val pages = totalHelpPages()
        // 支持 +N / -N 一次翻多页（+2 翻两页、-3 回三页；+ / - 等价于 +1 / -1）
        val forward = Regex("^\\+(\\d*)$").find(t)?.let {
            if (it.groupValues[1].isBlank()) 1 else it.groupValues[1].toIntOrNull()
        }
        val backward = Regex("^-(\\d*)$").find(t)?.let {
            if (it.groupValues[1].isBlank()) 1 else it.groupValues[1].toIntOrNull()
        }
        // 直接发序号（如「3」）跳转到第 3 个插件
        val jump = t.toIntOrNull()
        return when {
            // 带斜杠原生写法 + 无斜杠 help（指令面板点击填入）；普通“帮助”归还网盘助手（其自有命令），避免抢话
            t == "/help" || t == "/帮助" || t == "help" -> {
                helpPages[sessionKey] = 1
                PluginHit(true, renderHelp(1), null)
            }
            jump != null && jump in 1..pages && helpPages.containsKey(sessionKey) -> {
                helpPages[sessionKey] = jump
                PluginHit(true, renderHelp(jump), null)
            }
            forward != null && forward > 0 && helpPages.containsKey(sessionKey) -> {
                val cur = helpPages[sessionKey] ?: 1
                if (cur >= pages) PluginHit(true, renderHelp(cur) + "\n（已是最后一页）", null)
                else {
                    val target = (cur + forward).coerceAtMost(pages)
                    helpPages[sessionKey] = target
                    PluginHit(true, renderHelp(target), null)
                }
            }
            backward != null && backward > 0 && helpPages.containsKey(sessionKey) -> {
                val cur = helpPages[sessionKey] ?: 1
                if (cur <= 1) PluginHit(true, renderHelp(cur) + "\n（已是第一页）", null)
                else {
                    val target = (cur - backward).coerceAtLeast(1)
                    helpPages[sessionKey] = target
                    PluginHit(true, renderHelp(target), null)
                }
            }
            else -> {
                helpPages.remove(sessionKey)
                null
            }
        }
    }

    private fun helpPlugins(): List<PluginInfo> =
        installed().filter { it.id != "help" }.sortedBy { it.name }  // 帮助菜单自身不计入列表

    private fun totalHelpPages(): Int {
        val n = helpPlugins().size
        return n.coerceAtLeast(1)  // 每页展示一个插件的文档介绍
    }

    private fun renderHelp(idx: Int): String {
        val list = helpPlugins()
        val n = list.size
        if (n == 0) return "📋 插件帮助\n（暂无插件）"
        val i = idx.coerceIn(1, n)
        val p = list[i - 1]
        val sb = StringBuilder()
        sb.append("📋 插件帮助（第${i}/${n}个 · 共${n}个插件）\n")
        sb.append("🔸 被动触发方式：直接输入插件的指令或关键词即可触发，无需任何前缀；\n   输入与插件无关的内容时正常聊天，不会误触发。\n")
        sb.append("📌 插件列表（发序号直达，如「3」看第 3 个；或用 +N / -N 翻页）：\n")
        list.forEachIndexed { k, pl -> sb.append("${k + 1}. ${pl.name}\n") }
        sb.append("──────────────\n")
        sb.append("【${p.name}】v${p.version}${if (p.author.isNotBlank()) " by ${p.author}" else ""}\n")
        sb.append("触发方式：${TRIGGER_NAMES[p.trigger] ?: p.trigger}\n")
        val doc = p.doc.trim()
        if (doc.isNotBlank()) {
            sb.append(doc.take(400))
            sb.append("\n")
        } else {
            if (p.description.isNotBlank()) sb.append(p.description).append("\n")
            val cmds = p.commands.map { it.match }.distinct().joinToString("、")
            if (cmds.isNotBlank()) sb.append("指令：$cmds\n")
        }
        sb.append("\n—— 发序号直达（如「5」），或 + / - 看下一个/上一个，+2 / -2 一次翻多页 ——")
        return sb.toString()
    }

    // ---------------- 触发方式：总是(hook) / LLM(技能) ----------------
    /** 总是触发（hook）：返回所有 always 插件的指令，注入每次 AI 对话的系统提示 */
    fun alwaysInstructions(): List<String> {
        val out = mutableListOf<String>()
        for (p in installed()) {
            if (!p.enabled || p.trigger != "always") continue
            val ins = p.commands.mapNotNull { c ->
                c.prompt.ifBlank { c.reply?.ifBlank { null } }.takeIf { !it.isNullOrBlank() }
            }
            if (ins.isEmpty()) continue
            out.add("【${p.name}·总是生效】" + ins.joinToString("；"))
        }
        return out
    }

    /** 插件自带 skills/ 扫描缓存：pluginId → (skills 目录 mtime, 技能列表) */
    private val pluginSkillCache = ConcurrentHashMap<String, Pair<Long, List<LlmSkill>>>()

    /** 找到插件根目录或一层子目录下的 skills/（兼容 zip 带顶层目录的布局） */
    private fun locateSkillsDir(root: File): File? {
        File(root, "skills").takeIf { it.isDirectory }?.let { return it }
        root.listFiles()?.sortedBy { it.name }?.forEach { c ->
            if (c.isDirectory && !c.name.startsWith(".")) {
                File(c, "skills").takeIf { it.isDirectory }?.let { return it }
            }
        }
        return null
    }

    /** 解析 SKILL.md：--- 之间的 name/description + 正文作为执行说明（对齐 AstrBot 4.13+ Agent Skills） */
    private fun parseSkillFile(f: File, fallbackName: String): LlmSkill? {
        val text = try { f.readText() } catch (_: Exception) { return null }
        var name = fallbackName
        var desc = ""
        val fm = Regex("""^---\s*\r?\n([\s\S]*?)\r?\n---""").find(text)
        if (fm != null) {
            for (line in fm.groupValues[1].lines()) {
                val i = line.indexOf(':')
                if (i <= 0) continue
                val k = line.substring(0, i).trim()
                val v = line.substring(i + 1).trim().trim('"', '\'')
                when (k) {
                    "name" -> if (v.isNotBlank()) name = v
                    "description" -> if (v.isNotBlank()) desc = v
                }
            }
        }
        val body = if (fm != null) text.substring(fm.range.last + 1).trim() else text.trim()
        if (body.isBlank()) return null
        if (desc.isBlank()) {
            desc = body.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()?.take(80) ?: name
        }
        val skillName = "/" + name.trim().trimStart('/')
        return LlmSkill(skillName, desc.take(120), "（来自插件自带的 skills/）\n" + body.take(12000))
    }

    /** 插件自带 skills/ 下的全部技能（按目录 mtime 缓存，避免每条消息重复读盘） */
    private fun pluginSkillsOf(p: PluginInfo): List<LlmSkill> {
        val root = try { PythonRuntime.pluginDir(p.id) } catch (_: Exception) { null } ?: return emptyList()
        val skillsDir = locateSkillsDir(root) ?: return emptyList()
        val stamp = skillsDir.lastModified()
        val cached = pluginSkillCache[p.id]
        if (cached != null && cached.first == stamp) return cached.second
        val out = mutableListOf<LlmSkill>()
        val single = File(skillsDir, "SKILL.md")
        if (single.isFile) {
            parseSkillFile(single, p.name)?.let { out.add(it) }
        } else {
            skillsDir.listFiles()?.sortedBy { it.name }?.forEach { d ->
                if (d.isDirectory && !d.name.startsWith(".")) {
                    val f = File(d, "SKILL.md")
                    if (f.isFile) parseSkillFile(f, d.name)?.let { out.add(it) }
                }
            }
        }
        if (out.isNotEmpty()) Logs.info("[技能] 插件 ${p.name} 提供 ${out.size} 个 skills/ 技能")
        pluginSkillCache[p.id] = stamp to out
        return out
    }

    /** LLM 触发：返回所有 llm 插件的技能列表（供大模型调用） */
    fun llmSkills(): List<LlmSkill> {
        val out = mutableListOf<LlmSkill>()
        for (p in installed()) {
            if (!p.enabled) continue
            // 插件自带 skills/（AstrBot 4.13+ Agent Skills）：不依赖 trigger，装了就可用
            out.addAll(pluginSkillsOf(p))
            if (p.trigger != "llm") continue
            for (c in p.commands) {
                val name = c.match.trim().ifBlank { "/" + p.name }
                out.add(LlmSkill(
                    name = name,
                    desc = p.description.ifBlank { c.prompt.take(80) }.ifBlank { name },
                    prompt = c.prompt.ifBlank { c.reply ?: "" }
                ))
            }
        }
        return out
    }

    /** 按技能名查找 LLM 技能（忽略大小写与开头的 /） */
    fun findSkill(name: String): LlmSkill? {
        val key = name.trim().lowercase().removePrefix("/")
        return llmSkills().firstOrNull {
            it.name.trim().lowercase().removePrefix("/") == key
        }
    }

    /**
     * 总是触发(hook)插件的正则清理规则。
     * @param skipHook 为 true 时跳过所有 hook 插件（filter_blankline / 命令正则），
     *                  只保留字词替换插件——用于插件直接回复，保留其原有格式不被 hook 破坏。
     */
    fun cleanRules(skipHook: Boolean = false): List<Pair<Regex, String>> {
        val out = mutableListOf<Pair<Regex, String>>()
        for (p in installed()) {
            if (!p.enabled || p.trigger != "always") continue
            // 字词替换：始终应用（按字面匹配，不当正则），逐行应用
            if (p.type == "wordreplace") {
                out.addAll(wordReplaceRules(p.id))
                continue
            }
            if (skipHook) continue  // 跳过 hook 插件（filter_blankline / 命令正则），保留原始格式
            // 空行插件按配置选模式：0=压缩多余空行，1=完全去空行（不留换行/不回车）
            if (p.id == "filter_blankline") {
                val mode = configMap("filter_blankline")["mode"]?.trim() ?: "0"
                out.add(if (mode == "1") Regex("\n") to "" else Regex("\n\\s*\n+") to "\n")
                continue
            }
            for (c in p.commands) {
                val r = c.regex ?: continue
                if (r.isBlank()) continue
                try {
                    out.add(Regex(r) to Regex.escapeReplacement(c.replaceWith ?: ""))
                } catch (_: Exception) {
                }
            }
        }
        return out
    }

    /**
     * 解析字词替换插件的规则：每行「源文本→目标文本」。
     * - 源按字面匹配（Regex.escape，避免用户写的 . * ( 被当正则）
     * - 目标为空 = 删除源文本
     * - 源超过 1000 字跳过（防误操作，比如整段粘进来）；源为空跳过（会把内容插到处都是）
     */
    private fun wordReplaceRules(pluginId: String): List<Pair<Regex, String>> {
        val out = mutableListOf<Pair<Regex, String>>()
        val cm = configMap(pluginId)
        // 新格式（2.0.0）：find1..find5 / replace1..5 分对（配置页左右两框直填）。
        // 语义：左框空=忽略该行；**右框空=删除左框文本**（替换为空串即删除）
        for (i in 1..5) {
            val source = cm["find$i"]?.trim().orEmpty()
            if (source.isEmpty() || source.length > 1000) continue
            val target = cm["replace$i"]?.trim().orEmpty()
            try {
                out.add(Regex(Regex.escape(source)) to Regex.escapeReplacement(target))
            } catch (_: Exception) {
            }
        }
        if (out.isNotEmpty()) return out
        // 兼容旧版单框 rules（每行 源→目标；未迁移的老配置仍生效）
        val rules = cm["rules"]?.trim().orEmpty()
        if (rules.isBlank()) return out
        for (line in rules.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            val parts = trimmed.split("→", limit = 2)
            if (parts.size != 2) continue
            val source = parts[0].trim()
            val target = parts[1].trim()
            if (source.isEmpty() || source.length > 1000) continue
            try {
                out.add(Regex(Regex.escape(source)) to Regex.escapeReplacement(target))
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** 对回复文本应用所有总是触发(hook)插件的正则清理（如去括号、去表情、去空行） */
    fun cleanReply(text: String): String {
        var t = text
        for ((re, repl) in cleanRules()) t = re.replace(t, repl)
        return t
    }

    private fun callWebhook(url: String, message: String): String {
        return try {
            val body = org.json.JSONObject()
                .put("message", message)
                .put("time", System.currentTimeMillis())
                .toString()
            val req = Request.Builder()
                .url(url)
                .post(okhttp3.RequestBody.create("application/json; charset=utf-8".toMediaType(), body))
                .build()
            val resp = http.newCall(req).execute()
            if (resp.isSuccessful) resp.body?.string()?.trim().orEmpty() else "webhook 返回 HTTP ${resp.code}"
        } catch (e: Exception) {
            "webhook 调用失败: ${e.message}"
        }
    }

}

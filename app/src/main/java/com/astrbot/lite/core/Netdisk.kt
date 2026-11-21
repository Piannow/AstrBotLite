package com.astrbot.lite.core

import android.content.Context
import android.util.Base64
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 网盘助手：把分享链接列出目录、进子目录、取下载直链。
 * 走各网盘的「公开分享接口」，无需登录。支持：123网盘、夸克网盘、UC网盘。
 * 触发：网盘:http... 密码:xxx（密码可选） → 打开<文件夹> → 选择<文件>（信息+直链）
 *
 * v1.0.40 优化（参考 LinkSwift 1.1.3）：
 * - 修复凭证（stoken）长期缓存导致的偶发 400：凭证带时间戳，超时自动刷新，失败再刷新重试
 * - 修复夸克/UC 共用错误域名与错误下载 UA（UC 应为 uc-cloud-drive）
 * - 修复 stoken / 密码未做 URL 编码导致的 400（如 stoken 含 + 会被当成空格）
 * - 修复 GET 请求携带 Content-Type 被部分网关判为 400
 * - Cookie 改为「实时从 WebView CookieManager 读取」，解决快照过期造成的登录失效
 * - 统一重试 + 业务错误码提示（未登录 / 游客大小限制 / 流量不足等）
 */
object Netdisk {

    private val json = "application/json; charset=utf-8".toMediaType()

    /**
     * Cookie 会话存储：domain → (name → Cookie)。
     *
     * 为什么必须这样做（2026-09 curl 实测）：夸克/UC 的 API **每次响应都 Set-Cookie 轮换**
     * __sdid（登录态还有 __pus/__puus），且 domain=quark.cn（跨子域共享）。这等价于
     * 浏览器为 LinkSwift 做的事：token → detail → download 三个请求必须携带同一会话的最新值。
     *
     * 旧实现的两个致命问题：
     * 1. 按 url.host 整体替换：detail 响应只 Set-Cookie 一个 __sdid，就把该 host 的整个
     *    cookie 列表替换掉，之前存的会话 cookie 全丢；
     * 2. 请求还手动带 Cookie 头（WebView 快照），OkHttp 会把 jar 的 cookie 追加合并，
     *    同一 cookie 出现「快照旧值 + jar 新值」两份 → 服务器按旧值校验 → 41020「转存文件token校验异常」。
     */
    private val cookieStore = ConcurrentHashMap<String, MutableMap<String, Cookie>>()

    /** DoH 查询专用 client（不挂自定义 Dns，防递归） */
    private val dohHttp = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * 弹性 DNS（1.7.89）：系统解析失败/空 → 阿里 DoH（HTTPS JSON）兜底。
     * 真机日志实锤：运营商 DNS 间歇对 drive-pc.drive.quark.cn 返回
     * "No address associated with hostname"（与成功请求交替出现，负缓存放大故障窗口），
     * 导致夸克凭证/搜索校验大批失败。DoH 走独立 HTTPS 通道绕过污染。
     */
    private val resilientDns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            try {
                val sys = okhttp3.Dns.SYSTEM.lookup(hostname)
                if (sys.isNotEmpty()) return sys
            } catch (_: Exception) {
            }
            try {
                val u = "https://dns.alidns.com/resolve?name=" +
                    java.net.URLEncoder.encode(hostname, "UTF-8") + "&type=A"
                val req = okhttp3.Request.Builder().url(u)
                    .header("Accept", "application/dns-json")
                    .build()
                dohHttp.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val o = JSONObject(resp.body?.string().orEmpty())
                        val ans = o.optJSONArray("Answer") ?: o.optJSONArray("answer")
                        val ips = mutableListOf<java.net.InetAddress>()
                        if (ans != null) {
                            for (i in 0 until ans.length()) {
                                val a = ans.optJSONObject(i) ?: continue
                                if (a.optInt("type", 0) == 1) {
                                    val data = a.optString("data", "")
                                    if (data.isNotEmpty()) ips.add(java.net.InetAddress.getByName(data))
                                }
                            }
                        }
                        if (ips.isNotEmpty()) {
                            Logs.info("[DNS] $hostname 系统解析失败 → DoH 命中 ${ips.size} 条")
                            return ips
                        }
                    }
                }
            } catch (e: Exception) {
                Logs.warn("[DNS] DoH 兜底失败 $hostname: ${e.message}")
            }
            throw java.io.IOException("Unable to resolve host: $hostname")
        }
    }

    private val http = OkHttpClient.Builder()
        .dns(resilientDns)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .followRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val now = System.currentTimeMillis()
                for (c in cookies) {
                    val domain = c.domain.trimStart('.').ifBlank { url.host }
                    val map = cookieStore.getOrPut(domain) { ConcurrentHashMap() }
                    if (c.expiresAt <= now) map.remove(c.name) else map[c.name] = c
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val host = url.host
                val path = url.encodedPath.ifBlank { "/" }
                val now = System.currentTimeMillis()
                val out = LinkedHashMap<String, Cookie>()
                val matched = cookieStore.entries
                    .filter { (domain, _) -> host == domain || host.endsWith(".$domain") }
                    .sortedBy { it.key.length }
                for ((_, map) in matched) {
                    val it = map.entries.iterator()
                    while (it.hasNext()) {
                        val (name, c) = it.next()
                        if (c.expiresAt <= now) {
                            it.remove()
                        } else if (path.startsWith(c.path)) {
                            out[name] = c
                        }
                    }
                }
                return out.values.toList()
            }
        })
        .build()

    private val sessions = ConcurrentHashMap<String, Session>()

    private lateinit var cookieDir: File
    private val panCookies = ConcurrentHashMap<String, String>()

    /**
     * 实时 Cookie 提供者：由 UI 层（NetdiskLoginActivity）注入，直接读 WebView 的 CookieManager。
     * 这样网盘网页里刷新/续期过的 Cookie 能立刻生效，避免「快照过期 → 登录失效」。
     * 仅当 WebView 已初始化（用户打开过登录页）时才注入，未注入时回落到持久化快照。
     */
    @Volatile
    var cookieProvider: ((platform: String) -> String)? = null

    @Volatile
    var tokenProvider: ((platform: String) -> String)? = null

    private val panTokens = mutableMapOf<String, String>()

    fun init(ctx: Context) {
        cookieDir = File(ctx.filesDir, "pan_cookies").apply { mkdirs() }
        for (p in arrayOf("123", "quark", "uc")) {
            val f = File(cookieDir, "$p.txt")
            if (f.exists()) {
                val v = f.readText().trim()
                if (v.isNotBlank()) panCookies[p] = v
            }
            val tf = File(cookieDir, "${p}_token.txt")
            if (tf.exists()) {
                val v = tf.readText().trim()
                if (v.isNotBlank()) panTokens[p] = v
            }
        }
    }

    fun setCookie(platform: String, cookie: String) {
        val v = cookie.trim()
        if (v.isBlank()) return
        panCookies[platform] = v
        try {
            File(cookieDir, "$platform.txt").writeText(v)
        } catch (_: Exception) {
        }
    }

    fun clearCookie(platform: String) {
        panCookies.remove(platform)
        try {
            File(cookieDir, "$platform.txt").delete()
        } catch (_: Exception) {
        }
        SEED_DOMAIN[platform]?.let { cookieStore.remove(it) }
    }

    fun tokenOf(platform: String): String {
        tokenProvider?.let { return it(platform).trim().ifBlank { null } ?: panTokens[platform].orEmpty() }
        return panTokens[platform].orEmpty()
    }

    fun setToken(platform: String, token: String) {
        val v = token.trim()
        if (v.isBlank()) return
        panTokens[platform] = v
        try {
            File(cookieDir, "${platform}_token.txt").writeText(v)
        } catch (_: Exception) {
        }
    }

    fun clearToken(platform: String) {
        panTokens.remove(platform)
        try {
            File(cookieDir, "${platform}_token.txt").delete()
        } catch (_: Exception) {
        }
    }

    private val SEED_DOMAIN = mapOf(
        "123" to "123pan.com",
        "quark" to "quark.cn",
        "uc" to "uc.cn"
    )

    fun seedCookies(platform: String, cookieStr: String) {
        val domain = SEED_DOMAIN[platform] ?: return
        cookieStore.remove(domain)
        if (cookieStr.isBlank()) return
        val map = cookieStore.getOrPut(domain) { ConcurrentHashMap() }
        for (pair in cookieStr.split(";")) {
            val name = pair.substringBefore("=").trim()
            val value = pair.substringAfter("=", "").trim()
            if (name.isBlank()) continue
            try {
                map[name] = Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(domain)
                    .path("/")
                    .build()
            } catch (_: Exception) {
            }
        }
        Logs.info("网盘会话已播种 ${map.size} 个 Cookie（$platform，域 $domain）")
    }

    fun cookieOf(platform: String): String {
        val provider = cookieProvider
        if (provider != null) {
            try {
                val c = provider(platform).trim()
                if (c.isNotBlank()) {
                    panCookies[platform] = c
                    return c
                }
            } catch (_: Throwable) {
            }
        }
        return panCookies[platform] ?: ""
    }

    fun hasCookie(platform: String): Boolean = cookieOf(platform).isNotBlank()

    fun hasLoginTicket(platform: String): Boolean = looksLoggedIn(platform, cookieOf(platform))

    class NFile(
        val name: String,
        val id: String,
        val isFolder: Boolean,
        val size: Long,
        val token: String = "",
        val etag: String = "",
        val s3KeyFlag: String = ""
    )

    class Session(
        val url: String,
        val password: String?,
        val platform: String,
        var shareId: String
    ) {
        var token = ""
        var tokenTs = 0L
        var qkBase = ""
        var dir = "/"
        var dirId = "root"
        var files: List<NFile> = emptyList()
        var pathStack = ArrayDeque<Pair<String, String>>()
        var lastAccess = System.currentTimeMillis()
        var searchKeyword: String = ""
        var searchHits: List<IndexedValue<NFile>> = emptyList()
        var searchPage: Int = 0
    }

    /** 全网搜索结果条目（来自 PanSou 兼容搜索接口） */
    class GResult(
        val title: String,
        val url: String,
        val password: String,
        val platform: String,
        val time: Long
    )

    /** 某会话的全网搜索状态：关键词 + 结果 + 当前页 + 最近展示时间（数字归属仲裁用） */
    private class GSearch(
        var keyword: String = "",
        var results: List<GResult> = emptyList(),
        var page: Int = 0,
        var shownAt: Long = System.currentTimeMillis()
    )

    private val gSearches = ConcurrentHashMap<String, GSearch>()

    /** 内置搜索服务地址：PanSou 协议（POST JSON {"kw":...}）。插件配置里可加自定义地址优先使用 */
    private val PANSOU_APIS = listOf(
        "https://so.252035.xyz/api/search"
    )

    private const val TOKEN_TTL_MS = 15 * 60 * 1000L
    private const val SESSION_TTL_MS = 6 * 60 * 60 * 1000L

    /** 最近一次网盘指令活跃时间——与番剧选集并存时，纯数字按“谁最近活跃”仲裁归属 */
    @Volatile
    var lastActiveAt: Long = 0L
        private set

    fun handle(sessionKey: String, text: String, config: List<PluginConfigField>): String {
        lastActiveAt = System.currentTimeMillis()
        val t = text.trim()
        return when {
            t.startsWith("网盘") -> init(sessionKey, t.removePrefix("网盘").trim(), config)
            // ── 搜索结果态（还没建立会话）复用盘内同款交互（1.7.49）：
            //    打开/选择/直链 + 序号或标题 → 打开该结果；列表/刷新 → 列当前结果页；
            //    %/回退/返回 → 清除搜索结果；添加 → 提示先打开
            (t.startsWith("打开") || t.startsWith("选择") || t.startsWith("直链")) &&
                sessions[sessionKey] == null && gSearches[sessionKey] != null ->
                pickGlobalResult(sessionKey, when {
                    t.startsWith("打开") -> t.removePrefix("打开")
                    t.startsWith("选择") -> t.removePrefix("选择")
                    else -> t.removePrefix("直链")
                }.trim())
            (t.startsWith("列表") || t.startsWith("刷新")) &&
                sessions[sessionKey] == null && gSearches[sessionKey] != null ->
                formatGlobalPage(gSearches[sessionKey]!!)
            ((t == "%" || t == "％" || t == "%25" || (t.length <= 3 && t.contains("%"))) ||
                t.startsWith("回退") || t.startsWith("返回")) &&
                sessions[sessionKey] == null && gSearches[sessionKey] != null ->
                clearGlobal(sessionKey)
            t.startsWith("添加") && sessions[sessionKey] == null && gSearches[sessionKey] != null ->
                "请先打开资源（发结果序号或「网盘:链接」），进入网盘后才能「添加」子目录"
            t.startsWith("打开") -> openDir(sessionKey, t.removePrefix("打开").trim())
            t.startsWith("选择") -> select(sessionKey, t.removePrefix("选择").trim())
            t.startsWith("直链") -> select(sessionKey, t.removePrefix("直链").trim())
            t.startsWith("刷新") || t.startsWith("列表") -> refresh(sessionKey)
            t == "帮助" -> help()
            t == "%" || t == "％" || t == "%25" || (t.length <= 3 && t.contains("%")) -> goBack(sessionKey)
            t == "上一页" || t == "上页" || t == "前一页" -> pageMove(sessionKey, -1)
            t.startsWith("搜索") -> searchCommand(sessionKey, t.removePrefix("搜索").trim(), config)
            t.startsWith("搜") && !t.startsWith("搜索") -> searchCommand(sessionKey, t.removePrefix("搜").trim(), config)
            t.startsWith("找") -> searchCommand(sessionKey, t.removePrefix("找").trim().removePrefix(":").removePrefix("：").trim(), config)
            t.startsWith("回退") -> goBack(sessionKey)
            t.startsWith("返回") -> goBack(sessionKey)
            t.startsWith("添加") -> addPath(sessionKey, t.removePrefix("添加").trim())
            t.matches(Regex("""[1-9]\d*""")) && (sessions.containsKey(sessionKey) || gSearches.containsKey(sessionKey)) -> goto(sessionKey, t)
            sessions.containsKey(sessionKey) -> "指令我不太懂哦～试试：\n" + help()
            else -> help()
        }
    }

    fun hasSession(sessionKey: String): Boolean = sessions.containsKey(sessionKey)

    /** 该会话是否有全网搜索结果在等用户选择（无网盘会话时序号走结果列表） */
    fun hasGlobalSearch(sessionKey: String): Boolean = gSearches.containsKey(sessionKey)

    fun isControlText(text: String): Boolean {
        val t = text.trim()
        return t == "%" || t == "％" || t == "%25" || (t.length <= 3 && t.contains("%")) ||
            t.startsWith("回退") || t.startsWith("返回") ||
            t.startsWith("搜索") || t.startsWith("搜") ||
            t.startsWith("添加") ||
            t == "找" || t.startsWith("找 ") || t.startsWith("找:") || t.startsWith("找：") ||
            t == "上一页" || t == "上页" || t == "前一页"
    }

    private fun session(sessionKey: String): Session? {
        pruneSessions()
        val s = sessions[sessionKey] ?: return null
        s.lastAccess = System.currentTimeMillis()
        if (s.platform == "quark" || s.platform == "uc") {
            if (s.token.isBlank() || System.currentTimeMillis() - s.tokenTs > TOKEN_TTL_MS) {
                refreshToken(s)
            }
        }
        return s
    }

    private fun pruneSessions() {
        val now = System.currentTimeMillis()
        val dead = sessions.entries.filter { now - it.value.lastAccess > SESSION_TTL_MS }.map { it.key }
        for (k in dead) sessions.remove(k)
    }

    private fun refreshToken(s: Session): String? {
        val t = qkToken(s.shareId, s.password, s.platform, s.qkBase)
        if (!t.isNullOrBlank()) {
            s.token = t
            s.tokenTs = System.currentTimeMillis()
        }
        return t
    }

    private fun goto(sessionKey: String, num: String): String {
        val n = num.toIntOrNull() ?: return "无效序号"
        val g = gSearches[sessionKey]
        // 双态并存仲裁（修“搜索列表显示 1-8，但输 3 提示序号超出”）：
        // 搜索列表刚展示过（shownAt 比会话最近操作更近）→ 数字选搜索结果；
        // 旧逻辑只要会话存在就走目录序号，目录项不足 3 即报超出。
        val sLast = sessions[sessionKey]?.lastAccess ?: 0L
        if (g != null && g.results.isNotEmpty() && g.shownAt > sLast) {
            val r = g.results.getOrNull(n - 1)
                ?: return "序号超出范围（搜索结果共 ${g.results.size} 条），发「找」翻页查看"
            return openGlobalResult(sessionKey, r)
        }
        val s = session(sessionKey)
        if (s == null) {
            val g2 = g ?: return "还没有网盘会话，请先发：网盘:<链接>"
            val r = g2.results.getOrNull(n - 1)
                ?: return "序号超出范围（搜索结果共 ${g2.results.size} 条），发「找 关键词」重新搜索"
            return openGlobalResult(sessionKey, r)
        }
        val idx = n - 1
        val target = s.files.getOrNull(idx)
            ?: return "序号超出范围（当前共 ${s.files.size} 项），发「列表」查看"
        if (target.isFolder) return enterDir(s, target)
        return select(sessionKey, target.name)
    }

    private fun listLimit(config: List<PluginConfigField>): Int =
        config.firstOrNull { it.key == "listLimit" }?.value?.toIntOrNull()?.coerceIn(1, 200) ?: 100

    private fun init(sessionKey: String, arg: String, config: List<PluginConfigField>): String {
        val (url, pwd) = parseInit(arg)
        if (url == null) return help()
        val platform = detectPlatform(url)
            ?: return "暂不支持该网盘（当前支持：123网盘 / 夸克网盘 / UC网盘）"
        return try {
            val s = Session(url, pwd, platform, "")
            seedCookies(platform, cookieOf(platform))
            when (platform) {
                "123" -> {
                    val key = Regex("""/s/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
                        ?: return "无法从链接解析分享Key（应为 123pan.com/s/xxx 格式）"
                    s.shareId = key
                }
                "quark", "uc" -> {
                    val pid = Regex("""/s/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
                        ?: return "无法从链接解析分享ID（应为 pan.quark.cn/s/xxx 或 drive.uc.cn/s/xxx 格式）"
                    s.shareId = pid
                    s.dirId = "0"
                    val t = qkToken(pid, pwd, platform, "")
                        ?: return "获取${platformName(platform)}分享凭证失败（请检查密码是否正确）"
                    s.token = t
                    s.tokenTs = System.currentTimeMillis()
                }
            }
            sessions[sessionKey] = s
            val files = listFiles(s, config)
            s.files = files
            formatList(s, files, config)
        } catch (e: Exception) {
            Logs.err("网盘打开失败: ${e.message}")
            "❌ 打开网盘失败：${friendly(e)}"
        }
    }

    private fun parseInit(arg: String): Pair<String?, String?> {
        var a = arg.trim().removePrefix(":").trim()
        var pwd: String? = null
        val pm = Regex("""密码\s*[:：]\s*(\S+)""").find(a)
        if (pm != null) {
            pwd = pm.groupValues[1]
            a = a.replace(pm.value, "").trim()
        }
        val um = Regex("""(https?://\S+)""").find(a)
        val url = um?.groupValues?.get(1)?.trimEnd('，', ',', '。')
        when (pwd?.lowercase()) {
            "true", "false", "null", "none" -> {
                Logs.warn("网盘 parseInit: 密码解析到非法值 \"$pwd\"，视为无密码（原始参数: ${arg.take(80)}）")
                pwd = null
            }
        }
        return url to pwd
    }

    private fun detectPlatform(url: String): String? = when {
        // 1.7.89 宽化：搜索结果里存在非 pan. 前缀分享域名（www.quark.cn / drive.quark.cn 等），
        // 过窄正则会把有效结果误判"不支持的盘"过滤掉（真机日志实锤被误杀）
        url.contains("123pan") || url.contains("123684") || url.contains("123865") ||
            url.contains("123912") || url.contains("123952") -> "123"
        url.contains("quark.cn") -> "quark"
        url.contains("uc.cn") -> "uc"
        else -> null
    }

    private fun openDir(sessionKey: String, arg: String): String {
        val s = session(sessionKey) ?: return "还没有网盘会话，请先发：网盘:<链接> 密码:<密码>"
        val name = arg.trim()
        val target = s.files.firstOrNull { it.name == name }
            ?: (name.toIntOrNull()?.minus(1))?.let { s.files.getOrNull(it) }
        if (target == null) return "找不到该文件夹，发「列表」看当前目录内容"
        if (!target.isFolder) return "${target.name} 不是文件夹，用「选择${target.name}」获取文件"
        return enterDir(s, target)
    }

    private fun enterDir(s: Session, target: NFile): String {
        s.pathStack.addLast(Pair(s.dir, s.dirId))
        s.dir = s.dir.trimEnd('/') + "/" + target.name
        s.dirId = target.id
        return try {
            val files = listFiles(s, emptyList())
            s.files = files
            formatList(s, files, emptyList())
        } catch (e: Exception) {
            "❌ 打开目录失败：${friendly(e)}"
        }
    }

    private fun select(sessionKey: String, arg: String): String {
        val s = session(sessionKey) ?: return "还没有网盘会话，请先发：网盘:<链接> 密码:<密码>"
        val name = arg.trim()
        val target = s.files.firstOrNull { it.name == name }
            ?: name.toIntOrNull()?.minus(1)?.let { s.files.getOrNull(it) }
        if (target == null) return "找不到该文件，发「列表」看当前目录内容"
        if (target.isFolder) return "${target.name} 是文件夹，用「打开${target.name}」进入"
        return try {
            val r = downloadUrl(s, target)
            val link = r?.url
            if (link.isNullOrBlank()) {
                val hint = r?.error
                return when (s.platform) {
                    "quark", "uc" ->
                        if (hint.isNullOrBlank()) "获取直链失败：${platformName(s.platform)}登录可能已失效，请到插件页网盘助手点「登录」重新登录后再试"
                        else "获取直链失败：$hint"
                    else -> "获取下载直链失败${if (hint.isNullOrBlank()) "（可能需要登录/验证码）" else "：$hint"}"
                }
            }
            val sb = StringBuilder()
            sb.append("📄 文件：${target.name}\n")
            sb.append("类型：文件\n")
            if (target.size > 0) sb.append("大小：${fmtSize(target.size)}\n")
            val dot = target.name.lastIndexOf('.')
            if (dot > 0 && dot < target.name.length - 1) sb.append("后缀：${target.name.substring(dot + 1)}\n")
            sb.append("🔗 直链：$link")
            if (s.platform == "quark" || s.platform == "uc") {
                // 1.7.94：按 LinkSwift（油猴实证配方）补全下载三件套——只带 Cookie 仍会 412
                sb.append("\n📌 下载提示：打开若报 412（需登录），请求头补齐三件套：")
                sb.append("\n  ① Cookie：网盘登录态")
                sb.append("\n  ② UA：" + (if (s.platform == "quark") QUARK_DL_UA else UC_DL_UA))
                sb.append("\n  ③ Referer：" + (if (s.platform == "quark") "https://pan.quark.cn/" else "https://drive.uc.cn/"))
                sb.append("\n  Gopeed：先加 Cookie 试下；仍 412 再补 UA+Referer。App 内可先「登录」提升直链成功率。")
            }
            sb.toString()
        } catch (e: Exception) {
            "❌ 获取直链失败：${friendly(e)}"
        }
    }

    private fun refresh(sessionKey: String): String {
        val s = session(sessionKey) ?: return "还没有网盘会话，请先发：网盘:<链接> 密码:<密码>"
        return try {
            s.files = listFiles(s, emptyList())
            formatList(s, s.files, emptyList())
        } catch (e: Exception) {
            "❌ 刷新失败：${friendly(e)}"
        }
    }

    private fun formatList(s: Session, files: List<NFile>, config: List<PluginConfigField>): String {
        val sb = StringBuilder()
        sb.append("📁 ${platformName(s.platform)} · 当前目录：${s.dir}\n\n")
        if (files.isEmpty()) {
            sb.append("（空目录）\n")
            return sb.toString()
        }
        files.forEachIndexed { i, f ->
            sb.append("${i + 1}.  ")
            if (f.isFolder) {
                sb.append("📂 ").append(f.name).append("\n")
            } else {
                sb.append("📄 ").append(f.name)
                if (f.size > 0) sb.append("   ").append(fmtSize(f.size).lowercase())
                sb.append("\n")
            }
        }
        sb.append("\n\n💡 操作说明\n")
        sb.append("打开<文件夹> 进入\n")
        sb.append("选择<文件> 获取信息+直链\n")
        sb.append("添加<目录名> 直接进入子目录")
        return sb.toString()
    }

    private fun help(): String = buildString {
        append("📦 网盘助手用法\n")
        append("【全网搜资源】找 关键词 —— 搜夸克/UC/123网盘的公开分享，发序号直接打开\n")
        append("1. 打开分享：网盘:<分享链接> 密码:<密码>（密码可选）\n")
        append("2. 直接发数字序号：文件夹→进入，文件→选择并取直链\n")
        append("3. 打开<文件夹名> / 选择<文件名> —— 也可按名称操作\n")
        append("4. 列表 / 刷新 —— 查看当前目录\n")
        append("5. % / 回退 / 返回 —— 返回上级目录\n")
        append("6. 搜索 关键词 / 搜 关键词 / 找 关键词 —— 全网搜夸克/UC/123公开分享（不支持盘内搜索）\n")
        append("7. 翻页：发「找」「搜索」下一页 ·「上一页」上一页 ·「搜索 N」跳第 N 页（第 X/Y 页显示在列表头部）\n")
        append("8. 无需登录即可取直链：123网盘（不限大小）；夸克/UC 可先「登录」提升成功率\n")
        append("支持：123网盘 / 夸克网盘 / UC网盘")
    }

    private fun goBack(sessionKey: String): String {
        val s = session(sessionKey) ?: return "还没有网盘会话，请先发：网盘:<链接>"
        if (s.pathStack.isEmpty()) return "已在根目录，无法回退"
        val (prevDir, prevDirId) = s.pathStack.removeLast()
        s.dir = prevDir
        s.dirId = prevDirId
        return try {
            val files = listFiles(s, emptyList())
            s.files = files
            formatList(s, files, emptyList())
        } catch (e: Exception) {
            "❌ 回退失败：${friendly(e)}"
        }
    }

    private fun addPath(sessionKey: String, arg: String): String {
        val s = session(sessionKey) ?: return "还没有网盘会话，请先发：网盘:<链接>"
        if (arg.isBlank()) return "用法：添加<目录名> —— 在当前目录进入子目录\n例：添加 重点资料"
        val name = arg.trim()
        if (name == ".") return "当前目录就是这里，无需添加"
        if (name == ".." || name == "../") return goBack(sessionKey)
        val target = s.files.firstOrNull { it.isFolder && it.name.equals(name, ignoreCase = true) }
            ?: s.files.firstOrNull { it.isFolder && it.name.contains(name, ignoreCase = true) }
        if (target == null) return "当前目录「${s.dir}」里没有「$name」这个文件夹\n发「列表」查看当前内容"
        if (!target.isFolder) return "${target.name} 不是文件夹，用「选择${target.name}」获取文件"
        s.pathStack.addLast(Pair(s.dir, s.dirId))
        s.dir = s.dir.trimEnd('/') + "/" + target.name
        s.dirId = target.id
        return try {
            val files = listFiles(s, emptyList())
            s.files = files
            formatList(s, files, emptyList())
        } catch (e: Exception) {
            "❌ 进入目录「${target.name}」失败：${friendly(e)}"
        }
    }

    private fun searchFiles(sessionKey: String, keyword: String): String {
        val s = session(sessionKey) ?: return "还没有网盘会话，请先发：网盘:<链接>"
        if (keyword.isBlank()) {
            if (s.searchKeyword.isBlank()) return "请输入搜索关键词，如：搜索 文档"
            s.searchPage++
            return formatSearchPage(s)
        }
        val hits = s.files.withIndex().filter { it.value.name.contains(keyword, ignoreCase = true) }
        if (hits.isEmpty()) return "在当前目录中未找到包含「$keyword」的文件"
        s.searchKeyword = keyword
        s.searchHits = hits
        s.searchPage = 0
        return formatSearchPage(s)
    }

    private fun formatSearchPage(s: Session): String {
        val hits = s.searchHits
        val pageSize = 10
        val totalPages = (hits.size + pageSize - 1) / pageSize
        val page = s.searchPage.coerceAtMost(totalPages - 1)
        s.searchPage = page
        val from = page * pageSize
        val to = minOf(from + pageSize, hits.size)
        val sb = StringBuilder()
        sb.append("🔍 搜索「${s.searchKeyword}」结果（共 ${hits.size} 项，第 ${page + 1}/${totalPages} 页）：\n")
        for (i in from until to) {
            val (idx, f) = hits[i]
            val icon = if (f.isFolder) "📁" else "📄"
            val size = if (!f.isFolder && f.size > 0) " (${formatSize(f.size)})" else ""
            sb.append("${idx + 1}. $icon ${f.name}$size\n")
        }
        if (totalPages > 1) sb.append("翻页：「搜索」「搜」下一页 ·「上一页」上一页 ·「搜索 N」跳第 N 页\n")
        sb.append("发上面的序号可直接进入文件夹/获取直链，也可以用 打开/选择 + 名称")
        return sb.toString()
    }

    // ---------------- 全网资源搜索（PanSou 兼容接口） ----------------

    private fun searchGlobal(sessionKey: String, keyword: String, config: List<PluginConfigField>): String {
        if (keyword.isBlank()) {
            return "请输入要找的内容，如：找 三体\n（也可以发「搜索 关键词」，没打开网盘时同样搜全网）"
        }
        // 地址顺序：插件配置的自定义地址 → 内置公共实例
        val custom = config.firstOrNull { it.key == "searchApi" }?.value?.trim().orEmpty()
        val apis = buildList {
            if (custom.isNotBlank()) add(custom)
            addAll(PANSOU_APIS)
        }
        var lastErr: String? = null
        for (api in apis) {
            try {
                val pr = pansouSearch(api, keyword) ?: continue
                if (pr.list.isEmpty()) {
                    // 精确区分：搜到了但全是本插件不支持的盘型 vs 真的没结果
                    return if (pr.otherCount > 0)
                        "🔍 找到 ${pr.otherCount} 条其它资源（磁力/百度/阿里等），但本插件仅支持 123/夸克/UC 打开——换个关键词试试"
                    else "没搜到「$keyword」的网盘资源，换个关键词试试"
                }
                // 先验证再返回：并发探测每个分享是否仍有效（未删除/未违规屏蔽/密码可用），只给有效结果
                val valid = validateResults(pr.list)
                if (valid.isEmpty()) {
                    return "🔍 搜到 ${pr.list.size} 条，但校验后全部失效（已删除/违规/密码不符）——换个关键词试试"
                }
                val st = GSearch(keyword, valid, 0)
                gSearches[sessionKey] = st
                val filtered = pr.list.size - valid.size
                return formatGlobalPage(st) +
                    if (filtered > 0) "\n（已自动过滤 $filtered 条失效/不可用资源）" else ""
            } catch (e: Exception) {
                lastErr = e.message
                Logs.err("全网搜索失败 @$api: ${e.message}")
            }
        }
        return "❌ 全网搜索失败（公共搜索服务不稳定，已自动重试 3 次）${if (lastErr.isNullOrBlank()) "" else "：${lastErr}"}，稍后再试"
    }

    /** 全网搜专用短超时 client：公共 PanSou 实例抖动大（压测 5 次含 400/502/超时），短超时+快速重试 */
    private val pansouHttp = OkHttpClient.Builder()
        .dns(resilientDns)
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** results=支持盘结果（夸克/UC/123）；otherCount=其它盘/磁力数量（用于“盘型不支持”的精确提示） */
    private data class PanResult(val list: List<GResult>, val otherCount: Int)

    /**
     * 重试版搜索：同一接口最多 3 次（间隔 0.8s/2s）。
     * 公共实例实测间歇 400/502/超时（5 次压测 3 次异常）——单发必挂，重试可救回瞬时故障。
     */
    private fun pansouSearch(api: String, keyword: String): PanResult? {
        var last: Exception? = null
        for (attempt in 1..3) {
            try {
                return pansouSearchOnce(api, keyword)
            } catch (e: Exception) {
                last = e
                Logs.warn("全网搜索 @$api 第 $attempt/3 次失败: ${e.message}")
                if (attempt < 3) {
                    try {
                        Thread.sleep(if (attempt == 1) 800L else 2000L)
                    } catch (_: InterruptedException) {
                        return null
                    }
                }
            }
        }
        throw last ?: Exception("搜索失败")
    }

    /** 结果有效性校验：并发探测每个分享（与真实打开同路径），只保留仍然有效的。
     *  失效=已删除/违规屏蔽/密码不符/盘不支持。慢而未确认的保留（宁可多给不可误杀）。 */
    private fun validateResults(list: List<GResult>): List<GResult> {
        if (list.isEmpty()) return list
        val invalid = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val threads = list.mapIndexed { i, r ->
            Thread {
                try {
                    // 轻微错峰：夸克/UC 的 token 接口防 10 连发触发风控
                    Thread.sleep((i * 150).toLong())
                    val err = probeShare(r.url, r.password.ifBlank { null })
                    if (err != null) {
                        invalid.add(i)
                        Logs.info("[全网搜] 过滤失效资源「${r.title.take(30)}」→ $err")
                    }
                } catch (_: InterruptedException) {
                } catch (e: Exception) {
                    Logs.warn("[全网搜] 校验异常（保留该条）: ${e.message}")
                }
            }
        }
        threads.forEach { it.start() }
        // 每条最多等 7s；超时未确认的按“有效”保留（避免慢网络误杀）
        threads.forEach { it.join(7000) }
        return list.filterIndexed { i, _ -> i !in invalid }
    }

    /**
     * 校验单个分享是否仍有效（null=有效，否则返回失效原因）。
     * 与 init 打开分享完全同路径但不落会话：
     * - 123：share/list 业务码（分享删除/违规 → biz!=0）
     * - 夸克/UC：qkToken（失败=失效/密码错）→ 再拉目录确认
     */
    private fun probeShare(url: String, pwd: String?): String? {
        val platform = detectPlatform(url) ?: return "不支持的盘"
        return try {
            seedCookies(platform, cookieOf(platform))
            when (platform) {
                "123" -> {
                    val key = Regex("""/s/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
                        ?: return "链接格式异常"
                    val s = Session(url, pwd, platform, "")
                    s.shareId = key
                    s.dirId = "0"
                    p123List(s, "0", 1)
                    null
                }
                else -> {
                    val pid = Regex("""/s/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
                        ?: return "链接格式异常"
                    val t = qkToken(pid, pwd, platform, "") ?: return "已失效或密码不符"
                    val s = Session(url, pwd, platform, "")
                    s.shareId = pid
                    s.dirId = "0"
                    s.token = t
                    s.tokenTs = System.currentTimeMillis()
                    listFiles(s, emptyList())
                    null
                }
            }
        } catch (e: Exception) {
            e.message?.take(60) ?: "校验失败"
        }
    }

    /**
     * 调 PanSou 兼容接口，只取夸克/UC/123 三类结果（带短超时与统计）。
     * 1.7.88 修复：服务端参数契约漂移——实测 {kw,res,src} 三参组合返回 HTTP 400，
     * 而 {kw,res} / {kw,src} / {kw} 均正常（同一分钟内多次压测）。故按参数变体自动降级：
     * 400/422 立即换下一形态重试，全部形态失败才交给外层重试/报错。
     */
    private fun pansouSearchOnce(api: String, keyword: String): PanResult {
        // 变体顺序按"结果量"实测排序：kw+src(all) 扩源（三体 113 条 vs kw+res 41 条）；
        // 三参组合曾被服务端 400，放在首位兼容其恢复，400/422 即降级下一形态。
        val variants = listOf(
            JSONObject().put("kw", keyword).put("res", "merge").put("src", "all"),
            JSONObject().put("kw", keyword).put("src", "all"),
            JSONObject().put("kw", keyword).put("res", "merge"),
            JSONObject().put("kw", keyword)
        )
        var last400: Exception? = null
        for ((idx, body) in variants.withIndex()) {
            val (code, text) = post(api, body.toString(), "", UA)
            if (code == 400 || code == 422) {
                last400 = Exception("HTTP $code（参数形态 ${idx + 1}/${variants.size} 被拒，已降级）")
                Logs.warn("全网搜索 @$api 参数降级：${body} → HTTP $code，换下一形态")
                continue
            }
            if (code != 200) throw Exception("HTTP $code")
            val o = try {
                JSONObject(text)
            } catch (_: Exception) {
                throw Exception("搜索服务返回异常")
            }
            if (o.optInt("code", -1) != 0) throw Exception(o.optString("message", "搜索服务错误"))
            val byType = o.optJSONObject("data")?.optJSONObject("merged_by_type")
                ?: return PanResult(emptyList(), 0)
            val out = mutableListOf<GResult>()
            fun collect(key: String, platform: String) {
                val arr = byType.optJSONArray(key) ?: return
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    val url = it.optString("url", "")
                    if (url.isBlank()) continue
                    val title = it.optString("note", "").replace(Regex("[\\r\\n]+"), " ").ifBlank { "未命名资源" }
                    out.add(
                        GResult(
                            title = title,
                            url = url,
                            password = it.optString("password", ""),
                            platform = platform,
                            time = parseTime(it.optString("datetime", ""))
                        )
                    )
                }
            }
            collect("quark", "quark")
            collect("uc", "uc")
            collect("123", "123")
            // 其它盘型（磁力/百度/阿里/迅雷…）总数——插件只支持 123/夸克/UC，用于空结果时的精确提示
            var otherCount = 0
            for (k in byType.keys()) {
                if (k == "quark" || k == "uc" || k == "123") continue
                otherCount += byType.optJSONArray(k)?.length() ?: 0
            }
            if (out.isEmpty()) return PanResult(emptyList(), otherCount)
            // 去重（同链接多来源）+ 按时间倒序（无时间的排后面）
            val seen = HashSet<String>()
            return PanResult(
                out.filter { seen.add(it.url) }
                    .sortedWith(compareByDescending<GResult> { it.time }.thenBy { it.title }),
                otherCount
            )
        }
        throw last400 ?: Exception("搜索失败")
    }

    /** 解析 PanSou 时间（2026-07-16T16:30:04Z）；无效时间（0001 开头）返回 0 */
    private fun parseTime(s: String): Long {
        if (s.isBlank() || s.startsWith("0001")) return 0L
        return try {
            val t = s.substring(0, minOf(19, s.length))
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(t)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatGlobalPage(st: GSearch): String {
        val all = st.results
        if (all.isEmpty()) return "没搜到「${st.keyword}」的网盘资源，换个关键词试试"
        // 每次展示刷新时间戳：数字归属仲裁以“最近给用户看过哪个列表”为准
        st.shownAt = System.currentTimeMillis()
        val pageSize = 10
        val totalPages = (all.size + pageSize - 1) / pageSize
        val page = st.page.coerceIn(0, totalPages - 1)
        st.page = page
        val from = page * pageSize
        val to = minOf(from + pageSize, all.size)
        val sb = StringBuilder()
        sb.append("🔍 全网搜索「${st.keyword}」（共 ${all.size} 条 · 第 ${page + 1}/${totalPages} 页）：\n")
        for (i in from until to) {
            val r = all[i]
            val tag = when (r.platform) {
                "quark" -> "夸克"
                "uc" -> "UC"
                else -> "123"
            }
            sb.append("${i + 1}. [$tag] ${r.title.take(50)}")
            if (r.password.isNotBlank()) sb.append("（密码:${r.password}）")
            sb.append("\n")
        }
        if (totalPages > 1) sb.append("翻页：「找」「搜索」下一页 ·「上一页」上一页 ·「找 N」跳第 N 页\n")
        sb.append("发序号直接打开对应分享（无需先建网盘会话）")
        return sb.toString()
    }

    // ---------------- 搜索统一路由 / 翻页（1.7.49；1.7.50 补丁：不支持盘内搜索，一律全网搜） ----------------

    /**
     * 搜索/搜/找 统一路由——**全部走全网搜索**（明确不支持盘内搜索）：
     * - 带页码 N → 跳第 N 页（越界钳位并注明）
     * - 无参数   → 翻下一页（末页/首页有明确提示）
     * - 关键词   → 全网搜夸克/UC/123 公开分享
     */
    private fun searchCommand(sessionKey: String, arg: String, config: List<PluginConfigField>): String {
        val num = arg.trim().toIntOrNull()
        return when {
            num != null -> jumpPage(sessionKey, num)
            arg.isBlank() -> pageMove(sessionKey, +1)
            else -> searchGlobal(sessionKey, arg, config)
        }
    }

    /** 翻页（+1 下一页 / -1 上一页），作用于全网搜索结果 */
    private fun pageMove(sessionKey: String, delta: Int): String {
        val g = gSearches[sessionKey]
        if (g != null && g.results.isNotEmpty()) return moveGlobal(g, delta)
        return "还没有搜索结果：先发「找 关键词」全网搜"
    }

    private fun moveGlobal(g: GSearch, delta: Int): String {
        val totalPages = (g.results.size + 9) / 10
        val cur = g.page.coerceIn(0, totalPages - 1)
        if (delta > 0 && cur >= totalPages - 1) return formatGlobalPage(g) + "\n⚠️ 已经是最后一页（第 ${cur + 1}/$totalPages 页）"
        if (delta < 0 && cur <= 0) return formatGlobalPage(g) + "\n⚠️ 已经是第 1 页"
        g.page = cur + delta
        return formatGlobalPage(g)
    }

    /** 跳页（1 基）：全网结果态；越界钳位并注明 */
    private fun jumpPage(sessionKey: String, num: Int): String {
        val g = gSearches[sessionKey] ?: return "还没有全网搜索结果：先发「找 关键词」"
        val totalPages = (g.results.size + 9) / 10
        val target = num.coerceIn(1, totalPages)
        g.page = target - 1
        val note = if (target != num) "（页码超范围，已定位第 $target/$totalPages 页）" else ""
        return formatGlobalPage(g) + note
    }

    /** 搜索结果态的“打开/选择/直链”：序号或标题关键词 → 打开该结果（与盘内同款语义） */
    private fun pickGlobalResult(sessionKey: String, arg: String): String {
        val g = gSearches[sessionKey] ?: return help()
        val a = arg.trim()
        if (a.isBlank()) return "用法：打开/选择 + 结果序号，或资源标题关键词（如：选择 2）"
        a.toIntOrNull()?.let { n ->
            val r = g.results.getOrNull(n - 1)
                ?: return "序号超出范围（搜索结果共 ${g.results.size} 条，发「找」翻页查看）"
            return openGlobalResult(sessionKey, r)
        }
        val hit = g.results.indexOfFirst { it.title.contains(a, ignoreCase = true) }
        if (hit < 0) return "结果里没有标题含「$a」的资源；直接发序号更稳（如：选择 2）"
        return openGlobalResult(sessionKey, g.results[hit])
    }

    /** 搜索结果态的“%/回退/返回”：清除搜索结果（等价退出该上下文） */
    private fun clearGlobal(sessionKey: String): String {
        val had = gSearches.remove(sessionKey) != null
        return if (had) "✅ 已清除搜索结果（重新搜：找 关键词）" else help()
    }

    /** 打开全网搜索结果：自动提取密码 → 走 init 进入网盘会话 */
    private fun openGlobalResult(sessionKey: String, r: GResult): String {
        val pwd = r.password.ifBlank {
            Regex("[?&]pwd=([A-Za-z0-9]+)").find(r.url)?.groupValues?.get(1) ?: ""
        }
        Logs.info("全网搜索打开：${r.platform} ${r.url.take(90)}")
        val arg = if (pwd.isNotBlank()) "${r.url} 密码:$pwd" else r.url
        val reply = init(sessionKey, arg, emptyList())
        return "📂 来自全网搜索：${r.title.take(40)}\n\n$reply"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private inline fun <T> retry(times: Int = 3, block: (attempt: Int) -> T): T {
        var last: Exception? = null
        for (i in 0 until times) {
            try {
                return block(i)
            } catch (e: Exception) {
                last = e
                if (i < times - 1) {
                    try {
                        Thread.sleep(250L * (i + 1))
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
        throw last ?: Exception("请求失败")
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun friendly(e: Exception): String {
        val m = e.message ?: return "未知错误"
        return when {
            m.startsWith("HTTP 400") -> "网盘拒绝了请求(400)，已自动重试仍失败。多为分享凭证过期，请重新发一次「网盘:<链接>」"
            m.startsWith("HTTP 401") || m.startsWith("HTTP 403") -> "登录态失效或没有权限(401/403)，请到插件页网盘助手重新「登录」"
            m.startsWith("HTTP 404") -> "分享已失效或链接有误(404)"
            m.startsWith("HTTP 429") -> "请求过于频繁(429)，稍等一会再试"
            m.contains("timeout", true) || m.contains("超时") -> "网盘响应超时，请稍后重试"
            else -> m
        }
    }

    private fun post(
        url: String,
        body: String,
        cookie: String = "",
        ua: String = UA,
        referer: String = "",
        bearer: String = ""
    ): Pair<Int, String> {
        val b = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "*/*")
            .header("User-Agent", ua)
        if (bearer.isNotBlank()) b.header("Authorization", "Bearer $bearer")
        if (referer.isNotBlank()) b.header("Referer", referer)
        if (cookie.isNotBlank()) b.header("Cookie", cookie)
        val req = b.post(RequestBody.create(json, body)).build()
        val resp = http.newCall(req).execute()
        return resp.code to (resp.body?.string().orEmpty())
    }

    private fun get(url: String, cookie: String = "", ua: String = UA, referer: String = ""): Pair<Int, String> {
        val b = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("User-Agent", ua)
        if (referer.isNotBlank()) b.header("Referer", referer)
        if (cookie.isNotBlank()) b.header("Cookie", cookie)
        val resp = http.newCall(b.get().build()).execute()
        return resp.code to (resp.body?.string().orEmpty())
    }

    // ---------------- 123网盘 ----------------
    private fun p123List(s: Session, dirId: String, limit: Int): List<NFile> {
        val body = JSONObject()
            .put("shareKey", s.shareId)
            .put("pwd", s.password ?: "")
            .put("ParentId", dirId)
            .put("Page", 1)
            .put("Limit", limit)
            .put("OrderBy", "FileName")
        val (code, text) = post("https://www.123pan.com/api/share/list", body.toString(), cookieOf("123"))
        if (code != 200) throw Exception("HTTP $code")
        val o = JSONObject(text)
        val biz = o.optInt("code", -1)
        if (biz != 0) throw Exception("123网盘 ${o.optString("message", "错误 $biz")}")
        val arr = o.optJSONObject("data")?.optJSONArray("InfoList") ?: JSONArray()
        val out = mutableListOf<NFile>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            out.add(
                NFile(
                    name = it.optString("FileName", "未命名"),
                    id = it.optString("FileId", ""),
                    isFolder = it.optInt("Type", 1) == 0,
                    size = it.optLong("Size", 0),
                    etag = it.optString("Etag", ""),
                    s3KeyFlag = it.optString("S3KeyFlag", "")
                )
            )
        }
        return out
    }

    private fun p123Unwrap(url: String): String {
        return try {
            val q = URI(url).rawQuery ?: return url
            val p = q.split("&").firstOrNull { it.startsWith("params=") }?.removePrefix("params=")
                ?: return url
            var s = p
            try {
                s = URLDecoder.decode(s, "UTF-8")
            } catch (_: Exception) {
            }
            val decoded = try {
                String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
            if (!decoded.isNullOrBlank() && decoded.startsWith("http")) decoded else url
        } catch (_: Exception) {
            url
        }
    }

    private fun p123Download(s: Session, f: NFile): LinkResult {
        val cookie = cookieOf("123")
        val bearer = tokenOf("123")
        try {
            val body = JSONObject()
                .put("ShareKey", s.shareId)
                .put("pwd", s.password ?: "")
                .put("FileID", f.id)
                .put("S3keyFlag", f.s3KeyFlag)
                .put("Size", f.size)
                .put("Etag", f.etag)
            val b = Request.Builder()
                .url("https://www.123pan.com/api/share/download/info")
                .header("Content-Type", "application/json")
                .header("Accept", "*/*")
                .header("Platform", "ios")
                .header("User-Agent", UA)
            if (cookie.isNotBlank()) b.header("Cookie", cookie)
            if (bearer.isNotBlank()) b.header("Authorization", "Bearer $bearer")
            val resp = http.newCall(b.post(RequestBody.create(json, body.toString())).build()).execute()
            val text = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                val o = JSONObject(text)
                val biz = o.optInt("code", -1)
                val url = o.optJSONObject("data")?.optString("DownloadUrl").orEmpty()
                    .ifBlank { o.optJSONObject("data")?.optString("DownloadURL").orEmpty() }
                if (url.isNotBlank()) return LinkResult(p123Unwrap(url), null)
                if (biz == 5112) return LinkResult(null, "123网盘要求先登录后再取链")
                if (biz == 5113) return LinkResult(null, "123网盘提示本月免费流量不足")
                if (biz != 0) Logs.err("123 直链方式一 code=$biz ${o.optString("message")}")
            }
        } catch (e: Exception) {
            Logs.err("123 直链方式一异常: ${e.message}")
        }
        return try {
            val body = JSONObject()
                .put("shareKey", s.shareId)
                .put("pwd", s.password ?: "")
                .put("fileId", f.id)
            val (rc, rt) = post("https://www.123pan.com/api/share/download/request", body.toString(), cookie, bearer = bearer)
            val code = if (rc == 200) JSONObject(rt).optJSONObject("data")?.optString("AsFunctionCode").orEmpty() else ""
            val (c2, t2) = post(
                "https://www.123pan.com/api/share/download/info",
                body.put("AsFunctionCode", code).toString(),
                cookie, bearer = bearer
            )
            if (c2 != 200) return LinkResult(null, "HTTP $c2")
            val o = JSONObject(t2)
            val biz = o.optInt("code", -1)
            if (biz != 0) return LinkResult(null, "123网盘 ${o.optString("message", "错误 $biz")}")
            val url = o.optJSONObject("data")?.optString("DownloadUrl").orEmpty()
                .ifBlank { o.optJSONObject("data")?.optString("DownloadURL").orEmpty() }
            if (url.isBlank()) LinkResult(null, "123网盘未返回直链") else LinkResult(p123Unwrap(url), null)
        } catch (e: Exception) {
            LinkResult(null, e.message)
        }
    }

    // ---------------- 夸克 / UC 网盘（同一套 clouddrive 后端） ----------------
    private class QkConf(val base: String, val origin: String, val pr: String, val ua: String)

    private fun qkConf(platform: String): QkConf = if (platform == "uc")
        QkConf("https://pc-api.uc.cn/1/clouddrive", "https://drive.uc.cn", "UCBrowser", UC_DL_UA)
    else
        QkConf("https://drive-pc.drive.quark.cn/1/clouddrive", "https://pan.quark.cn", "ucpro", QUARK_DL_UA)

    private fun qkBases(platform: String, prefer: String = ""): List<String> {
        val primary = qkConf(platform).base
        val secondary = when (platform) {
            "uc" -> "https://pc-api.uc.cn/1/clouddrive"
            "quark" -> "https://drive-pc.quark.cn/1/clouddrive"
            else -> primary
        }
        return if (prefer.isNotBlank() && prefer != primary) listOf(prefer, primary) else listOf(primary, secondary)
    }

    private fun qkToken(pid: String, pwd: String?, platform: String, preferBase: String = ""): String? {
        val conf = qkConf(platform)
        for (base in qkBases(platform, preferBase)) {
            try {
                val body = JSONObject().put("pwd_id", pid).put("passcode", pwd ?: "")
                val url = "$base/share/sharepage/token?pr=${conf.pr}&fr=pc&uc_param_str=" +
                    "&__dt=${(Math.random() * 9000 + 400).toInt()}&__t=${System.currentTimeMillis()}"
                val b = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Content-Type", "application/json")
                    .header("Accept", "*/*")
                    .header("Origin", conf.origin)
                    .header("Referer", conf.origin + "/")
                val resp = http.newCall(b.post(RequestBody.create(json, body.toString())).build()).execute()
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Logs.err("夸克/UC 凭证 HTTP ${resp.code} @$base: ${text.take(120)}")
                    continue
                }
                val o = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    continue
                }
                val tk = o.optJSONObject("data")?.optString("stoken")?.takeIf { it.isNotBlank() }
                if (tk != null) return tk
            } catch (e: Exception) {
                Logs.err("夸克/UC 凭证异常 @$base: ${e.message}")
            }
        }
        return null
    }

    private fun qkList(s: Session, dirId: String, limit: Int): List<NFile> {
        val bases = qkBases(s.platform, s.qkBase)
        var lastErr: Exception? = null
        for (base in bases) {
            for (attempt in 0..1) {
                try {
                    val files = qkListOnce(s, dirId, limit, base)
                    s.qkBase = base
                    return files
                } catch (e: Exception) {
                    lastErr = e
                    val needRefresh = attempt == 0 && isAuthError(e)
                    if (needRefresh) {
                        Logs.info("夸克/UC 列表凭证疑似过期，刷新后重试: ${e.message}")
                        refreshToken(s)
                    }
                }
            }
        }
        throw lastErr ?: Exception("夸克/UC 列表失败")
    }

    private fun isAuthError(e: Exception): Boolean {
        val m = e.message.orEmpty()
        return m.contains("HTTP 400") || m.contains("HTTP 401") || m.contains("HTTP 403")
    }

    private fun qkListOnce(s: Session, dirId: String, limit: Int, base: String): List<NFile> {
        val conf = qkConf(s.platform)
        val params = "pwd_id=${enc(s.shareId)}&passcode=${enc(s.password ?: "")}&stoken=${enc(s.token)}" +
            "&pdir_fid=${enc(dirId)}&force=0&_page=1&_size=$limit&_fetch_banner=0&_fetch_share=0" +
            "&_fetch_total=1&_sort=file_type:asc,updated_at:desc"
        val b = Request.Builder()
            .url("$base/share/sharepage/detail?$params")
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("Origin", conf.origin)
            .header("Referer", conf.origin + "/")
        val resp = http.newCall(b.get().build()).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            Logs.err("夸克/UC 列表 HTTP ${resp.code} @$base: ${text.take(120)}")
            throw Exception("HTTP ${resp.code}")
        }
        val o = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw Exception("夸克/UC 返回异常")
        }
        val biz = o.optInt("code", 0)
        if (biz != 0) throw Exception("夸克/UC ${o.optString("message", "错误 $biz")}")
        val arr = o.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val out = mutableListOf<NFile>()
        var hasToken = 0
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val tk = it.optString("share_fid_token", "")
            if (tk.isNotBlank()) hasToken++
            out.add(
                NFile(
                    name = it.optString("file_name", "未命名"),
                    id = it.optString("fid", ""),
                    isFolder = it.optBoolean("dir", false),
                    size = it.optLong("size", 0),
                    token = tk
                )
            )
        }
        Logs.info("夸克/UC 列表 ${out.size} 项，含 share_fid_token $hasToken 项")
        return out
    }

    class LinkResult(val url: String?, val error: String?)

    private fun qkDownload(s: Session, f: NFile): LinkResult {
        val isShare = s.url.contains("/s/") || s.url.contains("/share/")
        val bases = qkBases(s.platform, s.qkBase)
        var last: LinkResult? = null
        var currentToken = f.token
        for (base in bases) {
            for (attempt in 0..1) {
                val r = if (isShare) {
                    qkDownloadOnceShare(s, f.id, currentToken, base)
                } else {
                    qkDownloadOnceHome(s.platform, f.id, base, cookieOf(s.platform))
                }
                if (r.url != null) {
                    s.qkBase = base
                    return r
                }
                last = r
                if (attempt == 1) break
                val msg = r.error.orEmpty()
                val tokenErr = isShareTokenError(msg)
                // 1.7.94：412（需要登录才能下载）纳入鉴权失败——刷新 stoken 后重试并**切域名**
                // （LinkSwift 实测用 drive-pc.quark.cn，与我方 primary drive-pc.drive.quark.cn 互为备选，
                //  风控常只拦其一）
                val authErr = msg.contains("HTTP 400") || msg.contains("HTTP 401") ||
                    msg.contains("HTTP 403") || msg.contains("HTTP 412")
                when {
                    tokenErr -> {
                        Logs.info("夸克/UC 文件 token 过期（41020），重列目录取新 token 后重试")
                        refreshToken(s)
                        try {
                            val fresh = qkList(s, s.dirId, 200)
                            s.files = fresh
                            val freshFile = fresh.firstOrNull { it.id == f.id }
                            if (freshFile != null) currentToken = freshFile.token
                        } catch (e: Exception) {
                            Logs.err("刷新 share_fid_token 失败: ${e.message}")
                        }
                    }
                    authErr -> {
                        Logs.info("夸克/UC 直链鉴权/登录态失败（${msg.take(40)}），刷新 stoken 并切域名后重试")
                        refreshToken(s)
                    }
                    else -> return r
                }
            }
        }
        // 终局：412 给登录指引（LinkSwift 语义：需要登录才能下载）
        val err = last?.error.orEmpty()
        return if (err.contains("412")) {
            LinkResult(null, "HTTP 412（需要登录才能下载）——请到插件页网盘助手点「登录」刷新网盘登录态后重试")
        } else {
            last ?: LinkResult(null, "未知错误")
        }
    }

    private fun isShareTokenError(msg: String): Boolean {
        if (msg.isBlank()) return false
        if (msg.contains("41020")) return true
        val lower = msg.lowercase()
        return (lower.contains("token") && (lower.contains("校验") || lower.contains("invalid") || lower.contains("expired")))
    }

    private fun qkDownloadOnceHome(platform: String, fid: String, base: String, cookie: String): LinkResult {
        val conf = qkConf(platform)
        val body = JSONObject().put("fids", JSONArray().put(fid)).put("sp", 100)
        val url = "$base/file/download?entry=ft&fr=pc&pr=${conf.pr}"
        return qkDoRequest(url, body, conf, cookie)
    }

    private fun qkDownloadOnceShare(s: Session, fid: String, fToken: String, base: String): LinkResult {
        val conf = qkConf(s.platform)
        val body = JSONObject()
            .put("fids", JSONArray().put(fid))
            .put("fids_token", JSONArray().put(fToken))
            .put("pwd_id", s.shareId)
            .put("stoken", s.token)
            .put("sp", 100)
        val url = "$base/file/download?entry=ft&fr=pc&pr=${conf.pr}"
        return qkDoRequest(url, body, conf, cookieOf(s.platform))
    }

    private fun qkDoRequest(url: String, body: JSONObject, conf: QkConf, cookie: String = ""): LinkResult {
        return try {
            val b = Request.Builder()
                .url(url)
                .header("User-Agent", conf.ua)
                .header("Content-Type", "application/json")
                .header("Accept", "*/*")
                .header("Origin", conf.origin)
                .header("Referer", conf.origin + "/")
            if (cookie.isNotBlank()) b.header("Cookie", cookie)
            val resp = http.newCall(b.post(RequestBody.create(json, body.toString())).build()).execute()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Logs.err("夸克/UC 直链 HTTP ${resp.code} $url: ${text.take(150)}")
                // 1.7.94：412 = 需要登录才能下载（LinkSwift 实证语义）——给专属文案供上层重试/指引
                if (resp.code == 412) return LinkResult(null, "HTTP 412（需要登录才能下载）")
                return LinkResult(null, if (resp.code == 400) "HTTP 400（凭证可能已过期）" else "HTTP ${resp.code}")
            }
            val o = try {
                JSONObject(text)
            } catch (_: Exception) {
                return LinkResult(null, "夸克/UC 返回异常")
            }
            val biz = o.optInt("code", -1)
            if (biz != 0) {
                val msg = when (biz) {
                    31001 -> "网盘要求先登录，请在插件页网盘助手点「登录」后重试"
                    23018 -> "超出游客可获取的文件大小限制，请登录网盘后再试"
                    else -> o.optString("message", "错误 $biz")
                }
                Logs.err("夸克/UC 直链业务码 $biz: $msg")
                return LinkResult(null, msg)
            }
            val arr = o.optJSONArray("data")
            if (arr == null || arr.length() == 0) {
                Logs.err("夸克/UC 直链响应异常: ${text.take(200)}")
                return LinkResult(null, "网盘未返回下载链接")
            }
            val obj = arr.optJSONObject(0) ?: return LinkResult(null, "网盘未返回下载链接")
            val link = obj.optString("download_url").takeIf { it.isNotBlank() }
                ?: return LinkResult(null, "网盘未返回下载链接")
            if (link.contains("pds.quark.cn") && link.contains("OSSAccessKeyId")) {
                val objKey = Regex("""drive\.quark\.cn/[a-zA-Z0-9]+/[a-zA-Z0-9]+/[a-f0-9]+/[a-f0-9]+""")
                    .find(link)?.value?.removePrefix("drive.quark.cn/")
                    ?: Regex("""pds\.quark\.cn/.+?(?=\?|$)""").find(link)?.value?.removePrefix("pds.quark.cn/")
                val fnEnc = Regex("""filename[^&]*""").find(link)?.value?.removePrefix("filename=")?.substringAfter("filename%3D", link.substringAfter("filename=", ""))
                    ?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { null } }
                if (objKey != null) {
                    val driveUrl = buildString {
                        append("https://dl-pc-zb.drive.quark.cn/$objKey")
                        append("?abt=8_0_&sp=100&dfi=193")
                        fnEnc?.let { append("&filename=").append(URLEncoder.encode(it, "UTF-8")) }
                        // 保留原直链的鉴权参数（auth_key/token/orp/...），缺失则链接打不开
                        link.substringAfter('?', "").split('&')
                            .firstOrNull { it.startsWith("auth_key=") }
                            ?.let { append("&").append(it) }
                    }
                    Logs.info("[直链] 大文件 OSS URL 转换为直链: ${driveUrl.take(80)}...")
                    return LinkResult(driveUrl, null)
                }
            }
            LinkResult(link, null)
        } catch (e: Exception) {
            LinkResult(null, e.message)
        }
    }

    // ---------------- 通用 ----------------
    private fun listFiles(s: Session, config: List<PluginConfigField>): List<NFile> {
        val limit = listLimit(config)
        return retry(2) {
            when (s.platform) {
                "123" -> p123List(s, s.dirId, limit)
                "quark", "uc" -> qkList(s, s.dirId, limit)
                else -> throw Exception("暂不支持的平台")
            }
        }
    }

    private fun downloadUrl(s: Session, f: NFile): LinkResult = when (s.platform) {
        "123" -> p123Download(s, f)
        "quark", "uc" -> qkDownload(s, f)
        else -> LinkResult(null, "暂不支持的平台")
    }

    private fun platformName(p: String): String = when (p) {
        "123" -> "123网盘"
        "quark" -> "夸克网盘"
        "uc" -> "UC网盘"
        else -> p
    }

    private fun fmtSize(b: Long): String {
        if (b <= 0) return ""
        return when {
            b >= 1L shl 30 -> "%.2fGB".format(b.toDouble() / (1L shl 30))
            b >= 1L shl 20 -> "%.1fMB".format(b.toDouble() / (1L shl 20))
            else -> "%.0fKB".format(b.toDouble() / (1L shl 10))
        }
    }

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

    private const val QUARK_DL_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/3.20.0 Chrome/112.0.5615.165 Electron/24.1.3.8 Safari/537.36 Channel/pckk_other_ch"

    private const val UC_DL_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) uc-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"

    // ---------------- 真实登录验证 ----------------
    fun verifyLogin(platform: String): Boolean? {
        val cookie = cookieOf(platform)
        if (cookie.isBlank()) return false
        return when (platform) {
            "quark" -> verifyClouddriveMember(
                "https://drive-pc.quark.cn/1/clouddrive/member?pr=ucpro&fr=pc&uc_param_str=" +
                    "&__dt=${(Math.random() * 9000 + 400).toInt()}&__t=${System.currentTimeMillis()}",
                cookie,
                "https://pan.quark.cn/"
            )
            "uc" -> verifyClouddriveMember(
                "https://pc-api.uc.cn/1/clouddrive/member?pr=UCBrowser&fr=pc&uc_param_str=" +
                    "&__dt=${(Math.random() * 9000 + 400).toInt()}&__t=${System.currentTimeMillis()}",
                cookie,
                "https://drive.uc.cn/"
            )
            "123" -> looksLoggedIn(platform, cookie)
            else -> null
        }
    }

    private fun verifyClouddriveMember(url: String, cookie: String, referer: String): Boolean? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json, */*")
            .header("Cookie", cookie)
            .header("Referer", referer)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val text = try {
                resp.body?.string().orEmpty()
            } catch (_: Exception) {
                ""
            }
            when {
                resp.code == 200 -> !looksLoggedOutJson(text)
                resp.code == 401 || resp.code == 403 -> false
                else -> {
                    Logs.info("登录校验 HTTP ${resp.code}（接口异常，不判定）: ${text.take(80)}")
                    null
                }
            }
        }
    } catch (e: Exception) {
        Logs.err("登录校验异常: ${e.message}")
        null
    }

    private fun looksLoggedOutJson(text: String): Boolean {
        if (text.isBlank()) return true
        val l = text.lowercase()
        if (l.contains("\"code\":31001") || l.contains("\"code\":41020") || l.contains("\"code\":-1")) return true
        if (l.contains("require login") || l.contains("未登录")) return true
        return false
    }

    private val LOGIN_KEYS = mapOf(
        "quark" to listOf("b-user-id", "__pus"),
        "uc" to listOf("b-user-id", "__pus"),
        "123" to listOf("auth-token", "authortoken")
    )

    private fun looksLoggedIn(platform: String, cookie: String): Boolean {
        if (cookie.isBlank()) return false
        val keys = LOGIN_KEYS[platform] ?: return cookie.length > 40
        val lower = cookie.lowercase()
        return keys.any { lower.contains("${it}=") }
    }
}

/**
 * 看番助手（B 站版，参考 bililidownloadtool 2.2.0）：
 * - 搜索：B 站搜索页 HTML 解析（不依赖第三方聚合域名，避免搜不到）
 * - 链接路由：识别 ep / ss / BV / av / 短链，按类型调对应 playurl API
 * - 播放直链：DASH 音视频分片（登录态可拿 720p+/会员番剧）
 * 登录态由 [BiliAuth] 提供。
 */
object AnimeDownloader {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    data class AnimeSearchResult(val id: String, val title: String, val cover: String, val episodeCount: Int)
    data class AnimeEpisode(val number: Int, val title: String, val url: String?, val pageUrl: String, val source: String = "bili")
    data class AnimeInfo(val title: String, val cover: String, val status: String, val description: String, val episodes: List<AnimeEpisode>)

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
    /** 移动端 UA（B 站搜索 API 风控更宽松） */
    private const val UA_MOBILE = "Mozilla/5.0 (Linux; Android 14; SM-X910F Build/U1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"
    private const val REF_HOME = "https://www.bilibili.com/"
    private const val REF_SEARCH = "https://search.bilibili.com/"

    private fun cookieHeader(): String = BiliAuth.cookie()

    private fun biliGet(url: String, referer: String = REF_HOME): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply { if (cookieHeader().isNotBlank()) header("Cookie", cookieHeader()) }
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logs.err("[番剧] HTTP ${resp.code}：$url")
                return null
            }
            resp.body?.string()
        }
    }

    // ---------------- 链接路由（参考 bililidownloadtool video_parser.parse_media_url） ----------------
    /** 从用户输入识别 B 站链接类型；纯文字关键词返回 null（走搜索） */
    fun routeLink(text: String): Pair<String, String>? {
        val t = text.trim()
        // b23.tv 短链先解析重定向
        val resolved = if (t.contains("b23.tv")) resolveShort(t) else t
        return when {
            resolved.contains("bangumi") || resolved.contains("/ep") || resolved.matches(Regex(".*ep\\d+.*")) ->
                "ep" to (Regex("ep(\\d+)").find(resolved)?.groupValues?.get(1) ?: "")
            resolved.contains("/ss") || resolved.matches(Regex(".*ss\\d+.*")) ->
                "ss" to (Regex("ss(\\d+)").find(resolved)?.groupValues?.get(1) ?: "")
            Regex("BV[0-9A-Za-z]{10}").find(resolved)?.value != null ->
                "BV" to Regex("BV[0-9A-Za-z]{10}").find(resolved)!!.value
            Regex("av\\d+").find(resolved)?.value != null ->
                "av" to Regex("av\\d+").find(resolved)!!.value
            else -> null
        }
    }

    private fun resolveShort(url: String): String {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            http.newCall(req).execute().use { resp ->
                val loc = resp.header("Location") ?: resp.request.url.toString()
                loc
            }
        } catch (_: Exception) { url }
    }

    // ---------------- WBI 签名（参考 bililidownloadtool core/infra/wbi_sign.py） ----------------
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52
    )

    /** 从 nav 接口取 wbi_img（img_key / sub_key）。未登录也能取到。 */
    private fun fetchWbiKeys(): Pair<String, String>? {
        val body = biliGet("https://api.bilibili.com/x/web-interface/nav") ?: return null
        return try {
            val root = org.json.JSONObject(body)
            if (root.optInt("code") != 0) return null
            val wbi = root.optJSONObject("wbi_img") ?: return null
            val imgKey = wbi.optString("img_url", "").split("/").lastOrNull()?.substringBeforeLast(".") ?: ""
            val subKey = wbi.optString("sub_url", "").split("/").lastOrNull()?.substringBeforeLast(".") ?: ""
            if (imgKey.isEmpty() || subKey.isEmpty()) null else imgKey to subKey
        } catch (_: Exception) { null }
    }

    /** WBI 签名（含 dm_img 反作弊参数）：mixin_key = 打乱(img_key+sub_key) 取 32 位；
     *  w_rid = md5(排序后的 query + mixin_key)。B 站搜索/详情接口要求带 dm_img_* 参数。
     *  返回可直接拼接的完整 query string（含 w_rid）。 */
    private fun signWbi(params: Map<String, String>): String {
        val keys = fetchWbiKeys() ?: ("img_key" to "sub_key")
        val imgKey = keys.first; val subKey = keys.second
        val combined = imgKey + subKey
        val padded = if (combined.length < 64) combined + "0".repeat(64 - combined.length) else combined
        val mixin = StringBuilder()
        for (i in MIXIN_KEY_ENC_TAB) mixin.append(padded[i])
        val mixinKey = mixin.toString()
        val wts = (System.currentTimeMillis() / 1000).toString()
        val withWts = params.toMutableMap().apply {
            put("wts", wts)
            // B 站反作弊参数（搜索/详情接口要求，参考 bililidownloadtool 实际请求）
            put("dm_cover_img_str", "EF")
            put("dm_img_inter", "{\"ds\":[],\"wh\":[0,0,0],\"of\":[0,0,0]}")
            put("dm_img_list", "[]")
            put("dm_img_str", "KC")
            put("web_location", "1550101")
        }
        val sorted = withWts.toSortedMap()
        // 过滤特殊字符（B 站 WBI 要求去掉 !'()*）
        val cleaned = sorted.mapValues { (k, v) ->
            v.filterNot { c -> "!'()*".contains(c) }
        }.toMutableMap()
        val query = cleaned.map { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }.joinToString("&")
        val wRid = md5(query + mixinKey)
        return query + "&w_rid=" + wRid
    }

    private fun md5(s: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        val bytes = digest.digest(s.toByteArray())
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /**
     * 搜索关键词：走 B 站官方搜索 API（带 WBI 签名 + 移动端 UA），番剧(search_type=3)优先，无结果再查视频(search_type=1)。
     * 比 HTML 解析稳定（B 站搜索页 HTML 在移动端/机顶盒上常抓空）。
     */
    fun searchAnime(name: String): List<AnimeSearchResult> {
        val kw = name.trim()
        if (kw.isEmpty()) return emptyList()
        // ① 番剧搜索（search_type=3）—— B 站番剧库，按相关度排序
        val bangumiResults = biliSearchApi(kw, 3)
        if (bangumiResults.isNotEmpty()) return bangumiResults
        // ② 视频搜索（search_type=1，番剧未命中时的兜底）
        val videoResults = biliSearchApi(kw, 1)
        if (videoResults.isNotEmpty()) return videoResults
        // ③ HTML 兜底
        return searchAnimeHtml(kw)
    }

    // ---------------- 邦宁云播（video.isyour.love）聚合搜索：完整流，无需大会员/登录 ----------------
    /** 云播搜索结果条目：一条 = 某片源站的一部片，自带全集 m3u8 直链 */
    data class IyResult(
        val name: String,          // 片名（如 "夏日重现"）
        val describe: String,      // "2022★日本★日韩动漫"（年份+地区+类型）
        val typeId: String,        // 源站标识（yy/js/md/ry/hy/zd...）
        val userMark: String,      // "t-优质" / "hd" 质量标记
        val eps: List<Pair<String, String>> // (集名, m3u8 完整直链)
    )

    private const val IY_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /**
     * 邦宁云播聚合搜索（官方接口文档：video.isyour.love/html/playerHelp.html）。
     * - 接口：GET /player/getSource?name=<关键词>&pg=<页>（20 条/页，按相关度）
     * - 鉴权：headers token = md5("isme" + t + k)，t=毫秒时间戳，k=9 位随机字母数字
     * - 每条结果 Data[].S 即该集完整 m3u8 直链（实测 1920x1080 多码率，无需大会员/登录）
     */
    fun isyourSearch(name: String, pg: Int = 1): List<IyResult> {
        val kw = name.trim()
        if (kw.isEmpty()) return emptyList()
        return try {
            val t = System.currentTimeMillis()
            val k = buildString { repeat(9) { append(IY_ALPHABET[kotlin.random.Random.nextInt(IY_ALPHABET.length)]) } }
            val token = md5("isme$t$k")
            val q = java.net.URLEncoder.encode(kw, "UTF-8")
            val req = Request.Builder()
                .url("https://video.isyour.love/player/getSource?name=$q&pg=$pg")
                .header("User-Agent", UA)
                .header("Referer", "https://video.isyour.love/player/getplayer?url=$q")
                .header("token", token)
                .header("t", t.toString())
                .header("k", k)
                .header("Accept", "application/json, text/plain, */*")
                .build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logs.warn("[云播] 搜索 HTTP ${resp.code}（name=$kw pg=$pg）")
                    null
                } else resp.body?.string()
            } ?: return emptyList()
            val arr = org.json.JSONArray(body)
            val list = mutableListOf<IyResult>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val data = o.optJSONArray("Data") ?: continue
                val eps = mutableListOf<Pair<String, String>>()
                for (j in 0 until data.length()) {
                    val e = data.optJSONObject(j) ?: continue
                    val n = e.optString("N", "").ifBlank { "第${j + 1}集" }
                    val s = e.optString("S", "")
                    if (s.isNotBlank() && s.startsWith("http")) eps.add(n to s)
                }
                if (eps.isEmpty()) continue
                val mark = o.optString("UserMark", "").let { if (it == "null") "" else it }
                list.add(
                    IyResult(
                        name = o.optString("Name", "").trim(),
                        describe = o.optString("Describe", "").trim(),
                        typeId = o.optString("TypeId", "").trim(),
                        userMark = mark,
                        eps = eps
                    )
                )
            }
            Logs.info("[云播] 搜索「$kw」pg=$pg → ${list.size} 条（首条 ${list.firstOrNull()?.name} ${list.firstOrNull()?.eps?.size}集）")
            list
        } catch (e: Exception) {
            Logs.err("[云播] 搜索异常: ${e.message}")
            emptyList()
        }
    }

    /**
     * 补集标题：云播源每集只有「第01集」没有标题，这里用 B 站搜索+season 接口（无需登录）
     * 取每集标题（如「未视感」），按集顺序返回。
     * 搜索结果逐个尝试，**集数与 expectedCount 对齐的结果直接采用**（防搜到别部/预告打乱顺序）；
     * 全部不对齐时返回第一个有标题的结果（由调用方检测数量并提示）。
     * @return 集标题列表；失败返回 null，不影响播放
     */
    fun fetchEpisodeTitles(name: String, expectedCount: Int): List<String>? {
        val kw = name.trim()
        if (kw.isEmpty() || expectedCount <= 0) return null
        return try {
            val results = searchAnime(kw)
            if (results.isEmpty()) {
                Logs.warn("[云播] 补标题：B 站搜索无结果「$kw」")
                return null
            }
            var fallback: List<String>? = null
            for (r in results.take(3)) {
                val info = fetchAnimeInfo(r.id) ?: continue
                if (info.episodes.isEmpty()) continue
                val titles = info.episodes.map { it.title.trim() }.filter { it.isNotEmpty() }
                if (titles.isEmpty()) continue
                if (info.episodes.size == expectedCount) {
                    Logs.info("[云播] 补标题：「${r.title}」${titles.size} 集对齐 ✓")
                    return titles
                }
                if (fallback == null) fallback = titles
            }
            Logs.warn("[云播] 补标题：无对齐结果（期望 $expectedCount），fallback=${fallback?.size}")
            fallback
        } catch (e: Exception) {
            Logs.warn("[云播] 补集标题失败: ${e.message}")
            null
        }
    }

    /** B 站官方搜索 API（WBI 签名）：search_type=3 番剧 / 1 视频 */
    private fun biliSearchApi(kw: String, searchType: Int): List<AnimeSearchResult> {
        val list = mutableListOf<AnimeSearchResult>()
        try {
            val params = mapOf(
                "keyword" to kw,
                "search_type" to searchType.toString(),
                "page" to "1",
                "page_size" to "30",
                "order" to "0",
                "limit" to "50"
            )
            val qs = signWbi(params)
            val url = "https://api.bilibili.com/x/web-interface/wbi/search/type?$qs"
            // 移动端 UA（B 站搜索对 PC UA 风控更严）
            val body = biliGetMobile(url, "https://search.bilibili.com/") ?: return emptyList()
            val root = org.json.JSONObject(body)
            if (root.optInt("code") != 0) {
                Logs.warn("[番剧] 搜索 API code=${root.optInt("code")} msg=${root.optString("message")} type=$searchType")
                return emptyList()
            }
            val data = root.optJSONObject("data") ?: return emptyList()
            val resultArr = data.optJSONArray("result")
            if (resultArr == null) {
                Logs.warn("[番剧] 搜索 API 无 result（type=$searchType）")
                return emptyList()
            }
            for (i in 0 until resultArr.length()) {
                val o = resultArr.getJSONObject(i)
                val rawTitle = o.optString("title", "")
                val title = rawTitle.replace(Regex("<[^>]+>"), "").trim()
                if (title.isEmpty()) continue
                // 番剧(API 已按相关度排序) 不过滤；视频搜索保留关键词包含过滤
                if (searchType == 1 && !title.contains(kw, ignoreCase = true)) continue
                val cover = o.optString("pic", o.optString("cover", ""))
                val epCount = o.optInt("duration", o.optInt("episode_count", 0))
                when (searchType) {
                    3 -> {
                        // 番剧结果字段：season_id / ep_id / stat / bvid 等
                        // 优先 ss，其次 ep，再用 bvid 兜底
                        val ss = o.optString("season_id", o.optString("ss", ""))
                        val ep = o.optString("ep_id", o.optString("ep", ""))
                        val bvid = o.optString("bvid", "")
                        val id = when {
                            ss.isNotEmpty() -> "ss$ss"
                            ep.isNotEmpty() -> "ep$ep"
                            bvid.isNotEmpty() -> bvid
                            else -> ""
                        }
                        if (id.isNotEmpty()) {
                            Logs.info("[番剧] 番剧搜索结果[$i]：$title → $id")
                            list.add(AnimeSearchResult("bili:$id", title, cover, epCount))
                        }
                    }
                    else -> {
                        // 视频：BV 号
                        val bvid = o.optString("bvid", o.optString("aid", ""))
                        if (bvid.isNotEmpty()) list.add(AnimeSearchResult("bili:$bvid", title, cover, epCount))
                    }
                }
            }
            if (list.isEmpty()) Logs.warn("[番剧] 搜索 type=$searchType 共 ${resultArr.length()} 条原始结果，过滤后 0 条")
        } catch (e: Exception) {
            Logs.err("[番剧] WBI 搜索 API 异常 type=$searchType：${e.message}")
        }
        return list
    }

    /** 移动端 UA 的 biliGet（B 站搜索 API 对 PC UA 风控更严，移动端 UA 更易通过） */
    private fun biliGetMobile(url: String, referer: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA_MOBILE)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Origin", referer)
            .apply { if (cookieHeader().isNotBlank()) header("Cookie", cookieHeader()) }
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logs.err("[番剧] HTTP ${resp.code}（mobile UA）：$url")
                return null
            }
            resp.body?.string()
        }
    }

    /**
     * HTML 搜索页解析（WBI API 被风控时的兜底，也是"纯文字关键词"的主路径）。
     * B 站搜索页实测结构（2026-10）：
     * - 番剧卡片：`<a title="标题" class="text_ellipsis" href="https://www.bilibili.com/bangumi/play/ssXXX">`
     *   卡片头有 title + ss 号 + 分类/年份 + 话数；卡片底部 media-footer 里挂着该季所有 ep 直链
     *   （ep 号比 ss 号更稳——season 接口用 ep_id 取，实测 ep_id 总能取到 25 集完整数据）
     * - 视频卡片：`<a ... href="https://www.bilibili.com/video/BV...">`（无 bvid 字段，靠链接）
     * 策略：先抓 ss 卡片（带 title），把该卡片 footer 里的第一个 ep 号并进去；
     * 再抓独立 ep 卡片（无 ss 关联的）作为补充。
     */
    private fun searchAnimeHtml(name: String): List<AnimeSearchResult> {
        val q = java.net.URLEncoder.encode(name, "UTF-8")
        // 优先 pgc tab（番剧），无结果再 video tab
        val html = biliGet("https://search.bilibili.com/all?search_type=pgc&keyword=$q&order=0&limit=30", REF_SEARCH)
            ?: biliGet("https://search.bilibili.com/all?search_type=video&keyword=$q&order=0&limit=30", REF_SEARCH)
            ?: return emptyList()
        Logs.info("[番剧] HTML 搜索「$name」，长度=${html.length}")
        val list = mutableListOf<AnimeSearchResult>()
        val usedIds = linkedSetOf<String>()
        try {
            // ① ss 卡片：`<a title="X" ... href="...bangumi/play/ssNNN">`
            val ssCard = Regex(
                """<a\s+title="([^"]{2,80})"[^>]*href="[^"]*bangumi/play/(ss|ep)(\d+)"[^>]*>""",
                RegexOption.DOT_MATCHES_ALL
            ).findAll(html).take(10)
            for (m in ssCard) {
                val title = m.groupValues[1]
                val num = m.groupValues[3]
                val prefix = m.groupValues[2]
                val key = "${prefix}$num"
                if (!usedIds.add(key)) continue
                // 卡片底部 media-footer 里该季第一个 ep（更稳的 season 入口）
                val pos = m.range.first
                val after = html.substring(pos, minOf(pos + 8000, html.length))
                val firstEp = Regex("""bangumi/play/ep(\d+)""").find(after)?.groupValues?.get(1)
                // 优先用 ep（season 接口用 ep_id 取 25 集完整数据实测更稳）；无 ep 才用 ss
                val entryId = if (firstEp != null) "ep$firstEp" else "${prefix}$num"
                if (usedIds.add(entryId)) {
                    list.add(AnimeSearchResult("bili:$entryId", title, "", 0))
                }
            }
            // ② 独立 ep 卡片补充（不在任何 ss 卡片里的 ep）
            if (list.size < 5) {
                val epCards = Regex("""bangumi/play/ep(\d+)""").findAll(html).take(30)
                val epKeys = linkedSetOf<String>()
                for (m in epCards) {
                    val epNum = m.groupValues[1]
                    val key = "ep$epNum"
                    if (!epKeys.add(key)) continue
                    if (usedIds.contains(key)) continue
                    usedIds.add(key)
                    val pos = m.range.first
                    val before = html.substring(maxOf(0, pos - 800), pos)
                    val title = Regex("""title="([^"]{2,80})"""")
                        .findAll(before).lastOrNull()?.groupValues?.get(1) ?: ""
                    list.add(AnimeSearchResult("bili:ep$epNum", title, "", 0))
                    if (list.size >= 10) break
                }
            }
            // ③ 视频卡片兜底（BV 号）
            if (list.size < 5) {
                Regex("""bilibili\.com/video/(BV[0-9A-Za-z]{10})""").findAll(html).take(10).forEach { m ->
                    val bvid = m.groupValues[1]
                    if (!usedIds.add("bvid:$bvid")) return@forEach
                    val pos = m.range.first
                    val before = html.substring(maxOf(0, pos - 800), pos)
                    val title = Regex("""title="([^"]{2,80})"""")
                        .findAll(before).lastOrNull()?.groupValues?.get(1) ?: ""
                    list.add(AnimeSearchResult("bili:$bvid", title, "", 0))
                }
            }
        } catch (e: Exception) {
            Logs.err("[番剧] HTML 搜索解析异常: ${e.message}")
        }
        Logs.info("[番剧] HTML 搜索「$name」最终 ${list.size} 条：${list.take(3).joinToString(",") { it.id }}")
        return list
    }

    /** 获取番剧详情（ep / ss / BV / av 路由到对应信息接口） */
    fun fetchAnimeInfo(id: String): AnimeInfo? {
        // id 可能是 "bili:ss12345" / "bili:BVxxxx" / "bili:ep123" 或 B 站真实 URL
        val clean = id.removePrefix("bili:").let {
            try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
        }
        // ① 直接路由（裸 ss/ep/BV/av 字符串或 B 站 URL）
        val routed = routeLink(clean)
        if (routed != null) {
            val kind = routed.first
            val num = routed.second
            return when (kind) {
                // 保留前缀传下去，避免 fetchBangumi 把 ep 号误当 season_id
                "ep" -> fetchBangumi("ep$num")
                "ss" -> fetchBangumi("ss$num")
                "BV" -> fetchVideo(num)
                "av" -> fetchAv(num)
                else -> null
            }
        }
        // ② 纯文字关键词（如 "夏日重现"）→ 先搜索拿真实 id，再取详情
        val results = searchAnime(clean)
        if (results.isEmpty()) {
            Logs.warn("[番剧] fetchAnimeInfo：关键词「$clean」搜不到结果")
            return null
        }
        // 逐个试，第一个能取到详情的就返回
        for (r in results) {
            val info = fetchAnimeInfo(r.id)
            if (info != null && info.episodes.isNotEmpty()) {
                Logs.info("[番剧] fetchAnimeInfo：「$clean」→ ${r.title}（${info.episodes.size}集）")
                return info
            }
        }
        return null
    }
    private fun fetchBangumi(biliId: String): AnimeInfo? {
        // biliId 可能是 "ss12345"、"ep12345"、"ss12345"（带前缀）或纯数字
        // 实测（2026-10）：B 站 season 接口只认 ep_id / season_id，ss_id 已废弃（-404 啥都木有）。
        // ss 编号无法直接喂 season 接口，需先查该 ss 下第一集的 ep_id。
        val ssOrEp = Regex("^(?:ss|ep)(\\d+)$").find(biliId)
        val num = ssOrEp?.groupValues?.get(1) ?: Regex("(\\d+)").find(biliId)?.groupValues?.get(1) ?: return null
        val isEp = ssOrEp?.value?.startsWith("ep") == true || biliId.startsWith("ep")
        // ① ep 直接查
        if (isEp) return fetchSeasonByEpId(num)
        // ② ss 编号：先尝试当 season_id 查（多数 ss 号 == season_id）
        val bySeason = fetchSeasonBySeasonId(num)
        if (bySeason != null) return bySeason
        // ③ season_id 也查不到 → 该 ss 可能是"旧番剧号"（≠season_id），拉 season 页 HTML 取第一个 ep_id 再查
        Logs.warn("[番剧] ss$num 查 season_id 失败，改抓 HTML 取 ep 号")
        val htmlEp = firstEpFromSeasonPage(num) ?: return null
        return fetchSeasonByEpId(htmlEp)
    }

    /** ep_id 查 season（最稳，实测 25 集全） */
    private fun fetchSeasonByEpId(epNum: String): AnimeInfo? {
        val url = "https://api.bilibili.com/pgc/view/web/season?ep_id=$epNum"
        val body = biliGet(url, "https://www.bilibili.com/bangumi/play/ep$epNum") ?: return null
        val root = org.json.JSONObject(body)
        if (root.optInt("code") != 0) {
            Logs.warn("[番剧] season(ep_id=$epNum) code=${root.optInt("code")} msg=${root.optString("message")}")
            return null
        }
        return parseSeasonJson(root)
    }

    /** season_id 查 season（ss 号通常 == season_id） */
    private fun fetchSeasonBySeasonId(seasonNum: String): AnimeInfo? {
        val url = "https://api.bilibili.com/pgc/view/web/season?season_id=$seasonNum"
        val body = biliGet(url, "https://www.bilibili.com/bangumi/play/ss$seasonNum") ?: return null
        val root = org.json.JSONObject(body)
        if (root.optInt("code") != 0) return null
        val info = parseSeasonJson(root)
        if (info != null && info.episodes.isNotEmpty()) return info
        return null
    }

    /** 抓 season 播放页 HTML，取该季第一个 ep_id（ss 号≠season_id 的兜底） */
    private fun firstEpFromSeasonPage(ssNum: String): String? {
        val html = biliGet("https://www.bilibili.com/bangumi/play/ss$ssNum", REF_SEARCH) ?: return null
        val ep = Regex("""bangumi/play/ep(\d+)""").find(html)?.groupValues?.get(1)
        if (ep != null) Logs.info("[番剧] ss$ssNum HTML 取到第一集 ep=$ep")
        return ep
    }

    /** 解析 season 接口返回的 JSON，提取 title + episodes（正片 + 额外合集） */
    private fun parseSeasonJson(root: org.json.JSONObject): AnimeInfo? {
        // B 站 season 接口：result 字段（不是 data！）
        val resultObj = root.optJSONObject("result") ?: root.optJSONObject("data") ?: return null
        val title = resultObj.optString("title", "")
        val cover = resultObj.optString("cover", "")
        val status = resultObj.optString("status", "")
        val desc = resultObj.optString("desc", "")
        // 会员番剧检测
        val isVip = resultObj.optInt("is_vip", 0) == 1 ||
            resultObj.optJSONObject("rights")?.optInt("is_vip", 0) == 1
        val episodes = mutableListOf<AnimeEpisode>()
        // 正片：result.episodes
        val topEps = resultObj.optJSONArray("episodes")
        if (topEps != null) collectEps(topEps, null, episodes)
        // 分P / 花絮等额外合集：result.section / result.sections
        val section = resultObj.optJSONArray("section")
        if (section != null) {
            for (i in 0 until section.length()) {
                val s = section.getJSONObject(i)
                val st = s.optString("title", "")
                collectEps(s.optJSONArray("episodes"), st, episodes)
            }
        }
        val sections = resultObj.optJSONArray("sections")
        if (sections != null) {
            for (i in 0 until sections.length()) {
                val s = sections.getJSONObject(i)
                val st = s.optString("title", "")
                collectEps(s.optJSONArray("episodes"), st, episodes)
            }
        }
        if (episodes.isEmpty()) {
            Logs.warn("[番剧] 「$title」详情返回 0 集（result.episodes/section/sections 全空）")
        }
        episodes.sortBy { it.number }
        val statusTag = if (isVip) (if (status.isBlank()) "会员番剧" else "$status · 会员番剧") else status
        return AnimeInfo(title, cover, statusTag, desc, episodes)
    }

    /** 收集一个 episodes 数组里的集数（ep_id / bvid / cid） */
    /**
     * v1.7.24 懒加载：不在详情阶段预取播放直链（25 集 × 2 次 HTTP 太慢，搜索体验卡死）。
     * 改为把 bvid/cid 暂存进 AnimeEpisode.url（格式 "bvid:xxx|cid:yyy"），
     * 用户选定集数时 PluginManager 调 [resolvePlayUrl] 才真正取 DASH。
     * 兼容旧格式（已是完整 URL 的直接返回）。
     */
    private fun collectEps(arr: org.json.JSONArray?, sectionTitle: String?, out: MutableList<AnimeEpisode>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
                    // 集号 = 数组下标（episodes 数组有序，1-based）；B 站 season 接口无 num 字段，ep_id 是大数不能当集号
            val seq = i + 1
            val epId = e.optLong("ep_id", e.optLong("id", 0))
            // 集标题：优先 long_title（真实集名"再见夏日"），无则 title，再无则"第N集"
            val epName = e.optString("long_title", "").ifBlank { e.optString("title", "") }
            val epTitle = if (sectionTitle.isNullOrBlank()) {
                if (epName.isBlank()) "第${seq}集" else "第${seq}集 $epName"
            } else {
                if (epName.isBlank()) "$sectionTitle 第${seq}集" else "$sectionTitle 第${seq}集 $epName"
            }
            val epBvid = e.optString("bvid", "")
            // pageUrl 用 ep_id（B 站番剧播放页真实入口），无 ep_id 才用序号占位
            val pageUrl = if (epId > 0) "https://www.bilibili.com/bangumi/play/ep$epId" else ""
            // 懒加载：暂存 bvid:cid 给 resolvePlayUrl；无 bvid 时存 ep 号
            val cid = e.optLong("cid", 0).toString()
            val lazy = when {
                epBvid.isNotEmpty() && cid != "0" -> "bvid:$epBvid|cid:$cid"
                epBvid.isNotEmpty() -> "ep:$epId"
                else -> "ep:$epId"
            }
            out.add(AnimeEpisode(seq, epTitle, lazy, pageUrl))
        }
    }

    /**
     * 解析懒加载的播放直链（collectEps 暂存的 bvid:cid / ep 号）。
     * 兼容旧格式：若 url 已是 http 直链则直接返回。
     */
    fun resolvePlayUrl(lazyRef: String): String? {
        if (lazyRef.isBlank()) return null
        return when {
            lazyRef.startsWith("http://") || lazyRef.startsWith("https://") -> lazyRef
            lazyRef.startsWith("bvid:") -> {
                val bvid = lazyRef.removePrefix("bvid:")
                val cid = lazyRef.substringAfter("cid:", "")
                if (bvid.isNotBlank() && cid.isNotBlank()) biliPlayUrlBangumi(bvid, cid) else biliPlayUrlBangumiByEp(bvid)
            }
            lazyRef.startsWith("ep:") -> biliPlayUrlBangumiByEp(lazyRef.removePrefix("ep:"))
            else -> null
        }
    }

    /**
     * 番剧播放直链（DASH 分片，参考 bililidownloadtool _get_play_info）。
     * 核心原则：API 已按用户权限返回可用流，本地只做"按资格过滤"——
     * 1080P/4K 码（112/116/120）需大会员；720P(64)/480P 等登录即可；
     * 会员番剧未登录时用 try_look=1 可拿试看流。取过滤后最高清晰度的视频+音频。
     *
     * @param bvid 集数的 bvid（来自 season 接口 episodes[].bvid）
     * @param cid  集数的 cid（来自 season 接口 episodes[].cid）
     */
    private fun biliPlayUrlBangumi(bvid: String, cid: String): String? {
        // 让 API 尽量给最高流：fnval=4048（4K 请求）+ fourk=1 + drm_tech_type=2
        // 参考 bililidownloadtool get_bangumi_episode_playinfo（cid + bvid，登录态直接可用）
        // 实测：登录态返回 19 档视频（含 4K qn=125 HDR）+ 3 档音频（最高 192k）
        val url = "https://api.bilibili.com/pgc/player/web/playurl?cid=$cid&bvid=$bvid&fnval=4048&fourk=1&drm_tech_type=2&otype=json"
        val ref = "https://www.bilibili.com/bangumi/play/$bvid"
        val body = biliGet(url, ref) ?: return null
        val picked = pickDashUrl(body, ref)
        if (picked != null) return picked
        // 未登录 / 登录但无权限时，回退 try_look=1 拿试看流
        val fallbackUrl = "https://api.bilibili.com/pgc/player/web/playurl?cid=$cid&bvid=$bvid&fnval=16&fourk=1&drm_tech_type=2&try_look=1&otype=json"
        val fbBody = biliGet(fallbackUrl, ref) ?: return null
        return pickDashUrl(fbBody, ref)
    }

    /** 按 ep_id 取番剧播放直链（无 bvid/cid 时的兜底） */
    private fun biliPlayUrlBangumiByEp(epId: String): String? {
        val url = "https://api.bilibili.com/pgc/player/web/playurl?ep_id=$epId&fnval=4048&fourk=1&drm_tech_type=2&otype=json&try_look=1"
        val ref = "https://www.bilibili.com/bangumi/play/ep$epId"
        val body = biliGet(url, ref) ?: return null
        return pickDashUrl(body, ref)
    }

    /** 普通视频播放直链（同番剧逻辑，参考 bililidownloadtool play_url_api） */
    private fun biliPlayUrlNormal(bvid: String, cid: String): String? {
        val url = "https://api.bilibili.com/x/player/playurl?cid=$cid&bvid=$bvid&fnval=4048&fourk=1&drm_tech_type=2&otype=json&try_look=1"
        val ref = "https://www.bilibili.com/video/$bvid"
        val body = biliGet(url, ref) ?: return null
        return pickDashUrl(body, ref)
    }

    /**
     * 解析 DASH 响应：按 [isQualityAvailable] 过滤可用视频码，取最高；拼视频+音频。
     * 参考源码 VIP_ONLY_QN=[74,100,112,116,125,126,127]，登录即可（try_look=1）。
     * 注意：B 站 playurl 接口响应数据在 result 字段（非 data！）—— 实测确认。
     */
    private fun pickDashUrl(body: String, ref: String): String? {
        return try {
            val root = org.json.JSONObject(body)
            if (root.optInt("code") != 0) {
                Logs.warn("[番剧] playurl code=${root.optInt("code")} msg=${root.optString("message")}（$ref）")
                return null
            }
            // 关键：B 站 playurl 的数据在 result（data 字段为空）
            val data = root.optJSONObject("result") ?: root.optJSONObject("data") ?: return null
            // DASH 优先；无 DASH 时回退 durl（MP4 单片，会员番剧 2 集起常给 durl 试看流）
            val dash = data.optJSONObject("dash")
            val durl = data.optJSONArray("durl")
            if (dash != null) {
                val v = dash.optJSONArray("video") ?: return null
                val vip = BiliAuth.vip()
                // 按源码 VIP_ONLY_QN 过滤；id 是清晰度码。同一清晰度可能有多档（HEVC/AVC），取带宽最大的
                var bestV: org.json.JSONObject? = null
                var bestId = 0
                var bestBw = -1L
                for (i in 0 until v.length()) {
                    val item = v.getJSONObject(i)
                    val qnId = item.optInt("id")
                    val bw = item.optLong("bandwidth", 0)
                    if (isQualityAvailable(qnId, vip) && (qnId > bestId || (qnId == bestId && bw > bestBw))) {
                        bestV = item; bestId = qnId; bestBw = bw
                    }
                }
                val vid = bestV?.optString("baseUrl") ?: bestV?.optString("base_url")
                if (vid.isNullOrBlank()) {
                    Logs.warn("[番剧] 无可用 DASH 视频流（$ref，vip=$vip）")
                    return null
                }
                val a = dash.optJSONArray("audio")
                val aud = if (a != null && a.length() > 0) a.optJSONObject(0) else null
                val aid = aud?.optString("baseUrl") ?: aud?.optString("base_url")
                val tag = when {
                    bestId >= 120 -> "（4K）"
                    bestId >= 112 -> "（1080P）"
                    bestId == 80 -> "（1080P）"
                    bestId == 64 -> "（720P）"
                    bestId == 32 -> "（480P）"
                    bestId == 16 -> "（360P）"
                    else -> ""
                }
                if (!aid.isNullOrBlank()) "$vid\n音频：$aid$tag" else "$vid$tag"
            } else if (durl != null && durl.length() > 0) {
                // durl 单片：MP4 试看流（会员番剧 2 集起、未登录 4K/1080P 等场景）
                // 取第一个 durl；无独立音频（durl 已含音频轨道）。
                // 实测主源偶发 404，优先用 backup_url[0]（若存在）
                val item = durl.optJSONObject(0)
                val mainUrl = item?.optString("url", item?.optString("base_url", ""))
                val backups = item?.optJSONArray("backup_url")
                val b0 = if (backups != null && backups.length() > 0) backups.optString(0) else ""
                val url = if (b0.isNotBlank()) b0 else mainUrl
                if (url.isNullOrBlank() || url.isEmpty()) {
                    Logs.warn("[番剧] durl 解析失败（$ref）")
                    return null
                }
                val isPreview = data.optInt("is_preview", 0) == 1
                val tag = when {
                    isPreview -> "（试看流，完整需登录/大会员）"
                    else -> "（MP4 单片）"
                }
                url + tag
            } else {
                Logs.warn("[番剧] playurl 无 dash 也无 durl（$ref）")
                null
            }
        } catch (e: Exception) {
            Logs.err("[番剧] DASH 解析失败: ${e.message}")
            null
        }
    }

    /** 清晰度码资格（源码 is_quality_available）：VIP_ONLY 需大会员，其余 try_look=1 均可 */
    private fun isQualityAvailable(qnId: Int, vip: Boolean): Boolean {
        val vipOnly = intArrayOf(74, 100, 112, 116, 125, 126, 127)
        if (vipOnly.contains(qnId)) return vip
        return true
    }

    private fun fetchVideo(bvid: String): AnimeInfo? {
        val body = biliGet("https://api.bilibili.com/x/web-interface/view?bvid=$bvid") ?: return null
        val root = org.json.JSONObject(body)
        if (root.optInt("code") != 0) return null
        val d = root.optJSONObject("data") ?: return null
        val title = d.optString("title")
        val cover = d.optString("pic")
        val desc = d.optString("desc")
        val cid = d.optLong("cid").toString()
        // 多 P 视频：遍历 pages 生成多集（v1.7.24 懒加载：暂存 bvid:cid，选集时才取直链）
        val pages = d.optJSONArray("pages") ?: org.json.JSONArray()
        val episodes = mutableListOf<AnimeEpisode>()
        for (i in 0 until pages.length()) {
            val p = pages.getJSONObject(i)
            val pageNum = p.optInt("page", i + 1)
            val pageTitle = p.optString("part", "第$pageNum 集")
            val pageCid = p.optLong("cid", d.optLong("cid")).toString()
            val lazyRef = if (pageCid != "0") "bvid:$bvid|cid:$pageCid" else "bvid:$bvid|cid:$cid"
            episodes.add(AnimeEpisode(pageNum, pageTitle, lazyRef, "https://www.bilibili.com/video/$bvid?p=$pageNum"))
        }
        if (episodes.isEmpty()) {
            val lazyRef = if (cid != "0") "bvid:$bvid|cid:$cid" else "bvid:$bvid|cid:$cid"
            episodes.add(AnimeEpisode(1, "第1集", lazyRef, "https://www.bilibili.com/video/$bvid"))
        }
        return AnimeInfo(title, cover, "", desc, episodes)
    }

    private fun fetchAv(avId: String): AnimeInfo? {
        val body = biliGet("https://api.bilibili.com/x/web-interface/view?aid=$avId") ?: return null
        val root = org.json.JSONObject(body)
        if (root.optInt("code") != 0) return null
        val bvid = root.optJSONObject("data")?.optString("bvid", "")
        if (bvid.isNullOrBlank()) return null
        return fetchVideo(bvid)
    }
}


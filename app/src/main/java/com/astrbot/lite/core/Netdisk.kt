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
        "uc" to "uc.cn",
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
        val s3KeyFlag: String = "",
    )

    class Session(
        val url: String,
        val password: String?,
        val platform: String,
        var shareId: String,
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
        val time: Long,
    )

    /** 某会话的全网搜索状态：关键词 + 结果 + 当前页 + 最近展示时间（数字归属仲裁用） */
    private class GSearch(
        var keyword: String = "",
        var results: List<GResult> = emptyList(),
        var page: Int = 0,
        var shownAt: Long = System.currentTimeMillis(),
    )

    private val gSearches = ConcurrentHashMap<String, GSearch>()

    /** 内置搜索服务地址：PanSou 协议（POST JSON {"kw":...}）。插件配置里可加自定义地址优先使用 */
    private val PANSOU_APIS = listOf(
        "https://so.252035.xyz/api/search",
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
                sessions[sessionKey] == null &&
                gSearches[sessionKey] != null ->
                pickGlobalResult(
                    sessionKey,
                    when {
                        t.startsWith("打开") -> t.removePrefix("打开")
                        t.startsWith("选择") -> t.removePrefix("选择")
                        else -> t.removePrefix("直链")
                    }.trim(),
                )
            (t.startsWith("列表") || t.startsWith("刷新")) &&
                sessions[sessionKey] == null &&
                gSearches[sessionKey] != null ->
                formatGlobalPage(gSearches[sessionKey]!!)
            (
                (t == "%" || t == "％" || t == "%25" || (t.length <= 3 && t.contains("%"))) ||
                    t.startsWith("回退") ||
                    t.startsWith("返回")
                ) &&
                sessions[sessionKey] == null &&
                gSearches[sessionKey] != null ->
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
        return t == "%" ||
            t == "％" ||
            t == "%25" ||
            (t.length <= 3 && t.contains("%")) ||
            t.startsWith("回退") ||
            t.startsWith("返回") ||
            t.startsWith("搜索") ||
            t.startsWith("搜") ||
            t.startsWith("添加") ||
            t == "找" ||
            t.startsWith("找 ") ||
            t.startsWith("找:") ||
            t.startsWith("找：") ||
            t == "上一页" ||
            t == "上页" ||
            t == "前一页"
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

    private fun listLimit(config: List<PluginConfigField>): Int = config.firstOrNull { it.key == "listLimit" }?.value?.toIntOrNull()?.coerceIn(1, 200) ?: 100

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
        url.contains("123pan") ||
            url.contains("123684") ||
            url.contains("123865") ||
            url.contains("123912") ||
            url.contains("123952") -> "123"
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
                        if (hint.isNullOrBlank()) {
                            "获取直链失败：${platformName(s.platform)}登录可能已失效，请到插件页网盘助手点「登录」重新登录后再试"
                        } else {
                            "获取直链失败：$hint"
                        }
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
        sb.append("🔍 搜索「${s.searchKeyword}」结果（共 ${hits.size} 项，第 ${page + 1}/$totalPages 页）：\n")
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
                    return if (pr.otherCount > 0) {
                        "🔍 找到 ${pr.otherCount} 条其它资源（磁力/百度/阿里等），但本插件仅支持 123/夸克/UC 打开——换个关键词试试"
                    } else {
                        "没搜到「$keyword」的网盘资源，换个关键词试试"
                    }
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
        return "❌ 全网搜索失败（公共搜索服务不稳定，已自动重试 3 次）${if (lastErr.isNullOrBlank()) "" else "：$lastErr"}，稍后再试"
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
            JSONObject().put("kw", keyword),
        )
        var last400: Exception? = null
        for ((idx, body) in variants.withIndex()) {
            val (code, text) = post(api, body.toString(), "", UA)
            if (code == 400 || code == 422) {
                last400 = Exception("HTTP $code（参数形态 ${idx + 1}/${variants.size} 被拒，已降级）")
                Logs.warn("全网搜索 @$api 参数降级：$body → HTTP $code，换下一形态")
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
                            time = parseTime(it.optString("datetime", "")),
                        ),
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
                otherCount,
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
        sb.append("🔍 全网搜索「${st.keyword}」（共 ${all.size} 条 · 第 ${page + 1}/$totalPages 页）：\n")
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
        bearer: String = "",
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
                    s3KeyFlag = it.optString("S3KeyFlag", ""),
                ),
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
                cookie,
                bearer = bearer,
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

    private fun qkConf(platform: String): QkConf = if (platform == "uc") {
        QkConf("https://pc-api.uc.cn/1/clouddrive", "https://drive.uc.cn", "UCBrowser", UC_DL_UA)
    } else {
        QkConf("https://drive-pc.drive.quark.cn/1/clouddrive", "https://pan.quark.cn", "ucpro", QUARK_DL_UA)
    }

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
                    token = tk,
                ),
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
                val authErr = msg.contains("HTTP 400") ||
                    msg.contains("HTTP 401") ||
                    msg.contains("HTTP 403") ||
                    msg.contains("HTTP 412")
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
                    ?.let {
                        try {
                            URLDecoder.decode(it, "UTF-8")
                        } catch (_: Exception) {
                            null
                        }
                    }
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
                "https://pan.quark.cn/",
            )
            "uc" -> verifyClouddriveMember(
                "https://pc-api.uc.cn/1/clouddrive/member?pr=UCBrowser&fr=pc&uc_param_str=" +
                    "&__dt=${(Math.random() * 9000 + 400).toInt()}&__t=${System.currentTimeMillis()}",
                cookie,
                "https://drive.uc.cn/",
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
        "123" to listOf("auth-token", "authortoken"),
    )

    private fun looksLoggedIn(platform: String, cookie: String): Boolean {
        if (cookie.isBlank()) return false
        val keys = LOGIN_KEYS[platform] ?: return cookie.length > 40
        val lower = cookie.lowercase()
        return keys.any { lower.contains("$it=") }
    }
}

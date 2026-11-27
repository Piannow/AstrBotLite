package com.astrbot.lite.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** AstrBot 官方插件市场条目（Python 插件，安装时自动转换） */
data class AstrBotMarketEntry(
    val name: String,
    val displayName: String,
    val author: String,
    val version: String,
    val desc: String,
    val repo: String,
    val downloadUrl: String,
) {
    /** 展示名：优先用 AstrBot 里的插件名（display_name），没有才退回包体名 */
    val shownName: String get() = displayName.ifBlank { name }
    val zipUrl: String?
        get() = if (downloadUrl.isNotBlank()) downloadUrl else PluginConverter.githubRepoZip(repo)
}

/**
 * 插件转换：把电脑上 AstrBot 的 Python 插件转换为 AstrBot Lite 可用的 plugin.json。
 * 已集成进安装流程：AstrBot 市场选插件 / 导入 Python 插件 / 粘贴源码 → 自动转换后安装。
 */
object PluginConverter {

    const val DEFAULT_MARKET_URL = "https://api.soulter.top/astrbot/plugins"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 转换结果；assets 为随插件一起持久化的二进制资产（相对路径 → 字节，render 型底图/字体用） */
    class ConvResult(val json: String?, val log: String, val assets: Map<String, ByteArray> = emptyMap())

    /** 市场缓存目录（PluginManager.init 注入）；无目录时退化为纯在线拉取 */
    @Volatile
    private var cacheDir: java.io.File? = null

    @Volatile
    private var marketRefreshing = false

    /** 市场专用短超时 client：市场 JSON 大（2000+ 条），慢地址不再等满 60s（修“加载太久/装不了”） */
    private val marketHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun initCache(dir: java.io.File) {
        cacheDir = dir
    }

    /** 短超时单地址拉取；失败返回 null（不抛） */
    private fun downloadMarket(url: String): String? = try {
        val resp = marketHttp.newCall(
            Request.Builder().url(url).header("User-Agent", "AstrBotLite/1.0").build(),
        ).execute()
        if (!resp.isSuccessful) {
            Logs.warn("市场 HTTP ${resp.code}: ${url.take(80)}")
            null
        } else {
            resp.body?.string()
        }
    } catch (e: Exception) {
        Logs.warn("市场拉取失败 ${url.take(80)}: ${e.message}")
        null
    }

    /**
     * 尝试多个市场地址，返回能解析出插件列表的文本；全部失败返回 null（1.7.83）：
     * - 缓存优先（秒开）：磁盘缓存有内容立即返回；超过 5 分钟则后台静默刷新，下次即新
     * - 无缓存才同步下载：每地址 8s 连接/25s 读取短超时，慢地址快速失败换下一个
     */
    fun tryDownloadMarket(userUrl: String): String? {
        val urls = mutableListOf<String>()
        userUrl.trim().takeIf { it.isNotBlank() }?.let { urls.add(it) }
        // 永久修复：api.soulter.top 在国内网络下无法访问，jsDelivr CDN 优先
        urls.add("https://cdn.jsdelivr.net/gh/AstrBotDevs/AstrBot_Plugins_Collection@main/plugin_cache_original.json")
        urls.add(DEFAULT_MARKET_URL)
        urls.add("https://api.soulter.top/plugins")
        urls.add("https://raw.githubusercontent.com/AstrBotDevs/AstrBot_Plugins_Collection/main/plugin_cache_original.json")
        val distinctUrls = urls.distinct()
        val cache = cacheDir?.let { java.io.File(it, "astrbot_market_cache.json") }
        // ① 缓存优先：有内容即秒开；超过 5 分钟视为陈旧 → 后台刷新（不阻塞本次）
        if (cache != null && cache.isFile) {
            val text = try {
                cache.readText()
            } catch (_: Exception) {
                ""
            }
            if (text.isNotBlank() && parseAstrBotMarket(text).isNotEmpty()) {
                val stale = System.currentTimeMillis() - cache.lastModified() > 5 * 60_000L
                if (stale && !marketRefreshing) {
                    marketRefreshing = true
                    Thread {
                        try {
                            for (u in distinctUrls) {
                                val t = downloadMarket(u) ?: continue
                                if (parseAstrBotMarket(t).isNotEmpty()) {
                                    cache.writeText(t)
                                    Logs.info("AstrBot 市场缓存已后台刷新：${u.take(80)}")
                                    break
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            marketRefreshing = false
                        }
                    }.apply {
                        isDaemon = true
                        name = "market-refresh"
                        start()
                    }
                }
                Logs.info("AstrBot 市场（本地缓存${if (stale) "·后台刷新中" else "·新鲜"}）")
                return text
            }
        }
        // ② 无缓存 → 同步拉取（短超时 failover）
        for (u in distinctUrls) {
            val text = downloadMarket(u) ?: continue
            if (parseAstrBotMarket(text).isNotEmpty()) {
                try {
                    cache?.writeText(text)
                } catch (_: Exception) {
                }
                Logs.info("AstrBot 市场加载成功：${u.take(80)}")
                return text
            }
        }
        return null
    }

    /**
     * 单源下载（1.7.207 补齐）：带取消检查的下载入口。
     * 取消只影响结果（返回 null），不做断点续传——与调用方（插件包安装）语义一致。
     */
    fun downloadSingle(
        url: String,
        shouldCancel: () -> Boolean = { false },
        onProgress: ((Long, Long) -> Unit)? = null,
    ): ByteArray? {
        if (shouldCancel()) return null
        val bytes = download(url, onProgress) ?: return null
        return if (shouldCancel()) null else bytes
    }

    /** 下载字节；onProgress(已收, 总长[-1 未知]) 流式回调（安装进度用） */
    fun download(url: String, onProgress: ((Long, Long) -> Unit)? = null): ByteArray? {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "AstrBotLite/1.0 (Android)").build()
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            if (onProgress == null) return body.bytes()
            val total = body.contentLength()
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            body.byteStream().use { input ->
                var n: Int
                var done = 0L
                while (input.read(buf).also { n = it } > 0) {
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    fun downloadText(url: String): String? {
        val b = download(url) ?: return null
        return String(b, Charsets.UTF_8)
    }

    fun githubRepoZip(repoUrl: String): String? {
        var u = repoUrl.trim().removeSuffix("/").removeSuffix(".git")
        if (u.startsWith("github.com")) u = "https://$u"
        val m = Regex("""github\.com/([^/]+)/([^/]+)""").find(u) ?: return null
        val owner = m.groupValues[1]
        val repo = m.groupValues[2]
        return "https://github.com/$owner/$repo/archive/refs/heads/master.zip"
    }

    // ---------------- GitHub 镜像（类 AstrBot：检测镜像延迟、选镜像安装） ----------------
    data class GhMirror(val name: String, val prefix: String)

    /** 镜像站清单：prefix 为空 = 官方直连；其余为「前缀 + GitHub 直链」式加速代理 */
    val GH_MIRRORS = listOf(
        GhMirror("直连", ""),
        GhMirror("ghproxy.net", "https://ghproxy.net/"),
        GhMirror("gh-proxy.com", "https://gh-proxy.com/"),
        GhMirror("ghfast.top", "https://ghfast.top/"),
        GhMirror("ghproxy.cn", "https://ghproxy.cn/"),
        GhMirror("github.moeyy.xyz", "https://github.moeyy.xyz/"),
    )

    /** 给 GitHub 直链套镜像前缀（"直连"/未知名 = 原样返回） */
    fun viaMirror(url: String, mirrorName: String): String {
        val prefix = GH_MIRRORS.firstOrNull { it.name == mirrorName }?.prefix ?: ""
        return if (prefix.isEmpty()) url else prefix + url
    }

    /** 探测单个镜像延迟（取 robots.txt 首字节耗时）；连不上返回 null */
    fun probeLatency(prefix: String): Long? {
        val target = prefix + "https://github.com/robots.txt"
        return try {
            val t0 = System.currentTimeMillis()
            val resp = http.newCall(
                Request.Builder().url(target).header("User-Agent", "Mozilla/5.0").build(),
            ).execute()
            resp.close()
            System.currentTimeMillis() - t0
        } catch (_: Exception) {
            null
        }
    }

    /** 并发探测全部镜像（每个最多 4 秒），返回 镜像名 → 毫秒（null=不可用） */
    fun probeAll(): Map<String, Long?> {
        val out = java.util.concurrent.ConcurrentHashMap<String, Long?>()
        val threads = GH_MIRRORS.map { m ->
            Thread {
                val ms = probeLatency(m.prefix)
                out[m.name] = ms
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }
        return GH_MIRRORS.associate { it.name to out[it.name] }
    }

    /**
     * GitHub 安装候选（镜像名 to zip 直链）——**纯手动镜像模式**：
     * 只使用用户在「检测镜像延迟→选择」里选定的镜像（未选过 = 直连），
     * 不做自动探测排序、不自动切换镜像；失败就报错，由用户换镜像重试。
     * 解析支持：github.com/owner/repo（可带多余路径）、裸 owner/repo、任意 .zip 直链；
     * 仓库形式会同时给 master/main 两个分支（不同仓库默认分支不同）。
     */
    fun ghCandidates(input: String): List<Pair<String, String>> {
        val u = input.trim()
        if (u.isEmpty()) return emptyList()
        val urls = when {
            // GitHub 归档 master.zip：补一个 main 分支候选（不同仓库默认分支不同）
            u.startsWith("http") && Regex("""/master\.zip$""").containsMatchIn(u) ->
                listOf(u, u.replace("/master.zip", "/main.zip"))
            // 已是 zip 直链（release 资产 / 归档直链）：原样套镜像，不重拼分支
            u.startsWith("http") && u.endsWith(".zip", ignoreCase = true) -> listOf(u)
            else -> {
                val repoMatch = Regex("""github\.com/([^/\s"]+)/([^/\s."']+)""").find(u)
                    ?: Regex("""^([\w.-]+)/([\w.-]+)$""").find(u)
                if (repoMatch != null) {
                    val owner = repoMatch.groupValues[1]
                    val repo = repoMatch.groupValues[2].removeSuffix(".git")
                    listOf(
                        "https://github.com/$owner/$repo/archive/refs/heads/master.zip",
                        "https://github.com/$owner/$repo/archive/refs/heads/main.zip",
                    )
                } else if (u.startsWith("http")) {
                    listOf(u)
                } else {
                    return emptyList()
                }
            }
        }
        // 手动：只用所选镜像（空 = 直连）
        val chosen = GH_MIRRORS.firstOrNull { it.name == Config.ghMirror }?.name ?: "直连"
        return urls.map { z -> chosen to viaMirror(z, chosen) }
    }

    /** 从 GitHub 下载（按镜像候选逐个尝试，校验 zip 的 PK 头）；全部失败返回 null */
    fun downloadGithub(
        input: String,
        onProgress: ((Long, Long) -> Unit)? = null,
        shouldCancel: () -> Boolean = { false },
        onSourceChange: ((String) -> Unit)? = null,
    ): ByteArray? {
        val cands = ghCandidates(input)
        if (cands.isEmpty()) {
            Logs.warn("[GitHub] 无法解析链接: $input")
            return null
        }
        for ((name, url) in cands) {
            if (shouldCancel()) return null
            onSourceChange?.invoke("尝试镜像 $name")
            Logs.info("[GitHub] 尝试镜像 $name → ${url.take(90)}")
            val b = download(url, onProgress) ?: continue
            if (b.size > 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()) {
                Logs.info("[GitHub] 下载成功（镜像 $name，${b.size} 字节）")
                return b
            }
        }
        Logs.warn("[GitHub] 全部镜像下载失败（${cands.size} 个候选）")
        return null
    }

    /** 市场 zip 下载：GitHub 链接走镜像候选兜底，其它链接直连；onProgress 透传给下载循环 */
    fun downloadWithMirror(
        url: String,
        onProgress: ((Long, Long) -> Unit)? = null,
        sourceLabel: String = "",
        shouldCancel: () -> Boolean = { false },
        onSourceChange: ((String) -> Unit)? = null,
    ): ByteArray? {
        val tag = if (sourceLabel.isBlank()) "" else "[$sourceLabel] "
        if (url.contains("github.com")) {
            for ((name, u) in ghCandidates(url)) {
                if (shouldCancel()) return null
                onSourceChange?.invoke("${tag}尝试镜像 $name")
                val b = download(u, onProgress) ?: continue
                if (b.size > 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()) return b
            }
            return null
        }
        return download(url, onProgress)
    }

    // ---------------- AstrBot 市场解析 ----------------
    fun parseAstrBotMarket(text: String): List<AstrBotMarketEntry> {
        val list = mutableListOf<AstrBotMarketEntry>()
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return parseArray(text)
        }
        val it = root.keys()
        while (it.hasNext()) {
            val key = it.next()
            if (key == "\$meta") continue
            val o = root.optJSONObject(key) ?: continue
            list.add(
                AstrBotMarketEntry(
                    name = o.optString("name").ifBlank { key.substringAfterLast('/') },
                    displayName = o.optString("display_name"),
                    author = o.optString("author", ""),
                    version = o.optString("version", ""),
                    desc = o.optString("desc").ifBlank { o.optString("short_desc") },
                    repo = o.optString("repo", ""),
                    downloadUrl = o.optString("download_url", ""),
                ),
            )
        }
        if (list.isNotEmpty()) return list
        return parseArray(text)
    }

    private fun parseArray(text: String): List<AstrBotMarketEntry> {
        val list = mutableListOf<AstrBotMarketEntry>()
        val arr = try {
            JSONArray(text)
        } catch (_: Exception) {
            return list
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                AstrBotMarketEntry(
                    name = o.optString("name"),
                    displayName = o.optString("display_name"),
                    author = o.optString("author", ""),
                    version = o.optString("version", ""),
                    desc = o.optString("desc").ifBlank { o.optString("description") },
                    repo = o.optString("repo", ""),
                    downloadUrl = o.optString("zip_path").ifBlank { o.optString("download_url") },
                ),
            )
        }
        return list
    }

    // ---------------- 转换 ----------------
    /** 从 zip 转换（优先直接识别 plugin.json，否则解析 main.py）。
     *  @param lenient 宽松模式（AstrBot 市场链路用）：真 Python 环境能跑多文件包，
     *  不做"单文件限制/render/text2image 启发式"拦截，只提取配置（_conf_schema）、
     *  文档（README）与命令供 plugin.json 使用——修复"配置没迁移、文档变通用文案"。 */
    fun convertZip(bytes: ByteArray, lenient: Boolean = false): ConvResult {
        val files = unzipText(
            bytes,
            listOf("main.py", "plugin.py", "metadata.yaml", "metadata.yml", "_conf_schema.json", "plugin.json", "readme.md", "readme.txt", "constants.py"),
        )
        val readme = (files["readme.md"] ?: files["readme.txt"])?.trim()
        val confSchema = files["_conf_schema.json"]?.trim()
        val pj = files["plugin.json"]
        if (!pj.isNullOrBlank()) {
            val log = if (readme.isNullOrBlank()) {
                "✅ 已识别现成 Lite 插件（plugin.json），无需转换"
            } else {
                "✅ 已识别现成 Lite 插件（plugin.json），并附带 README 文档"
            }
            return ConvResult(if (readme.isNullOrBlank()) pj else withDoc(pj, cleanDoc(readme)), log)
        }
        val main = files["main.py"] ?: files["plugin.py"] ?: ""
        val meta = files["metadata.yaml"] ?: files["metadata.yml"] ?: ""
        if (main.isBlank()) return ConvResult(null, "⚠ 压缩包中没有找到 main.py 或 plugin.json，无法转换")
        val constantsPy = files["constants.py"] ?: ""
        if (lenient) {
            // AstrBot 市场链路（真 Python 环境跑原包）：跳过单文件限制与类型启发式，
            // 直接走"配置 + 文档 + 命令"提取（type 由调用方标 python）
            return convertSource(main, meta, readme, confSchema, emptyList(), constantsPy, lenient = true)
        }
        // 发图/拉图：从整个压缩包里自动抽取全部图片直链（不限数量），交给 convertSource 配成 image 型插件
        val imageUrls = collectImageUrls(bytes)
        // 本地渲染（举牌/文字图）：主文件引用底图+文本区并输出图片，命令多为 f-string 命名。
        // 直接转成 render 型并抽取包内二进制资产（底图/字体），跳过对多文件包的拒绝。
        detectRender(main, constantsPy)?.let { spec ->
            val assets = extractAssetBytes(bytes)
            val json = buildRenderJson(spec, parseMeta(meta, main, mutableListOf()), readme)
            return ConvResult(json, "✅ 识别为本地渲染插件（举牌/文字图），已提取 ${assets.size} 项资产", assets)
        }
        // 联网文生图：主文件调外部 AI 出图 API（OpenAI 兼容 /images/generations 等）→ text2image 型，
        // 生成后由 ImageGenEngine 拿描述出图并作为图片消息发出（需在配置中填 API 地址/Key/模型）。
        if (detectText2Image(main, constantsPy)) {
            val json = buildText2ImageJson(parseMeta(meta, main, mutableListOf()), readme)
            return ConvResult(json, "✅ 识别为联网文生图插件，已生成 text2image 型插件（请在“配置”中填 API 地址/Key/模型，用「画图 <描述>」触发）")
        }
        // 自带图片：包内带多张本地图片、随机发送一张 → localimage 型，抽取并持久化图片资产（无需联网/配置）。
        val localAssets = extractAssetBytes(bytes)
        if (detectLocalImage(localAssets, main, constantsPy)) {
            val json = buildLocalImageJson(parseMeta(meta, main, mutableListOf()), readme)
            return ConvResult(json, "✅ 识别为自带图片插件，已提取 ${localAssets.size} 项资产并生成 localimage 型插件（触发即随机发一张本地图片）", localAssets)
        }
        return convertSource(main, meta, readme, confSchema, imageUrls, constantsPy)
    }

    /** 给现成 plugin.json 附加 doc 字段（README） */
    private fun withDoc(json: String, doc: String): String = try {
        val o = JSONObject(json)
        o.put("doc", doc)
        o.toString(2)
    } catch (_: Exception) {
        json
    }

    /** 把 README（markdown/HTML 混合）清洗成给用户看的纯文本：去 HTML/图片/代码块/表格符号，保留正文 */
    private fun cleanDoc(md: String): String {
        var s = md
        // HTML 标签（徽章 <img>、<p> 等）→ 空白
        s = s.replace(Regex("<[^>]*>"), " ")
        // markdown 图片 ![](...) 删除；链接 [text](url) 只留 text
        s = s.replace(Regex("""!\[[^\]]*\]\([^)]*\)"""), "")
        s = s.replace(Regex("""\[[^\]]*\]\([^)]*\)"""), "")
        // 围栏代码块 ```...``` 整段删除（文件树/依赖等 dev 向内容，不属于给用户看的正文）
        s = s.replace(Regex("```[\\s\\S]*?```"), "")
        // 残留反引号、加粗/斜体标记
        s = s.replace("`", "").replace("**", "").replace("__", "")
        // 逐行：表格、标题、水平线
        val lines = s.lines().map { raw ->
            val t = raw.trim()
            val cells = raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            when {
                // 表格分隔行 |---|---| → 删除
                raw.contains("|") &&
                    cells.isNotEmpty() &&
                    cells.all { it.matches(Regex("^[\\-:\\s]*$")) && it.contains("-") } -> ""
                // 表格数据行 | a | b | → a：b
                raw.contains("|") -> cells.joinToString("：")
                // 标题 # / ## / ### → 去掉井号
                t.startsWith("#") -> t.replace(Regex("^#{1,6}\\s*"), "")
                // 水平线 --- / *** / ___ → 删除
                Regex("^(-{3,}|\\*{3,}|_{3,})$").containsMatchIn(t) -> ""
                else -> t
            }
        }
        var out = lines.joinToString("\n")
        out = out.replace(Regex("[ \\t]+\\n"), "\n") // 去掉行尾空白
        out = out.replace(Regex("\\n{3,}"), "\n\n") // 压缩多余空行
        return out.trim()
    }

    /** 解析 main.py（+ 可选 metadata.yaml + _conf_schema.json + README + 自动抽取的图片直链 + constants.py）为 Lite 插件 JSON。
     *  @param lenient 宽松模式（AstrBot 市场链路）：跳过单文件限制与 render/text2image/image 启发式，
     *  保证 _conf_schema 配置与 README 文档迁移到 plugin.json（type=python 由调用方标注）。 */
    fun convertSource(
        mainPy: String,
        metaYaml: String?,
        readme: String? = null,
        confSchema: String? = null,
        imageUrls: List<String> = emptyList(),
        constantsPy: String = "",
        assets: Map<String, ByteArray> = emptyMap(),
        lenient: Boolean = false,
    ): ConvResult {
        val log = mutableListOf<String>()
        if (!lenient) {
            // 本地渲染（举牌/文字图）：优先识别。识别成功则转成 render 型并带上资产（跳过多文件包拒绝）
            detectRender(mainPy, constantsPy)?.let { spec ->
                val json = buildRenderJson(spec, parseMeta(metaYaml, mainPy, log), readme)
                log.add("✅ 识别为本地渲染插件（举牌/文字图），已生成 render 型插件")
                return ConvResult(json, log.joinToString("\n"), assets)
            }
            // 联网文生图：源码调外部 AI 出图 API → text2image 型（源码导入无二进制资产，仅做识别）
            if (detectText2Image(mainPy, constantsPy)) {
                val json = buildText2ImageJson(parseMeta(metaYaml, mainPy, log), readme)
                log.add("✅ 识别为联网文生图插件，已生成 text2image 型插件（请在配置中填 API 地址/Key/模型）")
                return ConvResult(json, log.joinToString("\n"), assets)
            }
            // 先判断是否属于 Lite 根本无法还原的插件（多文件相对导入包），是则拒绝转换
            detectUnconvertible(mainPy)?.let { reason ->
                Logs.err("插件无法转换：$reason")
                return ConvResult(null, "⚠ 该插件无法转换：$reason\n（Lite 版只支持单文件 Python 插件，不支持含兄弟模块的多文件包）")
            }
        }
        val meta = parseMeta(metaYaml, mainPy, log)
        // _conf_schema.json 是 AstrBot 插件配置项的权威来源，优先于 metadata.yaml 里的 config: 段
        if (!confSchema.isNullOrBlank()) {
            meta.configFields.clear()
            parseConfSchema(confSchema, meta, log)
        }
        val commands = mutableListOf<Triple<String, String, String>>() // match, prompt, reply
        val seen = mutableSetOf<String>()

        val simple = Regex("""self\.register\(\s*(['"])([^'"]+)\1\s*,\s*(['"])([^'"]*)\3\s*,\s*([A-Za-z_][A-Za-z0-9_]*)""")
        for (m in simple.findAll(mainPy)) {
            val cmd = m.groupValues[2]
            val desc = m.groupValues[4]
            val handler = m.groupValues[5]
            if (!seen.add(cmd)) continue
            commands.add(buildCommand(mainPy, cmd, desc, handler, meta.shownName, log))
        }
        val tuple = Regex("""\(\s*(['"])([^'"]+)\1\s*,\s*(['"])([^'"]*)\3\s*,\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)""")
        for (ri in Regex("""register\s*\(""").findAll(mainPy)) {
            val seg = mainPy.substring(ri.range.first, (ri.range.first + 600).coerceAtMost(mainPy.length))
            for (m in tuple.findAll(seg)) {
                val cmd = m.groupValues[2]
                val desc = m.groupValues[4]
                val handler = m.groupValues[5]
                if (!seen.add(cmd)) continue
                commands.add(buildCommand(mainPy, cmd, desc, handler, meta.shownName, log))
            }
        }

        // @filter.command("X", alias={...}) / @command_group("组") + @组.command("子") 装饰器风格
        // 上游 4.x 命令注册支持关键字参数（alias/priority）与命令组，旧正则要求紧跟 ")" 会漏掉
        commands.addAll(extractDecoratedCommands(mainPy, seen, meta.shownName, log))

        // @filter.regex("正则")：正则触发型插件（上游 filter.regex），转为 Lite 的 re: 命令
        val regexDeco = Regex("""@([A-Za-z_][\w.]*\.)?regex\s*\(""")
        for (rm in regexDeco.findAll(mainPy)) {
            val parsed = balancedArgs(mainPy, rm.range.last) ?: continue
            val pattern = firstStringLiteral(parsed.first) ?: continue
            val pat = unescape(pattern)
            val key = "re:" + pat
            if (!seen.add(key)) continue
            val defM = Regex("""(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
                .find(mainPy, parsed.second) ?: continue
            val handler = defM.groupValues[1]
            commands.add(buildCommand(mainPy, key, docstring(mainPy, handler), handler, meta.shownName, log))
        }

        // @filter.llm_tool(name="x")：LLM 工具（上游 4.x），转为 LLM 技能命令
        val toolDeco = Regex("""@([A-Za-z_][\w.]*\.)?llm_tool\s*\(""")
        for (tm in toolDeco.findAll(mainPy)) {
            val parsed = balancedArgs(mainPy, tm.range.last) ?: continue
            val argsRaw = parsed.first
            val nameInArgs = Regex("""\bname\s*=\s*(['"])((?:\\.|(?!\1).)*)\1""")
                .find(argsRaw)?.groupValues?.get(2)
            val defM = Regex("""(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
                .find(mainPy, parsed.second) ?: continue
            val handler = defM.groupValues[1]
            val toolName = (nameInArgs ?: handler).trim()
            if (toolName.isBlank() || !seen.add(toolName)) continue
            commands.add(Triple("/$toolName", aiPrompt("/$toolName", docstring(mainPy, handler), meta.shownName), ""))
        }

        if (commands.isEmpty()) {
            log.add("⚠ 未识别到命令注册，已生成一个示例命令，安装后可编辑")
            commands.add(Triple("/" + slug(meta.shownName).ifBlank { "cmd" }, "", ""))
        }

        // Python 原生插件（市场链路）必须保持 passive，才能进入 Python 垫片分发；
        // always/llm 只对 Lite 自己的 prompt 插件有意义，套到 python 插件上会导致插件永远不执行
        val trigger = if (lenient) "passive" else detectTrigger(mainPy, log)
        // 识别 re.sub 类正则清理规则（hook 插件常用：去空行/去括号/去标签等）
        val cleanRule = detectCleanRegex(mainPy)
        val cleanRegex = cleanRule?.first
        val cleanReplace = cleanRule?.second ?: ""
        if (cleanRegex != null) {
            log.add("✅ 检测到正则清理规则：$cleanRegex（对每条回复生效，${if (cleanReplace.isEmpty()) "删除匹配" else "替换为 \u201c$cleanReplace\u201d"}）")
            if (commands.isNotEmpty()) {
                commands[0] = Triple(
                    commands[0].first,
                    "回复请保持纯净：不要在括号或其他描述性标签内输出心声、动作或神态描写，直接输出正文。",
                    commands[0].third,
                )
            }
        }
        val name = meta.shownName.ifBlank { "转换的插件" }
        val id = slug(name).ifBlank { "converted_plugin" }
        // 发图/拉图：判定是否为“图片类”插件（抽到直链 或 源码命中图片关键词）。
        // 命中则预填 imageUrl 配置项（把全部抽取到的直链一行一个塞进去，不限数量），并把插件输出为 image 型，
        // 这样安装后命令可正常调用：Lite 会随机下载其中一张直链并以图片消息发出。
        val looksImage = !lenient && (imageUrls.isNotEmpty() || IMAGE_PLUGIN_KEYWORDS.containsMatchIn(mainPy))
        val configFields = meta.configFields.toMutableList()
        if (looksImage && configFields.none { it.key == "imageUrl" || it.key == "images" }) {
            configFields.add(
                PluginConfigField(
                    key = "imageUrl",
                    label = "图片直链（一行一个或用逗号分隔；机器人随机下载并作为图片消息发送，不限数量）",
                    value = imageUrls.joinToString("\n"),
                    secret = false,
                    multiLine = true,
                ),
            )
        }
        val pluginType = if (lenient) {
            "python"
        } else if (looksImage) {
            "image"
        } else {
            "prompt"
        }
        if (looksImage) {
            log.add(if (imageUrls.isNotEmpty()) "✅ 已自动抽取 ${imageUrls.size} 条图片直链，配成「发图」插件（image 型，不限数量）" else "✅ 识别为发图插件，已预留「图片直链」配置项，请在配置中填入直链")
        }
        val json = buildJson(id, name, meta.version.ifBlank { "1.0.0" }, meta.author, meta.desc, trigger, commands, cleanRegex, configFields, cleanReplace, readme?.let { cleanDoc(it) } ?: "", pluginType)
        log.add("✅ 转换完成：$name v${meta.version}（触发方式：${triggerName(trigger)}，类型：${if (pluginType == "image") "发图" else "AI 指令"}）")
        return ConvResult(json, log.joinToString("\n"))
    }

    /** 识别 re.sub('正则', '替换', ...) 的清理规则：返回 (正则, 替换串)。替换为空串=删除匹配；'\n'=压缩空行。 */
    private fun detectCleanRegex(mainPy: String): Pair<String, String>? {
        // 匹配 re.sub( r?'PATTERN', r?'REPLACEMENT', ... )——REPLACEMENT 可为空串（删除）或 '\n'（压缩空行）等
        val m = Regex("""re\.sub\(\s*(['"])((?:\\.|(?!\1).)*)\1\s*,\s*(['"])((?:\\.|(?!\3).)*)\3""").find(mainPy) ?: return null
        // regex 保留原文（多为 r'' 原始串，\n 等转义由 Kotlin 正则解析）；替换串按 Python 字符串转义还原（'\n'→真实换行）
        return m.groupValues[2] to unescape(m.groupValues[4])
    }

    /** 从 openIdx（'(' 的位置）起取出配对的参数串（跳过字符串内的括号）。返回 (参数串, 右括号下标) */
    private fun balancedArgs(src: String, openIdx: Int): Pair<String, Int>? {
        if (openIdx >= src.length || src[openIdx] != '(') return null
        var depth = 0
        var i = openIdx
        var quote: Char? = null
        var esc = false
        while (i < src.length) {
            val ch = src[i]
            if (quote != null) {
                if (esc) {
                    esc = false
                } else if (ch == '\\') {
                    esc = true
                } else if (ch == quote) {
                    quote = null
                }
            } else {
                when (ch) {
                    '\'', '"' -> quote = ch
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return src.substring(openIdx + 1, i) to i
                    }
                }
            }
            i++
        }
        return null
    }

    private fun firstStringLiteral(args: String): String? = Regex("""^\s*f?(['"])((?:\\.|(?!\1).)*)\1""").find(args)?.groupValues?.get(2)

    /** 命令装饰器的别名：位置参数（第二个起）与 alias={...}/aliases=[...] 关键字参数 */
    private fun decoratorAliases(args: String): List<String> {
        val out = mutableListOf<String>()
        var first = true
        for (m in Regex("""f?(['"])((?:\\.|(?!\1).)*)\1""").findAll(args)) {
            val seg = args.substring(0, m.range.first).substringAfterLast(',').substringAfterLast('(').trim()
            if (seg.endsWith("=")) continue // 关键字参数值，稍后按关键字单独处理
            if (first) {
                first = false
                continue
            } // 第一个是命令名
            out.add(unescape(m.groupValues[2]))
        }
        val kw = Regex("""\baliases?\s*=\s*([{\[])([\s\S]*?)\1""").find(args)
        if (kw != null) {
            Regex("""f?(['"])((?:\\.|(?!\1).)*)\1""").findAll(kw.groupValues[2]).forEach {
                out.add(unescape(it.groupValues[2]))
            }
        }
        return out.filter { it.isNotBlank() }.distinct()
    }

    /**
     * 解析上游 4.x 的装饰器命令注册：
     * - @filter.command("x", alias={"y"}, priority=1)
     * - @filter.command_group("math") def math() ... @math.command("add") → "math add"
     * 旧实现用单条正则要求 ")" 紧跟字符串，带 alias/priority 的命令会被整条漏掉。
     */
    private fun extractDecoratedCommands(
        mainPy: String,
        seen: MutableSet<String>,
        pluginName: String,
        log: MutableList<String>,
    ): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        // ① 命令组：装饰后的函数名就是后续 @<name>.command 的前缀
        val groupVars = mutableMapOf<String, String>()
        for (gm in Regex("""@([A-Za-z_][\w.]*\.)?command_group\s*\(""").findAll(mainPy)) {
            val parsed = balancedArgs(mainPy, gm.range.last) ?: continue
            val gname = firstStringLiteral(parsed.first)?.let { unescape(it) }?.trim() ?: continue
            val defM = Regex("""(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
                .find(mainPy, parsed.second) ?: continue
            groupVars[defM.groupValues[1]] = gname
        }
        // ② 普通命令 / 组内子命令
        for (cm in Regex("""@([A-Za-z_][\w.]*\.)?command\s*\(""").findAll(mainPy)) {
            val parsed = balancedArgs(mainPy, cm.range.last) ?: continue
            val argsRaw = parsed.first
            val rawName = firstStringLiteral(argsRaw) ?: continue
            val name = unescape(rawName).trim()
            if (name.isEmpty()) continue
            val defM = Regex("""(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
                .find(mainPy, parsed.second) ?: continue
            val handler = defM.groupValues[1]
            val group = groupVars[cm.groupValues[1].removeSuffix(".")]
            val full = if (group != null) "$group $name" else name
            val desc = docstring(mainPy, handler)
            if (seen.add(full)) out.add(buildCommand(mainPy, full, desc, handler, pluginName, log))
            for (a in decoratorAliases(argsRaw)) {
                val aliasFull = if (group != null) "$group $a" else a
                if (seen.add(aliasFull)) out.add(buildCommand(mainPy, aliasFull, desc, handler, pluginName, log))
            }
        }
        return out
    }

    private fun buildCommand(mainPy: String, cmd: String, desc: String, handler: String, pluginName: String, log: MutableList<String>): Triple<String, String, String> {
        val block = methodBlock(mainPy, handler)
        val cmdMatch = if (cmd.startsWith("/") || cmd.startsWith("re:")) cmd else "/$cmd"
        if (block.isBlank()) {
            log.add("命令 $cmdMatch：未找到处理器函数体，已转为 AI 指令模式")
            return Triple(cmdMatch, aiPrompt(cmdMatch, desc, pluginName), "")
        }
        val fRe = Regex("""plain_result\(\s*f(['"])((?:\\.|(?!\1).)*)\1\s*\)""")
        fRe.find(block)?.let { m ->
            log.add("命令 $cmdMatch：检测到动态回复（含变量），已提取文本模板，请安装后检查")
            return Triple(cmdMatch, "", unescape(m.groupValues[2]))
        }
        val constRe = Regex("""plain_result\(\s*(['"])((?:\\.|(?!\1).)*)\1\s*\)""")
        constRe.find(block)?.let { m ->
            log.add("命令 $cmdMatch：✅ 已提取固定回复")
            return Triple(cmdMatch, "", unescape(m.groupValues[2]))
        }
        if (Regex("""message_result\s*\(""").containsMatchIn(block)) {
            log.add("命令 $cmdMatch：原插件返回富媒体，已转为 AI 指令模式")
            return Triple(cmdMatch, aiPrompt(cmdMatch, desc, pluginName), "")
        }
        log.add("命令 $cmdMatch：未识别到固定回复，已转为 AI 指令模式")
        return Triple(cmdMatch, aiPrompt(cmdMatch, desc, pluginName), "")
    }

    private fun aiPrompt(cmd: String, desc: String, pluginName: String): String = "当用户输入 $cmd 时：${desc.ifBlank { "执行插件 ${pluginName.ifBlank { "未知" }} 的功能" }}。请根据命令意图给出有帮助的回答。"

    private fun methodBlock(src: String, handler: String): String {
        val re = Regex("""(?:def|async def)\s+${Regex.escape(handler)}\s*\([^)]*\)\s*:""")
        val m = re.find(src) ?: return ""
        val before = src.substring(0, m.range.last + 1)
        val firstLineIdx = before.count { it == '\n' }
        val lines = src.lines()
        if (firstLineIdx + 1 >= lines.size) return ""
        val bodyIndent = lines[firstLineIdx + 1].takeWhile { it == ' ' || it == '\t' }.length
        val sb = StringBuilder()
        for (i in firstLineIdx + 1 until lines.size) {
            val l = lines[i]
            if (l.isBlank()) {
                sb.append(l).append('\n')
                continue
            }
            if (l.takeWhile { it == ' ' || it == '\t' }.length <= bodyIndent) break
            sb.append(l).append('\n')
        }
        return sb.toString()
    }

    /** 取方法 def 行紧随其后的单行三引号 docstring（"""...""" 或 '''...'''），用作命令描述 */
    private fun docstring(src: String, handler: String): String {
        val pat = "(?:def|async def)\\s+" + Regex.escape(handler) +
            "\\s*\\([^)]*\\)\\s*:\\s*\\n\\s*(?:\"\"\"([^\\n\"]*)\"\"\"|'''([^\\n']*)''')"
        val m = Regex(pat).find(src) ?: return ""
        return m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
    }

    private fun parseMeta(yaml: String?, mainPy: String, log: MutableList<String>): Meta {
        val m = Meta()
        yaml?.let { y ->
            // 解析 config 段：AstrBot 插件的可配置项（key/name/default/description/type）
            var inConfig = false
            var cur = mutableMapOf<String, String>()
            for (line in y.lines()) {
                val t = line.trim()
                if (t == "config:" || t.startsWith("config:")) {
                    inConfig = true
                    continue
                }
                if (!inConfig) continue
                if (t.startsWith("-")) {
                    if (cur.isNotEmpty()) {
                        m.configFields.add(toField(cur))
                        cur = mutableMapOf()
                    }
                    continue
                }
                val idx = t.indexOf(":")
                if (idx <= 0) continue
                val key = t.substring(0, idx).trim()
                val value = t.substring(idx + 1).trim().trim('"', '\'')
                if (key in CONFIG_KEYS) cur[key] = value
            }
            if (cur.isNotEmpty()) m.configFields.add(toField(cur))
            // 其余元信息
            for (line in y.lines()) {
                val t = line.trim()
                if (t.startsWith("#") || !t.contains(":")) continue
                val idx = t.indexOf(":")
                val key = t.substring(0, idx).trim()
                val value = t.substring(idx + 1).trim().trim('"', '\'')
                when (key) {
                    "name" -> m.name = value
                    "display_name" -> m.displayName = value
                    "desc" -> m.desc = value
                    "short_desc" -> if (m.desc.isBlank()) m.desc = value
                    "description" -> if (m.desc.isBlank()) m.desc = value
                    "author" -> m.author = value
                    "version" -> m.version = value
                }
            }
            if (m.configFields.isNotEmpty()) {
                log.add("✅ 已转换 ${m.configFields.size} 个配置项（安装后可在“配置”中修改）")
            }
        }
        val regRe = Regex("""@register\(\s*(['"])([^'"]+)\1\s*,\s*(['"])([^'"]*)\3\s*,\s*(['"])([^'"]*)\5\s*,\s*(['"])([^'"]*)\7""")
        regRe.find(mainPy)?.let {
            if (m.name.isBlank()) m.name = it.groupValues[2]
            if (m.author.isBlank()) m.author = it.groupValues[4]
            if (m.desc.isBlank()) m.desc = it.groupValues[6]
            if (m.version.isBlank()) m.version = it.groupValues[8]
        }
        log.add("插件信息：${m.shownName.ifBlank { "未知" }} v${m.version.ifBlank { "?" }} by ${m.author.ifBlank { "?" }}")
        return m
    }

    /**
     * 判断 main.py 是否属于 Lite 根本无法还原的插件，命中则返回原因（用于拒绝转换），否则返回 null。
     *
     * 只做「多文件包」这一项硬性拦截：main.py 使用相对导入（from .x import / import .x）说明它依赖
     * 同包的兄弟模块（models、drawer 等），而 Lite 只能提取单个 main.py，兄弟模块拿不到，转出来必坏。
     * 这种插件连 AstrBot 本身都不一定能装（如「魔裁 Memes」），因此直接跳过，不必转。
     *
     * 注意：发送/管理表情包（如「奶龙」，单文件、image_result 发已存的图）不算无法转换——
     * Lite 现在支持发图/拉图（自动抽取图片直链并配成 image 型插件），可正常发出图片，故不在此拦截。
     */
    private fun detectUnconvertible(mainPy: String): String? {
        // 相对导入（from .x import / import .x）→ 多文件包，Kotlin 无法还原兄弟模块
        val relImport = Regex("""\bfrom\s+\.{1,2}\s|\bimport\s+\.""")
        if (relImport.containsMatchIn(mainPy)) {
            return "使用了多文件相对导入（from .x import），Lite 无法运行多文件 Python 包"
        }
        return null
    }

    // ---------------- 本地渲染（举牌/文字图）识别 ----------------
    /** 本地渲染插件特征：底图相对路径 + 4 个整数的文本区域 + 有图片输出 + 至少一个命令 */
    private data class RenderSpec(
        val commandNames: List<Pair<String, List<String>>>, // 命令名 → 别名
        val templateRel: String, // 底图相对路径（如 assets/character/base.png）
        val fontRel: String?, // 字体相对路径（可空）
        val region: String, // 文本区域 "x,y,w,h"
        val textColor: String, // "r,g,b,a"
        val bracketColor: String, // "r,g,b,a"
        val maxFontHeight: String, // 像素
    )

    /** 判定是否为本地渲染插件：主文件输出图片 + constants 里能解析出底图与文本区。命中返回 RenderSpec，否则 null */
    private fun detectRender(mainPy: String, constantsPy: String): RenderSpec? {
        val hasImageResult = Regex("""image_result|event\.image|send_image""", RegexOption.IGNORE_CASE).containsMatchIn(mainPy)
        if (!hasImageResult) return null
        val consts = constStrings(constantsPy)
        val templateRel = quotedPath(constLine(constantsPy, "BASE_IMAGE"))
        if (templateRel.isBlank()) return null
        val region = tupleInts(constLine(constantsPy, "TEXT_REGION"))
        if (region.split(",").size < 4) return null
        val fontRel = quotedPath(constLine(constantsPy, "FONT_PATH")).ifBlank { null }
        val textColor = tupleInts(constLine(constantsPy, "TEXT_COLOR")).ifBlank { "45,38,35,255" }
        val bracketColor = tupleInts(constLine(constantsPy, "BRACKET_COLOR")).ifBlank { "156,88,207,255" }
        val maxFontHeight = tupleInts(constLine(constantsPy, "MAX_FONT_HEIGHT")).split(",").firstOrNull()?.ifBlank { "200" } ?: "200"
        val commandNames = extractDecoCommands(mainPy, consts)
        if (commandNames.isEmpty()) return null
        return RenderSpec(commandNames, templateRel, fontRel, region, textColor, bracketColor, maxFontHeight)
    }

    /** 抽取 @...command("X", alias={...}) 装饰器命令：命令名 + 别名（都解析 f-string）。兼容 f"{VAR}说" 这种 f-string 命名 */
    private fun extractDecoCommands(mainPy: String, consts: Map<String, String>): List<Pair<String, List<String>>> {
        val out = mutableListOf<Pair<String, List<String>>>()
        val seen = mutableSetOf<String>()
        val startRe = Regex("""@([A-Za-z_][\w.]*\.)?command\(""")
        for (m in startRe.findAll(mainPy)) {
            val after = mainPy.substring(m.range.last + 1) // 跳过 command( 的左括号
            val argM = Regex("""^\s*f?(['"])(.*?)\1""").find(after) ?: continue
            val first = resolveFString(argM.groupValues[2], consts)
            if (first.isBlank()) continue
            val aliasRe = Regex("""alias\s*=\s*([{\[])(.*?)\1""", RegexOption.DOT_MATCHES_ALL)
            val aliasM = aliasRe.find(after)
            val aliases = if (aliasM != null) {
                aliasM.groupValues[2]
                    .split(",")
                    .map { tok ->
                        Regex("""f?(['"])(.*?)\1""").find(tok.trim())?.groupValues?.get(2)
                            ?.let { resolveFString(it, consts) } ?: tok.trim()
                    }
                    .filter { it.isNotBlank() }
            } else {
                emptyList()
            }
            if (seen.add(first)) out.add(first to aliases)
        }
        return out
    }

    /** 解析 f-string 里的 {NAME} 占位：用 constants 里的字符串常量回填，剩余无法解析的 {} 段直接去掉 */
    private fun resolveFString(body: String, consts: Map<String, String>): String {
        var s = body
        for ((k, v) in consts) s = s.replace("{$k}", v)
        return Regex("\\{[^}]*}").replace(s, "")
    }

    /** 收集形如 NAME = "..." / NAME = '...' 的字符串常量（大写下划线命名） */
    private fun constStrings(py: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val re = Regex("""^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(['"])(.*?)\2""", RegexOption.MULTILINE)
        for (m in re.findAll(py)) out[m.groupValues[1]] = unescape(m.groupValues[3])
        return out
    }

    /** 取常量 NAME = <表达式> 的右侧原文 */
    private fun constLine(py: String, name: String): String {
        val re = Regex("""(^|\n)\s*${Regex.escape(name)}\s*=\s*([^\n#]+)""")
        val m = re.find(py) ?: return ""
        return m.groupValues[2].trim()
    }

    /** 把表达式里的所有引号字面量用 / 连接（还原 Python 的 pathlib ` / ` 拼接为普通相对路径） */
    private fun quotedPath(rhs: String): String = Regex("""(['"])([^'"]+)\1""").findAll(rhs).map { it.groupValues[2] }.joinToString("/")

    /** 把表达式里的所有整数用 , 连接（还原 Python 的 (a, b, c, d) 元组为 CSV） */
    private fun tupleInts(rhs: String): String = Regex("""-?\d+""").findAll(rhs).map { it.value }.joinToString(",")

    /** 生成 render 型 plugin.json：命令（match，无 prompt/reply）+ 渲染配置（底图/字体/区域/颜色/最大字高） */
    private fun buildRenderJson(spec: RenderSpec, meta: Meta, readme: String?): String {
        val name = meta.shownName.ifBlank { "本地渲染插件" }
        val o = JSONObject()
        o.put("id", slug(name).ifBlank { "render_plugin" })
        o.put("name", name)
        o.put("version", meta.version.ifBlank { "1.0.0" })
        o.put("author", meta.author)
        o.put("description", meta.desc.ifBlank { "本地渲染举牌/文字图" })
        o.put("type", "render")
        o.put("trigger", "passive")
        if (!readme.isNullOrBlank()) o.put("doc", cleanDoc(readme))
        val carr = JSONArray()
        for ((nm, aliases) in spec.commandNames) {
            val co = JSONObject().put("match", "/" + nm)
            carr.put(co)
            for (a in aliases) carr.put(JSONObject().put("match", "/" + a))
        }
        o.put("commands", carr)
        val configArr = JSONArray()
        configArr.put(PluginConfigField("template", "底图（随插件持久化的相对路径，或图片直链）", spec.templateRel, false, false).toJson())
        spec.fontRel?.let { configArr.put(PluginConfigField("font", "字体文件（相对路径）", it, false, false).toJson()) }
        configArr.put(PluginConfigField("region", "文字区域 x,y,w,h（像素）", spec.region, false, false).toJson())
        configArr.put(PluginConfigField("textColor", "文字颜色 RGBA（r,g,b,a）", spec.textColor, false, false).toJson())
        configArr.put(PluginConfigField("bracketColor", "【】括号颜色 RGBA（r,g,b,a）", spec.bracketColor, false, false).toJson())
        configArr.put(PluginConfigField("maxFontHeight", "最大字高（像素）", spec.maxFontHeight, false, false).toJson())
        o.put("config", configArr)
        o.put("enabled", true)
        return o.toString(2)
    }

    /** 抽取包内二进制资产（图片/字体）：去掉公共顶层目录，按相对路径存为 字节，随插件持久化 */
    private val ASSET_EXTS = setOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".ttf", ".ttc", ".otf")

    private fun extractAssetBytes(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        val found = mutableListOf<Pair<String, ByteArray>>()
        try {
            val zis = ZipInputStream(ByteArrayInputStream(bytes))
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val nm = e.name.lowercase()
                    if (ASSET_EXTS.any { nm.endsWith(it) }) found += (e.name to zis.readBytes())
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
            zis.close()
        } catch (_: Exception) {
            return out
        }
        if (found.isEmpty()) return out
        // 去掉公共顶层目录（如 astrbot_plugin_xxx/）：所有条目首段相同则剥离一层
        val segs = found.map { it.first.split("/") }
        val top = segs.firstOrNull { it.size > 1 }?.first()
        val strip = if (top != null && segs.count { it.first() == top } == segs.size) 1 else 0
        for ((n, data) in found) {
            val rel = n.split("/").drop(strip).joinToString("/")
            if (rel.isNotEmpty()) out[rel] = data
        }
        return out
    }

    private val CONFIG_KEYS = setOf("key", "name", "default", "type", "description", "desc")

    // ---------------- 联网文生图（text2image）识别 ----------------
    /** 判定是否为联网文生图插件：源码含“出图”特征词，且有外部调用/密钥痕迹（避免误判纯图片发送插件） */
    private val T2I_GEN_SIGNAL = Regex(
        """images/generations|gpt-image|gpt_image|dall[e·]|\bdalle\b|stable.?diffusion|\btxt2img\b|text2image|text-to-image|文生图|AI绘画|aigenimage|comfyui""",
        RegexOption.IGNORE_CASE,
    )
    private val T2I_EXT_SIGNAL = Regex(
        """requests\.(post|get)|httpx\.|aiohttp\.|openai|api_?key|apikey|api-key|\bBearer\b|/v1/|/v3/|https?://""",
        RegexOption.IGNORE_CASE,
    )

    private fun detectText2Image(mainPy: String, constantsPy: String): Boolean {
        val src = mainPy + "\n" + constantsPy
        if (!T2I_GEN_SIGNAL.containsMatchIn(src)) return false
        return T2I_EXT_SIGNAL.containsMatchIn(src)
    }

    /** 生成 text2image 型 plugin.json：默认「画图」命令 + baseUrl/apiKey/model/size/responseFormat 配置 */
    private fun buildText2ImageJson(meta: Meta, readme: String?): String {
        val name = meta.shownName.ifBlank { "联网文生图插件" }
        val o = JSONObject()
        o.put("id", slug(name).ifBlank { "text2image_plugin" })
        o.put("name", name)
        o.put("version", meta.version.ifBlank { "1.0.0" })
        o.put("author", meta.author.ifBlank { "AstrBot Lite" })
        o.put("description", meta.desc.ifBlank { "联网文生图：输入文字描述调外部 AI 出图服务生成图片" })
        o.put("type", "text2image")
        o.put("trigger", "passive")
        if (!readme.isNullOrBlank()) o.put("doc", cleanDoc(readme))
        val carr = JSONArray()
        carr.put(JSONObject().put("match", "画图"))
        carr.put(JSONObject().put("match", "/画图"))
        carr.put(JSONObject().put("match", "/draw"))
        carr.put(JSONObject().put("match", "画一张"))
        o.put("commands", carr)
        val configArr = JSONArray()
        configArr.put(PluginConfigField("baseUrl", "文生图 API 地址(OpenAI 兼容，如 https://<域名>/v1)", "", false, false).toJson())
        configArr.put(PluginConfigField("apiKey", "API Key", "", true, false).toJson())
        configArr.put(PluginConfigField("model", "模型名(填你服务里的文生图模型)", "", false, false).toJson())
        configArr.put(PluginConfigField("size", "出图尺寸(如 1024x1024)", "1024x1024", false, false).toJson())
        configArr.put(PluginConfigField("responseFormat", "返回格式(留空自动；b64_json/png/jpeg)", "", false, false).toJson())
        o.put("config", configArr)
        o.put("enabled", true)
        return o.toString(2)
    }

    // ---------------- 自带图片（localimage）识别 ----------------
    private val LOCAL_IMG_EXTS = setOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp")
    private val LOCAL_SEND_SIGNAL = Regex(
        """image_result|send_image|表情包|梗图|表情|\bsticker\b|send_picture|random""",
        RegexOption.IGNORE_CASE,
    )
    private val LOCAL_RANDOM_SIGNAL = Regex(
        """random\.(choice|randint|sample)|choices?\(|random\.sample|shuffle\(""",
        RegexOption.IGNORE_CASE,
    )

    /** 判定是否为自带图片插件：包内带多张本地图片（随机发一张），或单张但源码有“发送+随机”痕迹 */
    private fun detectLocalImage(assets: Map<String, ByteArray>, mainPy: String, constantsPy: String): Boolean {
        val imgAssets = assets.filter { LOCAL_IMG_EXTS.any { ext -> it.key.lowercase().endsWith(ext) } }
        if (imgAssets.isEmpty()) return false
        if (imgAssets.size >= 2) return true
        val src = mainPy + "\n" + constantsPy
        return LOCAL_SEND_SIGNAL.containsMatchIn(src) && LOCAL_RANDOM_SIGNAL.containsMatchIn(src)
    }

    /** 生成 localimage 型 plugin.json：默认「发图/来一张」命令，随机取一张随插件持久化的本地图片发出 */
    private fun buildLocalImageJson(meta: Meta, readme: String?): String {
        val name = meta.shownName.ifBlank { "自带图片插件" }
        val o = JSONObject()
        o.put("id", slug(name).ifBlank { "localimage_plugin" })
        o.put("name", name)
        o.put("version", meta.version.ifBlank { "1.0.0" })
        o.put("author", meta.author.ifBlank { "AstrBot Lite" })
        o.put("description", meta.desc.ifBlank { "自带图片：随机发送一张随插件带来的本地图片" })
        o.put("type", "localimage")
        o.put("trigger", "passive")
        if (!readme.isNullOrBlank()) o.put("doc", cleanDoc(readme))
        val carr = JSONArray()
        carr.put(JSONObject().put("match", "发图"))
        carr.put(JSONObject().put("match", "/发图"))
        carr.put(JSONObject().put("match", "来一张"))
        carr.put(JSONObject().put("match", "/来一张"))
        o.put("commands", carr)
        o.put("enabled", true)
        return o.toString(2)
    }

    private fun toField(map: Map<String, String>): PluginConfigField {
        val key = map["key"] ?: "config_${System.currentTimeMillis()}"
        val type = map["type"]?.lowercase() ?: "text"
        return PluginConfigField(
            key = key,
            label = map["name"] ?: key,
            value = map["default"] ?: "",
            secret = type.contains("pass") || type.contains("secret") || type.contains("token") || type.contains("key"),
        )
    }

    /**
     * 解析 AstrBot 插件的 _conf_schema.json → Lite 配置项。
     * 兼容：顶层对象 { key: {description,type,hint,default} }、顶层数组 [{key/name,type,default,...}]、
     * 以及 {"config"/"properties": {...}} 包裹形式。
     */
    private fun parseConfSchema(schema: String, meta: Meta, log: MutableList<String>) {
        val trimmed = schema.trim()
        val items = mutableListOf<Pair<String, JSONObject>>()
        try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    val key = it.optString("key").ifBlank { it.optString("name") }
                    if (key.isNotEmpty()) items.add(key to it)
                }
            } else {
                val root = JSONObject(trimmed)
                val target = when {
                    root.has("config") && root.opt("config") is JSONObject -> root.optJSONObject("config")
                    root.has("properties") && root.opt("properties") is JSONObject -> root.optJSONObject("properties")
                    else -> root
                }
                target?.keys()?.forEach { k ->
                    val v = target.opt(k)
                    if (v is JSONObject) items.add(k to v)
                }
            }
        } catch (e: Exception) {
            log.add("⚠ _conf_schema.json 解析失败：${e.message}")
            return
        }
        items.forEach { (key, v) -> expandSchemaField(key, v, meta, 0) }
        if (meta.configFields.isNotEmpty()) {
            log.add("✅ 已从 _conf_schema.json 转换 ${meta.configFields.size} 个配置项（安装后可在“配置”中修改）")
        }
    }

    /**
     * 递归展开一个 schema 字段（1.7.89）：
     * - `object` + `items` → 展开为 `组.子字段` 形式的一串配置项（AstrBot 4.x 新架构：可见配置
     *   挂在 6 个 object 分组内、顶层平铺字段全是 invisible 镜像——此前 object 降级成空 JSON 框
     *   导致组内 40+ 真实字段全部丢失，即"配置迁移失败"的真身）；
     * - 其余类型按官方类型映射控件；invisible 项跳过（对齐官方管理面板）。
     */
    private fun expandSchemaField(key: String, v: JSONObject, meta: Meta, depth: Int) {
        if (v.optBoolean("invisible", false)) return
        val type = v.optString("type").lowercase()
        if (type == "object" && depth < 4) {
            val sub = v.optJSONObject("items")
            if (sub != null && sub.length() > 0) {
                var any = false
                sub.keys().forEach { sk ->
                    val sv = sub.opt(sk)
                    if (sv is JSONObject) {
                        expandSchemaField("$key.$sk", sv, meta, depth + 1)
                        any = true
                    }
                }
                if (any) return // 组已展开为子字段，不再输出组级 JSON 框
            }
        }
        val desc = v.optString("description").ifBlank { v.optString("hint") }
        val label = desc.ifBlank { v.optString("name", key.substringAfterLast('.')) }
        val defaultRaw = v.opt("default")
        // options 候选（官方：提供下拉列表可选项）
        val opts = v.optJSONArray("options")
        val options = if (opts != null) {
            (0 until opts.length()).mapNotNull { i ->
                opts.optString(i).takeIf { it.isNotBlank() }
            }
        } else {
            emptyList()
        }
        val secret = type.contains("secret") ||
            v.optBoolean("secret", false) ||
            key.contains("secret", true) ||
            key.contains("password", true) ||
            key.contains("token", true)
        // 按官方类型映射到 Lite 控件类型（1.7.87 类型化转换）
        val (ftype, defaultStr) = when {
            type == "bool" || type == "boolean" ->
                "bool" to (
                    when (defaultRaw) {
                        true -> "true"
                        false -> "false"
                        else -> "false"
                    }
                    )
            type == "int" || type == "float" || type == "double" || type == "number" ->
                "number" to (defaultRaw?.toString() ?: if (type == "int") "0" else "0.0")
            type == "string" && options.isNotEmpty() ->
                "select" to (defaultRaw?.toString() ?: options.first())
            type == "list" ->
                "list" to (if (defaultRaw is JSONArray) defaultRaw.toString() else "[]")
            // dict / template_list / file / 空 object → 结构化 JSON 降级（容量与复杂度受控）
            type == "object" || type == "dict" || type == "template_list" || type == "file" ->
                "json" to (defaultRaw?.toString() ?: "{}")
            type == "text" || v.has("editor_mode") ->
                "text" to (defaultRaw?.toString() ?: "")
            else -> "string" to (defaultRaw?.toString() ?: "")
        }
        val multiLine = ftype == "text" || (ftype == "string" && defaultStr.length > 40)
        meta.configFields.add(
            PluginConfigField(key, label, defaultStr, secret, multiLine, ftype, options),
        )
    }

    private fun detectTrigger(mainPy: String, log: MutableList<String>): String {
        val hookPatterns = arrayOf(
            "on_decorating_result", "on_llm_request", "on_llm_response",
            "on_waiting_llm_request", "on_agent_begin", "on_agent_done",
            "on_using_llm_tool", "on_llm_tool_respond", "after_message_sent",
            "on_plugin_error", "event_message_type", "platform_adapter_type",
            "on_message", "on_group_message", "on_c2c_message", "def hook",
        )
        if (hookPatterns.any { mainPy.contains(it) }) {
            log.add("检测到 hook 处理器，触发方式默认：总是触发(hook)")
            return "always"
        }
        if (Regex("""register_llm_tool|llm_tool|tools\s*=|on_llm_request""").containsMatchIn(mainPy)) {
            log.add("检测到 LLM 工具/技能注册，触发方式默认：LLM触发(技能)")
            return "llm"
        }
        return "passive"
    }

    private fun triggerName(t: String): String = when (t) {
        "always" -> "总是触发(hook)"
        "llm" -> "LLM触发(技能)"
        "never" -> "从不触发"
        else -> "被动触发(命令)"
    }

    private fun buildJson(id: String, name: String, version: String, author: String, desc: String, trigger: String, commands: List<Triple<String, String, String>>, cleanRegex: String?, configFields: List<PluginConfigField> = emptyList(), cleanReplace: String = "", doc: String = "", type: String = "prompt"): String {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("version", version)
        o.put("author", author)
        o.put("description", desc)
        o.put("type", type)
        o.put("trigger", trigger)
        if (doc.isNotBlank()) o.put("doc", doc)
        val arr = JSONArray()
        commands.forEachIndexed { i, (match, prompt, reply) ->
            val co = JSONObject()
            co.put("match", match)
            if (prompt.isNotBlank()) co.put("prompt", prompt)
            if (reply.isNotBlank()) co.put("reply", reply)
            if (i == 0 && !cleanRegex.isNullOrBlank()) {
                co.put("regex", cleanRegex)
                // re.sub('A','B') 的替换值：空串=删除（filter 插件旧行为）；'\n'=压缩空行
                if (cleanReplace.isNotEmpty()) co.put("replaceWith", cleanReplace)
            }
            arr.put(co)
        }
        o.put("commands", arr)
        // 转换 AstrBot 插件的配置项：安装后可在插件页“配置”中编辑（API key / 开关 / 参数）
        if (configFields.isNotEmpty()) {
            val carr = JSONArray()
            configFields.forEach { carr.put(it.toJson()) }
            o.put("config", carr)
        }
        o.put("enabled", true)
        return o.toString(2)
    }

    /**
     * 提取 zip 内指定文本文件。
     * 1.7.89 修复同名覆盖：zip 内可能同时有根 README.md 与 docs/README.md——
     * 按"路径最浅优先"取根文档（此前后者覆盖前者 → 文档内容错乱/看似空模板）。
     */
    private fun unzipText(bytes: ByteArray, keywords: List<String>): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        val bestDepth = mutableMapOf<String, Int>()
        try {
            val zis = ZipInputStream(ByteArrayInputStream(bytes))
            var e = zis.nextEntry
            while (e != null) {
                val name = e.name.lowercase()
                if (!e.isDirectory) {
                    val matched = keywords.firstOrNull { k -> name.endsWith(k.lowercase()) }
                    if (matched != null) {
                        val content = zis.readBytes().toString(Charsets.UTF_8)
                        val key = matched.lowercase()
                        val d = name.count { it == '/' }
                        if (d < (bestDepth[key] ?: Int.MAX_VALUE)) {
                            out[key] = content
                            bestDepth[key] = d
                        }
                    } else {
                        zis.readBytes() // 消费流：非目标条目也必须读完，否则 zip 流错位
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
            zis.close()
        } catch (_: Exception) {
        }
        return out
    }

    private fun unescape(s: String): String = s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")

    // ---------------- 发图/拉图：图片直链自动抽取 ----------------
    /** 图片直链：http(s) 且以位图扩展名结尾（可带 ?query）；不限数量。
     *  故意排除 svg（矢量 XML，QQ 富媒体接口无法发送会 400，且会把 shields.io 等徽章链接误当图片） */
    private val IMAGE_URL_RE = Regex(
        """https?://[^\s"'\\)\(\]\[,<>;`]+?\.(?:png|jpe?g|gif|webp|bmp|tiff?)(?:\?[^\s"'\\)\(\]\[,<>;`]*)?""",
        RegexOption.IGNORE_CASE,
    )

    /** 识别“图片类”插件的关键词（命中其一即视为发图插件，即便暂未抽到直链也预留 imageUrl 配置） */
    private val IMAGE_PLUGIN_KEYWORDS = Regex(
        """image_result|send_image|图片|表情包|表情|奶龙|nailong|\bmeme\b|sticker|image_url|img_url|IMAGE_LIST""",
        RegexOption.IGNORE_CASE,
    )
    private val SCAN_TEXT_EXTS = setOf(".py", ".json", ".txt", ".md", ".csv", ".yaml", ".yml", ".conf", ".toml", ".list", ".in")

    /** 从压缩包的全部文本文件里抽取所有图片直链（去重、不限数量）。二进制图片文件不含 URL，忽略 */
    private fun collectImageUrls(bytes: ByteArray): List<String> {
        val found = LinkedHashSet<String>()
        try {
            val zis = ZipInputStream(ByteArrayInputStream(bytes))
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val nm = e.name.lowercase()
                    if (SCAN_TEXT_EXTS.any { nm.endsWith(it) }) {
                        val content = try {
                            zis.readBytes().toString(Charsets.UTF_8)
                        } catch (_: Exception) {
                            ""
                        }
                        for (m in IMAGE_URL_RE.findAll(content)) found.add(m.value)
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
            zis.close()
        } catch (_: Exception) {
        }
        return found.toList()
    }

    private fun slug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]"), "_").trim('_')

    private class Meta {
        var name = "" // 包体名（astrbot_plugin_xxx）
        var displayName = "" // AstrBot 里展示的插件名（优先使用）
        var desc = ""
        var author = ""
        var version = ""
        val configFields = mutableListOf<PluginConfigField>()
        val shownName: String get() = displayName.ifBlank { name }
    }
}

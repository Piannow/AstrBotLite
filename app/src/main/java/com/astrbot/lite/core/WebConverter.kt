package com.astrbot.lite.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Web 离线转换（1.7.87）：AstrBot 插件安装进 Python 环境后，若检测到 web 视图，
 * 执行一次转换——把 HTML/CSS 里的 https 外链（CDN 脚本/样式/字体/图片）下载缓存到
 * `apy/{id}/_web_cache/`，生成 manifest.json 映射；WebView 加载时按 manifest 把外链
 * 重写为本地虚拟路径 → 页面完全离线、可交互（不依赖 https 网络）。
 *
 * 容量控制（用户要求"别太离谱"）：
 *  - 单文件 ≤ 2MB、累计总量 ≤ 12MB、外链候选 ≤ 40 个、HTML/CSS 扫描文件 ≤ 60 个；
 *  - 只存"映射清单 + 下载的资源"，不复制页面本体、不改原文件；
 *  - 下载失败静默跳过（页面对应功能降级，安装不失败）。
 */
object WebConverter {

    private const val CACHE_DIR = "_web_cache"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 12L * 1024 * 1024
    private const val MAX_CANDIDATES = 40
    private const val MAX_SCAN_FILES = 60

    /** 常见前端 CDN（jsDelivr/unpkg/cdnjs/BootCDN/静态文件/Google Fonts/npmmirror） */
    private val CDN_HOSTS = listOf(
        "cdn.jsdelivr.net", "fastly.jsdelivr.net", "gcore.jsdelivr.net",
        "unpkg.com", "cdnjs.cloudflare.com", "cdn.bootcdn.net", "cdn.bootcss.com",
        "staticfile.org", "fonts.googleapis.com", "fonts.gstatic.com",
        "registry.npmmirror.com", "lf-cdn.com", "lf6-cdn.com", "lf3-cdn.com",
        "lf24-cdn.com", "npm.elemecdn.com", "cdn.staticfile.org", "ajax.googleapis.com"
    )

    private val RES_EXT = Regex(
        """\.(js|mjs|css|woff2?|ttf|eot|otf|svg|png|jpe?g|gif|webp|ico|mp3|mp4)(\?|#|$)""",
        RegexOption.IGNORE_CASE
    )
    private val HTML_SRC = Regex("""(?:src|href|data-src|poster)\s*=\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
    private val CSS_URL = Regex("""url\(\s*["']?(https?://[^"')]+)["']?\s*\)""", RegexOption.IGNORE_CASE)

    private val http by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** web 视图根探测（对齐 PythonRuntime.pythonPluginWebRoot 的目录命名） */
    private fun findWebRoot(apyDir: File): File? {
        for (n in listOf("views", "pages", "webui", "ui", "web")) {
            val d = File(apyDir, n)
            if (d.isDirectory) return d
        }
        // zip 带顶层目录的回退：一层子目录内
        apyDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name }
            ?.forEach { c ->
                for (n in listOf("views", "pages", "webui", "ui", "web")) {
                    val d = File(c, n)
                    if (d.isDirectory) return d
                }
            }
        return null
    }

    private fun sha1_10(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun extOf(url: String): String {
        val path = url.substringBefore('#').substringBefore('?')
        val e = path.substringAfterLast('.', "")
        return if (e.length in 1..5 && e.all { it.isLetterOrDigit() }) ".$e" else ".bin"
    }

    private fun isCandidate(url: String): Boolean {
        if (!url.startsWith("http")) return false
        if (RES_EXT.containsMatchIn(url)) return true
        val host = try {
            java.net.URI(url).host?.lowercase() ?: return false
        } catch (_: Exception) {
            return false
        }
        return CDN_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * 安装链路调用：探测 web + 离线化外链。
     * @param apyDir 插件在 Python 环境的根目录（filesDir/apy/{id}）
     * @param onProgress (阶段文案, 已完成, 总数) —— 驱动安装进度条
     * @return null = 无 web 视图；否则返回转换元数据（写入 _web_cache/manifest.json）
     */
    fun convert(apyDir: File, onProgress: (String, Int, Int) -> Unit): JSONObject? {
        val webRoot = findWebRoot(apyDir) ?: return null
        if (!webRoot.isDirectory) return null

        // ① 页面清单（容量极小，几十字节）
        val pages = mutableListOf<String>()
        if (File(webRoot, "index.html").isFile) pages.add(".")
        webRoot.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?.forEach { if (File(it, "index.html").isFile) pages.add(it.name) }

        // ② 扫描 HTML/CSS 提取 https 外链（文件数与候选数都封顶）
        val candidates = LinkedHashSet<String>()
        val scan = mutableListOf<File>()
        fun collectHtml(dir: File) {
            if (scan.size >= MAX_SCAN_FILES) return
            dir.listFiles()?.forEach { f ->
                if (scan.size >= MAX_SCAN_FILES) return@forEach
                when {
                    f.isDirectory && !f.name.startsWith(".") -> collectHtml(f)
                    f.isFile && (f.name.endsWith(".html", true) || f.name.endsWith(".htm", true)) -> scan.add(f)
                }
            }
        }
        collectHtml(webRoot)
        webRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".css", true) }
            .take(MAX_SCAN_FILES).forEach { scan.add(it) }
        for (f in scan.take(MAX_SCAN_FILES)) {
            try {
                val text = f.readText(Charsets.UTF_8)
                if (f.name.endsWith(".css", true)) {
                    CSS_URL.findAll(text).forEach { m -> if (isCandidate(m.groupValues[1])) candidates.add(m.groupValues[1]) }
                } else {
                    HTML_SRC.findAll(text).forEach { m -> if (isCandidate(m.groupValues[1])) candidates.add(m.groupValues[1]) }
                }
            } catch (_: Exception) {
            }
            if (candidates.size >= MAX_CANDIDATES) break
        }
        val urls = candidates.take(MAX_CANDIDATES).toList()
        if (urls.isEmpty()) {
            // 无外链：仍写空 manifest（标记"已转换完成"），并返回页面清单
            val meta = JSONObject().put("pages", JSONArray(pages)).put("offline", JSONObject().put("ok", 0).put("total", 0))
            writeManifest(apyDir, emptyMap(), meta)
            onProgress("Web 转换完成（无需下载离线资源）", 1, 1)
            return meta
        }

        // ③ 逐个下载到 _web_cache（单文件/总量封顶，失败跳过）
        val cacheDir = File(apyDir, CACHE_DIR).apply { mkdirs() }
        val map = HashMap<String, String>()
        var totalBytes = 0L
        var done = 0
        var skipped = 0
        for (u in urls) {
            onProgress("Web 离线转换：下载资源 ${done + 1}/${urls.size}", done, urls.size)
            val name = sha1_10(u) + extOf(u)
            val target = File(cacheDir, name)
            if (target.isFile && target.length() in 1..MAX_FILE_BYTES) {
                map[u] = name
                totalBytes += target.length()
                done++
                continue
            }
            try {
                val resp = http.newCall(
                    okhttp3.Request.Builder().url(u)
                        .header("User-Agent", "AstrBotLite/1.0 (Android)")
                        .build()
                ).execute()
                val body = resp.body
                if (!resp.isSuccessful || body == null) {
                    skipped++; done++; continue
                }
                val bytes = body.bytes()
                if (bytes.isEmpty() || bytes.size > MAX_FILE_BYTES || totalBytes + bytes.size > MAX_TOTAL_BYTES) {
                    skipped++; done++; continue
                }
                target.writeBytes(bytes)
                map[u] = name
                totalBytes += bytes.size
                done++
            } catch (_: Exception) {
                skipped++; done++
            }
        }

        // ④ manifest + 元数据
        val offline = JSONObject()
            .put("ok", map.size)
            .put("total", urls.size)
            .put("skipped", skipped)
            .put("bytes", totalBytes)
        val meta = JSONObject().put("pages", JSONArray(pages)).put("offline", offline)
        writeManifest(apyDir, map, meta)
        Logs.info("[Web转换] ${apyDir.name}：${map.size}/${urls.size} 外链已离线化（${totalBytes / 1024}KB，页面 ${pages.size} 个）")
        onProgress("Web 离线转换完成（${map.size}/${urls.size} 资源已缓存）", urls.size, urls.size)
        return meta
    }

    private fun writeManifest(apyDir: File, map: Map<String, String>, meta: JSONObject) {
        try {
            val dir = File(apyDir, CACHE_DIR).apply { mkdirs() }
            val out = JSONObject()
            out.put("links", JSONObject(map as Map<*, *>))
            out.put("meta", meta)
            File(dir, "manifest.json").writeText(out.toString())
        } catch (e: Exception) {
            Logs.warn("[Web转换] manifest 写入失败: ${e.message}")
        }
    }

    /** WebView 侧读取：url → 本地文件名 映射（无 manifest 返回空表） */
    fun loadManifest(apyDir: File): Map<String, String> {
        return try {
            val f = File(File(apyDir, CACHE_DIR), "manifest.json")
            if (!f.isFile) return emptyMap()
            val links = JSONObject(f.readText()).optJSONObject("links") ?: return emptyMap()
            val out = mutableMapOf<String, String>()
            for (k in links.keys()) out[k] = links.optString(k)
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** 文本中的 https 外链 → 本地虚拟路径（命中 manifest 才替换；未命中保持原样） */
    fun rewrite(text: String, manifest: Map<String, String>): String {
        if (manifest.isEmpty()) return text
        var out = text
        for ((url, file) in manifest) {
            if (out.contains(url)) out = out.replace(url, "http://app.local/$CACHE_DIR/$file")
        }
        return out
    }
}

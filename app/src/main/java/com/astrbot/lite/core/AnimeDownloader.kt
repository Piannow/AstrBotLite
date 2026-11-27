package com.astrbot.lite.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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

    private fun resolveShort(url: String): String = try {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { resp ->
            val loc = resp.header("Location") ?: resp.request.url.toString()
            loc
        }
    } catch (_: Exception) {
        url
    }

    // ---------------- WBI 签名（参考 bililidownloadtool core/infra/wbi_sign.py） ----------------
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52,
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
        } catch (_: Exception) {
            null
        }
    }

    /** WBI 签名（含 dm_img 反作弊参数）：mixin_key = 打乱(img_key+sub_key) 取 32 位；
     *  w_rid = md5(排序后的 query + mixin_key)。B 站搜索/详情接口要求带 dm_img_* 参数。
     *  返回可直接拼接的完整 query string（含 w_rid）。 */
    private fun signWbi(params: Map<String, String>): String {
        val keys = fetchWbiKeys() ?: ("img_key" to "sub_key")
        val imgKey = keys.first
        val subKey = keys.second
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
        val name: String, // 片名（如 "夏日重现"）
        val describe: String, // "2022★日本★日韩动漫"（年份+地区+类型）
        val typeId: String, // 源站标识（yy/js/md/ry/hy/zd...）
        val userMark: String, // "t-优质" / "hd" 质量标记
        val eps: List<Pair<String, String>>, // (集名, m3u8 完整直链)
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
                } else {
                    resp.body?.string()
                }
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
                        eps = eps,
                    ),
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
                "limit" to "50",
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
                RegexOption.DOT_MATCHES_ALL,
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
            try {
                java.net.URLDecoder.decode(it, "UTF-8")
            } catch (_: Exception) {
                it
            }
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
                        bestV = item
                        bestId = qnId
                        bestBw = bw
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

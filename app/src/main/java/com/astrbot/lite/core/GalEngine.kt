package com.astrbot.lite.core

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object GalEngine {
    private const val BASE = "https://www.kungal.com/api/v1"
    const val PAGE_BASE = "https://www.kungal.com/galgame/"
    private const val PAGE_SIZE = 8

    private val jsonMt: MediaType = "application/json; charset=utf-8".toMediaType()

    // 1.7.237：加上 callTimeout + 瞬时失败重试（站点偶发 502/429 或连接被重置时不再直接判失败）
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val pageSize: Int get() = PAGE_SIZE

    data class GalItem(
        val id: String,
        val name: String,
        val origin: String,
        val date: String,
        val rating: Double?,
        val ratingCount: Int,
        val platforms: String,
        val languages: String,
        val coverUrl: String,
        val rank: Int,
        val metric: Double,
    )

    data class GalPage(
        val total: Int,
        val gte: Boolean,
        val items: List<GalItem>,
    )

    data class GalDetail(
        val name: String,
        val origin: String,
        val aliases: List<String>,
        val date: String,
        val maker: String,
        val rating: Double?,
        val ratingCount: Int,
        val platforms: String,
        val languages: String,
        val views: Long,
        val likes: Long,
        val published: Boolean,
        val coverUrl: String,
    )

    data class GalRes(
        val id: String,
        val type: String,
        val title: String,
        val size: String,
        val providers: List<String>,
        val languages: String,
        val state: String,
        val workId: String,
        val workName: String,
        val note: String,
    )

    data class GalDownload(
        val urls: List<String>,
        val extractionCode: String,
        val archivePassword: String,
    )

    data class GalResPage(
        val total: Int,
        val gte: Boolean,
        val items: List<GalRes>,
    )

    private fun typeZh(t: String): String = when (t) {
        "collection" -> "合集"
        "artbook" -> "设定集"
        "cg" -> "CG"
        "mod" -> "MOD"
        "ost" -> "原声"
        "game" -> "本体"
        "tool" -> "工具"
        "patch" -> "补丁"
        "video" -> "视频"
        "voice" -> "语音包"
        "crack_fix" -> "修复"
        "wallpaper" -> "壁纸"
        "walkthrough" -> "攻略"
        else -> "其他"
    }

    private fun langZh(l: String): String = when (l) {
        "en-us" -> "英文"
        "ja-jp" -> "日文"
        "zh-cn" -> "简中"
        "zh-tw" -> "繁中"
        else -> "其他"
    }

    private fun platZh(p: String): String = when (p) {
        "and" -> "安卓"
        "ios" -> "iOS"
        "lin" -> "Linux"
        "n3d" -> "3DS"
        "mac" -> "macOS"
        "nds" -> "NDS"
        "ps1" -> "PS1"
        "ps2" -> "PS2"
        "ps3" -> "PS3"
        "ps4" -> "PS4"
        "ps5" -> "PS5"
        "psp" -> "PSP"
        "psv" -> "PSV"
        "sw2" -> "Switch"
        "swi" -> "Switch"
        "vnd" -> "VN引擎"
        "x1s" -> "Xbox"
        "web" -> "Web"
        "win" -> "Windows"
        "xb1" -> "Xbox"
        "xb3" -> "Xbox"
        "xbo" -> "Xbox"
        "xxs" -> "Xbox"
        else -> p
    }

    private fun joinMapped(values: List<String>, map: (String) -> String): String = values.map(map).distinct().joinToString("/")

    private fun sliceDate(date: String, precision: String): String = when (precision) {
        "year" -> date.take(4)
        "month" -> date.take(7)
        else -> date
    }

    private fun zhNameOf(localized: JSONObject, display: String, latin: String?): Pair<String, String> {
        val zh = localized.optJSONObject("zh-Hans")?.optString("value") ?: ""
        val origin = if (zh.isBlank()) {
            latin?.takeIf { it.isNotBlank() } ?: display
        } else {
            display
        }
        val name = if (zh.isBlank()) display else zh
        return Pair(name, origin)
    }

    /** 统一请求：瞬时失败（429/5xx/网络异常）重试 3 次，4xx 直接失败 */
    private fun request(req: Request, label: String): JSONObject? {
        for (attempt in 1..3) {
            var retry = false
            try {
                http.newCall(req).execute().use { response ->
                    val str = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        Logs.warn("[Gal] $label → HTTP ${response.code}: ${str.take(200)}")
                        retry = response.code == 429 || response.code >= 500
                    } else {
                        return try {
                            JSONObject(str)
                        } catch (e: Exception) {
                            Logs.warn("[Gal] $label JSON 解析失败: ${e.message}")
                            null
                        }
                    }
                }
            } catch (e: Exception) {
                Logs.warn("[Gal] $label 网络异常: ${e.message}")
                retry = true
            }
            if (!retry || attempt == 3) return null
            try {
                Thread.sleep(if (attempt == 1) 400L else 1200L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private fun get(path: String): JSONObject? {
        val req = Request.Builder()
            .url(BASE + path)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/129")
            .header("Accept", "application/json")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", "https://www.kungal.com/")
            .get()
            .build()
        return request(req, "GET $path")
    }

    private fun post(path: String, body: String): JSONObject? {
        val req = Request.Builder()
            .url(BASE + path)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/129")
            .header("Accept", "application/json")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Content-Type", "application/json")
            .header("Referer", "https://www.kungal.com/")
            .post(RequestBody.create(jsonMt, body))
            .build()
        return request(req, "POST $path")
    }

    private fun parseItem(o: JSONObject): GalItem {
        val localized = o.optJSONObject("localized") ?: JSONObject()
        val display = o.optString("display_name", "")
        val latin = if (o.isNull("latin")) null else o.optString("latin")
        val (name, origin) = zhNameOf(localized, display, latin)
        val id = o.optString("id")
        val originStr = if (origin == name) "" else origin
        val date = o.optString("release_date", "")
        val rating = if (o.isNull("rating_score")) null else o.optDouble("rating_score")
        val ratingCount = o.optInt("rating_count")
        val platforms = joinMapped(jsonList(o, "resource_platforms")) { platZh(it) }
        val languages = joinMapped(jsonList(o, "resource_languages")) { langZh(it) }
        val coverUrl = o.optJSONObject("cover")?.optString("url") ?: ""
        return GalItem(id, name, originStr, date, rating, ratingCount, platforms, languages, coverUrl, 0, 0.0)
    }

    private fun jsonList(o: JSONObject, key: String): List<String> {
        val arr: JSONArray = o.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }

    fun search(keyword: String, page: Int = 1): GalPage? {
        val q = URLEncoder.encode(keyword.trim(), "UTF-8")
        val root = get("/search/works?q=$q&page=$page&limit=$PAGE_SIZE&include_nsfw=true") ?: return null
        val items = root.optJSONArray("items") ?: return GalPage(0, false, emptyList())
        val out = ArrayList<GalItem>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            out.add(parseItem(o))
        }
        return GalPage(root.optInt("total"), root.optString("total_relation") == "gte", out)
    }

    fun ranking(sort: String = "rating_desc", limit: Int = 10): List<GalItem>? {
        val root = get("/rankings/works?sort=$sort&limit=$limit&include_nsfw=true") ?: return null
        val items = root.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<GalItem>()
        for (i in 0 until items.length()) {
            val e = items.optJSONObject(i) ?: continue
            val w = e.optJSONObject("work") ?: continue
            val localized = w.optJSONObject("localized") ?: JSONObject()
            val display = w.optString("display_name", "")
            val latin = if (w.isNull("latin")) null else w.optString("latin")
            val (name, origin) = zhNameOf(localized, display, latin)
            val id = w.optString("id")
            val originStr = if (origin == name) "" else origin
            val coverUrl = w.optJSONObject("cover")?.optString("url") ?: ""
            out.add(
                GalItem(
                    id, name, originStr, "", null, 0, "", "", coverUrl,
                    e.optInt("rank"), e.optDouble("metric_value"),
                ),
            )
        }
        return out
    }

    fun detail(workId: String): GalDetail? {
        val o = get("/works/$workId?include_nsfw=true") ?: return null
        val localized = o.optJSONObject("localized") ?: JSONObject()
        val display = o.optString("display_name", "")
        val latin = if (o.isNull("latin")) null else o.optString("latin")
        val (name, origin) = zhNameOf(localized, display, latin)
        val aliases = jsonList(o, "aliases").filter { it != name && it != origin }
        val originStr = if (origin == name) "" else origin
        val date = o.optString("release_date", "")
        val datePrecision = o.optString("release_date_precision", "day")
        val slicedDate = sliceDate(date, datePrecision)
        val maker = o.optJSONObject("maker")?.optString("display_name") ?: ""
        val rating = if (o.isNull("rating_score")) null else o.optDouble("rating_score")
        val ratingCount = o.optInt("rating_count")
        val platforms = joinMapped(jsonList(o, "resource_platforms")) { platZh(it) }
        val languages = joinMapped(jsonList(o, "resource_languages")) { langZh(it) }
        val views = o.optLong("view_count")
        val likes = o.optLong("like_count")
        val published = o.optBoolean("is_published")
        val coverUrl = o.optJSONObject("cover")?.optString("url") ?: ""
        return GalDetail(
            name, originStr, aliases, slicedDate, maker, rating, ratingCount,
            platforms, languages, views, likes, published, coverUrl,
        )
    }

    fun resources(workId: String, limit: Int = 8): List<GalRes>? {
        val root = get("/works/$workId/resources?limit=$limit&include_nsfw=true") ?: return null
        return parseResList(root, workId)
    }

    fun searchResources(keyword: String, page: Int = 1): GalResPage? {
        val q = URLEncoder.encode(keyword.trim(), "UTF-8")
        val root = get("/galgame-resources?q=$q&page=$page&limit=$PAGE_SIZE&include_nsfw=true") ?: return null
        return GalResPage(
            root.optInt("total"),
            root.optString("total_relation") == "gte",
            parseResList(root, ""),
        )
    }

    private fun docText(content: JSONObject?, max: Int): String {
        if (content == null) return ""
        val sb = StringBuilder()
        docTextWalk(sb, max, content)
        return sb.toString().take(max)
    }

    private fun docTextWalk(sb: StringBuilder, max: Int, o: JSONObject) {
        if (sb.length >= max) return
        val text = o.optString("text", "")
        if (text.isNotBlank()) {
            if (sb.length > 0) sb.append(' ')
            sb.append(text)
            if (sb.length >= max) return
        }
        val ch = o.optJSONArray("children") ?: return
        for (i in 0 until ch.length()) {
            val c = ch.optJSONObject(i) ?: continue
            docTextWalk(sb, max, c)
            if (sb.length >= max) return
        }
    }

    fun pwdHint(note: String): String? {
        if (note.isBlank()) return null
        if (Regex("无密码|免密码|无需密码|不需要密码|没有密码").containsMatchIn(note)) return "🔓无密码"
        if (Regex("密码|提取码|访问码|解压码", RegexOption.IGNORE_CASE).containsMatchIn(note)) return "🔒密码见简介"
        return null
    }

    private fun workNameOf(w: JSONObject?): String {
        if (w == null) return ""
        val zh = w.optJSONObject("localized")?.optJSONObject("zh-Hans")?.optString("value") ?: ""
        return zh.ifBlank { w.optString("display_name", "") }
    }

    private fun parseResList(root: JSONObject, fallbackWorkId: String): List<GalRes> {
        val items = root.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<GalRes>()
        for (i in 0 until items.length()) {
            val r = items.optJSONObject(i) ?: continue
            val w = r.optJSONObject("work")
            val id = r.optString("id")
            val type = r.optString("resource_type", "other")
            val title = r.optString("title", "").trim()
            val size = r.optString("size", "")
            val providers = jsonList(r, "provider_names")
            val languages = joinMapped(jsonList(r, "resource_languages")) { langZh(it) }
            val state = r.optString("state", "")
            val workId = w?.optString("id") ?: fallbackWorkId
            out.add(
                GalRes(
                    id, type, title, size, providers, languages, state,
                    workId, workNameOf(w), docText(r.optJSONObject("content"), 300),
                ),
            )
        }
        return out
    }

    fun downloadUrls(resId: String): GalDownload? {
        val o = post("/galgame-resources/$resId/downloads", "{}") ?: return null
        val arr = o.optJSONArray("download_urls") ?: return null
        val urls = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotBlank()) urls.add(s)
        }
        if (urls.isEmpty()) return null
        return GalDownload(
            urls,
            o.optString("extraction_code", ""),
            o.optString("archive_password", ""),
        )
    }

    fun typeLabel(t: String): String = typeZh(t)

    fun stateLabel(s: String): String = when (s) {
        "valid" -> "有效"
        "expired" -> "已失效"
        else -> s
    }

    fun platLabel(p: String): String = platZh(p)

    fun langLabel(l: String): String = langZh(l)

    fun lineOf(idx: Int, it: GalItem, withRank: Boolean): String {
        val sb = StringBuilder()
        if (!withRank || it.rank <= 0) sb.append(idx).append(". ") else sb.append(it.rank).append(". ")
        sb.append(it.name)

        val bits = ArrayList<String>()
        if (it.origin.isNotBlank()) bits.add(it.origin)
        if (it.date.isNotBlank()) bits.add(it.date)

        val rating = if (withRank && it.rating == null) it.metric else it.rating
        if (rating != null && (withRank || it.ratingCount > 0)) {
            var txt = String.format("⭐%.2f", rating).trimEnd('0').trimEnd('.')
            if (txt.isBlank()) txt = "⭐$rating"
            bits.add(txt)
        }
        if (it.platforms.isNotBlank()) bits.add(it.platforms)
        if (it.languages.isNotBlank()) bits.add(it.languages)

        if (bits.isNotEmpty()) sb.append("（").append(bits.joinToString(" · ")).append("）")
        return sb.toString()
    }
}

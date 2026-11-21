package com.astrbot.converter

import org.json.JSONArray
import org.json.JSONObject

/** 插件市场条目 */
data class MarketPlugin(
    val name: String,
    val author: String,
    val version: String,
    val desc: String,
    val repo: String,
    val downloadUrl: String
) {
    val displayName: String get() = "$name v${version.ifBlank { "?" }} by ${author.ifBlank { "?" }}"
    val zipUrl: String?
        get() {
            if (downloadUrl.isNotBlank()) return downloadUrl
            return Net.githubRepoZip(repo)
        }
}

/** 解析 AstrBot 插件市场 JSON（兼容“数组格式”与“\$meta 新格式”） */
object Market {
    const val DEFAULT_URL = "https://api.soulter.top/plugins"

    fun parse(text: String): List<MarketPlugin> {
        val list = mutableListOf<MarketPlugin>()
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return parseArray(text)
        }
        // $meta 新格式：根为对象，键为 plugin_id 或 name，值含 author/name/version/repo/desc/download_url
        val it = root.keys()
        while (it.hasNext()) {
            val key = it.next()
            if (key == "\$meta") continue
            val o = root.optJSONObject(key) ?: continue
            list.add(MarketPlugin(
                name = o.optString("name").ifBlank { key.substringAfterLast('/') },
                author = o.optString("author", ""),
                version = o.optString("version", ""),
                desc = o.optString("desc").ifBlank { o.optString("short_desc") },
                repo = o.optString("repo", ""),
                downloadUrl = o.optString("download_url", "")
            ))
        }
        if (list.isNotEmpty()) return list
        return parseArray(text)
    }

    private fun parseArray(text: String): List<MarketPlugin> {
        val list = mutableListOf<MarketPlugin>()
        val arr = try {
            JSONArray(text)
        } catch (_: Exception) {
            return list
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(MarketPlugin(
                name = o.optString("name"),
                author = o.optString("author", ""),
                version = o.optString("version", ""),
                desc = o.optString("desc").ifBlank { o.optString("description") },
                repo = o.optString("repo", ""),
                downloadUrl = o.optString("zip_path").ifBlank { o.optString("download_url") }
            ))
        }
        return list
    }
}

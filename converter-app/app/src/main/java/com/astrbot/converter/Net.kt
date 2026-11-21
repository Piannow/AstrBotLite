package com.astrbot.converter

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 极简 HTTP 下载（跟随重定向，带 UA） */
object Net {
    private const val MAX = 32 * 1024 * 1024 // 32MB 上限

    /** 下载为字节数组；失败返回 null */
    fun download(url: String): ByteArray? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "AstrBotPluginConverter/1.0 (Android)")
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code !in 200..299) return null
            val input = conn.inputStream
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                total += n
                if (total > MAX) return null
                out.write(buf, 0, n)
            }
            input.close()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    /** 下载为文本 */
    fun downloadText(url: String): String? {
        val b = download(url) ?: return null
        return String(b, Charsets.UTF_8)
    }

    /** GitHub 仓库 zip 地址（依次尝试 master / main 分支） */
    fun githubRepoZip(repoUrl: String): String? {
        var u = repoUrl.trim().removeSuffix("/").removeSuffix(".git")
        if (u.startsWith("github.com")) u = "https://$u"
        val m = Regex("""github\.com/([^/]+)/([^/]+)""").find(u) ?: return null
        val owner = m.groupValues[1]
        val repo = m.groupValues[2]
        return "https://github.com/$owner/$repo/archive/refs/heads/master.zip"
    }
}

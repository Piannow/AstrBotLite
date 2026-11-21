package com.astrbot.converter

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** ZIP 相关工具 */
object ZipUtil {

    /** 从 zip 字节中按文件名关键词提取文本文件（返回 文件名→内容） */
    fun unzipText(bytes: ByteArray, keywords: List<String>): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        try {
            val zis = ZipInputStream(ByteArrayInputStream(bytes))
            var e = zis.nextEntry
            while (e != null) {
                val name = e.name.lowercase()
                if (!e.isDirectory) {
                    val matched = keywords.firstOrNull { k -> name.endsWith(k.lowercase()) }
                    if (matched != null) {
                        val text = zis.readBytes().toString(Charsets.UTF_8)
                        out[matched.lowercase()] = text
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

    /** 从 zip 中提取 main.py / metadata.yaml / plugin.json（按需） */
    fun extractPluginFiles(bytes: ByteArray): ZipContent {
        val files = unzipText(bytes, listOf("main.py", "plugin.py", "metadata.yaml", "metadata.yml", "plugin.json"))
        return ZipContent(
            mainPy = files["main.py"] ?: files["plugin.py"] ?: "",
            metaYaml = files["metadata.yaml"] ?: files["metadata.yml"] ?: "",
            pluginJson = files["plugin.json"] ?: ""
        )
    }

    /** 把 plugin.json 打成 zip（{id}/plugin.json，匹配 AstrBot Lite 安装格式） */
    fun buildPluginZip(pluginJson: String, id: String): ByteArray {
        val bos = ByteArrayOutputStream()
        val zos = ZipOutputStream(bos)
        zos.putNextEntry(ZipEntry("$id/plugin.json"))
        zos.write(pluginJson.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
        zos.close()
        return bos.toByteArray()
    }

    data class ZipContent(val mainPy: String, val metaYaml: String, val pluginJson: String)
}

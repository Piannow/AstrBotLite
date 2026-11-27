package com.astrbot.lite.core

import android.content.Context
import java.io.File

/**
 * 生成媒体（图片/视频）的临时文件管理。
 *
 * 规则：生成的图片/视频在发送前落到 filesDir/media_tmp/，发送完（无论成功还是失败）立刻删除，
 * 保证生成的内容不在本机长期留存；每次启动再清一次上次异常退出（崩溃/断电）留下的残留。
 * 保存与删除都写日志（运行日志里能核对：存了什么、多大、为什么删）。
 */
object TempMedia {
    private const val DIR_NAME = "media_tmp"

    /** 残留判定：超过 1 小时还没被删掉的，视为上次异常退出留下的 */
    private const val STALE_MS = 60 * 60 * 1000L
    private var dir: File? = null

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, DIR_NAME).apply { mkdirs() }
        cleanupStale()
    }

    /** 保存为临时文件；失败返回 null（发送方仍可用内存里的字节继续发，不阻断） */
    fun save(bytes: ByteArray, ext: String): File? {
        val d = dir ?: return null
        return try {
            val name = "gen_" + System.currentTimeMillis() + "." + (ext.ifBlank { "bin" })
            val f = File(d, name)
            f.writeBytes(bytes)
            Logs.info("[临时文件] 已保存：$name（${sizeText(bytes.size.toLong())}，发送后即删）")
            f
        } catch (e: Exception) {
            Logs.warn("[临时文件] 保存失败：${e.message}（改用内存数据直接发送）")
            null
        }
    }

    /** 删除临时文件并写日志（reason 说明为什么删，方便在日志里核对） */
    fun delete(file: File?, reason: String) {
        if (file == null) return
        try {
            val size = file.length()
            if (!file.exists()) return
            if (!file.delete()) {
                Logs.warn("[临时文件] 删除失败：${file.name}（${sizeText(size)}）")
                return
            }
            Logs.info("[临时文件] 已删除：${file.name}（${sizeText(size)}，$reason）")
        } catch (e: Exception) {
            Logs.warn("[临时文件] 删除异常：${e.message}")
        }
    }

    /** 启动清理：删掉上次异常退出留下的临时媒体 */
    private fun cleanupStale() {
        val d = dir ?: return
        try {
            val now = System.currentTimeMillis()
            var count = 0
            var total = 0L
            d.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                if (now - f.lastModified() > STALE_MS) {
                    total += f.length()
                    if (f.delete()) count++
                }
            }
            if (count > 0) Logs.info("[临时文件] 启动清理残留：$count 个（共 ${sizeText(total)}）")
        } catch (e: Exception) {
            Logs.warn("[临时文件] 启动清理失败：${e.message}")
        }
    }

    private fun sizeText(n: Long): String = if (n < 1024 * 1024) "${n / 1024}KB" else String.format("%.2fMB", n / 1024.0 / 1024.0)
}

package com.astrbot.lite.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 聊天记录本地持久化：JSONL 追加写 filesDir/chats/chat.log
 * 应用内部存储，重启后仍可查看；导出到外部时使用系统文件选择器（无需权限）
 *
 * 1.7.207 修复「过久了闪退」：旧版只追加、从不裁剪，`load()` 又先把**整个文件**
 * 逐行读进内存再取末 1000 条。长时间运行后 chat.log 无限增长（实测 50 万条 = 38MB），
 * 每次刷新状态页都要全量读一次 → 内存峰值随运行时长线性上升 → OOM 闪退。
 * 现改为：写满阈值后自动保留末尾若干行重写；读取只取文件尾段，内存恒定。
 */
object ChatStore {
    /** 落盘上限：超过即裁剪（与 Logs 同思路，防止无限增长） */
    private const val MAX_BYTES = 2L * 1024 * 1024
    /** 裁剪后保留的条数（足够覆盖 UI 的 1000 条展示 + 余量） */
    private const val KEEP_LINES = 1200
    /** 读取时展示的条数 */
    private const val SHOW_LINES = 1000

    private lateinit var file: File

    fun init(ctx: Context) {
        val dir = File(ctx.filesDir, "chats").apply { mkdirs() }
        file = File(dir, "chat.log")
    }

    fun append(entry: ChatEntry) {
        try {
            synchronized(this) {
                val o = JSONObject()
                    .put("from", entry.from)
                    .put("content", entry.content)
                    .put("time", entry.time)
                    .put("session", entry.session)
                file.appendText(o.toString() + "\n")
                trimIfLarge()
            }
        } catch (_: Exception) {
        }
    }

    /** 超阈值时读尾段保留 KEEP_LINES 行重写（O(尾段) 而非 O(全文件)） */
    private fun trimIfLarge() {
        try {
            if (!file.exists() || file.length() <= MAX_BYTES) return
            val tail = readTail(MAX_BYTES)
            val lines = tail.split("\n").filter { it.isNotBlank() }
            val keep = lines.takeLast(KEEP_LINES)
            file.writeText(keep.joinToString("\n") + "\n")
        } catch (_: Exception) {
        }
    }

    /**
     * 只读文件末尾 maxBytes 字节；首行可能是被截断的半条记录，丢弃。
     * 这样内存占用与文件大小解耦。
     */
    private fun readTail(maxBytes: Long): String {
        if (!file.exists()) return ""
        val len = file.length()
        if (len <= 0) return ""
        val start = (len - maxBytes).coerceAtLeast(0)
        val buf = ByteArray((len - start).toInt())
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            var off = 0
            while (off < buf.size) {
                val n = raf.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
        }
        var text = String(buf, Charsets.UTF_8)
        if (start > 0) {
            // 从中间切开的首行不可靠，丢掉
            val nl = text.indexOf('\n')
            text = if (nl >= 0) text.substring(nl + 1) else ""
        }
        return text
    }

    fun load(): List<ChatEntry> = readEntries().takeLast(SHOW_LINES)

    /** 管理页用：读出全部记录（文件本身有 MAX_BYTES 上限，不会无限增长） */
    fun loadAll(): List<ChatEntry> = readEntries()

    private fun readEntries(): List<ChatEntry> {
        val list = ArrayList<ChatEntry>()
        try {
            if (!file.exists()) return list
            // 只读尾段（旧版 forEachLine 会全量读入）
            val text = if (file.length() > MAX_BYTES) readTail(MAX_BYTES) else file.readText()
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                try {
                    val o = JSONObject(line)
                    list.add(
                        ChatEntry(
                            o.optString("from", "user"),
                            o.optString("content", ""),
                            o.optLong("time", System.currentTimeMillis()),
                            o.optString("session", "")
                        )
                    )
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    /** 管理页保存：编辑内容 / 批量删除后整份重写（同步加锁，避免和运行中的追加互相覆盖） */
    fun saveAll(list: List<ChatEntry>) {
        try {
            synchronized(this) {
                val sb = StringBuilder()
                for (e in list) {
                    sb.append(
                        JSONObject()
                            .put("from", e.from)
                            .put("content", e.content)
                            .put("time", e.time)
                            .put("session", e.session)
                            .toString()
                    ).append('\n')
                }
                file.writeText(sb.toString())
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 原子读-改-写：管理页批量删除/编辑用。
     * 整个过程中持锁，运行中的 append 会排队等待，不会「读到旧列表再把新消息覆盖掉」。
     */
    fun mutate(block: (MutableList<ChatEntry>) -> Unit) {
        try {
            synchronized(this) {
                val list = readEntries().toMutableList()
                block(list)
                val sb = StringBuilder()
                for (e in list) {
                    sb.append(
                        JSONObject()
                            .put("from", e.from)
                            .put("content", e.content)
                            .put("time", e.time)
                            .put("session", e.session)
                            .toString()
                    ).append('\n')
                }
                file.writeText(sb.toString())
            }
        } catch (_: Exception) {
        }
    }

    /** 聊天记录的真实存储路径（显示给用户看） */
    fun path(): String = if (::file.isInitialized) file.absolutePath else ""

    fun clear() {
        try {
            synchronized(this) { file.writeText("") }
        } catch (_: Exception) {
        }
    }

    /** 导出用的纯文本 */
    fun text(): String {
        val sb = StringBuilder()
        load().forEach {
            val who = if (it.from == "user") "用户" else "机器人"
            sb.append("[$who] ").append(it.content).append("\n")
        }
        return sb.toString()
    }
}

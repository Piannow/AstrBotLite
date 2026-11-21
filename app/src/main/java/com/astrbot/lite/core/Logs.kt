package com.astrbot.lite.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 运行日志：内存环形缓冲 + 本地文件持久化（JSONL），用于“终端日志”页与排障
 *
 * 1.7.237 修复「日志无限堆积」：
 *  - 内存同时受行数（2000）与总字数（约 240k 字符）双重上限约束，不再只限行数；
 *  - 单条日志超过 2000 字自动截断（LLM 请求体/响应体这类超长 DEBUG 不再把缓冲撑爆）；
 *  - 落盘与裁剪移出同步块（原来每条日志都在锁内做文件 I/O，日志多时会把所有写日志线程卡住）；
 *  - 文件裁剪改成“按字节尾部读取”，不再每次 readLines 全文重写。
 */
object Logs {
    private val lines = ArrayDeque<LogLine>()
    private var chars = 0
    private val listeners = CopyOnWriteArrayList<(LogLine) -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var file: File? = null

    private const val MAX_LINES = 2000
    private const val MAX_CHARS = 240_000
    private const val MAX_LINE_CHARS = 2000
    /** 日志文件最大字节数：超出即砍掉最旧的一半，避免无限增长（机顶盒存储小） */
    private const val MAX_FILE_BYTES = 256 * 1024L

    fun init(ctx: Context) {
        val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "run.log")
        try {
            if (file?.exists() == true) {
                val loaded = mutableListOf<LogLine>()
                file!!.forEachLine { line ->
                    try {
                        val o = JSONObject(line)
                        loaded.add(
                            LogLine(
                                o.optString("level", "INFO"),
                                o.optString("text", ""),
                                o.optLong("time", 0L)
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
                synchronized(lines) {
                    lines.clear()
                    chars = 0
                    loaded.takeLast(MAX_LINES).forEach { push(it) }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun push(line: LogLine) {
        lines.addLast(line)
        chars += line.text.length + 32
        while (lines.size > MAX_LINES || chars > MAX_CHARS) {
            val first = lines.removeFirst()
            chars -= first.text.length + 32
        }
    }

    fun add(level: String, text: String) {
        val safe = if (text.length > MAX_LINE_CHARS) text.take(MAX_LINE_CHARS) + "…" else text
        val line = LogLine(level, safe, System.currentTimeMillis())
        synchronized(lines) {
            push(line)
        }
        // 落盘放到锁外：日志量大时不再让所有线程排队等文件 I/O
        persist(level, safe, line.time)
        listeners.forEach { it(line) }
    }

    private fun persist(level: String, text: String, time: Long) {
        try {
            file?.appendText(
                JSONObject()
                    .put("level", level)
                    .put("text", text)
                    .put("time", time)
                    .toString() + "\n"
            )
        } catch (_: Exception) {
        }
        trimLogFileIfLarge()
    }

    fun info(text: String) = add("INFO", text)
    fun warn(text: String) = add("WARN", text)
    fun err(text: String) = add("ERR", text)
    /** 调试级日志（详细调用追踪：请求体/响应体/LLM 改写等） */
    fun debug(text: String) = add("DEBUG", text)

    /** 日志文件超阈值时砍掉最旧的一半（只读尾部字节，避免整份重写） */
    private fun trimLogFileIfLarge() {
        val f = file ?: return
        try {
            if (!f.exists() || f.length() <= MAX_FILE_BYTES) return
            val keep = (MAX_FILE_BYTES / 2).toInt()
            val tail = ByteArray(keep)
            RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val start = (len - keep).coerceAtLeast(0L)
                raf.seek(start)
                var off = 0
                while (off < keep) {
                    val n = raf.read(tail, off, keep - off)
                    if (n <= 0) break
                    off += n
                }
                var begin = 0
                while (begin < off && tail[begin] != '\n'.code.toByte()) begin++
                if (begin >= off - 1) return
                val text = String(tail, begin + 1, off - begin - 1, Charsets.UTF_8)
                f.writeText(text)
            }
        } catch (_: Exception) {
        }
    }

    fun addListener(l: (LogLine) -> Unit) {
        // 去重：同一监听器重复注册会让同一条日志显示多遍，且持有旧页面的引用造成泄漏
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: (LogLine) -> Unit) {
        listeners.remove(l)
    }

    fun text(): String {
        val sb = StringBuilder()
        synchronized(lines) {
            lines.forEach {
                sb.append("[").append(fmt.format(Date(it.time))).append("] [")
                    .append(it.level).append("] ").append(it.text).append("\n")
            }
        }
        return sb.toString()
    }

    /** 只取末尾 maxChars 个字符（UI 首屏渲染用，避免一次性吞下整个缓冲） */
    fun textTail(maxChars: Int = 60_000): String {
        val full = text()
        return if (full.length <= maxChars) full else full.substring(full.length - maxChars)
    }

    fun clear() {
        synchronized(lines) {
            lines.clear()
            chars = 0
        }
        try {
            file?.writeText("")
        } catch (_: Exception) {
        }
    }
}

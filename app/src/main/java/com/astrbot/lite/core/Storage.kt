package com.astrbot.lite.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 统一存储根。
 *
 * 1.7.240 修复「从 1.7.222 升级后账号等信息没有迁移」：
 *  222 的根目录是 `Android/data/<包名>/`（getExternalFilesDir(null).parentFile），
 *  后续版本误改成了 `Android/data/<包名>/files/`，导致 222 的 state/accounts.json、
 *  plugins/、chats/ 全部“看不见”。现在按 222 的布局优先解析，并保留对 files/ 布局的兼容：
 *
 *  1. 已有数据的目录优先（按 222 布局 → files/ 布局 → 内部目录的顺序）
 *  2. 都没有数据时，默认走 222 的外部包根目录（可写），否则 files/，再否则内部目录
 *  3. 首次切换时仍从内部 filesDir 迁移老数据（1.7.190 之前的布局）
 */
object Storage {
    private const val TAG = "AstrBotLite"
    private const val MIGRATION_MARKER = ".migrated_v2"

    @Volatile
    private var base: File? = null

    /** 在 App 启动时调用一次；重复调用幂等 */
    @Synchronized
    fun init(ctx: Context) {
        if (base != null) return
        val app = ctx.applicationContext
        val root = resolveRoot(app)
        base = root
        migrateLegacy(app, root)
        ensureLayout(root)
        Log.i(TAG, "[存储] 数据根目录：${root.absolutePath}")
    }

    /** 存储根目录 */
    fun root(): File {
        val r = base ?: File(".").absoluteFile
        if (!r.exists()) r.mkdirs()
        return r
    }

    /** root 下的子目录，自动创建 */
    fun dir(name: String): File {
        val d = File(root(), name)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun resolveRoot(app: Context): File {
        val ext: File? = try {
            app.getExternalFilesDir(null)
        } catch (_: Throwable) {
            null
        }
        val candidates = mutableListOf<File>()
        // 222 布局：Android/data/<包名>/
        ext?.parentFile?.let { candidates.add(it) }
        // 现布局：Android/data/<包名>/files/
        if (ext != null) candidates.add(ext)
        // 内部兜底
        candidates.add(app.filesDir)

        val withData = candidates.firstOrNull { hasData(it) }
        if (withData != null) return withData
        for (d in candidates) {
            if (probeWritable(d)) return d
        }
        return app.filesDir
    }

    /** 目录里是否已有本应用数据（避免把空的包根/空 files/ 当成数据目录） */
    private fun hasData(d: File): Boolean {
        if (!d.isDirectory) return false
        for (marker in listOf("state", "plugins", "chats", "personas")) {
            val sub = File(d, marker)
            if (sub.isDirectory && sub.listFiles()?.isNotEmpty() == true) return true
        }
        for (name in listOf("state/accounts.json", "bot_state.json")) {
            if (File(d, name).isFile) return true
        }
        return false
    }

    private fun probeWritable(d: File): Boolean {
        return try {
            if (!d.isDirectory && !d.mkdirs()) return false
            val probe = File(d, ".probe_${System.nanoTime()}")
            probe.writeText("ok")
            val ok = probe.readText() == "ok"
            probe.delete()
            ok
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensureLayout(root: File) {
        for (n in listOf(
            "plugins", "plugins_python", "market", "downloads", "exports",
            "personas", "chats", "profiles", "logs", "media", "state",
        )) {
            File(root, n).mkdirs()
        }
    }

    /**
     * 1.7.190 之前的老数据在内部 filesDir，一次性搬到目标根目录。
     * 已存在同名目录时跳过（保留用户现有数据），失败不写标记，下次启动重试。
     */
    private fun migrateLegacy(ctx: Context, root: File) {
        if (File(root, MIGRATION_MARKER).isFile()) return
        if (root == ctx.filesDir) return
        val internal = ctx.filesDir
        val dirMap = listOf(
            "plugins" to "plugins",
            "apy" to "plugins_python",
            "exports" to "exports",
            "personas" to "personas",
            "chats" to "chats",
            "profiles" to "profiles",
            "logs" to "logs",
            "media_tmp" to "media",
            "pool" to "state/pool",
            "thoughts" to "state/thoughts",
            "knowledge" to "state/knowledge",
        )
        val fileMap = listOf(
            "bot_state.json" to "state/bot_state.json",
            "qq_menu_panel_cleaned" to "state/qq_menu_panel_cleaned",
            "qq_panel_sync_ver" to "state/qq_panel_sync_ver",
        )
        var migrated = 0
        var failed = false
        for ((oldName, newRel) in dirMap) {
            val src = File(internal, oldName)
            if (!src.isDirectory) continue
            val dst = File(root, newRel)
            if (dst.isDirectory && dst.listFiles()?.isNotEmpty() == true) continue
            if (copyDir(src, dst)) migrated++ else failed = true
        }
        for ((oldName, newRel) in fileMap) {
            val src = File(internal, oldName)
            if (!src.isFile) continue
            val dst = File(root, newRel)
            if (dst.exists()) continue
            if (copyFile(src, dst)) migrated++ else failed = true
        }
        val cacheSrc = File(ctx.cacheDir, "astrbot_market_cache.json")
        if (cacheSrc.isFile) {
            val dst = File(root, "market/astrbot_market_cache.json")
            if (!dst.exists()) {
                if (copyFile(cacheSrc, dst)) migrated++ else failed = true
            }
        }
        if (failed) {
            Log.w(TAG, "[存储] 老数据迁移部分失败，下次启动自动重试")
            return
        }
        try {
            File(root, MIGRATION_MARKER).writeText(System.currentTimeMillis().toString())
        } catch (_: Exception) {
        }
        if (migrated > 0) Log.i(TAG, "[存储] 老数据迁移完成：$migrated 项（旧目录保留作备份）")
    }

    private fun copyDir(src: File, dst: File): Boolean {
        return try {
            val tmp = File(dst.parentFile, dst.name + ".tmp")
            tmp.deleteRecursively()
            if (!tmp.mkdirs()) return false
            src.walkTopDown().forEach { f ->
                val rel = f.toRelativeString(src)
                val target = if (rel.isEmpty()) tmp else File(tmp, rel)
                if (f.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    f.copyTo(target, overwrite = true)
                }
            }
            if (dst.isDirectory && dst.listFiles()?.isEmpty() == true) dst.delete()
            if (!tmp.renameTo(dst)) {
                tmp.deleteRecursively()
                Log.w(TAG, "[存储] 迁移改名失败：${src.name}")
                return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "[存储] 迁移目录失败 ${src.name}：${t.message}")
            false
        }
    }

    private fun copyFile(src: File, dst: File): Boolean {
        return try {
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, dst.name + ".tmp")
            tmp.delete()
            src.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(dst)) {
                tmp.delete()
                Log.w(TAG, "[存储] 迁移改名失败：${src.name}")
                return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "[存储] 迁移文件失败 ${src.name}：${t.message}")
            false
        }
    }
}

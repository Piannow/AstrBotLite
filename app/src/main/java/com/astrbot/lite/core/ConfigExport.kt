package com.astrbot.lite.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import java.io.File
import org.json.JSONObject
import androidx.core.content.FileProvider

/**
 * 配置一键分享 / 一键导入（1.7.173）
 *
 * 设计：把 SharedPreferences 的全部字符串/布尔/整型键值打成 JSON，
 * 生成 `astrbot_lite_config.json` 放到公共下载目录，用 Intent 分享出去。
 * 接收方通过 SAF 选文件 → 应用读取 → 逐项写回 Config → 重启生效。
 *
 * 覆盖范围：所有通过 `sp.edit().put*` 持久化的键。新字段加进来即可自动包含。
 */
object ConfigExport {

    private const val FILE_PROVIDER_AUTH = com.astrbot.lite.BuildConfig.APPLICATION_ID + ".fileprovider"

    /** 应用内识别配置文件用的魔数（防误导入其它 JSON） */
    private const val MAGIC = "astrbot_lite_config"
    private const val VERSION = 2
    private const val FILE_NAME = "astrbot_lite_config.json"

    /** 导出当前配置到文件，返回文件绝对路径（失败返回 null） */
    fun export(ctx: Context): String? {
        return try {
            val json = JSONObject()
            json.put("magic", MAGIC)
            json.put("version", VERSION)
            json.put("exported_at", System.currentTimeMillis())
            json.put("device", Build.MANUFACTURER + " " + Build.MODEL)
            json.put("app_version", com.astrbot.lite.BuildConfig.VERSION_NAME)

            val raw = ctx.getSharedPreferences("astrbot_lite", Context.MODE_PRIVATE)
            val all = raw.all
            val str = JSONObject()
            val bool = JSONObject()
            val int = JSONObject()
            val long = JSONObject()
            val float = JSONObject()
            // 简单判定类型：Config.kt 里键名约定，用 sp 直接读再判断
            for ((k, v) in all) {
                when (v) {
                    is String -> str.put(k, v)
                    is Boolean -> bool.put(k, v)
                    is Int -> int.put(k, v)
                    is Long -> long.put(k, v)
                    is Float -> float.put(k, v)
                    is Short -> int.put(k, (v as Short).toInt())
                    is Byte -> int.put(k, (v as Byte).toInt())
                }
            }
            json.put("string", str)
            json.put("boolean", bool)
            json.put("int", int)
            json.put("long", long)
            json.put("float", float)
            // v2：AI 池 + 账号快照（"完美迁移"）
            try { json.put("ai_pool", AiPool.exportSnapshot()) } catch (_: Throwable) {}
            try { json.put("accounts", AccountManager.exportSnapshot()) } catch (_: Throwable) {}

            val dir = getDownloadDir(ctx)
            val target = File(dir, FILE_NAME)
            target.writeText(json.toString(2))
            target.absolutePath
        } catch (e: Exception) {
            Toast.makeText(ctx, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    /** 用 Intent 把配置文件分享出去（系统分享面板：微信/QQ/其它） */
    fun shareFile(ctx: Context, filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) return false
            val uri = FileProvider.getUriForFile(ctx, FILE_PROVIDER_AUTH, file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "分享配置")
            ctx.startActivity(chooser)
            true
        } catch (e: Exception) {
            Toast.makeText(ctx, "分享失败: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * 从 SAF Uri 导入配置。
     * @return 是否成功；失败时 Toast 提示
     */
    fun import(ctx: Context, uri: Uri): Boolean {
        return try {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            ?: run {
                Toast.makeText(ctx, "读取文件失败", Toast.LENGTH_LONG).show()
                return false
            }
            val json = JSONObject(text)
            if (json.optString("magic") != MAGIC) {
                Toast.makeText(ctx, "不是有效的配置文件（缺少 magic 标识）", Toast.LENGTH_LONG).show()
                return false
            }
            val sp = ctx.getSharedPreferences("astrbot_lite", Context.MODE_PRIVATE).edit()
            applySection(json.optJSONObject("string")) { k, v -> sp.putString(k, v as String) }
            applySection(json.optJSONObject("boolean")) { k, v -> sp.putBoolean(k, v as Boolean) }
            applySection(json.optJSONObject("int")) { k, v -> sp.putInt(k, v as Int) }
            applySection(json.optJSONObject("long")) { k, v -> sp.putLong(k, v as Long) }
            applySection(json.optJSONObject("float")) { k, v -> sp.putFloat(k, v as Float) }
            sp.apply()
            // v2：恢复 AI 池 + 账号快照
            try { json.optJSONObject("ai_pool")?.let { AiPool.importSnapshot(it) } } catch (e: Exception) { android.util.Log.w("ConfigExport", "ai_pool: ${e.message}") }
            try { json.optJSONObject("accounts")?.let { AccountManager.importSnapshot(it) } } catch (e: Exception) { android.util.Log.w("ConfigExport", "accounts: ${e.message}") }
            Toast.makeText(ctx, "配置导入成功，重启应用生效", Toast.LENGTH_LONG).show()
            true
        } catch (e: Exception) {
            Toast.makeText(ctx, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private inline fun applySection(
        obj: JSONObject?,
        crossinline put: (String, Any) -> Unit
    ) {
        if (obj == null) return
        for (k in obj.keys()) {
            when (val v = obj.get(k)) {
                is Boolean -> put(k, v)
                is Int -> put(k, v)
                is Long -> put(k, v)
                is Float -> put(k, v)
                // 1.7.200：org.json 解析小数返回 Double，不补此分支浮点配置导入时被静默丢弃
                is Double -> put(k, v.toFloat())
                is String -> put(k, v)
            }
        }
    }

    /** 配置导出目录（1.7.190 改版）：统一存储根下 exports/（Android/data/com.astrbot.lite/exports），
     *  用户要求导出配置集中在数据目录内方便查找；
     *  数据根不可写的极端情况回退公共下载目录（FileProvider 已覆盖，分享不受影响）。 */
    private fun getDownloadDir(ctx: Context): File {
        val d = Storage.dir("exports")
        if (d.isDirectory) return d
        val pub = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AstrBotLite")
        if (pub.exists() || pub.mkdirs()) return pub
        return ctx.filesDir
    }
}

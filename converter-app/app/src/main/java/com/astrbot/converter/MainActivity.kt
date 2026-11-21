package com.astrbot.converter

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * AstrBot 插件转换器：把电脑上 AstrBot 插件（Python）转换为手机 AstrBot Lite 可用插件。
 * 支持：ZIP 导入 / 链接导入 / 粘贴源码 / AstrBot 插件市场挑选 → 编辑 → 导出 JSON/ZIP。
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val PICK = 1001
    private val SAVE_JSON = 2001
    private val SAVE_ZIP = 2002

    private lateinit var etLink: EditText
    private lateinit var etMarketUrl: EditText
    private lateinit var tvMeta: TextView
    private lateinit var cmdContainer: LinearLayout
    private lateinit var tvLog: TextView
    private lateinit var tvJson: TextView
    private lateinit var spTrigger: Spinner

    private var result: ConvResult? = null
    private val cmdRows = mutableListOf<Pair<EditText, EditText>>() // (固定回复, AI指令)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    // ---------------- 界面 ----------------
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(root) }
        root.setPadding(dp(14), dp(14), dp(14), dp(24))

        // 标题
        root.addView(title("AstrBot 插件转换器", 22f))
        root.addView(subtitle("把电脑上能用的 AstrBot 插件，转换成手机上 AstrBot Lite 能用的插件"))

        // ① 导入
        root.addView(section("① 导入插件"))
        val row1 = row()
        root.addView(row1)
        row1.addView(btn("选择 ZIP/文件") { selectFile() }, half())
        row1.addView(btn("粘贴源码") { pasteDialog() }, half())
        root.addView(subtitle("支持 .zip（含 main.py）/.py/.json（Lite 插件）等"))

        root.addView(EditText(this).apply {
            hint = "插件链接（.zip / .py / GitHub 仓库地址）"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
        }.also { etLink = it }, wrapFull().apply { topMargin = dp(8) })
        root.addView(btn("下载并转换链接") { linkImport() }, wrapFull())

        // ② 插件市场
        root.addView(section("② AstrBot 插件市场（像 AstrBot 一样挑选）"))
        root.addView(EditText(this).apply {
            setText(Market.DEFAULT_URL)
            hint = "插件市场地址"
            maxLines = 1
            textSize = 12f
        }.also { etMarketUrl = it }, wrapFull())
        root.addView(btn("打开插件市场挑选插件") { openMarket() }, wrapFull())

        // ③ 转换结果
        root.addView(section("③ 转换结果与编辑"))
        tvMeta = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.color.textMain))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(tvMeta, wrapFull())
        cmdContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(cmdContainer, wrapFull())
        root.addView(btn("＋ 添加命令") { addCommandRow("/新命令") }, wrapFull())

        root.addView(TextView(this).apply {
            text = "转换日志"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color(R.color.textMain))
            setPadding(0, dp(10), 0, dp(2))
        })
        tvLog = TextView(this).apply {
            textSize = 12f
            setTextColor(color(R.color.textSub))
            setBackgroundColor(color(R.color.bg))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(ScrollView(this).apply {
            addView(tvLog)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)))

        // ④ 导出
        root.addView(section("④ 导出"))
        val row4 = row()
        root.addView(row4)
        row4.addView(btn("保存 JSON") { saveJson() }, quarter())
        row4.addView(btn("保存 ZIP") { saveZip() }, quarter())
        row4.addView(btn("分享") { shareJson() }, quarter())
        row4.addView(btn("复制") { copyJson() }, quarter())
        root.addView(subtitle("在 AstrBot Lite 的「插件」页点「导入本地插件」，选择保存的 JSON 或 ZIP 即可安装。"))

        tvJson = TextView(this).apply {
            textSize = 10f
            isSingleLine = false
            setTextColor(color(R.color.termText))
            setBackgroundColor(color(R.color.termBg))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(ScrollView(this).apply {
            addView(tvJson)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)))

        setContentView(scroll)
    }

    // ---------------- 导入 ----------------
    private fun selectFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, PICK)
    }

    private fun pasteDialog() {
        val main = EditText(this).apply {
            hint = "粘贴 main.py 源码"
            minLines = 5
            gravity = Gravity.TOP
        }
        val meta = EditText(this).apply {
            hint = "（可选）粘贴 metadata.yaml 内容"
            minLines = 2
            gravity = Gravity.TOP
        }
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(main)
            addView(meta, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        AlertDialog.Builder(this)
            .setTitle("粘贴插件源码")
            .setView(v)
            .setPositiveButton("转换") { _, _ ->
                val m = main.text.toString().trim()
                val y = meta.text.toString().trim()
                if (m.isEmpty() && y.isEmpty()) {
                    toast("请至少粘贴 main.py 内容")
                    return@setPositiveButton
                }
                if (m.isNotEmpty()) parseSources(m, y.ifEmpty { null }, "粘贴的源码")
                else toast("只有 metadata 无法转换，请粘贴 main.py")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun linkImport() {
        val url = etLink.text.toString().trim()
        if (url.isEmpty()) { toast("请先粘贴插件链接"); return }
        toast("正在下载...")
        Thread {
            try {
                val low = url.lowercase()
                when {
                    low.endsWith(".zip") || low.endsWith(".py") -> {
                        val b = Net.download(url) ?: throw Exception("下载失败")
                        handler.post { importBytes(url, b) }
                    }
                    url.contains("github.com") -> {
                        val zipUrl = Net.githubRepoZip(url) ?: throw Exception("无法解析 GitHub 地址")
                        var b = Net.download(zipUrl)
                        if (b == null) b = Net.download(zipUrl.replace("/master.zip", "/main.zip"))
                        if (b == null) throw Exception("下载插件包失败")
                        handler.post { importBytes(url, b) }
                    }
                    else -> {
                        val b = Net.download(url) ?: throw Exception("下载失败")
                        handler.post { importBytes(url, b) }
                    }
                }
            } catch (e: Exception) {
                handler.post { toast("链接导入失败：${e.message}") }
            }
        }.start()
    }

    /** 按文件类型分派导入 */
    private fun importBytes(name: String, bytes: ByteArray) {
        val low = name.lowercase()
        when {
            low.endsWith(".zip") -> {
                val zc = ZipUtil.extractPluginFiles(bytes)
                when {
                    zc.pluginJson.isNotBlank() -> importLiteJson(zc.pluginJson)
                    zc.mainPy.isNotBlank() -> parseSources(zc.mainPy, zc.metaYaml, name)
                    else -> toast("ZIP 中未找到 main.py 或 plugin.json")
                }
            }
            low.endsWith(".json") -> importLiteJson(String(bytes, Charsets.UTF_8))
            low.endsWith(".py") || low.endsWith(".txt") -> parseSources(String(bytes, Charsets.UTF_8), null, name)
            low.endsWith(".yaml") || low.endsWith(".yml") -> parseSources("", String(bytes, Charsets.UTF_8), name)
            else -> {
                val s = String(bytes, Charsets.UTF_8)
                if (Regex("""register\s*\(|from astrbot|import astrbot""").containsMatchIn(s)) parseSources(s, null, name)
                else toast("不支持的文件类型：$name")
            }
        }
    }

    // ---------------- 插件市场 ----------------
    private fun openMarket() {
        val url = etMarketUrl.text.toString().trim().ifEmpty { Market.DEFAULT_URL }
        toast("正在加载插件市场...")
        Thread {
            val text = Net.downloadText(url)
            handler.post {
                if (text == null) { toast("加载市场失败：$url"); return@post }
                val list = Market.parse(text)
                if (list.isEmpty()) { toast("市场没有返回插件（格式可能已变化）"); return@post }
                showMarketDialog(list)
            }
        }.start()
    }

    private fun showMarketDialog(list: List<MarketPlugin>) {
        val marketAdapter = object : BaseAdapter() {
            override fun getCount(): Int = list.size
            override fun getItem(p: Int): Any = list[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val m = list[p]
                val v = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                }
                v.addView(TextView(this@MainActivity).apply {
                    text = m.displayName
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(color(R.color.textMain))
                })
                v.addView(TextView(this@MainActivity).apply {
                    text = m.desc.ifBlank { "（无描述）" }
                    textSize = 12f
                    setTextColor(color(R.color.textSub))
                })
                return v
            }
        }
        val lv = ListView(this)
        lv.adapter = marketAdapter
        AlertDialog.Builder(this)
            .setTitle("AstrBot 插件市场（${list.size} 个）· 点击转换")
            .setView(lv)
            .setNegativeButton("关闭", null)
            .show()
        lv.setOnItemClickListener { _, _, position, _ ->
            convertMarket(list[position])
        }
    }

    private fun convertMarket(p: MarketPlugin) {
        val zipUrl = p.zipUrl
        if (zipUrl == null) { toast("该插件没有可用的下载地址"); return }
        toast("正在下载并转换 ${p.name} ...")
        Thread {
            var bytes = Net.download(zipUrl)
            if (bytes == null && zipUrl.contains("/master.zip")) {
                bytes = Net.download(zipUrl.replace("/master.zip", "/main.zip"))
            }
            if (bytes == null) {
                handler.post { toast("下载失败：$zipUrl") }
                return@Thread
            }
            val zc = ZipUtil.extractPluginFiles(bytes)
            handler.post {
                when {
                    zc.mainPy.isNotBlank() -> parseSources(zc.mainPy, zc.metaYaml, p.name)
                    zc.pluginJson.isNotBlank() -> importLiteJson(zc.pluginJson)
                    else -> toast("插件包中没有找到 main.py / plugin.json")
                }
            }
        }.start()
    }

    // ---------------- 结果处理 ----------------
    private fun parseSources(main: String, meta: String?, label: String) {
        val r = Converter.convert(main, meta)
        showResult(r)
        toast("已转换：${r.name}（${r.commands.size} 个命令）")
    }

    private fun importLiteJson(json: String) {
        val r = ConvResult("", "", "", "", "", mutableListOf(), mutableListOf())
        if (r.fromLiteJson(json)) showResult(r)
        else toast("不是有效的插件 JSON")
    }

    private fun showResult(r: ConvResult) {
        result = r
        cmdRows.clear()
        cmdContainer.removeAllViews()
        tvMeta.text = "${r.name}  v${r.version}  by ${r.author.ifBlank { "?" }}\n${r.desc}"
        val tIdx = ConvResult.TRIGGER_VALUES.indexOfFirst { it == r.trigger }.coerceAtLeast(0)
        spTrigger.setSelection(tIdx)
        r.commands.forEach { addCommandRow(it.match, it.desc, it.reply, it.prompt, it.note) }
        tvLog.text = r.log.joinToString("\n")
        tvJson.text = r.toLiteJson()
    }

    private fun addCommandRow(match: String, desc: String = "", reply: String = "", prompt: String = "", note: String = "") {
        val etReply = EditText(this).apply {
            hint = "固定回复（留空则用下方 AI 指令）"
            setText(reply)
            textSize = 13f
            maxLines = 2
        }
        val etPrompt = EditText(this).apply {
            hint = "AI 指令（prompt 模式）"
            setText(prompt)
            textSize = 13f
            maxLines = 3
        }
        val pair = etReply to etPrompt

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.card))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        }
        val head = row()
        card.addView(head)
        head.addView(TextView(this).apply {
            text = "$match  ${desc.ifBlank { "" }}".trim()
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color(R.color.textMain))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Button(this).apply {
            text = "删除"
            textSize = 11f
            setOnClickListener {
                cmdContainer.removeView(card)
                cmdRows.remove(pair)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (note.isNotBlank()) card.addView(TextView(this).apply {
            text = "⚠ $note"
            textSize = 11f
            setTextColor(color(R.color.warn))
        })
        card.addView(etReply, wrapFull().apply { topMargin = dp(4) })
        card.addView(etPrompt, wrapFull().apply { topMargin = dp(4) })
        cmdContainer.addView(card, lp)
        cmdRows.add(pair)
        result?.let { r -> while (r.commands.size < cmdRows.size) r.commands.add(ConvCommand("/新命令", "", "", "", "")) }
    }

    /** 把编辑框内容写回 result */
    private fun collectEdits() {
        val r = result ?: return
        for (i in cmdRows.indices) {
            if (i >= r.commands.size) r.commands.add(ConvCommand("/新命令", "", "", "", ""))
            r.commands[i].reply = cmdRows[i].first.text.toString().trim()
            r.commands[i].prompt = cmdRows[i].second.text.toString().trim()
        }
    }

    // ---------------- 导出 ----------------
    private fun currentJson(): String {
        collectEdits()
        return result?.toLiteJson() ?: "{}"
    }

    private fun saveJson() {
        if (result == null) { toast("请先导入并转换插件"); return }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/json"
        i.putExtra(Intent.EXTRA_TITLE, "${result!!.id}.json")
        startActivityForResult(i, SAVE_JSON)
    }

    private fun saveZip() {
        if (result == null) { toast("请先导入并转换插件"); return }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/zip"
        i.putExtra(Intent.EXTRA_TITLE, "${result!!.id}.zip")
        startActivityForResult(i, SAVE_ZIP)
    }

    private fun shareJson() {
        if (result == null) { toast("请先导入并转换插件"); return }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AstrBot Lite 插件 ${result!!.name}")
            putExtra(Intent.EXTRA_TEXT, currentJson())
        }
        startActivity(Intent.createChooser(i, "分享插件 JSON"))
    }

    private fun copyJson() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("plugin", currentJson()))
        toast("已复制 JSON")
    }

    // ---------------- 回调 ----------------
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri: Uri = data.data!!
        when (requestCode) {
            PICK -> {
                val name = displayName(uri)
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) toast("读取文件失败")
                    else importBytes(name, bytes)
                } catch (e: Exception) {
                    toast("读取文件失败：${e.message}")
                }
            }
            SAVE_JSON -> try {
                contentResolver.openOutputStream(uri)?.use { it.write(currentJson().toByteArray(Charsets.UTF_8)) }
                toast("已保存 ${result?.id ?: "插件"}.json")
            } catch (e: Exception) {
                toast("保存失败：${e.message}")
            }
            SAVE_ZIP -> try {
                val zip = ZipUtil.buildPluginZip(currentJson(), result?.id ?: "plugin")
                contentResolver.openOutputStream(uri)?.use { it.write(zip) }
                toast("已保存 ${result?.id ?: "插件"}.zip")
            } catch (e: Exception) {
                toast("保存失败：${e.message}")
            }
        }
    }

    private fun displayName(uri: Uri): String {
        var name = "plugin.zip"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = c.getString(idx) ?: name
            }
        }
        return name
    }

    // ---------------- 工具 ----------------
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int): Int = resources.getColor(id, null)
    private fun title(t: String, size: Float): TextView = TextView(this).apply {
        text = t
        textSize = size
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(color(R.color.textMain))
    }
    private fun subtitle(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(color(R.color.textSub))
        setPadding(0, dp(2), 0, dp(2))
    }
    private fun section(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(color(R.color.colorPrimary))
        setPadding(0, dp(16), 0, dp(4))
    }
    private fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun btn(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }
    private fun half(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = dp(2)
        marginEnd = dp(2)
    }
    private fun quarter(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = dp(2)
        marginEnd = dp(2)
    }
    private fun wrapFull(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
}

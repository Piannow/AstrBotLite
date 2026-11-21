package com.astrbot.lite.ui.tabs

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.astrbot.lite.BuildConfig
import com.astrbot.lite.R
import com.astrbot.lite.core.ChatStore
import com.astrbot.lite.core.Config
import com.astrbot.lite.core.ConfigExport
import com.astrbot.lite.core.EmbeddingEngine
import com.astrbot.lite.core.LogLine
import com.astrbot.lite.core.Logs
import com.astrbot.lite.core.PluginAutoInstaller
import com.astrbot.lite.ui.ChatHistoryActivity
import com.astrbot.lite.ui.DpadFocus
import com.astrbot.lite.ui.InitActivity
import com.astrbot.lite.ui.KnowledgeActivity
import com.astrbot.lite.ui.LogViewActivity
import com.astrbot.lite.ui.TestConsoleActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置标签页（按 1.7.222 反编译源码还原）：
 * 顶栏「设置 + 版本号」；左侧滚动区（功能开关 / 名单管理 / 主日志台 / 对话记录 / 关于），
 * 右侧 96dp 竖排工具按钮列。
 */
class SettingsTab(private val act: Activity) : LinearLayout(act) {

    private val handler = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private lateinit var tvLog: TextView
    private var tvDayLog: TextView? = null
    private lateinit var switchKnowledge: Switch
    private lateinit var switchWhitelistOn: Switch
    private lateinit var switchBlacklistOn: Switch
    private lateinit var rowWhitelist: View
    private lateinit var rowBlacklist: View
    private lateinit var etWhitelist: EditText
    private lateinit var etBlacklist: EditText
    private lateinit var btnWhite: Button
    private lateinit var btnBlack: Button
    private var listSectionWrapper: LinearLayout? = null
    private var listMode: String = "white"

    private val lpWrap = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private val lpHalf = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private var logListenerAttached = false
    private val logListener: (LogLine) -> Unit = { line ->
        handler.post {
            tvLog.append(
                "[" + fmt.format(Date(line.time)) + "] [" + line.level + "] " + line.text + "\n"
            )
            // 1.7.237：界面日志也限长（约 2 万字），长时间运行不再无限堆积
            val t = tvLog.text
            if (t.length > 20_000) tvLog.text = t.subSequence(t.length - 20_000, t.length)
        }
    }

    companion object {
        const val REQ_IMPORT_CONFIG = 7001

        fun handleImportConfigResult(ctx: Context, result: Int, data: Intent?) {
            if (result != Activity.RESULT_OK) return
            val uri = data?.data ?: return
            val ok = ConfigExport.import(ctx, uri)
            if (!ok) return
            val act = ctx as? Activity ?: return
            val missing = try {
                PluginAutoInstaller.findMissingPlugins()
            } catch (_: Throwable) {
                emptyList()
            }
            if (missing.isEmpty()) {
                Toast.makeText(act, "配置导入完成，重启应用生效", Toast.LENGTH_LONG).show()
            } else {
                PluginAutoInstaller.installAll(act, missing) { okCnt, fail ->
                    act.runOnUiThread {
                        val msg = if (fail.isEmpty()) {
                            "配置导入完成，已补齐 $okCnt 个插件，重启应用生效"
                        } else {
                            "配置导入完成：补齐 $okCnt 个，失败 ${fail.size} 个（${fail.joinToString("、")}）"
                        }
                        Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))

        // 顶栏：设置 + 版本号
        val topBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        topBar.addView(TextView(context).apply {
            text = "设置"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.textMain))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(TextView(context).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            textSize = 12f
            setTextColor(color(R.color.colorPrimary))
        })

        // 主体：左侧滚动设置项 + 右侧竖排工具按钮
        val mainRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        addView(mainRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val scroll = ScrollView(context)
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        scroll.addView(col, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        mainRow.addView(scroll, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        ))

        val toolCol = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        mainRow.addView(toolCol, LinearLayout.LayoutParams(
            dp(96), ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        addToolBtn(toolCol, "知识库") {
            act.startActivity(Intent(act, KnowledgeActivity::class.java))
        }
        addToolBtn(toolCol, "测试") {
            act.startActivity(Intent(act, TestConsoleActivity::class.java))
        }
        addToolBtn(toolCol, "知识库模型") { showEmbeddingConfigDialog() }
        addToolBtn(toolCol, "名单管理") { toggleListSection() }
        addToolBtn(toolCol, "导出配置") { shareConfig() }
        addToolBtn(toolCol, "导入配置") { importConfig() }

        // 功能开关
        col.addView(section("功能开关"))
        col.addView(TextView(context).apply {
            text = "AI 池常驻启用（无需开关）：特性分配后与插件配置页双向同步"
            textSize = 11f
            setTextColor(0xFF7B1FA2.toInt())
            setPadding(0, dp(2), 0, dp(6))
        })
        switchKnowledge = makeSwitch(col, "知识库", Config.knowledgeEnabled) {
            Config.knowledgeEnabled = it
        }.second

        // 名单管理（默认隐藏，右侧「名单管理」按钮切换）
        val wrapper = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
        }
        listSectionWrapper = wrapper
        col.addView(wrapper)
        wrapper.addView(section("名单管理"))
        wrapper.addView(TextView(context).apply {
            text = "名单填「用户ID(openid)」，不是QQ号——QQ官方机器人接口不下发QQ号。" +
                "让对方给机器人发「我的ID」，就能拿到那串ID（逗号/空格/换行分隔可填多个）。" +
                "白名单和黑名单互斥：开启一个会自动关闭另一个。"
            textSize = 12f
            setTextColor(resources.getColor(R.color.textSub, null))
            setPadding(0, dp(2), 0, dp(4))
        })
        val listTabRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        btnWhite = Button(context).apply {
            text = "白名单"
            textSize = 12f
            isFocusable = true
            DpadFocus.highlight(this)
        }
        btnBlack = Button(context).apply {
            text = "黑名单"
            textSize = 12f
            isFocusable = true
            DpadFocus.highlight(this)
        }
        listTabRow.addView(btnWhite, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        listTabRow.addView(btnBlack, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        wrapper.addView(listTabRow)

        val listPanel = LinearLayout(context).apply { orientation = VERTICAL }
        wrapper.addView(listPanel)
        listMode = if (!Config.whitelistOn && Config.blacklistOn) "black" else "white"
        val wPair = makeSwitch(listPanel, "启用白名单", Config.whitelistOn) { on ->
            Config.whitelistOn = on
            if (on) {
                Config.blacklistOn = false
                switchBlacklistOn.isChecked = false
            }
            refreshListToggle()
        }
        rowWhitelist = wPair.first
        switchWhitelistOn = wPair.second
        etWhitelist = EditText(context).apply {
            hint = "白名单用户ID(openid)，逗号/空格/换行分隔；留空=不限制"
            maxLines = 2
            isSingleLine = false
            textSize = 13f
            setText(Config.whitelist)
        }
        listPanel.addView(etWhitelist)
        val bPair = makeSwitch(listPanel, "启用黑名单", Config.blacklistOn) { on ->
            Config.blacklistOn = on
            if (on) {
                Config.whitelistOn = false
                switchWhitelistOn.isChecked = false
            }
            refreshListToggle()
        }
        rowBlacklist = bPair.first
        switchBlacklistOn = bPair.second
        etBlacklist = EditText(context).apply {
            hint = "黑名单用户ID(openid)，逗号/空格/换行分隔"
            maxLines = 2
            isSingleLine = false
            textSize = 13f
            setText(Config.blacklist)
        }
        listPanel.addView(etBlacklist)
        btnWhite.setOnClickListener {
            listMode = "white"
            refreshListToggle()
        }
        btnBlack.setOnClickListener {
            listMode = "black"
            refreshListToggle()
        }
        refreshListToggle()
        wrapper.addView(Button(context).apply {
            text = "保存名单"
            setOnClickListener {
                Config.whitelist = etWhitelist.text.toString().trim()
                Config.blacklist = etBlacklist.text.toString().trim()
                toast("名单已保存")
            }
        }, lpWrap)

        // 主日志台
        col.addView(section("主日志台"))
        tvLog = TextView(context).apply {
            setBackgroundColor(resources.getColor(R.color.termBg, null))
            setTextColor(resources.getColor(R.color.termText, null))
            textSize = 11f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            typeface = Typeface.MONOSPACE
        }
        val logScroll = ScrollView(context).apply {
            addView(tvLog)
            minimumHeight = dp(180)
        }
        col.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(220)
        ))
        val logRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        col.addView(logRow)
        logRow.addView(Button(context).apply {
            text = "刷新日志"
            setOnClickListener { showLog() }
        }, lpHalf)
        logRow.addView(Button(context).apply {
            text = "复制日志"
            setOnClickListener {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", Logs.text()))
                toast("已复制")
            }
        }, lpHalf)
        logRow.addView(Button(context).apply {
            text = "清空"
            setOnClickListener {
                Logs.clear()
                tvLog.text = ""
            }
        }, lpHalf)
        col.addView(Button(context).apply {
            text = "打开完整日志（全屏滑动）"
            setOnClickListener { act.startActivity(Intent(act, LogViewActivity::class.java)) }
        }, lpWrap)

        // 对话记录
        col.addView(section("对话记录"))
        tvDayLog = TextView(context).apply {
            setBackgroundColor(resources.getColor(R.color.termBg, null))
            setTextColor(resources.getColor(R.color.termText, null))
            textSize = 10f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            typeface = Typeface.MONOSPACE
            text = Logs.text().takeLast(500)
        }
        val dayLogScroll = ScrollView(context).apply {
            addView(tvDayLog)
            minimumHeight = dp(120)
        }
        col.addView(dayLogScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(140)
        ))
        col.addView(TextView(context).apply {
            text = "存储位置：" + ChatStore.path()
            textSize = 10f
            setTextColor(color(R.color.textSub))
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, dp(2))
        })
        col.addView(Button(context).apply {
            text = "管理聊天记录（按群/用户分组 · 多选删除 · 编辑内容）"
            setOnClickListener {
                act.startActivity(Intent(act, ChatHistoryActivity::class.java))
            }
        }, lpWrap)

        // 关于
        col.addView(section("关于"))
        col.addView(TextView(context).apply {
            text = "AstrBot Lite v${BuildConfig.VERSION_NAME}\n" +
                "· 接入 QQ 官方机器人（q.qq.com 开放平台）\n" +
                "· 兼容 OpenAI / DeepSeek / 智谱 / 火山方舟 等 API\n" +
                "· AI池 / 知识库 / 内心思考 / 好感度系统\n" +
                "· 白名单 / 黑名单\n" +
                "· 图生图 / AI生视频 / 字词替换插件\n" +
                "· 模拟QQ测试环境\n" +
                "· 人设卡：JSON 文本，可导入导出分享\n"
            textSize = 12f
            setTextColor(color(R.color.textSub))
        })
        col.addView(Button(context).apply {
            text = "重置应用（重新初始化）"
            setOnClickListener { confirmReset() }
        }, lpWrap)

        DpadFocus.apply(scroll)
        post { DpadFocus.apply(this) }
        DpadFocus.chainScrollView(scroll)
    }

    private fun addToolBtn(toolCol: LinearLayout, label: String, onclick: () -> Unit) {
        toolCol.addView(Button(context).apply {
            text = label
            textSize = 12f
            isFocusable = true
            isFocusableInTouchMode = true
            stateListAnimator = null
            DpadFocus.highlight(this)
            setOnClickListener { onclick() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })
    }

    private fun toggleListSection() {
        val w = listSectionWrapper ?: return
        w.visibility = if (w.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun showEmbeddingConfigDialog() {
        val etBase = EditText(context).apply {
            hint = "https://api.openai.com/v1"
            setText(Config.knowledgeEmbedBaseUrl)
            textSize = 13f
            inputType = 16
        }
        val etKey = EditText(context).apply {
            hint = "sk-..."
            setText(Config.knowledgeEmbedApiKey)
            textSize = 13f
        }
        val etModel = EditText(context).apply {
            hint = "text-embedding-3-small"
            setText(if (Config.knowledgeEmbedModel.isBlank()) "text-embedding-3-small" else Config.knowledgeEmbedModel)
            textSize = 13f
        }
        val tvResult = TextView(context).apply {
            textSize = 11f
            setTextColor(color(R.color.textSub))
            setPadding(0, dp(8), 0, 0)
        }
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        col.addView(TextView(context).apply {
            text = "Base URL"
            textSize = 12f
            setPadding(0, dp(4), 0, dp(2))
        })
        col.addView(etBase)
        col.addView(TextView(context).apply {
            text = "API Key"
            textSize = 12f
            setPadding(0, dp(8), 0, dp(2))
        })
        col.addView(etKey)
        col.addView(TextView(context).apply {
            text = "模型"
            textSize = 12f
            setPadding(0, dp(8), 0, dp(2))
        })
        col.addView(etModel)
        col.addView(tvResult)
        col.addView(TextView(context).apply {
            text = "提示：Base URL 需包含 /v1（如 https://api.openai.com/v1），端点会拼上 /embeddings。本地可填 http://127.0.0.1:11434/v1"
            textSize = 10f
            setTextColor(color(R.color.textSub))
            setPadding(0, dp(8), 0, 0)
        })
        val dlg = AlertDialog.Builder(act)
            .setTitle("知识库嵌入模型")
            .setView(col)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("测试", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                stateListAnimator = null
                DpadFocus.highlight(this)
            }
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                stateListAnimator = null
                DpadFocus.highlight(this)
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                stateListAnimator = null
                DpadFocus.highlight(this)
            }
            etBase.isFocusable = true
            etBase.isFocusableInTouchMode = true
            etKey.isFocusable = true
            etKey.isFocusableInTouchMode = true
            etModel.isFocusable = true
            etModel.isFocusableInTouchMode = true
            etBase.requestFocus()
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                Config.knowledgeEmbedBaseUrl = etBase.text.toString().trim()
                Config.knowledgeEmbedApiKey = etKey.text.toString().trim()
                Config.knowledgeEmbedModel = etModel.text.toString().trim()
                    .ifEmpty { "text-embedding-3-small" }
                toast("嵌入模型已保存")
                dlg.dismiss()
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val base = etBase.text.toString().trim()
                val key = etKey.text.toString().trim()
                val model = etModel.text.toString().trim()
                if (base.isBlank() || key.isBlank() || model.isBlank()) {
                    tvResult.text = "请先填完 Base URL / API Key / 模型"
                    return@setOnClickListener
                }
                tvResult.text = "正在测试..."
                Thread({
                    try {
                        val savedBase = Config.knowledgeEmbedBaseUrl
                        val savedKey = Config.knowledgeEmbedApiKey
                        val savedModel = Config.knowledgeEmbedModel
                        Config.knowledgeEmbedBaseUrl = base
                        Config.knowledgeEmbedApiKey = key
                        Config.knowledgeEmbedModel = model
                        val v = EmbeddingEngine.embed("hello")
                        Config.knowledgeEmbedBaseUrl = savedBase
                        Config.knowledgeEmbedApiKey = savedKey
                        Config.knowledgeEmbedModel = savedModel
                        act.runOnUiThread {
                            if (v != null) {
                                tvResult.text = "成功：返回 ${v.size} 维向量"
                            } else {
                                tvResult.text = "失败（看日志）"
                            }
                        }
                    } catch (e: Exception) {
                        act.runOnUiThread { tvResult.text = "错误：${e.message}" }
                    }
                }, "embed-test").apply { isDaemon = true }.start()
            }
        }
        dlg.show()
    }

    private fun refreshListToggle() {
        val isWhite = listMode == "white"
        rowWhitelist.visibility = if (isWhite) View.VISIBLE else View.GONE
        etWhitelist.visibility = if (isWhite) View.VISIBLE else View.GONE
        rowBlacklist.visibility = if (isWhite) View.GONE else View.VISIBLE
        etBlacklist.visibility = if (isWhite) View.GONE else View.VISIBLE
        btnWhite.setBackgroundColor(
            if (isWhite) resources.getColor(R.color.colorPrimary, null) else 0
        )
        btnWhite.setTextColor(
            if (isWhite) Color.WHITE else resources.getColor(R.color.textMain, null)
        )
        btnBlack.setBackgroundColor(
            if (isWhite) 0 else resources.getColor(R.color.colorPrimary, null)
        )
        btnBlack.setTextColor(
            if (isWhite) resources.getColor(R.color.textMain, null) else Color.WHITE
        )
        DpadFocus.syncBase(btnWhite)
        DpadFocus.syncBase(btnBlack)
    }

    fun refresh() {
        try {
            switchKnowledge.isChecked = Config.knowledgeEnabled
            switchWhitelistOn.isChecked = Config.whitelistOn
            switchBlacklistOn.isChecked = Config.blacklistOn
            etWhitelist.setText(Config.whitelist)
            etBlacklist.setText(Config.blacklist)
        } catch (_: Throwable) {
        }
        listMode = if (Config.whitelistOn) {
            "white"
        } else if (Config.blacklistOn) {
            "black"
        } else {
            listMode
        }
        try {
            refreshListToggle()
        } catch (_: Throwable) {
        }
        showLog()
        try {
            Logs.addListener(logListener)
            logListenerAttached = true
        } catch (_: Throwable) {
        }
    }

    fun onAttachForLog() {
        if (logListenerAttached) return
        logListenerAttached = true
        Logs.addListener(logListener)
    }

    fun onDetach() {
        Logs.removeListener(logListener)
        logListenerAttached = false
    }

    private fun showLog() {
        tvLog.setText(Logs.textTail(20_000))
        tvDayLog?.let { tv ->
            val list = try {
                ChatStore.load()
            } catch (_: Throwable) {
                emptyList()
            }
            val recent = list.takeLast(50)
            tv.text = if (recent.isEmpty()) {
                "（暂无对话）"
            } else {
                val df = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                recent.joinToString("\n") { e ->
                    val who = if (e.from == "user") "用户" else "机器人"
                    "[" + df.format(Date(e.time)) + "] [" + who + "] " + e.content
                }
            }
        }
    }

    private fun confirmReset() {
        val dlg = AlertDialog.Builder(act)
            .setTitle("重置应用")
            .setMessage("将清除全部配置并重新初始化（人设卡与插件文件保留在数据目录）。确定？")
            .setPositiveButton("确定") { _, _ ->
                Config.clearAll()
                act.startActivity(
                    Intent(act, InitActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                act.finish()
            }
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                stateListAnimator = null
                DpadFocus.highlight(this)
                requestFocus()
            }
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                stateListAnimator = null
                DpadFocus.highlight(this)
            }
        }
        dlg.show()
    }

    private fun makeSwitch(
        col: LinearLayout,
        label: String,
        initial: Boolean,
        onSave: (Boolean) -> Unit
    ): Pair<View, Switch> {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        col.addView(row, lpWrap)
        row.addView(TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(color(R.color.textMain))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(context).apply {
            isChecked = initial
            setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onSave(v) }
        }
        row.addView(sw)
        return row to sw
    }

    private fun section(t: String): TextView = TextView(context).apply {
        text = t
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.textMain))
        setPadding(0, dp(12), 0, dp(6))
    }

    private fun shareConfig() {
        val path = ConfigExport.export(context) ?: return
        ConfigExport.shareFile(context, path)
    }

    private fun importConfig() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        act.startActivityForResult(intent, REQ_IMPORT_CONFIG)
    }

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int): Int = resources.getColor(id, null)
}

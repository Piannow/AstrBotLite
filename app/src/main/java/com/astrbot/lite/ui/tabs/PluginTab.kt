package com.astrbot.lite.ui.tabs

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.astrbot.lite.R
import com.astrbot.lite.core.Account
import com.astrbot.lite.core.AccountManager
import com.astrbot.lite.core.AstrBotMarketEntry
import com.astrbot.lite.core.BotEngine
import com.astrbot.lite.core.Config
import com.astrbot.lite.core.InstallProgress
import com.astrbot.lite.core.KnowledgeBase
import com.astrbot.lite.core.KnowledgeEntry
import com.astrbot.lite.core.Logs
import com.astrbot.lite.core.Netdisk
import com.astrbot.lite.core.PersonaManager
import com.astrbot.lite.core.PluginConfigField
import com.astrbot.lite.core.PluginConverter
import com.astrbot.lite.core.PluginInfo
import com.astrbot.lite.core.PluginManager
import com.astrbot.lite.core.PythonRuntime
import com.astrbot.lite.core.TRIGGER_NAMES
import com.astrbot.lite.service.BotService
import com.astrbot.lite.ui.DpadFocus
import com.astrbot.lite.ui.InstallPanel
import com.astrbot.lite.ui.NetdiskLoginActivity
import com.astrbot.lite.ui.PluginWebUiActivity
import com.astrbot.lite.ui.TestConsoleActivity
import com.astrbot.lite.ui.WizardActivity
import java.util.Locale

/**
 * 插件标签页（按 1.7.222 反编译源码还原）：
 * 顶部标题 + 账号标签；左侧「已安装插件」卡片 + 批量栏；右侧账号卡（180dp）；
 * 下方三个子 Tab（Lite 市场 / Astrbot 市场 / 插件工具）各自独立面板。
 */
class PluginTab(private val act: Activity) : LinearLayout(act) {

    private val handler = Handler(Looper.getMainLooper())
    private val IMPORT_PICK = 3002

    var onAccountChanged: (() -> Unit)? = null

    @Volatile
    var currentScreen: Int = 0

    // ---------- Lite 市场 ----------
    private var liteAll: List<PluginInfo> = emptyList()
    private var litePage: List<PluginInfo> = emptyList()
    private lateinit var lvLite: ListView
    private lateinit var liteAdapter: LiteMarketAdapter
    private var liteQuery: String = ""
    private var litePageNum: Int = 1
    private val litePageSize: Int = 10
    private var liteTotal: Int = 0
    private var loadingMoreLite: Boolean = false
    private var loadingLite: Boolean = false

    // ---------- AstrBot 市场 ----------
    private var abAll: List<AstrBotMarketEntry> = emptyList()
    private var abPage: List<AstrBotMarketEntry> = emptyList()
    private lateinit var lvAstr: ListView
    private lateinit var abAdapter: AstrMarketAdapter
    private lateinit var etAbUrl: EditText
    private var abQuery: String = ""
    private var abPageNum: Int = 1
    private val abPageSize: Int = 10
    private var abTotal: Int = 0
    private var loadingMoreAstr: Boolean = false
    private var loadingAstr: Boolean = false

    // ---------- 已安装 ----------
    private var installed: List<PluginInfo> = emptyList()
    private lateinit var lvInstalled: ListView
    private lateinit var installedAdapter: InstalledAdapter

    // ---------- 多选（批量操作） ----------
    @Volatile private var selecting: Boolean = false
    private val selectedIds = linkedSetOf<String>()
    private var tvSelInfo: TextView? = null
    private var btnMulti: Button? = null
    private var btnAll: Button? = null
    private var btnDel: Button? = null
    private var btnMove: Button? = null
    private var btnCopy: Button? = null
    private var btnCancelSel: Button? = null

    // ---------- 账号卡 ----------
    private var tvAccountTag: TextView? = null
    private var accountCardHost: LinearLayout? = null

    // ---------- 三个面板 ----------
    private var paneLite: LinearLayout? = null
    private var paneAstr: LinearLayout? = null
    private var paneTools: LinearLayout? = null
    var tabLite: TextView? = null
    var tabAstr: TextView? = null
    var tabTools: TextView? = null

    /** 网盘插件：内置平台列表 + 自定义添加的平台 */
    private val netdiskPlatforms: MutableList<NetdiskPlatform> = mutableListOf(
        NetdiskPlatform("123网盘", "123"),
        NetdiskPlatform("夸克网盘", "quark"),
        NetdiskPlatform("UC网盘", "uc")
    )

    private data class NetdiskPlatform(val name: String, val id: String)

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))

        // 顶部标题 + 账号标签
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        addView(topRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        topRow.addView(TextView(context).apply {
            text = "插件"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color(R.color.textMain))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tvAccountTag = TextView(context).apply {
            textSize = 12f
            setTextColor(color(R.color.colorPrimary))
        }
        topRow.addView(tvAccountTag)
        updateTopTag()

        // 已安装（左：卡片 + 批量栏；右：账号卡）
        val mainRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        addView(mainRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val leftCol = LinearLayout(context).apply { orientation = VERTICAL }
        mainRow.addView(leftCol, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        ))
        buildInstalledSection(leftCol)

        accountCardHost = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        mainRow.addView(accountCardHost!!, LinearLayout.LayoutParams(
            dp(180), ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        refreshAccountCard()

        // 子 Tab
        val subTabs = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        addView(subTabs)
        tabLite = makeSubTab("Lite 市场", 0)
        tabAstr = makeSubTab("Astrbot 市场", 1)
        tabTools = makeSubTab("插件工具", 2)
        for (t in listOf(tabLite!!, tabAstr!!, tabTools!!)) {
            subTabs.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        paneLite = buildLitePane()
        paneAstr = buildAstrPane()
        paneTools = buildToolsPane()
        val paneWrap = LinearLayout(context).apply { orientation = VERTICAL }
        addView(paneWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.5f
        ))
        paneWrap.addView(paneLite!!, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        paneWrap.addView(paneAstr!!, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        paneWrap.addView(paneTools!!, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        refreshLite()
        refreshAstr()
        refresh()
        switchScreen(0)
        post { DpadFocus.apply(this) }
    }

    // ---------------- 已安装区 ----------------

    private fun buildInstalledSection(parent: LinearLayout) {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(color(R.color.card))
                cornerRadius = 12 * resources.displayMetrics.density
            }
        }
        parent.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        card.addView(TextView(context).apply {
            text = "已安装插件"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color(R.color.textMain))
            setPadding(0, 0, 0, dp(6))
        })

        installedAdapter = InstalledAdapter()
        lvInstalled = ListView(context).apply {
            adapter = installedAdapter
            isFocusable = true
            isFocusableInTouchMode = true
            setItemsCanFocus(false)
            setOnItemClickListener { _, _, position, _ ->
                val p = installed.getOrNull(position) ?: return@setOnItemClickListener
                if (selecting) {
                    if (p.id == "help") {
                        Toast.makeText(context, "help 是内置插件，不可卸载", Toast.LENGTH_SHORT).show()
                        return@setOnItemClickListener
                    }
                    if (selectedIds.contains(p.id)) selectedIds.remove(p.id) else selectedIds.add(p.id)
                    updateBatchBar()
                    installedAdapter.notifyDataSetChanged()
                } else {
                    openInstalledActions(position)
                }
            }
        }
        card.addView(lvInstalled, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val batch = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        parent.addView(batch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        tvSelInfo = TextView(context).apply {
            text = "已选 0 项"
            textSize = 12f
            setTextColor(color(R.color.textSub))
        }
        batch.addView(tvSelInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        btnAll = mkBtn("全选") { toggleSelectAll() }
        btnDel = mkBtn("卸载") { batchUninstall() }
        btnMove = mkBtn("移动") { batchMove() }
        btnCopy = mkBtn("复制") { batchCopy() }
        btnCancelSel = mkBtn("取消") { exitSelectMode() }
        btnMulti = mkBtn("多选") { enterSelectMode() }
        for (b in listOf(btnAll!!, btnDel!!, btnMove!!, btnCopy!!, btnCancelSel!!, btnMulti!!)) {
            batch.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) })
        }
        updateBatchBar()
    }

    private fun mkBtn(label: String, onclick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 11f
            setOnClickListener { onclick() }
        }

    private fun enterSelectMode() {
        selecting = true
        selectedIds.clear()
        updateBatchBar()
        installedAdapter.notifyDataSetChanged()
    }

    private fun exitSelectMode() {
        selecting = false
        selectedIds.clear()
        updateBatchBar()
        installedAdapter.notifyDataSetChanged()
    }

    private fun toggleSelectAll() {
        val selectable = installed.filter { it.id != "help" }
        if (selectable.isNotEmpty() && selectedIds.size == selectable.size) {
            selectedIds.clear()
        } else {
            selectedIds.clear()
            selectable.forEach { selectedIds.add(it.id) }
        }
        updateBatchBar()
        installedAdapter.notifyDataSetChanged()
    }

    private fun updateBatchBar() {
        tvSelInfo?.text = "已选 ${selectedIds.size} 项"
        btnMulti?.visibility = if (selecting) View.GONE else View.VISIBLE
        val showWhenSel = if (selecting) View.VISIBLE else View.GONE
        btnAll?.visibility = showWhenSel
        btnDel?.visibility = showWhenSel
        btnMove?.visibility = showWhenSel
        btnCopy?.visibility = showWhenSel
        btnCancelSel?.visibility = showWhenSel
        val none = selectedIds.isEmpty()
        btnDel?.isEnabled = !none
        btnMove?.isEnabled = !none
        btnCopy?.isEnabled = !none
        btnAll?.text =
            if (installed.isEmpty() || selectedIds.size != installed.size) "全选" else "取消全选"
    }

    private fun batchUninstall() {
        val targets = installed.filter { selectedIds.contains(it.id) && it.id != "help" }
        if (targets.isEmpty()) return
        AlertDialog.Builder(context)
            .setTitle("卸载插件")
            .setMessage("确定卸载这 ${targets.size} 个插件吗？\n\n" + targets.joinToString("、") { it.name })
            .setPositiveButton("确定", { _, _ ->
                val ids = targets.map { it.id }
                exitSelectMode()
                Thread({
                    var ok = 0
                    for (id in ids) {
                        try {
                            PluginManager.uninstall(id)
                            ok++
                        } catch (e: Exception) {
                            Logs.err("[批量卸载] $id 失败: ${e.message}")
                        }
                    }
                    handler.post {
                        refresh()
                        Toast.makeText(context, "卸载完成 $ok/${ids.size}", Toast.LENGTH_SHORT).show()
                    }
                }, "batch-uninstall").apply { isDaemon = true }.start()
            })
            .setNegativeButton("取消", null)
            .show()
    }

    private fun batchMove() {
        Toast.makeText(context, "移动：功能待接入（先占位）", Toast.LENGTH_SHORT).show()
    }

    private fun batchCopy() {
        Toast.makeText(context, "复制：功能待接入（先占位）", Toast.LENGTH_SHORT).show()
    }

    private fun openInstalledActions(position: Int) {
        if (position < 0 || position >= installed.size) return
        val p = installed[position]
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add((if (p.enabled) "停用" else "启用") to {
            PluginManager.setEnabled(p.id, !p.enabled)
            refresh()
        })
        actions.add("配置" to { showConfigDialog(p) })
        if (PythonRuntime.pythonPluginWebRoot(p.id) != null) {
            actions.add("Web 界面" to { PluginWebUiActivity.openWebUi(act, p.id, p.name) })
        }
        if (p.id != "help") {
            actions.add("触发方式" to { showTriggerDialog(p) })
        }
        actions.add("文档" to { showPluginDoc(p) })
        if (p.id == "netdisk") {
            actions.add("登录网盘" to { showNetdiskLogin() })
            actions.add("添加网盘平台" to { showAddNetdiskDialog() })
        }
        if (p.id != "help") {
            actions.add("卸载" to { confirmUninstall(p) })
        }
        showActionsDialog("${p.name} v${p.version}", actions)
    }

    private fun confirmUninstall(p: PluginInfo) {
        AlertDialog.Builder(context)
            .setTitle("卸载插件")
            .setMessage("确定要卸载「${p.name}」吗？\n卸载后该插件将从本地移除（不会自动装回）。")
            .setPositiveButton("确定") { _, _ -> startUninstallWithProgress(p) }
            .setNegativeButton("取消", null)
            .create()
            .show()
    }

    private fun startUninstallWithProgress(p: PluginInfo) {
        val layout = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(48, 32, 48, 24)
        }
        val tvPct = TextView(context).apply {
            textSize = 16f
            setPadding(0, 0, 0, 12)
            text = "正在卸载「${p.name}」…"
        }
        val bar = ProgressBar(context, null, 0).apply {
            isIndeterminate = false
            max = 100
        }
        layout.addView(tvPct)
        layout.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val dlg = AlertDialog.Builder(context)
            .setTitle("正在卸载")
            .setView(layout)
            .setCancelable(false)
            .create()
        dlg.show()
        Thread({
            try {
                PluginManager.uninstall(p.id, { pct, _stage, _done, _total ->
                    handler.post {
                        tvPct.text = "正在卸载「${p.name}」… $pct%"
                        bar.progress = pct
                    }
                })
            } catch (e: Exception) {
                Logs.err("[卸载] ${p.id} 失败: ${e.message}")
            }
            handler.post {
                tvPct.text = "已卸载「${p.name}」"
                bar.progress = 100
                dlg.dismiss()
                refresh()
            }
        }, "uninstall-${p.id}").apply { isDaemon = true }.start()
    }

    // ---------------- 账号卡 ----------------

    private fun refreshAccountCard() {
        val host = accountCardHost ?: return
        host.removeAllViews()
        val acc = AccountManager.active()
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(color(R.color.card))
                cornerRadius = 12 * resources.displayMetrics.density
                setStroke(dp(1), color(R.color.colorPrimary))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { openAccountMenuForPlugin() }
            DpadFocus.highlightGroup(this)
            stateListAnimator = null
        }
        host.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(TextView(context).apply {
            text = acc?.name ?: "未配置账号"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color(R.color.textMain))
            setPadding(0, 0, 0, dp(6))
        })
        if (acc != null) {
            val online = try {
                BotService.engines()[acc.id]?.isRunning == true
            } catch (_: Throwable) {
                false
            }
            card.addView(kvRow("环境", if (acc.env == "sandbox") "沙箱" else "正式"))
            card.addView(kvRowStatus("在线", online))
            val persona = try {
                PersonaManager.active().name
            } catch (_: Throwable) {
                "—"
            }
            card.addView(kvRow("人设卡", persona))
            card.addView(kvRow("插件数", installed.size.toString()))
        }
        card.addView(TextView(context).apply {
            text = "点击管理账号"
            textSize = 10f
            setTextColor(color(R.color.textSub))
            setPadding(0, dp(6), 0, 0)
        })
        post { DpadFocus.apply(card) }
    }

    private fun kvRow(k: String, v: String): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }
        row.addView(TextView(context).apply {
            text = k
            textSize = 10f
            setTextColor(color(R.color.textSub))
            width = dp(50)
        })
        row.addView(TextView(context).apply {
            text = v
            textSize = 12f
            setTextColor(color(R.color.textMain))
        })
        return row
    }

    private fun kvRowStatus(k: String, online: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        row.addView(TextView(context).apply {
            text = k
            textSize = 10f
            setTextColor(color(R.color.textSub))
            width = dp(50)
        })
        row.addView(TextView(context).apply {
            text = "\u25CF"
            textSize = 10f
            setTextColor(color(if (online) R.color.ok else R.color.err))
        })
        row.addView(TextView(context).apply {
            text = if (online) " 在线" else " 离线"
            textSize = 12f
            setTextColor(color(R.color.textMain))
        })
        return row
    }

    private fun openAccountMenuForPlugin() {
        val acc = AccountManager.active() ?: run {
            Toast.makeText(context, "请先在账号页添加账号", Toast.LENGTH_SHORT).show()
            return
        }
        val online = try {
            BotService.engines()[acc.id]?.isRunning == true
        } catch (_: Throwable) {
            false
        }
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items.add((if (online) "停止" else "启动") to {
            try {
                BotService.startAccount(act, acc.id, !online)
            } catch (_: Throwable) {
            }
            Toast.makeText(context, acc.name + (if (online) " 已停止" else " 已启动"), Toast.LENGTH_SHORT).show()
            handler.postDelayed({ refreshAccountCard() }, 500)
        })
        items.add("重启" to {
            val eng: BotEngine? = try {
                BotService.engines()[acc.id]
            } catch (_: Throwable) {
                null
            }
            if (eng != null) {
                Toast.makeText(context, acc.name + " 正在重启…", Toast.LENGTH_SHORT).show()
                eng.restart()
                handler.postDelayed({ refreshAccountCard() }, 1000)
            } else {
                Toast.makeText(context, acc.name + " 未在运行", Toast.LENGTH_SHORT).show()
            }
        })
        items.add("设置账号（QQ 接入 + AI 配置）" to {
            val i = Intent(act, WizardActivity::class.java)
            i.putExtra("account_id", acc.id)
            act.startActivity(i)
        })
        showActionSheetSimple(acc.name, items)
    }

    private fun showActionSheetSimple(title: String, actions: List<Pair<String, () -> Unit>>) {
        val v = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val dlg = AlertDialog.Builder(act).setTitle(title).setView(v).create()
        val buttons = mutableListOf<Button>()
        for ((label, action) in actions) {
            val b = Button(context).apply {
                text = label
                isFocusable = true
                isFocusableInTouchMode = true
                stateListAnimator = null
                DpadFocus.highlight(this)
                setOnClickListener {
                    dlg.dismiss()
                    action()
                }
            }
            buttons.add(b)
            v.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) })
        }
        val cancel = Button(context).apply {
            text = "取消"
            isFocusable = true
            isFocusableInTouchMode = true
            stateListAnimator = null
            DpadFocus.highlight(this)
            setOnClickListener { dlg.dismiss() }
        }
        buttons.add(cancel)
        v.addView(cancel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        dlg.setOnShowListener { buttons.firstOrNull()?.requestFocus() }
        dlg.show()
    }

    private fun updateTopTag() {
        tvAccountTag?.text = AccountManager.active()?.let {
            it.name + " · " + (if (it.env == "sandbox") "沙箱" else "正式")
        } ?: "未配置账号"
    }

    // ---------------- 子 Tab ----------------

    private fun makeSubTab(label: String, idx: Int): TextView =
        TextView(context).apply {
            text = label
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { switchScreen(idx) }
        }

    fun switchScreen(idx: Int) {
        currentScreen = idx
        paneLite?.visibility = if (idx == 0) View.VISIBLE else View.GONE
        paneAstr?.visibility = if (idx == 1) View.VISIBLE else View.GONE
        paneTools?.visibility = if (idx == 2) View.VISIBLE else View.GONE
        val on = color(R.color.colorPrimary)
        val off = color(R.color.textSub)
        tabLite?.apply {
            setTextColor(if (idx == 0) on else off)
            setTypeface(typeface, if (idx == 0) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        tabAstr?.apply {
            setTextColor(if (idx == 1) on else off)
            setTypeface(typeface, if (idx == 1) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        tabTools?.apply {
            setTextColor(if (idx == 2) on else off)
            setTypeface(typeface, if (idx == 2) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        when (idx) {
            0 -> refreshLite()
            1 -> refreshAstr()
            2 -> refresh()
        }
        post {
            val pane = when (idx) {
                0 -> paneLite
                1 -> paneAstr
                2 -> paneTools
                else -> null
            }
            if (pane != null) DpadFocus.apply(pane)
        }
    }

    // ---------------- 三个面板 ----------------

    private fun buildLitePane(): LinearLayout {
        val p = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        p.addView(EditText(context).apply {
            hint = "搜索 Lite 插件"
            inputType = 1
            maxLines = 1
            setTextSize(12f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    liteQuery = s?.toString()?.trim().orEmpty()
                    litePageNum = 1
                    showLitePage()
                }
            })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        p.addView(Button(context).apply {
            text = "刷新"
            textSize = 12f
            setOnClickListener { refreshLite() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        liteAdapter = LiteMarketAdapter()
        lvLite = ListView(context).apply {
            adapter = liteAdapter
            isFocusable = true
            isFocusableInTouchMode = true
            setItemsCanFocus(false)
            setOnItemClickListener { _, _, i, _ -> openMarketActions(i, 0) }
            setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) {}
                override fun onScroll(
                    view: android.widget.AbsListView?,
                    firstVisible: Int,
                    visibleCount: Int,
                    totalCount: Int
                ) {
                    if (totalCount > 0 && firstVisible + visibleCount >= totalCount - 3 &&
                        !loadingMoreLite && litePage.size < liteTotal
                    ) {
                        loadingMoreLite = true
                        litePageNum++
                        view?.post { showLitePage() }
                    }
                }
            })
        }
        p.addView(lvLite, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        return p
    }

    private fun buildAstrPane(): LinearLayout {
        val p = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        p.addView(EditText(context).apply {
            hint = "搜索 AstrBot 插件"
            inputType = 1
            maxLines = 1
            setTextSize(12f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    abQuery = s?.toString()?.trim().orEmpty()
                    abPageNum = 1
                    showAstrPage()
                }
            })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val abUrlRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        p.addView(abUrlRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        etAbUrl = EditText(context).apply {
            hint = "AstrBot 市场地址"
            setText(PluginConverter.DEFAULT_MARKET_URL)
            inputType = 16
            maxLines = 1
            setTextSize(11f)
        }
        abUrlRow.addView(etAbUrl, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        abUrlRow.addView(Button(context).apply {
            text = "加载"
            textSize = 11f
            setOnClickListener { refreshAstr() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        abAdapter = AstrMarketAdapter()
        lvAstr = ListView(context).apply {
            adapter = abAdapter
            isFocusable = true
            isFocusableInTouchMode = true
            setItemsCanFocus(false)
            setOnItemClickListener { _, _, i, _ -> openMarketActions(i, 1) }
            setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) {}
                override fun onScroll(
                    view: android.widget.AbsListView?,
                    firstVisible: Int,
                    visibleCount: Int,
                    totalCount: Int
                ) {
                    if (totalCount > 0 && firstVisible + visibleCount >= totalCount - 3 &&
                        !loadingMoreAstr && abPage.size < abTotal
                    ) {
                        loadingMoreAstr = true
                        abPageNum++
                        view?.post { showAstrPage() }
                    }
                }
            })
        }
        p.addView(lvAstr, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        return p
    }

    private fun buildToolsPane(): LinearLayout {
        val p = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val grid = LinearLayout(context).apply { orientation = HORIZONTAL }
        p.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        toolBtn(grid, "ZIP安装") { importLocal() }
        toolBtn(grid, "GitHub") { githubInstallDialog() }
        toolBtn(grid, "知识库") { openKnowledgeBase() }
        toolBtn(grid, "测试") { act.startActivity(Intent(act, TestConsoleActivity::class.java)) }
        toolBtn(grid, "名单") { openWhitelistDialog() }
        return p
    }

    private fun toolBtn(grid: LinearLayout, label: String, onclick: () -> Unit) {
        val b = Button(context).apply {
            text = label
            textSize = 12f
            setOnClickListener { onclick() }
            DpadFocus.highlight(this)
            stateListAnimator = null
        }
        grid.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
    }

    private fun openKnowledgeBase() {
        val list = KnowledgeBase.list()
        val content = if (list.isEmpty()) {
            "知识库为空（未启用或尚未入库）。"
        } else {
            list.take(50).joinToString("\n") { "· ${it.title}" }
        }
        AlertDialog.Builder(act)
            .setTitle("知识库（${list.size} 条）")
            .setMessage(content)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun openWhitelistDialog() {
        val etW = EditText(context).apply {
            hint = "白名单（逗号分隔）"
            setText(Config.whitelist)
            maxLines = 2
            setTextSize(12f)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val etB = EditText(context).apply {
            hint = "黑名单（逗号分隔）"
            setText(Config.blacklist)
            maxLines = 2
            setTextSize(12f)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        col.addView(etW)
        col.addView(etB)
        val dlg = AlertDialog.Builder(act)
            .setTitle("名单管理")
            .setView(col)
            .setPositiveButton("保存") { _, _ ->
                Config.whitelist = etW.text.toString().trim()
                Config.blacklist = etB.text.toString().trim()
                Toast.makeText(act, "名单已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
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
            etW.requestFocus()
        }
        dlg.show()
    }

    // ---------------- 市场数据 ----------------

    private fun refreshLite() {
        if (loadingLite) return
        loadingLite = true
        Thread {
            val m = PluginManager.loadCatalog()
            handler.post {
                liteAll = m
                showLitePage()
                loadingLite = false
            }
        }.start()
    }

    private fun refreshAstr() {
        if (loadingAstr) return
        loadingAstr = true
        Thread {
            val url = etAbUrl.text.toString().trim().ifEmpty { PluginConverter.DEFAULT_MARKET_URL }
            val text = PluginConverter.tryDownloadMarket(url)
            handler.post {
                abAll = text?.let { PluginConverter.parseAstrBotMarket(it) } ?: emptyList()
                showAstrPage()
                loadingAstr = false
            }
        }.start()
    }

    private fun showLitePage() {
        val list = if (liteQuery.isEmpty()) liteAll else liteAll.filter {
            it.name.contains(liteQuery, ignoreCase = true) ||
                it.author.contains(liteQuery, ignoreCase = true) ||
                it.description.contains(liteQuery, ignoreCase = true)
        }
        liteTotal = list.size
        litePage = list.take(minOf(litePageNum * litePageSize, list.size))
        liteAdapter.notifyDataSetChanged()
        loadingMoreLite = false
    }

    private fun showAstrPage() {
        val list = if (abQuery.isEmpty()) abAll else abAll.filter {
            it.name.contains(abQuery, ignoreCase = true) ||
                it.shownName.contains(abQuery, ignoreCase = true) ||
                it.author.contains(abQuery, ignoreCase = true) ||
                it.desc.contains(abQuery, ignoreCase = true)
        }
        abTotal = list.size
        abPage = list.take(minOf(abPageNum * abPageSize, list.size))
        abAdapter.notifyDataSetChanged()
        loadingMoreAstr = false
    }

    fun refresh() {
        installed = PluginManager.installed()
        try {
            installedAdapter.notifyDataSetChanged()
        } catch (_: Throwable) {
        }
        try {
            refreshAccountCard()
        } catch (_: Throwable) {
        }
        try {
            updateTopTag()
        } catch (_: Throwable) {
        }
        refreshLite()
        refreshAstr()
    }

    private fun openMarketActions(position: Int, screen: Int) {
        if (screen == 0) {
            if (position < 0 || position >= litePage.size) return
            val p = litePage[position]
            val isInstalled = PluginManager.installed(p.id) != null
            val actions = mutableListOf<Pair<String, () -> Unit>>()
            actions.add((if (isInstalled) "重新安装" else "安装") to { installLite(p) })
            if (p.doc.isNotBlank()) actions.add("文档" to { showPluginDoc(p) })
            showActionsDialog("${p.name} v${p.version}", actions)
            return
        }
        if (position < 0 || position >= abPage.size) return
        val e = abPage[position]
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add("安装到 Python 环境" to { installAstrBot(e) })
        showActionsDialog(e.shownName, actions)
    }

    private fun startInstallTask(taskId: String, title: String, block: () -> Boolean, onDone: (Boolean) -> Unit) {
        if (!InstallProgress.prepare(taskId, title)) {
            Toast.makeText(context, "该插件正在安装中，已显示其进度", Toast.LENGTH_SHORT).show()
            InstallPanel.show(act, title, taskId)
        } else {
            InstallPanel.show(act, title, taskId)
            Thread {
                InstallProgress.attach(taskId, title)
                val ok = try {
                    block()
                } catch (_: Exception) {
                    false
                }
                handler.post {
                    refresh()
                    onDone(ok)
                }
            }.start()
        }
    }

    private fun installLite(p: PluginInfo) {
        startInstallTask(p.id, "安装 ${p.name}", { PluginManager.installFromCatalog(p) }) { ok ->
            Toast.makeText(context, if (ok) "安装成功" else "安装失败，请查看日志", Toast.LENGTH_SHORT).show()
        }
    }

    private fun installAstrBot(e: AstrBotMarketEntry) {
        startInstallTask(e.name, "安装 ${e.shownName}", { PluginManager.installFromAstrBot(e) }) { ok ->
            Toast.makeText(
                context,
                if (ok) "安装成功（已装入 Python 统一环境）" else "${e.shownName} 安装失败，详见日志",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showActionsDialog(title: String, actions: List<Pair<String, () -> Unit>>) {
        val v = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        val dlg = AlertDialog.Builder(context).setTitle(title).setView(v).create()
        val buttons = mutableListOf<Button>()
        for ((label, action) in actions) {
            val b = Button(context).apply {
                text = label
                setOnClickListener {
                    dlg.dismiss()
                    action()
                }
            }
            buttons.add(b)
            v.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) })
        }
        val close = Button(context).apply {
            text = "关闭"
            setOnClickListener { }
        }
        buttons.add(close)
        v.addView(close, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })
        dlg.setOnShowListener {
            buttons.forEach {
                it.isFocusable = true
                it.isFocusableInTouchMode = true
                DpadFocus.highlight(it)
            }
            buttons.firstOrNull()?.requestFocus()
        }
        dlg.show()
    }

    private fun addAccountDialog() {
        val etName = EditText(context).apply {
            hint = "账号名称"
            setTextSize(13f)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val etAppId = EditText(context).apply {
            hint = "AppID"
            setTextSize(13f)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val etSecret = EditText(context).apply {
            hint = "AppSecret"
            setTextSize(13f)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        col.addView(etName)
        col.addView(etAppId)
        col.addView(etSecret)
        val envSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_item,
                arrayOf("正式 (prod)", "沙箱 (sandbox)")
            )
            isFocusable = true
        }
        try {
            DpadFocus.escapeSpinner(envSpinner)
        } catch (_: Throwable) {
        }
        col.addView(envSpinner)
        val dlg = AlertDialog.Builder(act)
            .setTitle("新增账号")
            .setView(col)
            .setPositiveButton("添加") { _, _ ->
                val envLabel = envSpinner.selectedItem?.toString() ?: "正式 (prod)"
                val env = if (envLabel.startsWith("沙箱")) "sandbox" else "prod"
                val appId = etAppId.text.toString().trim()
                if (appId.isEmpty()) {
                    Toast.makeText(act, "AppID 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val secret = etSecret.text.toString().trim()
                val name = etName.text.toString().trim().ifEmpty {
                    "机器人" + (AccountManager.loadAll().size + 1)
                }
                AccountManager.add(name, appId, secret, Config.qqApiBase, env)
                refresh()
                onAccountChanged?.invoke()
                Toast.makeText(act, "已添加账号 $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
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
            etName.requestFocus()
        }
        dlg.show()
    }

    private fun showConfigDialog(p: PluginInfo) {
        val editList = mutableListOf<EditText>()
        val inner = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        for (f: PluginConfigField in p.config) {
            inner.addView(TextView(context).apply {
                text = f.label + "（" + f.key + "）"
                textSize = 13f
                setTextColor(color(R.color.textMain))
                setPadding(0, dp(6), 0, dp(2))
            })
            val et = EditText(context).apply {
                hint = f.value
                maxLines = if (f.multiLine) 4 else 1
                setTextSize(12f)
                isFocusable = true
                isFocusableInTouchMode = true
            }
            editList.add(et)
            inner.addView(et)
        }
        val scroll = ScrollView(context)
        scroll.addView(inner)
        val dlg = AlertDialog.Builder(context)
            .setTitle("配置 ${p.name}")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ -> refresh() }
            .setNegativeButton("取消", null)
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
            try {
                DpadFocus.chainScrollView(scroll)
            } catch (_: Throwable) {
            }
            editList.firstOrNull()?.requestFocus()
        }
        dlg.show()
    }

    private fun showTriggerDialog(p: PluginInfo) {
        val items = TRIGGER_NAMES.values.toList()
        val keys = TRIGGER_NAMES.keys.toList()
        val labels = items.mapIndexed { i, name ->
            (if (keys[i] == p.trigger) "✓ " else "\u3000") + name
        }
        val dlg = AlertDialog.Builder(context)
            .setTitle("触发方式")
            .setSingleChoiceItems(
                labels.toTypedArray(),
                maxOf(keys.indexOf(p.trigger), 0)
            ) { d, which ->
                PluginManager.setTrigger(p.id, keys[which])
                d.dismiss()
                refresh()
            }
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnShowListener {
            dlg.listView?.apply {
                isFocusable = true
                isFocusableInTouchMode = true
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

    private fun showPluginDoc(p: PluginInfo) {
        val doc = p.doc.ifBlank { "暂无文档" }
        val dlg = AlertDialog.Builder(context)
            .setTitle("${p.name} 文档")
            .setMessage(doc)
            .setPositiveButton("关闭", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                stateListAnimator = null
                DpadFocus.highlight(this)
                requestFocus()
            }
        }
        dlg.show()
    }

    private fun showNetdiskLogin() {
        act.startActivity(Intent(act, NetdiskLoginActivity::class.java))
    }

    private fun showAddNetdiskDialog() {
        val et = EditText(context).apply {
            hint = "平台 ID（小写英文）"
            maxLines = 1
            setTextSize(13f)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val dlg = AlertDialog.Builder(context)
            .setTitle("添加网盘平台")
            .setView(et)
            .setPositiveButton("添加") { _, _ ->
                val id = et.text.toString().trim().lowercase(Locale.ROOT)
                if (id.isNotEmpty() && netdiskPlatforms.none { it.id == id }) {
                    netdiskPlatforms.add(NetdiskPlatform(id, id))
                    refresh()
                }
            }
            .setNegativeButton("取消", null)
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
            et.requestFocus()
        }
        dlg.show()
    }

    private fun importLocal() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        act.startActivityForResult(i, IMPORT_PICK)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != IMPORT_PICK || resultCode != Activity.RESULT_OK) return false
        val uri: Uri = data?.data ?: return false
        startInstallTask("import:local", "导入插件", {
            act.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.let { PluginManager.importLocal("import", it) } ?: false
        }) { ok ->
            Toast.makeText(context, if (ok) "插件导入成功" else "导入失败，请查看日志", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun githubInstallDialog() {
        val et = EditText(context).apply {
            hint = "GitHub 仓库或 zip 链接"
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val dlg = AlertDialog.Builder(context)
            .setTitle("GitHub 安装")
            .setView(et)
            .setPositiveButton("安装") { _, _ ->
                val url = et.text.toString().trim()
                if (url.isEmpty()) {
                    Toast.makeText(context, "请输入 GitHub 仓库或 zip 链接", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "正在从 GitHub 下载并安装…", Toast.LENGTH_SHORT).show()
                    startInstallTask("import:github", "GitHub 安装", {
                        val taskId = InstallProgress.currentId()
                        val bytes = PluginConverter.downloadGithub(
                            url,
                            { done, total ->
                                if (total > 0) {
                                    InstallProgress.updateFor(
                                        taskId,
                                        "下载插件包 ${done / 1024}/${total / 1024} KB",
                                        ((45L * done) / total).toInt().coerceIn(0, 45)
                                    )
                                }
                            },
                            { InstallProgress.isCancelled() },
                            { stage -> InstallProgress.stageFor(taskId, stage) }
                        )
                        bytes != null && PluginManager.importLocal("github.zip", bytes)
                    }) { ok ->
                        Toast.makeText(
                            context,
                            if (ok) "安装成功" else "安装失败：仓库/链接不可用",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
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
            et.requestFocus()
        }
        dlg.show()
    }

    fun handleDpadKey(keyCode: Int, event: KeyEvent): Boolean = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int): Int = resources.getColor(id, null)

    // ---------------- 适配器 ----------------

    inner class LiteMarketAdapter : BaseAdapter() {
        override fun getCount(): Int = litePage.size
        override fun getItem(p: Int): Any = litePage[p]
        override fun getItemId(p: Int): Long = p.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v: View
            val name: TextView
            val desc: TextView
            if (convertView == null) {
                val row = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                name = TextView(context).apply {
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(color(R.color.textMain))
                }
                desc = TextView(context).apply {
                    textSize = 11f
                    setTextColor(color(R.color.textSub))
                }
                row.addView(name)
                row.addView(desc)
                row.tag = LiteHolder(name, desc)
                v = row
            } else {
                val h = convertView.tag as LiteHolder
                name = h.name
                desc = h.desc
                v = convertView
            }
            val p = litePage[position]
            name.text = "${p.name} v${p.version}"
            desc.text = p.description
            return v
        }
    }

    private class LiteHolder(val name: TextView, val desc: TextView)

    inner class AstrMarketAdapter : BaseAdapter() {
        override fun getCount(): Int = abPage.size
        override fun getItem(p: Int): Any = abPage[p]
        override fun getItemId(p: Int): Long = p.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v: View
            val name: TextView
            val desc: TextView
            if (convertView == null) {
                val row = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                name = TextView(context).apply {
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(color(R.color.textMain))
                }
                desc = TextView(context).apply {
                    textSize = 11f
                    setTextColor(color(R.color.textSub))
                }
                row.addView(name)
                row.addView(desc)
                row.tag = AstrHolder(name, desc)
                v = row
            } else {
                val h = convertView.tag as AstrHolder
                name = h.name
                desc = h.desc
                v = convertView
            }
            val e = abPage[position]
            name.text = e.shownName
            desc.text = e.desc
            return v
        }
    }

    private class AstrHolder(val name: TextView, val desc: TextView)

    inner class InstalledAdapter : BaseAdapter() {
        override fun getCount(): Int = installed.size
        override fun getItem(p: Int): Any = installed[p]
        override fun getItemId(p: Int): Long = p.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val p = installed[position]
            val isSel = selectedIds.contains(p.id)
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                if (selecting && isSel) setBackgroundColor(Color.parseColor("#2280C1FF"))
            }
            if (selecting) {
                val isHelp = p.id == "help"
                row.addView(TextView(context).apply {
                    text = if (isHelp) "\uD83D\uDD12" else if (isSel) "\u2611" else "\u2610"
                    textSize = 18f
                    setPadding(0, 0, dp(8), 0)
                    setTextColor(color(if (isHelp) R.color.textSub else R.color.colorPrimary))
                })
            }
            val col = LinearLayout(context).apply { orientation = VERTICAL }
            col.addView(TextView(context).apply {
                text = "${p.name} v${p.version}  ${if (p.enabled) "\uD83D\uDFE2" else "\u26AA"}  ${TRIGGER_NAMES[p.trigger] ?: p.trigger}"
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(color(R.color.textMain))
            })
            if (p.id == "netdisk") {
                val tagRow = LinearLayout(context).apply { orientation = HORIZONTAL }
                col.addView(tagRow)
                for (plat in netdiskPlatforms) {
                    val logged = Netdisk.hasLoginTicket(plat.id)
                    tagRow.addView(TextView(context).apply {
                        text = plat.name + if (logged) " \u2713" else ""
                        textSize = 10f
                        setTextColor(Color.parseColor(if (logged) "#2E7D32" else "#C62828"))
                    })
                }
            }
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }
    }
}

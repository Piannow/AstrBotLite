package com.astrbot.lite.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.astrbot.lite.R
import com.astrbot.lite.core.Config
import com.astrbot.lite.service.BotService
import com.astrbot.lite.ui.tabs.AccountTab
import com.astrbot.lite.ui.tabs.PersonaTab
import com.astrbot.lite.ui.tabs.PluginTab
import com.astrbot.lite.ui.tabs.SettingsTab
import java.util.HashMap

/**
 * 主控制台：底部 4 个标签（状态 / 人设卡 / 插件 / 设置）
 */
class MainActivity : Activity() {

    private lateinit var content: FrameLayout
    private lateinit var accountTab: AccountTab
    private lateinit var personaTab: PersonaTab
    private lateinit var pluginTab: PluginTab
    private lateinit var settingsTab: SettingsTab

    private val tabs = HashMap<Int, Pair<TextView, View>>()
    private var currentTab = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        showLastCrashIfAny()

        accountTab = AccountTab(this)
        personaTab = PersonaTab(this)
        pluginTab = PluginTab(this)
        settingsTab = SettingsTab(this)

        listOf(accountTab, personaTab, pluginTab, settingsTab).forEach {
            content.addView(
                it,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        tabs[0] = findViewById<TextView>(R.id.tabAccount) to accountTab
        tabs[1] = findViewById<TextView>(R.id.tabPersona) to personaTab
        tabs[2] = findViewById<TextView>(R.id.tabPlugin) to pluginTab
        tabs[3] = findViewById<TextView>(R.id.tabSettings) to settingsTab

        for ((idx, pair) in tabs) {
            pair.first.setOnClickListener { switchTab(idx) }
            // 机顶盒 DPad：底部页签设为可聚焦，方向键可左右移动到页签、确定键切页
            pair.first.isFocusable = true
            pair.first.isClickable = true
            DpadFocus.highlight(pair.first)
        }
        // 机顶盒 DPad 焦点增强：给所有可交互控件套醒目的焦点高亮，方向键移动时能看清光标在哪
        DpadFocus.apply(findViewById<android.view.View>(R.id.content))
        DpadFocus.apply(findViewById<android.view.View>(R.id.bottomBar))
        switchTab(0)

        requestNotifPermissionIfNeeded()
        requestStoragePermissionIfNeeded()
        autoStartBotIfEnabled()
    }

    /** 1.7.125 排障：上次崩溃栈（若有）启动时先弹出来，内容可长按复制反馈。
     *  1.7.161：保留 crash_last.txt 并同步一份到下载目录，方便 adb/文件管理器直接拉取堆栈，
     *  不再"读完即删"导致崩溃栈丢失（闪退发生在启动之后，读删时机太早）。 */
    private fun showLastCrashIfAny() {
        try {
            val f = java.io.File(getExternalFilesDir(null) ?: filesDir, "crash_last.txt")
            if (!f.isFile) return
            val text = f.readText()
            // 不再删除源文件；另存一份到公共下载目录便于外拉
            try {
                @Suppress("DEPRECATION")
                val dl = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "AstrBotLite-crash-" + java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.getDefault())
                        .format(java.util.Date()) + ".txt",
                )
                dl.writeText(text)
            } catch (_: Throwable) {
            }
            // 修复（1.7.179）：启动页已弹过同一份崩溃栈时不再重复弹（拷贝仍保留，便于外拉）
            if (InitActivity.crashDialogShown) return
            android.app.AlertDialog.Builder(this)
                .setTitle("上次运行崩溃了（内容可长按选择复制）")
                .setMessage(text.take(4000))
                .setPositiveButton("知道了", null)
                .show()
        } catch (_: Throwable) {
        }
    }

    /** 之前启用过机器人：打开应用就自动启动，实现“点开即用” */
    private fun autoStartBotIfEnabled() {
        if (Config.botEnabled) {
            val e = BotService.engine()
            if (e == null || !e.isRunning) {
                BotService.start(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accountTab.attach()
        // 修复（1.7.183）：从后台回到前台时，若停留在设置页，日志监听已在 onPause 注销，
        // 这里按当前页签补挂，否则设置页日志从此不再实时追加
        when (currentTab) {
            3 -> settingsTab.onAttachForLog()
        }
    }

    override fun onPause() {
        super.onPause()
        accountTab.detach()
        settingsTab.onDetach()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val kc = event.keyCode
        val action = event.action
        val isDpad = kc == KeyEvent.KEYCODE_DPAD_UP ||
            kc == KeyEvent.KEYCODE_DPAD_DOWN ||
            kc == KeyEvent.KEYCODE_DPAD_LEFT ||
            kc == KeyEvent.KEYCODE_DPAD_RIGHT ||
            kc == KeyEvent.KEYCODE_DPAD_CENTER ||
            kc == KeyEvent.KEYCODE_ENTER ||
            kc == KeyEvent.KEYCODE_BACK
        if (isDpad && currentTab == 2) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (pluginTab.handleDpadKey(kc, event)) {
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (personaTab.onActivityResult(requestCode, resultCode, data)) return
        if (pluginTab.onActivityResult(requestCode, resultCode, data)) return
        if (requestCode == SettingsTab.REQ_IMPORT_CONFIG) {
            SettingsTab.handleImportConfigResult(this, resultCode, data)
            return
        }
    }

    /** 公开供子 Tab（如插件页账号卡）跨页跳转使用；0=账号 */
    fun switchTab(idx: Int) {
        if (idx == currentTab) return
        // 离开设置页：注销日志监听（否则监听器随切页累积，日志重复显示且泄漏旧页面）
        if (currentTab == 3) settingsTab.onDetach()
        currentTab = idx
        for ((i, pair) in tabs) {
            val tv = pair.first
            val on = i == idx
            pair.second.visibility = if (on) View.VISIBLE else View.GONE
            tv.setTextColor(resources.getColor(if (on) R.color.colorPrimary else R.color.textSub, null))
            tv.setTextSize(if (on) 15f else 14f)
            tv.setTypeface(tv.typeface, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            updateTabIndicator(tv, on)
        }
        when (idx) {
            0 -> accountTab.attach()
            1 -> personaTab.refresh()
            2 -> pluginTab.refresh()
            3 -> settingsTab.refresh()
        }
        // 机顶盒 DPad：切页后把焦点放到新页第一个可操作控件，方向键即可开始操作
        val shown = tabs[idx]?.second
        shown?.post {
            if (shown.isShown) {
                DpadFocus.firstFocusable(shown)?.requestFocus()
            }
        }
    }

    /** 页签选中指示：文字下方一条圆角小横条（不动 background，避免与焦点高亮互相覆盖） */
    private fun updateTabIndicator(tv: TextView, on: Boolean) {
        if (!on) {
            tv.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
            return
        }
        val d = resources.displayMetrics.density
        val ind = android.graphics.drawable.GradientDrawable().apply {
            setColor(resources.getColor(R.color.colorPrimary, null))
            cornerRadius = 2 * d
            setSize((30 * d).toInt(), (3 * d).toInt())
        }
        tv.setCompoundDrawablesWithIntrinsicBounds(null, null, null, ind)
        tv.compoundDrawablePadding = (2 * d).toInt()
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    /** Android 9 及以下申请存储权限（聊天记录/导出文件） */
    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT <= 28) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                    ),
                    200,
                )
            }
        }
    }
}

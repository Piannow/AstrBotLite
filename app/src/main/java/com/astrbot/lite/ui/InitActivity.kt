package com.astrbot.lite.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.astrbot.lite.R
import com.astrbot.lite.core.Config
import com.astrbot.lite.core.LogLine
import com.astrbot.lite.core.Logs
import com.astrbot.lite.core.PersonaManager
import com.astrbot.lite.core.PluginManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 初始化页：进度条 / 终端日志 一键切换（还原 base.apk 交互）
 */
class InitActivity : Activity() {

    companion object {
        /** 崩溃弹窗是否已在启动页展示过（进程内标记，避免 MainActivity 重复弹同一份崩溃） */
        @Volatile
        var crashDialogShown: Boolean = false
    }
    private val handler = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private var showLog = false

    private val logListener: (LogLine) -> Unit = { line ->
        handler.post {
            logText.append("[" + fmt.format(Date(line.time)) + "] [" + line.level + "] " + line.text + "\n")
            // 1.7.237：限长，避免初始化阶段长时间刷日志撑爆 TextView
            val t = logText.text
            if (t.length > 20_000) logText.text = t.subSequence(t.length - 20_000, t.length)
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_init)
        progress = findViewById(R.id.initProgress)
        status = findViewById(R.id.initStatus)
        logText = findViewById(R.id.initLog)
        logScroll = findViewById(R.id.logScroll)
        findViewById<View>(R.id.initRoot).setOnClickListener { toggle() }

        Logs.clear()
        Logs.addListener(logListener)
        startInit()
    }

    private fun toggle() {
        showLog = !showLog
        findViewById<View>(R.id.progressPanel).visibility = if (showLog) View.GONE else View.VISIBLE
        logScroll.visibility = if (showLog) View.VISIBLE else View.GONE
    }

    private fun startInit() {
        Thread {
            postProgress(5, "初始化数据目录...")
            PersonaManager.seedDefaults(this)
            postProgress(40, "创建插件目录...")
            PluginManager.installed()
            postProgress(65, "检查配置...")
            Config.initialized = true
            postProgress(85, "初始化完成")
            Thread.sleep(250)
            postProgress(100, "完成")
            Thread.sleep(250)
            runOnUiThread {
                val go = if (!Config.isQqConfigured() || !Config.isAiConfigured()) {
                    Intent(this, WizardActivity::class.java)
                } else {
                    Intent(this, MainActivity::class.java)
                }
                startActivity(go)
                finish()
            }
        }.start()
    }

    private fun postProgress(p: Int, text: String) {
        handler.post {
            progress.progress = p
            status.text = text
        }
        Logs.info(text)
    }

    override fun onDestroy() {
        super.onDestroy()
        Logs.removeListener(logListener)
    }
}

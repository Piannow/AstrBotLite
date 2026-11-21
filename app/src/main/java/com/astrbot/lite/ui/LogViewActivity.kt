package com.astrbot.lite.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.astrbot.lite.R
import com.astrbot.lite.core.LogLine
import com.astrbot.lite.core.Logs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏运行日志查看器：可滑动、自动跟随最新、一键回顶部/复制
 */
class LogViewActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private val logListener: (LogLine) -> Unit = { line ->
        handler.post {
            logText.append("[" + fmt.format(Date(line.time)) + "] [" + line.level + "] " + line.text + "\n")
            // 1.7.237：限长（约 6 万字），避免全屏日志无限堆积拖垮页面
            val t = logText.text
            if (t.length > 60_000) logText.text = t.subSequence(t.length - 60_000, t.length)
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnTop).setOnClickListener { logScroll.fullScroll(View.FOCUS_UP) }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", Logs.text()))
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
        }

        Logs.addListener(logListener)
        logText.text = Logs.textTail(60_000)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logs.removeListener(logListener)
    }
}

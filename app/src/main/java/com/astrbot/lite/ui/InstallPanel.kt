package com.astrbot.lite.ui

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.astrbot.lite.core.InstallProgress

/**
 * 插件安装进度（1.7.95 非模态版）：挂在宿主 Activity 内容区**顶部的悬浮进度条**，
 * 不遮挡操作（旧版全屏 Dialog=黑屏不可操作、需手动关闭，已按用户要求废弃）：
 * - 实时阶段文本 + 百分比 + 进度条；
 * - **安装/转换完成自动消失**（成功 2.5s、失败 5s，同时有 Toast 结果）；
 * - 进行中可点 × 收起（后台安装继续）。
 */
object InstallPanel {

    private val handler = Handler(Looper.getMainLooper())
    private var barView: View? = null
    private var hostContent: ViewGroup? = null

    fun show(act: Activity, title: String, taskId: String? = null) {
        InstallProgress.start("准备安装…")
        handler.removeCallbacksAndMessages(null)
        val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        // 同宿主重复打开：先摘旧条
        barView?.let { old ->
            try {
                content.removeView(old)
            } catch (_: Exception) {}
        }

        val stageText = TextView(act).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#ECEFF1"))
            text = "准备安装…"
        }
        val pctText = TextView(act).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#4FC3F7"))
            text = "0%"
        }
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val closeBtn = TextView(act).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(dp(act, 12), 0, dp(act, 8), 0)
            setOnClickListener { detach() }
        }
        val headRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(act).apply {
                    textSize = 13f
                    setTextColor(Color.parseColor("#4FC3F7"))
                    text = title
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(pctText)
            addView(closeBtn)
        }
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(act, 14), dp(act, 10), dp(act, 14), dp(act, 10))
            setBackgroundColor(Color.parseColor("#263238"))
            elevation = dp(act, 8).toFloat()
            addView(headRow)
            addView(
                stageText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(act, 4) },
            )
            addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(act, 6) },
            )
        }
        content.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        barView = root
        hostContent = content

        // 进度驱动
        InstallProgress.listener = { s, p ->
            stageText.text = s
            bar.progress = p
            pctText.text = "$p%"
            if (!InstallProgress.active) {
                // 完成：终态短暂展示后**自动消失**（成功 2.5s / 失败 5s）
                val ok = !s.contains("失败") && !s.contains("！")
                stageText.setTextColor(if (ok) Color.parseColor("#66BB6A") else Color.parseColor("#EF5350"))
                handler.postDelayed({ detach() }, if (ok) 2500L else 5000L)
            }
        }
        // 展示时若已结束（极快完成）：立即走终态
        if (!InstallProgress.active && InstallProgress.percent >= 100) {
            stageText.text = InstallProgress.stage
            bar.progress = 100
            pctText.text = "100%"
            val ok = !InstallProgress.stage.contains("失败") && !InstallProgress.stage.contains("！")
            stageText.setTextColor(if (ok) Color.parseColor("#66BB6A") else Color.parseColor("#EF5350"))
            handler.postDelayed({ detach() }, if (ok) 2500L else 5000L)
        }
    }

    /** 摘除进度条并释放监听（后台安装不受影响） */
    private fun detach() {
        handler.removeCallbacksAndMessages(null)
        InstallProgress.listener = null
        barView?.let { v ->
            try {
                hostContent?.removeView(v)
            } catch (_: Exception) {}
        }
        barView = null
        hostContent = null
    }

    private fun dp(act: Activity, v: Int): Int = (v * act.resources.displayMetrics.density).toInt()
}

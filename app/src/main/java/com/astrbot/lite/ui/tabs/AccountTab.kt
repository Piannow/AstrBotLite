package com.astrbot.lite.ui.tabs

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.astrbot.lite.R
import com.astrbot.lite.core.Account
import com.astrbot.lite.core.AccountManager
import com.astrbot.lite.core.PersonaManager
import com.astrbot.lite.service.BotService

/**
 * 账号标签页：账号卡网格 + 底部菜单（启动/停止、重启、设置账号、删除）。
 * 底层由 BotService 按 accountId 缓存独立 BotEngine，支持多账号同时在线。
 */
class AccountTab(private val act: Activity) : LinearLayout(act) {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var gridContainer: LinearLayout
    private lateinit var tvCount: TextView
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateCards()
            handler.postDelayed(this, 3000L)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))

        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(top, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        top.addView(TextView(context).apply {
            text = "账号"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color(R.color.textMain))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tvCount = TextView(context).apply {
            textSize = 12f
            setTextColor(color(R.color.textSub))
        }
        top.addView(tvCount)

        val scroll = ScrollView(context)
        addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        gridContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        scroll.addView(gridContainer)

        updateCards()
    }

    fun attach() {
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    fun detach() {
        handler.removeCallbacks(refreshRunnable)
    }

    /** 兼容 MainActivity.onActivityResult 调用（AccountTab 暂无该需求，直接返回 false） */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean = false

    fun refresh() {
        updateCards()
    }

    private fun updateCards() {
        if (!::gridContainer.isInitialized) return
        val accounts = AccountManager.loadAll()
        tvCount.text = accounts.size.toString() + " 个账号"
        gridContainer.removeAllViews()

        var i = 0
        while (i < accounts.size) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(0, 0, 0, dp(12))
            }
            gridContainer.addView(row)

            for (j in 0..1) {
                if (i + j < accounts.size) {
                    val acc = accounts[i + j]
                    val card = buildAccountCard(acc)
                    row.addView(card, LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply {
                        if (j == 0) marginEnd = dp(6) else marginStart = dp(6)
                    })
                } else {
                    row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                }
            }
            i += 2
        }

        val addRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        gridContainer.addView(addRow)
        addRow.addView(buildAddCard(), LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = dp(6) })
        addRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(6) })
        post { com.astrbot.lite.ui.DpadFocus.apply(gridContainer) }
    }

    private fun buildAccountCard(acc: Account): View {
        val online = BotService.engines()[acc.id]?.isRunning == true
        val active = acc.id == AccountManager.activeId()

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(color(R.color.card))
                cornerRadius = 12 * resources.displayMetrics.density
                setStroke(
                    if (active) dp(2) else dp(1),
                    if (active) color(R.color.colorPrimary) else color(R.color.divider)
                )
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { openAccountMenu(acc) }
            com.astrbot.lite.ui.DpadFocus.highlightGroup(this)
        }

        card.addView(TextView(context).apply {
            text = acc.name
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color(R.color.textMain))
            setPadding(0, 0, 0, dp(6))
        })

        card.addView(kvRow("环境", if (acc.env == "sandbox") "沙箱" else "正式"))
        card.addView(kvRowStatus("在线状态", online))
        card.addView(kvRow("AppID", maskAppId(acc.appId)))
        val persona = try { PersonaManager.active().name } catch (_: Throwable) { "—" }
        card.addView(kvRow("人设卡", persona))

        return card
    }

    private fun buildAddCard(): View {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(24), dp(12), dp(24))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(color(R.color.card))
                cornerRadius = 12 * resources.displayMetrics.density
                setStroke(dp(1), color(R.color.divider))
            }
            isClickable = true
            isFocusable = true
            addView(TextView(context).apply {
                text = "\uD83D\uDC64"
                textSize = 32f
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "新增账号…"
                textSize = 12f
                setTextColor(color(R.color.textSub))
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
            setOnClickListener { showAddAccountDialog() }
            com.astrbot.lite.ui.DpadFocus.highlightGroup(this)
        }
    }

    private fun kvRow(k: String, v: String): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(TextView(context).apply {
            text = k
            textSize = 11f
            setTextColor(color(R.color.textSub))
            width = dp(60)
        })
        row.addView(TextView(context).apply {
            text = v
            textSize = 13f
            setTextColor(color(R.color.textMain))
        })
        return row
    }

    private fun kvRowStatus(k: String, online: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(TextView(context).apply {
            text = k
            textSize = 11f
            setTextColor(color(R.color.textSub))
            width = dp(60)
        })
        row.addView(TextView(context).apply {
            text = "\u25CF"
            textSize = 11f
            setTextColor(if (online) color(R.color.ok) else color(R.color.err))
        })
        row.addView(TextView(context).apply {
            text = if (online) " 在线" else " 离线"
            textSize = 13f
            setTextColor(color(R.color.textMain))
        })
        return row
    }

    private fun maskAppId(id: String): String {
        if (id.length <= 6) return id
        return id.substring(0, 3) + "..." + id.substring(id.length - 3)
    }

    private fun openAccountMenu(acc: Account) {
        val online = BotService.engines()[acc.id]?.isRunning == true
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items.add((if (online) "停止" else "启动") to {
            BotService.startAccount(act, acc.id, !online)
            Toast.makeText(act, acc.name + (if (online) " 已停止" else " 已启动"), Toast.LENGTH_SHORT).show()
            handler.postDelayed({ updateCards() }, 500)
        })
        items.add("重启" to {
            val eng = BotService.engines()[acc.id]
            if (eng != null) {
                Toast.makeText(act, acc.name + " 正在重启…", Toast.LENGTH_SHORT).show()
                eng.restart()
                handler.postDelayed({ updateCards() }, 1000)
            } else {
                Toast.makeText(act, acc.name + " 未在运行", Toast.LENGTH_SHORT).show()
            }
        })
        items.add("设置账号（QQ 接入 + AI 配置）" to {
            val i = android.content.Intent(act, com.astrbot.lite.ui.WizardActivity::class.java)
            i.putExtra("account_id", acc.id)
            act.startActivity(i)
        })
        if (AccountManager.loadAll().size > 1) {
            items.add("删除此账号" to {
                AlertDialog.Builder(act)
                    .setTitle("删除账号")
                    .setMessage("确定删除「" + acc.name + "」吗？此操作不可撤销。")
                    .setPositiveButton("删除") { _, _ ->
                        // 修复：先停服务（失败不阻断删除），再检查 remove 返回值
                        try { BotService.startAccount(act, acc.id, false) } catch (_: Throwable) {}
                        val ok = AccountManager.remove(acc.id)
                        if (ok) {
                            updateCards()
                            Toast.makeText(act, "已删除「" + acc.name + "」", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(act, "删除失败：账号不存在或为唯一账号", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            })
        }
        showActionSheet(acc.name, items)
    }

    private fun showActionSheet(title: String, actions: List<Pair<String, () -> Unit>>) {
        val v = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val dlg = AlertDialog.Builder(act)
            .setTitle(title)
            .setView(v)
            .create()
        val buttons = mutableListOf<Button>()
        for ((label, action) in actions) {
            val b = Button(context).apply {
                text = label
                isFocusable = true
                isFocusableInTouchMode = true
                com.astrbot.lite.ui.DpadFocus.highlight(this)
                setOnClickListener {
                    dlg.dismiss()
                    action()
                }
            }
            buttons.add(b)
            v.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) })
        }
        val cancel = Button(context).apply {
            text = "取消"
            isFocusable = true
            isFocusableInTouchMode = true
            com.astrbot.lite.ui.DpadFocus.highlight(this)
            setOnClickListener { dlg.dismiss() }
        }
        buttons.add(cancel)
        v.addView(cancel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        dlg.setOnShowListener {
            buttons.firstOrNull()?.requestFocus()
        }
        dlg.show()
    }

    private fun showAddAccountDialog() {
        val etName = EditText(context).apply { hint = "账号名称（如：主力号）"; setTextSize(13f) }
        val etAppId = EditText(context).apply { hint = "AppID"; setTextSize(13f) }
        val etSecret = EditText(context).apply { hint = "AppSecret"; setTextSize(13f) }
        val spEnv = Spinner(context).apply {
            adapter = android.widget.ArrayAdapter(
                context, android.R.layout.simple_spinner_item,
                arrayOf("正式环境（prod）", "沙箱环境（sandbox）")
            )
        }
        try { com.astrbot.lite.ui.DpadFocus.escapeSpinner(spEnv) } catch (_: Throwable) {}
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        col.addView(etName)
        col.addView(etAppId)
        col.addView(etSecret)
        col.addView(spEnv)

        val dlg = AlertDialog.Builder(act)
            .setTitle("新增账号")
            .setView(col)
            .setPositiveButton("添加") { _, _ ->
                val name = etName.text.toString().trim().ifEmpty {
                    "机器人" + (AccountManager.loadAll().size + 1)
                }
                val appId = etAppId.text.toString().trim()
                val secret = etSecret.text.toString().trim()
                if (appId.isEmpty()) {
                    Toast.makeText(act, "AppID 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val env = if (spEnv.selectedItemPosition == 1) "sandbox" else "prod"
                AccountManager.add(name, appId, secret, "https://api.sgroup.qq.com", env)
                updateCards()
                Toast.makeText(act, "已添加「" + name + "」", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                com.astrbot.lite.ui.DpadFocus.highlight(this)
            }
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                com.astrbot.lite.ui.DpadFocus.highlight(this)
            }
            etName.requestFocus()
        }
        dlg.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int): Int = resources.getColor(id, null)
}

package com.astrbot.lite.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.astrbot.lite.R
import com.astrbot.lite.core.ChatEntry
import com.astrbot.lite.core.ChatStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天记录管理：按「群/私聊会话」分组，支持多选批量删除、逐条编辑内容。
 * 记录文件：filesDir/chats/chat.log（页面顶部显示真实路径）
 */
class ChatHistoryActivity : Activity() {

    private lateinit var entries: MutableList<ChatEntry>
    private val selected = mutableSetOf<String>()
    private lateinit var listCol: LinearLayout
    private lateinit var tvSummary: TextView
    private lateinit var btnDelete: Button
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entries = ChatStore.loadAll().toMutableList()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
            setBackgroundColor(resources.getColor(R.color.bg, null))
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(
            Button(this).apply {
                text = "返回"
                setOnClickListener { finish() }
            },
        )
        top.addView(
            TextView(this).apply {
                text = "聊天记录"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(resources.getColor(R.color.textMain, null))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(top)

        tvSummary = TextView(this).apply {
            textSize = 12f
            setTextColor(resources.getColor(R.color.textSub, null))
            setPadding(0, dp(6), 0, dp(2))
        }
        root.addView(tvSummary)
        root.addView(
            TextView(this).apply {
                text = "存于：" + ChatStore.path()
                textSize = 10f
                setTextColor(resources.getColor(R.color.textSub, null))
                setTextIsSelectable(true)
                setPadding(0, 0, 0, dp(6))
            },
        )

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            Button(this).apply {
                text = "全选"
                setOnClickListener { toggleSelectAll() }
            },
        )
        btnDelete = Button(this).apply {
            text = "删除选中 (0)"
            setOnClickListener { confirmDelete() }
        }
        actions.addView(btnDelete)
        actions.addView(
            Button(this).apply {
                text = "刷新"
                setOnClickListener { reload() }
            },
        )
        root.addView(actions)

        val scroll = ScrollView(this)
        listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listCol)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        setContentView(root)
        DpadFocus.apply(root)
        refresh()
    }

    private fun reload() {
        entries = ChatStore.loadAll().toMutableList()
        selected.clear()
        refresh()
    }

    private fun refresh() {
        listCol.removeAllViews()
        val groups = entries.groupBy { it.session }.mapValues { it.value }
        val ordered = groups.entries.sortedByDescending { (_, v) ->
            v.maxOfOrNull { it.time } ?: 0L
        }
        tvSummary.text = "共 ${ordered.size} 个会话 / ${entries.size} 条消息"
        updateDeleteBtn()
        if (ordered.isEmpty()) {
            listCol.addView(
                TextView(this).apply {
                    text = "暂无聊天记录"
                    textSize = 14f
                    setTextColor(resources.getColor(R.color.textSub, null))
                    setPadding(0, dp(20), 0, 0)
                },
            )
            return
        }
        for ((key, list) in ordered) {
            listCol.addView(buildRow(key, list))
        }
    }

    private fun buildRow(key: String, list: List<ChatEntry>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setBackgroundColor(0xFFFFFFFF.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { openDetail(key) }
        }
        val cb = CheckBox(this).apply {
            isChecked = key in selected
            setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(key) else selected.remove(key)
                updateDeleteBtn()
            }
        }
        row.addView(cb)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
        }
        val last = list.maxByOrNull { it.time }
        info.addView(
            TextView(this).apply {
                text = sessionLabel(key)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(resources.getColor(R.color.textMain, null))
            },
        )
        info.addView(
            TextView(this).apply {
                text = "${list.size} 条 · 最后 " + fmt.format(Date(last?.time ?: 0L))
                textSize = 11f
                setTextColor(resources.getColor(R.color.textSub, null))
                setPadding(0, dp(2), 0, dp(2))
            },
        )
        info.addView(
            TextView(this).apply {
                text = (last?.content ?: "").replace("\n", " ").take(60)
                textSize = 11f
                setTextColor(resources.getColor(R.color.textSub, null))
            },
        )
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(
            Button(this).apply {
                text = "查看/编辑"
                setOnClickListener { openDetail(key) }
            },
        )
        return row
    }

    private fun sessionLabel(key: String): String {
        if (key.isBlank()) return "旧记录（未分组）"
        val kind = key.substringBefore(':', "")
        val id = key.substringAfter(':', "")
        return when (kind) {
            "group" -> "群聊 $id"
            "c2c", "private" -> "私聊 $id"
            else -> key
        }
    }

    private fun openDetail(key: String) {
        val items = entries.mapIndexedNotNull { i, e -> if (e.session == key) i to e else null }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(col) }
        for ((idx, e) in items) {
            val who = if (e.from == "user") "用户" else "机器人"
            val contentView = TextView(this).apply {
                text = "[${fmt.format(Date(e.time))}] [$who] ${e.content}"
                textSize = 13f
                setTextColor(resources.getColor(R.color.textMain, null))
                setPadding(0, dp(6), 0, dp(4))
            }
            col.addView(contentView)
            col.addView(
                Button(this).apply {
                    text = "编辑这条"
                    setOnClickListener {
                        editEntry(idx, e) { newText ->
                            contentView.text = "[${fmt.format(Date(e.time))}] [$who] $newText"
                        }
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        AlertDialog.Builder(this)
            .setTitle(sessionLabel(key) + "（" + items.size + " 条）")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun editEntry(index: Int, snapshot: ChatEntry, onSaved: (String) -> Unit) {
        val et = EditText(this).apply {
            setText(snapshot.content)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("编辑消息内容")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                val newText = et.text.toString()
                ChatStore.mutate { list ->
                    val cur = list.getOrNull(index)
                    if (cur != null &&
                        cur.time == snapshot.time &&
                        cur.from == snapshot.from &&
                        cur.session == snapshot.session
                    ) {
                        list[index] = cur.copy(content = newText)
                    } else {
                        // 索引漂移（期间发生过裁剪）时按时间+来源+会话找回
                        val j = list.indexOfFirst {
                            it.time == snapshot.time &&
                                it.from == snapshot.from &&
                                it.session == snapshot.session
                        }
                        if (j >= 0) list[j] = list[j].copy(content = newText)
                    }
                }
                entries = ChatStore.loadAll().toMutableList()
                onSaved(newText)
                refresh()
                Toast.makeText(this, "已保存到聊天记录文件", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleSelectAll() {
        val keys = entries.map { it.session }.toSet()
        if (selected.containsAll(keys)) {
            selected.clear()
        } else {
            selected.clear()
            selected.addAll(keys)
        }
        refresh()
    }

    private fun updateDeleteBtn() {
        btnDelete.text = "删除选中 (${selected.size})"
        btnDelete.isEnabled = selected.isNotEmpty()
    }

    private fun confirmDelete() {
        if (selected.isEmpty()) return
        val keys = selected.toSet()
        AlertDialog.Builder(this)
            .setTitle("批量删除")
            .setMessage("将删除 ${keys.size} 个会话的全部聊天记录，且不可恢复。确定？")
            .setPositiveButton("删除") { _, _ ->
                ChatStore.mutate { list -> list.removeAll { it.session in keys } }
                selected.clear()
                reload()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

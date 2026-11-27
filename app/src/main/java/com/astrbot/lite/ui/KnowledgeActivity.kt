package com.astrbot.lite.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.astrbot.lite.R
import com.astrbot.lite.core.KnowledgeBase
import com.astrbot.lite.core.KnowledgeEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 知识库管理页：列表 + 新增 + 删除。
 * 入库只能通过这里手动完成（自动入库已在 BotEngine 关闭）。
 */
class KnowledgeActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var adapter: EntryAdapter
    private lateinit var tvCount: TextView
    private val entries = mutableListOf<KnowledgeEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(color(R.color.bg))
        }
        setContentView(root)

        // 顶部标题
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(
            top,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        top.addView(
            TextView(this).apply {
                text = "知识库"
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(color(R.color.textMain))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        tvCount = TextView(this).apply {
            textSize = 12f
            setTextColor(color(R.color.textSub))
        }
        top.addView(tvCount)

        // 按钮行
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(btnRow)
        btnRow.addView(
            Button(this).apply {
                text = "+ 新增"
                setOnClickListener { showAddDialog() }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        btnRow.addView(
            Button(this).apply {
                text = "重建索引"
                setOnClickListener { rebuildIndex() }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        btnRow.addView(
            Button(this).apply {
                text = "清空"
                setOnClickListener { confirmClear() }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        // 列表
        adapter = EntryAdapter()
        listView = ListView(this).apply {
            adapter = this@KnowledgeActivity.adapter
            setOnItemClickListener { _, _, position, _ -> showEntryMenu(position) }
        }
        root.addView(
            listView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        refresh()
    }

    private fun refresh() {
        entries.clear()
        try {
            entries.addAll(KnowledgeBase.list())
        } catch (_: Throwable) {}
        tvCount.text = entries.size.toString() + " 条"
        adapter.notifyDataSetChanged()
    }

    private fun showAddDialog() {
        val etTitle = EditText(this).apply {
            hint = "标题（必填）"
            textSize = 13f
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val etContent = EditText(this).apply {
            hint = "内容（必填，支持多行）"
            textSize = 13f
            minLines = 4
            maxLines = 8
            isSingleLine = false
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val etTags = EditText(this).apply {
            hint = "标签（选填，逗号分隔）"
            textSize = 13f
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        col.addView(etTitle)
        col.addView(
            etContent,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        col.addView(
            etTags,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        val dlg = AlertDialog.Builder(this)
            .setTitle("新增知识条目")
            .setView(col)
            .setPositiveButton("保存") { _, _ ->
                val title = etTitle.text.toString().trim()
                val content = etContent.text.toString().trim()
                val tags = etTags.text.toString().trim()
                if (title.isEmpty() || content.isEmpty()) {
                    Toast.makeText(this, "标题和内容不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val entry = KnowledgeEntry(
                    id = "k_" + System.currentTimeMillis(),
                    title = title,
                    content = content,
                    source = "手动录入",
                    sourcePerson = "",
                    createdAt = System.currentTimeMillis(),
                    tags = tags,
                )
                try {
                    KnowledgeBase.add(entry)
                    Thread {
                        try {
                            val v = com.astrbot.lite.core.EmbeddingEngine.embed(entry.title + "\n" + entry.content)
                            if (v != null) com.astrbot.lite.core.EmbeddingEngine.saveVector(entry.id, v)
                        } catch (_: Throwable) {}
                    }.apply {
                        isDaemon = true
                        start()
                    }
                    refresh()
                    Toast.makeText(this, "已入库", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "保存失败: " + e.message, Toast.LENGTH_LONG).show()
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
            etTitle.requestFocus()
        }
        dlg.show()
    }

    private fun showEntryMenu(position: Int) {
        val entry = entries.getOrNull(position) ?: return
        val items = arrayOf("查看全文", "删除")
        AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showFullText(entry)
                    1 -> confirmDelete(entry)
                }
            }
            .show()
    }

    private fun showFullText(entry: KnowledgeEntry) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val info = StringBuilder()
        info.append("标题：").append(entry.title).append("\n\n")
        info.append("内容：\n").append(entry.content).append("\n\n")
        if (entry.tags.isNotBlank()) info.append("标签：").append(entry.tags).append("\n")
        if (entry.sourcePerson.isNotBlank()) info.append("来源人：").append(entry.sourcePerson).append("\n")
        info.append("来源：").append(entry.source).append("\n")
        info.append("时间：").append(fmt.format(Date(entry.createdAt)))
        AlertDialog.Builder(this)
            .setTitle("条目详情")
            .setMessage(info.toString())
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun confirmDelete(entry: KnowledgeEntry) {
        val dlg = AlertDialog.Builder(this)
            .setTitle("删除条目")
            .setMessage("确定删除「" + entry.title + "」吗？")
            .setPositiveButton("删除") { _, _ ->
                try {
                    KnowledgeBase.remove(entry.id)
                    try {
                        com.astrbot.lite.core.EmbeddingEngine.deleteVector(entry.id)
                    } catch (_: Throwable) {}
                    refresh()
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "删除失败: " + e.message, Toast.LENGTH_LONG).show()
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

    private fun confirmClear() {
        val dlg = AlertDialog.Builder(this)
            .setTitle("清空知识库")
            .setMessage("将删除全部 " + entries.size + " 条知识，不可恢复。确定？")
            .setPositiveButton("确定") { _, _ ->
                try {
                    KnowledgeBase.clear()
                    try {
                        com.astrbot.lite.core.EmbeddingEngine.deleteAll()
                    } catch (_: Throwable) {}
                    refresh()
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "清空失败: " + e.message, Toast.LENGTH_LONG).show()
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

    private fun rebuildIndex() {
        if (entries.isEmpty()) {
            Toast.makeText(this, "知识库为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (!com.astrbot.lite.core.EmbeddingEngine.configured()) {
            Toast.makeText(this, "请先在设置里配置知识库嵌入模型", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("重建索引")
            .setMessage("将为全部 " + entries.size + " 条知识重新生成向量（耗时取决于条目数和网络），确定？")
            .setPositiveButton("开始") { _, _ -> doRebuild() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doRebuild() {
        val dialog = android.app.ProgressDialog(this).apply {
            setTitle("重建索引")
            setMessage("正在处理...")
            setCancelable(false)
            show()
        }
        val snapshot = entries.toList()
        Thread {
            var ok = 0
            var fail = 0
            for ((idx, e) in snapshot.withIndex()) {
                runOnUiThread { dialog.setMessage("正在处理 " + (idx + 1) + "/" + snapshot.size + " ...") }
                try {
                    val v = com.astrbot.lite.core.EmbeddingEngine.embed(e.title + "\n" + e.content)
                    if (v != null) {
                        com.astrbot.lite.core.EmbeddingEngine.saveVector(e.id, v)
                        ok++
                    } else {
                        fail++
                    }
                } catch (_: Throwable) {
                    fail++
                }
            }
            runOnUiThread {
                dialog.dismiss()
                Toast.makeText(this, "重建完成：成功 " + ok + " / 失败 " + fail, Toast.LENGTH_LONG).show()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    inner class EntryAdapter : BaseAdapter() {
        private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        override fun getCount(): Int = entries.size
        override fun getItem(p: Int): Any = entries[p]
        override fun getItemId(p: Int): Long = p.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val e = entries[position]
            val row = LinearLayout(this@KnowledgeActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setBackgroundColor(color(R.color.card))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = dp(8)
            row.layoutParams = lp

            row.addView(
                TextView(this@KnowledgeActivity).apply {
                    text = e.title
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(color(R.color.textMain))
                },
            )

            row.addView(
                TextView(this@KnowledgeActivity).apply {
                    text = if (e.content.length > 120) e.content.take(120) + "…" else e.content
                    textSize = 12f
                    setTextColor(color(R.color.textSub))
                    setPadding(0, dp(4), 0, dp(4))
                },
            )

            val bottom = StringBuilder()
            if (e.tags.isNotBlank()) bottom.append("标签：").append(e.tags).append("  ")
            bottom.append(fmt.format(Date(e.createdAt)))
            row.addView(
                TextView(this@KnowledgeActivity).apply {
                    text = bottom.toString()
                    textSize = 10f
                    setTextColor(color(R.color.textSub))
                },
            )

            return row
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int): Int = resources.getColor(id, null)
}

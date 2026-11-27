package com.astrbot.lite.ui.tabs

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import com.astrbot.lite.core.Persona
import com.astrbot.lite.core.PersonaManager

/**
 * 人设卡标签页：新建 / 编辑 / 删除 / 设为当前 / 导入 / 导出
 */
class PersonaTab(private val act: Activity) : LinearLayout(act) {

    private var personas: List<Persona> = emptyList()
    private var selectedIndex = 0
    private lateinit var adapter: PersonaAdapter
    private lateinit var listView: ListView
    private var pendingImport: Uri? = null

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))

        addView(
            TextView(context).apply {
                text = "人设卡（决定机器人的性格与说话方式）"
                textSize = 15f
                setTextColor(color(R.color.textSub))
            },
        )

        adapter = PersonaAdapter()
        listView = ListView(context).apply {
            adapter = this@PersonaTab.adapter
            choiceMode = android.widget.AbsListView.CHOICE_MODE_SINGLE
            setOnItemClickListener { _, _, position, _ ->
                // 触摸/确定键点击也要刷新高亮（原先只改索引不刷新，高亮会与选中脱节）
                if (position in personas.indices && position != selectedIndex) {
                    selectedIndex = position
                    this@PersonaTab.adapter.notifyDataSetChanged()
                }
            }
            // 机顶盒 DPad：方向键上下移动选中行时实时更新高亮，让"光标"清晰可见
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                    if (position in personas.indices && position != selectedIndex) {
                        selectedIndex = position
                        this@PersonaTab.adapter.notifyDataSetChanged()
                    }
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        addView(
            listView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        addView(row)
        row.addView(
            btn("设为当前") {
                val p = selected() ?: return@btn toast("请先选择人设卡")
                PersonaManager.setActive(p.id)
                refresh()
                toast("已设为当前人设：${p.name}")
            },
        )
        row.addView(
            btn("编辑") {
                val p = selected() ?: return@btn toast("请先选择人设卡")
                editDialog(p)
            },
        )
        row.addView(
            btn("删除") {
                val p = selected() ?: return@btn toast("请先选择人设卡")
                if (personas.size <= 1) return@btn toast("至少保留一张人设卡")
                PersonaManager.delete(p.id)
                refresh()
                toast("已删除：${p.name}")
            },
        )

        val row2 = LinearLayout(context).apply { orientation = HORIZONTAL }
        addView(row2)
        row2.addView(btn("新建") { newDialog() })
        row2.addView(
            btn("导出(分享)") {
                val p = selected() ?: return@btn toast("请先选择人设卡")
                export(p)
            },
        )
        row2.addView(
            btn("导入") {
                importFromFile()
            },
        )

        refresh()
    }

    fun refresh() {
        personas = PersonaManager.list()
        selectedIndex = selectedIndex.coerceIn(0, (personas.size - 1).coerceAtLeast(0))
        adapter.notifyDataSetChanged()
    }

    private fun selected(): Persona? = personas.getOrNull(selectedIndex) ?: personas.firstOrNull()

    private fun btn(text: String, onClick: () -> Unit): Button = Button(context).apply {
        this.text = text
        textSize = 13f
        setOnClickListener { onClick() }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(4)
        }
    }

    private fun newDialog() {
        val name = EditText(context).apply { hint = "人设名称（如：学姐）" }
        val prompt = EditText(context).apply {
            hint = "人设描述 / 系统提示词"
            minLines = 3
            isSingleLine = false
        }
        val v = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(name)
            addView(
                prompt,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }
        AlertDialog.Builder(act)
            .setTitle("新建人设卡")
            .setView(v)
            .setPositiveButton("保存") { _, _ ->
                val n = name.text.toString().trim()
                val p = prompt.text.toString().trim()
                if (n.isEmpty()) {
                    toast("请填写人设名称")
                    return@setPositiveButton
                }
                PersonaManager.save(
                    Persona(
                        id = PersonaManager.newPersonaId(),
                        name = n,
                        avatar = "🎭",
                        description = "",
                        prompt = p.ifEmpty { "你是$n。请用中文自然、友好地聊天。" },
                        greeting = "",
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                refresh()
                toast("已创建：$n")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editDialog(p: Persona) {
        val name = EditText(context).apply { setText(p.name) }
        val prompt = EditText(context).apply {
            setText(p.prompt)
            minLines = 4
            isSingleLine = false
        }
        val v = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(name)
            addView(
                prompt,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }
        AlertDialog.Builder(act)
            .setTitle("编辑人设卡")
            .setView(v)
            .setPositiveButton("保存") { _, _ ->
                PersonaManager.save(
                    p.copy(
                        name = name.text.toString().trim().ifEmpty { p.name },
                        prompt = prompt.text.toString().trim().ifEmpty { p.prompt },
                    ),
                )
                refresh()
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun export(p: Persona) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "人设卡：${p.name}")
            putExtra(Intent.EXTRA_TEXT, PersonaManager.exportJson(p))
        }
        act.startActivity(Intent.createChooser(send, "导出人设卡"))
    }

    private fun importFromFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        act.startActivityForResult(intent, REQ_IMPORT)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_IMPORT) return false
        if (resultCode != Activity.RESULT_OK || data?.data == null) return true
        val uri = data.data ?: return true
        try {
            val text = act.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (text.isNullOrBlank()) {
                toast("文件为空")
            } else {
                val p = PersonaManager.importJson(text)
                refresh()
                toast("导入成功：${p.name}")
            }
        } catch (e: Exception) {
            toast("导入失败：${e.message}")
        }
        return true
    }

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int): Int = resources.getColor(id, null)

    inner class PersonaAdapter : BaseAdapter() {
        override fun getCount(): Int = personas.size
        override fun getItem(p: Int): Any = personas[p]
        override fun getItemId(p: Int): Long = p.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val p = personas[position]
            val active = p.id == PersonaManager.active().id
            val v = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                // 机顶盒 DPad：方向键选中的行用高亮背景；其次标记当前生效人设
                setBackgroundColor(
                    when {
                        position == selectedIndex -> Color.parseColor("#BBDEFB")
                        active -> Color.parseColor("#E8EAF6")
                        else -> Color.TRANSPARENT
                    },
                )
            }
            v.addView(
                TextView(context).apply {
                    text = "${p.avatar} ${p.name}${if (active) "  (当前)" else ""}"
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(color(if (active) R.color.colorPrimary else R.color.textMain))
                },
            )
            if (p.description.isNotBlank()) {
                v.addView(
                    TextView(context).apply {
                        text = p.description
                        textSize = 12f
                        setTextColor(color(R.color.textSub))
                    },
                )
            }
            v.addView(
                TextView(context).apply {
                    text = p.prompt.take(80) + if (p.prompt.length > 80) "…" else ""
                    textSize = 12f
                    setTextColor(color(R.color.textSub))
                },
            )
            return v
        }
    }

    companion object {
        const val REQ_IMPORT = 2001
    }
}

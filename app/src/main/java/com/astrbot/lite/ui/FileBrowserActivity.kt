package com.astrbot.lite.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.astrbot.lite.R
import com.astrbot.lite.core.Logs
import java.io.File

/**
 * 文件浏览器（恢复 1.7.222 功能）：从存储根逐级浏览，选中文件返回 URI。
 * 用于「ZIP 安装」「导入配置」等需要本地文件的场景，弥补系统文件选择器缺失。
 *
 * 返回：RESULT_OK + Intent.data = Uri（file://）
 * 入参：extra "start" 起始目录（可选，默认存储根）
 *       extra "ext" 文件后缀过滤（可选，如 ".zip"，逗号分隔多个）
 */
class FileBrowserActivity : Activity() {

    data class FileItem(
        val file: File,
        val name: String,
        val isDir: Boolean,
        val size: Long
    )

    private lateinit var tvPath: TextView
    private lateinit var adapter: FileAdapter
    private lateinit var lv: ListView
    private var curDir: File = Environment.getExternalStorageDirectory()
    private var extFilter: List<String> = emptyList()
    private var items: List<FileItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra("start")
        if (!start.isNullOrBlank()) {
            val f = File(start)
            if (f.isDirectory) curDir = f
        }
        extFilter = intent.getStringExtra("ext")
            ?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotBlank() }
            ?: emptyList()
        buildUI()
        refresh()
        Logs.info("[文件浏览] 打开 $curDir")
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        setContentView(root)

        // 标题栏：当前路径 + 返回上级
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(titleBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        tvPath = TextView(this).apply {
            textSize = 12f
            setTextColor(color(R.color.textSub))
            maxLines = 1
        }
        titleBar.addView(tvPath, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleBar.addView(makeBtn("上级") { goUp() })
        titleBar.addView(makeBtn("取消") { finish() })

        // 快捷入口
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(btnRow)
        btnRow.addView(makeBtn("根目录") { curDir = Environment.getExternalStorageDirectory(); refresh() })
        btnRow.addView(makeBtn("下载") { jumpTo(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)) })
        btnRow.addView(makeBtn("App 私有") { jumpTo(getExternalFilesDir(null) ?: return@makeBtn) })

        // 文件列表
        adapter = FileAdapter()
        lv = ListView(this).apply {
            adapter = this@FileBrowserActivity.adapter
            isFocusable = true
            isFocusableInTouchMode = true
            setItemsCanFocus(false)
            setOnItemClickListener { _, _, position, _ -> onItemClick(position) }
        }
        root.addView(lv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        lv.setOnFocusChangeListener { _, _ -> adapter.notifyDataSetChanged() }
        DpadFocus.apply(lv)
    }

    private fun makeBtn(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            setOnClickListener { action() }
        }

    private fun jumpTo(dir: File) {
        if (dir.isDirectory) {
            curDir = dir
            refresh()
        }
    }

    private fun goUp() {
        val parent = curDir.parentFile ?: return
        if (parent.canRead()) {
            curDir = parent
            refresh()
        }
    }

    private fun refresh() {
        tvPath.text = curDir.absolutePath
        val list = curDir.listFiles() ?: emptyArray()
        val dirs = list.filter { it.isDirectory && !it.isHidden && it.canRead() }
            .sortedBy { it.name.lowercase() }
        val files = list.filter { it.isFile && !it.isHidden && it.canRead() }
            .filter { f ->
                if (extFilter.isEmpty()) true
                else extFilter.any { f.name.lowercase().endsWith(it) }
            }
            .sortedBy { it.name.lowercase() }
        items = (dirs.map { FileItem(it, it.name, true, 0) } +
                files.map { FileItem(it, it.name, false, it.length()) })
        adapter.notifyDataSetChanged()
        Logs.debug("[文件浏览] ${curDir.absolutePath}：${dirs.size} 目录 + ${files.size} 文件")
    }

    private fun onItemClick(position: Int) {
        val item = items.getOrNull(position) ?: return
        if (item.isDir) {
            if (item.file.canRead()) {
                curDir = item.file
                refresh()
            } else {
                Toast.makeText(this, "无权限访问", Toast.LENGTH_SHORT).show()
            }
        } else {
            confirmSelection(item.file)
        }
    }

    private fun confirmSelection(file: File) {
        val result = Intent().apply { data = android.net.Uri.fromFile(file) }
        setResult(RESULT_OK, result)
        Logs.info("[文件浏览] 已选：${file.absolutePath}")
        finish()
    }

    override fun onBackPressed() {
        val parent = curDir.parentFile
        if (parent != null && parent.canRead() && curDir != Environment.getExternalStorageDirectory()) {
            curDir = parent
            refresh()
        } else {
            super.onBackPressed()
        }
    }

    private fun sizeText(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int): Int = resources.getColor(id, null)

    private inner class FileAdapter : android.widget.BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@FileBrowserActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            row.removeAllViews()
            val item = items[position]
            val icon = TextView(this@FileBrowserActivity).apply {
                text = if (item.isDir) "📁" else "📄"
                textSize = 18f
                setPadding(0, 0, dp(8), 0)
            }
            val nameCol = LinearLayout(this@FileBrowserActivity).apply { orientation = LinearLayout.VERTICAL }
            nameCol.addView(TextView(this@FileBrowserActivity).apply {
                text = item.name
                textSize = 14f
                setTextColor(color(R.color.textMain))
                maxLines = 1
            })
            if (!item.isDir) {
                nameCol.addView(TextView(this@FileBrowserActivity).apply {
                    text = sizeText(item.size)
                    textSize = 11f
                    setTextColor(color(R.color.textSub))
                })
            }
            row.addView(icon)
            row.addView(nameCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            DpadFocus.highlightGroup(row)
            return row
        }
    }

    companion object {
        /** 打开文件浏览器；extFilter 如 ".zip" 或 ".json,.txt" */
        fun open(act: Activity, reqCode: Int, extFilter: String = "", startDir: String = "") {
            val i = Intent(act, FileBrowserActivity::class.java).apply {
                if (extFilter.isNotBlank()) putExtra("ext", extFilter)
                if (startDir.isNotBlank()) putExtra("start", startDir)
            }
            act.startActivityForResult(i, reqCode)
        }
    }
}

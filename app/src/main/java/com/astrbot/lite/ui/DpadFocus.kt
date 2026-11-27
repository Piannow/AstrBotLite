package com.astrbot.lite.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView

/**
 * 机顶盒 DPad（方向键+确定）焦点增强：
 * - 给 focusable 控件套上醒目的焦点高亮（获得焦点 → 主色背景白字，失焦 → 恢复原样），
 *   让遥控器用户一眼看清"光标"落在哪个按钮上。
 * - 提供 firstFocusable 用于切换页签后把焦点放到第一个可操作控件。
 */
object DpadFocus {

    /** ScrollView 滚动到底部但不抢焦点（fullScroll 会把焦点移到底部控件） */
    fun android.widget.ScrollView.scrollDescendantToBottom() {
        val child = getChildAt(0) ?: return
        val target = child.bottom - height
        if (target > scrollY) smoothScrollTo(0, target)
    }

    internal const val TAG_HL = 0x0A12_0001
    private const val TAG_BG = 0x0A12_0002
    private const val TAG_TEXT = 0x0A12_0003

    /** 不可见：控件在页面焦点顺序表中的下标（用户看不到，仅用于方向键定位） */
    internal const val TAG_ORDER = 0x0A12_0004

    private const val FOCUS_COLOR = "#4C6FFF" // 主色：获得焦点的实心背景（现代亮蓝）
    private const val FOCUS_LIGHT = "#E8EEFF" // 浅主色：输入框用淡背景
    private const val FOCUS_STROKE = "#FFFFFF" // 焦点描边：内圈白线，提升层次

    /** 给单个控件加焦点高亮（幂等）。列表行内的按钮在 getView 里调用。 */
    /** 给可聚焦 ViewGroup 容器加高亮（仅改背景不改文字） */
    fun highlightGroup(v: View) {
        if (v.getTag(TAG_HL) == true) return
        v.setTag(TAG_HL, true)
        v.setOnFocusChangeListener { view, has ->
            val d = view.resources.displayMetrics.density
            if (has) {
                view.setTag(TAG_BG, view.background)
                view.background = GradientDrawable().apply {
                    setColor(Color.parseColor(FOCUS_LIGHT))
                    cornerRadius = 8 * d
                    setStroke((2 * d).toInt(), Color.parseColor(FOCUS_COLOR))
                }
                view.elevation = 6 * d
            } else {
                val orig = view.getTag(TAG_BG) as? android.graphics.drawable.Drawable
                if (orig != null) {
                    view.background = orig
                } else {
                    view.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                }
                view.elevation = 0f
            }
        }
    }

    fun highlight(v: View) {
        // 容器（ScrollView/LinearLayout 等）不做背景覆盖：容器拿焦点时整块变蓝且确定键无响应，
        // 焦点应始终落在叶子控件上（firstFocusable 已保证），这里再兜一层。
        if (v is ViewGroup) return
        if (v.getTag(TAG_HL) == true) return
        v.setTag(TAG_HL, true)
        v.setOnFocusChangeListener { view, has ->
            val d = view.resources.displayMetrics.density
            if (has) {
                // 每次聚焦都重新备份当前背景/文字色：外部代码可能刚改过它们（如白名单/黑名单选中态切换），
                // 沿用首次备份会在失焦时恢复出过期样式（表现为"深蓝方框不消失"）
                view.setTag(TAG_BG, view.background)
                if (view is TextView) view.setTag(TAG_TEXT, view.currentTextColor)
                if (view is EditText) {
                    // 输入框：浅色底 + 主色描边（不反白，文字仍清晰）
                    view.background = GradientDrawable().apply {
                        setColor(Color.parseColor(FOCUS_LIGHT))
                        cornerRadius = 8 * d
                        setStroke((2 * d).toInt(), Color.parseColor(FOCUS_COLOR))
                    }
                    view.setTextColor(Color.parseColor("#1A1D26"))
                } else {
                    // 按钮/文本：圆角实心 + 白色描边，比原来的方块更精致醒目
                    view.background = GradientDrawable().apply {
                        setColor(Color.parseColor(FOCUS_COLOR))
                        cornerRadius = 8 * d
                        setStroke((2 * d).toInt(), Color.parseColor(FOCUS_STROKE))
                    }
                    if (view is TextView) view.setTextColor(Color.WHITE)
                    view.elevation = 6 * d
                }
            } else {
                val orig = view.getTag(TAG_BG) as? android.graphics.drawable.Drawable
                if (orig != null) {
                    view.background = orig
                } else {
                    view.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                }
                if (view is TextView) {
                    (view.getTag(TAG_TEXT) as? Int)?.let { view.setTextColor(it) }
                }
                view.elevation = 0f
            }
        }
    }

    /** 递归给 root 下所有 focusable 控件加焦点高亮。AdapterView（ListView 等）的行由 getView 自行处理，跳过。 */
    fun apply(root: View) {
        if (root is android.widget.AdapterView<*>) return
        if (root.isFocusable) highlight(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) apply(root.getChildAt(i))
        }
    }

    /**
     * 外部代码修改控件背景/文字色后调用（如白名单/黑名单选中态切换）：
     * 把新样式同步为焦点高亮的"基线"，否则在聚焦状态下改动后，失焦会恢复成改动前的旧样式。
     */
    fun syncBase(v: View) {
        if (v.getTag(TAG_HL) != true) return
        v.setTag(TAG_BG, v.background)
        if (v is TextView) v.setTag(TAG_TEXT, v.currentTextColor)
    }

    /** 找第一个可聚焦的控件（切换页签后把焦点放这里，方向键即可开始操作）。列表优先。 */
    fun firstFocusable(root: View): View? {
        // 列表（ListView 等）优先：进入页面焦点先落在列表上，用户可直接上下选择卡片
        if (root is android.widget.AdapterView<*> && root.isFocusable) return root
        if (root is ViewGroup) {
            // 先扫整棵子树找可聚焦的 AdapterView（如页面里既有按钮又有列表，焦点优先进列表）
            for (i in 0 until root.childCount) {
                findList(root.getChildAt(i))?.let { return it }
            }
            for (i in 0 until root.childCount) {
                firstFocusable(root.getChildAt(i))?.let { return it }
            }
            // 容器自身不直接接焦点（如 ScrollView 可聚焦但确定键无响应、整块高亮很丑），
            // 只有内部实在没有可聚焦控件时才退回容器做焦点锚点
            return if (root.isFocusable) root else null
        }
        return if (root.isFocusable) root else null
    }

    /** 在子树里找第一个可聚焦的 AdapterView（ListView），用于"列表优先"的焦点策略 */
    private fun findList(v: View): View? {
        if (v is android.widget.AdapterView<*> && v.isFocusable) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findList(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    // ---------------- 不可见的焦点顺序表 ----------------
    /**
     * 按视觉顺序（先序遍历）给页面里所有可聚焦控件打上不可见的下标标记，并返回该顺序表。
     *
     * 为什么需要：Android 默认的 focusSearch 按控件几何位置找邻居，在小屏/滚动区/列表内外
     * 交界处经常算错，表现为"按上键却跳到很远的控件""焦点在原地打转"。改成按绘制顺序走，
     * 上下键就会一格一格地、畅通地经过页面里每一个控件，符合遥控器用户的直觉。
     * 列表内部行不参与（由列表自身的选中机制管理），只把列表容器当一个节点。
     */
    fun markFocusOrder(root: View): List<View> {
        val out = mutableListOf<View>()
        collectOrdered(root, out)
        out.forEachIndexed { i, v -> v.setTag(TAG_ORDER, i) }
        return out
    }

    private fun collectOrdered(root: View, out: MutableList<View>) {
        when {
            root is android.widget.AdapterView<*> -> {
                if (root.isFocusable && root.visibility == View.VISIBLE) out.add(root)
            }
            root is ViewGroup -> {
                for (i in 0 until root.childCount) {
                    val c = root.getChildAt(i)
                    if (c.visibility == View.VISIBLE) collectOrdered(c, out)
                }
            }
            else -> if (root.isFocusable && root.visibility == View.VISIBLE) out.add(root)
        }
    }

    /**
     * 沿焦点顺序表走 delta 步（-1 上一个 / +1 下一个），跳过不可见/不可聚焦的节点。
     * 顺序表每次重算（页面会变：列表刷新、按钮增减），保证始终与当前界面一致。
     */
    fun stepFocusOrder(root: View, current: View, delta: Int): View? {
        val list = markFocusOrder(root)
        val i = list.indexOfFirst { it === current }
        if (i < 0) return null
        var j = i + delta
        while (j in list.indices) {
            val v = list[j]
            if (v.isShown && v.isFocusable) return v
            j += delta
        }
        return null
    }

    /** 读取控件在焦点顺序表中的下标（无标记返回 -1）。用于调试与跨区块判断，用户不可见。 */
    fun orderIndexOf(v: View): Int = (v.getTag(TAG_ORDER) as? Int) ?: -1

    /**
     * 一路向上取到窗口根（DecorView）。
     * 焦点顺序表以整窗为范围，方向键才能从页面内容一路走到窗口底部的页签栏（状态/人设卡…）。
     */
    fun windowRoot(v: View): View {
        var cur: View = v
        while (true) {
            val p = cur.parent
            cur = if (p is View) p else return cur
        }
    }

    // ---------------- Spinner 焦点逃逸 ----------------

    /**
     * Spinner 焦点逃逸：Android Spinner 获得焦点后方向键被内部消费，遥控器无法离开。
     * 本方法给 Spinner 加 keyListener：关闭状态下方向键转移焦点到相邻控件。
     * Spinner 弹出下拉时，按键事件由弹出窗口的 ListView 消费，不会传到这里的 OnKeyListener，无需特殊判断。
     */
    fun escapeSpinner(spinner: android.widget.Spinner) {
        spinner.setOnKeyListener { v, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val isDpad = when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                -> true
                else -> false
            }
            if (!isDpad) return@setOnKeyListener false
            val direction = when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                else -> return@setOnKeyListener false
            }
            val next = v.focusSearch(direction)
            if (next != null && next !== v) {
                next.requestFocus()
                return@setOnKeyListener true
            }
            false
        }
    }

    fun isDescendantOf(desc: View, root: View): Boolean {
        var p: View? = desc.parent as? View
        while (p != null) {
            if (p === root) return true
            p = p.parent as? View
        }
        return false
    }

    /**
     * ScrollView 焦点链：遥控器上下键沿"内容里当前可见的可聚焦控件"逐个移动，并自动把目标滚入视野。
     * 关键点（都是踩过的坑）：
     *  · 链在每次按键时动态重算：控件显隐变化（如名单"白名单/黑名单"切换）后，
     *    不会再把焦点指向被隐藏的控件——旧版静态链会卡在被 GONE 父容器包着的控件上，导致"滑不到底部"；
     *  · 滚动量按目标在滚动内容里的真实坐标算（支持按钮行等嵌套容器，旧版 target.top 是相对父容器的，会跳错位置）；
     *  · 列表（AdapterView）内部还有可走的项时先让列表自己走，到边界才跳出到链上；
     *  · Spinner 方向键会被内部消费，这里把上下左右都接到链上，保证能离开。
     */
    fun chainScrollView(sv: android.widget.ScrollView) {
        sv.post {
            val content = sv.getChildAt(0) as? ViewGroup ?: return@post
            installScrollChain(sv, content, HashSet())
        }
    }

    private fun installScrollChain(sv: android.widget.ScrollView, root: ViewGroup, seen: MutableSet<View>) {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is ViewGroup && c !is android.widget.AdapterView<*>) {
                installScrollChain(sv, c, seen)
            }
            if (c.isFocusable && seen.add(c)) {
                c.setOnKeyListener { v, keyCode, event ->
                    if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val isSpinner = v is android.widget.Spinner
                    val up = keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                        (isSpinner && keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                    val down = keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
                        (isSpinner && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    if (!up && !down) return@setOnKeyListener false
                    // 列表：内部还有可走的项就让列表自己处理，到边界才跳出到链上
                    if (v is android.widget.AdapterView<*>) {
                        val count = v.adapter?.count ?: 0
                        val pos = v.selectedItemPosition
                        if (down && pos in 0 until count - 1) return@setOnKeyListener false
                        if (up && pos > 0) return@setOnKeyListener false
                    }
                    val chain = scrollChainSnapshot(sv)
                    val idx = chain.indexOfFirst { it === v }
                    if (idx < 0) return@setOnKeyListener false
                    val next = chain.getOrNull(if (up) idx - 1 else idx + 1) ?: return@setOnKeyListener false
                    if (!next.requestFocus()) return@setOnKeyListener false
                    next.post { scrollTargetIntoView(sv, next) }
                    true
                }
            }
        }
    }

    /** 当前"真正可见且可聚焦"的控件快照（列表容器算一个节点，不展开内部行） */
    private fun scrollChainSnapshot(sv: android.widget.ScrollView): List<View> {
        val content = sv.getChildAt(0) as? ViewGroup ?: return emptyList()
        val out = mutableListOf<View>()
        fun walk(root: ViewGroup) {
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i)
                if (c is android.widget.AdapterView<*>) {
                    if (c.isShown && c.isFocusable) out.add(c)
                    continue
                }
                if (c is ViewGroup) walk(c)
                if (c.isShown && c.isFocusable) out.add(c)
            }
        }
        walk(content)
        return out
    }

    /** 目标不在视野内时滚到"内容上 1/3 处"（按滚动内容坐标系计算，嵌套容器也准确） */
    private fun scrollTargetIntoView(sv: android.widget.ScrollView, target: View) {
        val content = sv.getChildAt(0) as? ViewGroup ?: return
        if (sv.width <= 0 || sv.height <= 0) return
        val r = android.graphics.Rect()
        target.getDrawingRect(r)
        content.offsetDescendantRectToMyCoords(target, r)
        val viewport = android.graphics.Rect(0, sv.scrollY, sv.width, sv.scrollY + sv.height)
        if (android.graphics.Rect.intersects(viewport, r)) return
        val y = (r.top - sv.height / 3).coerceIn(0, maxOf(0, content.height - sv.height))
        sv.smoothScrollTo(0, y)
    }

    /**
     * 列表焦点逃逸：ListView（行内含按钮、itemsCanFocus=true）的方向键焦点容易"困"在列表内。
     * 当焦点位于列表第一行、再按"上"键 → 跳到 above（列表上方的控件）；
     * 当焦点位于列表最后一行、再按"下"键 → 跳到 below（列表下方的控件）。
     * 这样用户能从列表回到上方的切换/配置按钮，或继续到下方的按钮。
     */
    fun enableListEscape(listView: android.widget.ListView, above: View?, below: View?) {
        if (above == null && below == null) return
        listView.setOnKeyListener { v, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_UP &&
                keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN
            ) {
                return@setOnKeyListener false
            }
            val up = keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
            val count = listView.adapter?.count ?: return@setOnKeyListener false
            if (count <= 0) return@setOnKeyListener false
            val focused = listView.findFocus() ?: return@setOnKeyListener false
            if (!isDescendantOf(focused, listView)) return@setOnKeyListener false
            val first = listView.firstVisiblePosition
            val last = listView.lastVisiblePosition
            if (up) {
                // 已滚到顶部、焦点在第一行 → 逃逸到上方
                if (first > 0) return@setOnKeyListener false
                val firstChild = listView.getChildAt(0) ?: return@setOnKeyListener false
                if (!isDescendantOf(focused, firstChild)) return@setOnKeyListener false
                val t = above ?: return@setOnKeyListener false
                if (t.isShown) {
                    t.requestFocus()
                    return@setOnKeyListener true
                }
            } else {
                // 已滚到底部、焦点在最后一行 → 逃逸到下方
                if (last < count - 1) return@setOnKeyListener false
                val lastChild = listView.getChildAt(listView.childCount - 1) ?: return@setOnKeyListener false
                if (!isDescendantOf(focused, lastChild)) return@setOnKeyListener false
                val t = below ?: return@setOnKeyListener false
                if (t.isShown) {
                    t.requestFocus()
                    return@setOnKeyListener true
                }
            }
            false
        }
    }
}

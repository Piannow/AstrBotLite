package com.astrbot.lite.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 插件安装/转换实时进度（1.7.110 多任务并行版）。
 *
 * 模型：每个安装会话是一个独立任务（id=插件 id），**下载可多任务并行**；
 * 安装线程通过 [attach] 绑定自己线程到任务（ThreadLocal），之后 update/finish 自动归属。
 * UI 通过 [snapshot] 拉取展示、[selectNext] 点击进度条切换查看另一个任务。
 *
 * 兼容：旧单任务 API（start/update/finish/cancel/isCancelled）语义映射到"当前线程绑定的任务"。
 *
 * 阶段约定（AstrBot 市场链路）：
 *   0-45  下载插件包（流式字节进度）
 *  45-60  写入 Python 统一环境（解压按条目）
 *  60-70  转换生成 plugin.json
 *  70-97  Web 离线转换
 *  100    完成
 */
object InstallProgress {

    /** 单任务状态 */
    private class T(val title: String) {
        @Volatile var stage: String = "准备安装"

        @Volatile var percent: Int = 0

        @Volatile var active: Boolean = true

        @Volatile var cancelled: Boolean = false

        @Volatile var ok: Boolean = true

        @Volatile var message: String = ""
    }

    private val tasks = ConcurrentHashMap<String, T>()
    private val order = CopyOnWriteArrayList<String>()

    /** 前台展示的任务（进度条渲染它；点击切换） */
    @Volatile
    var selectedId: String? = null
        private set

    /** 当前线程绑定的任务 id（安装线程 attach 后，旧 API 自动归属） */
    private val local = ThreadLocal<String?>()

    private val handler = Handler(Looper.getMainLooper())

    /** 刷新信号（UI 收到后主动 snapshot() 拉状态——签名不变、值由 UI 自查，规避多任务竞争） */
    @Volatile
    var listener: ((stage: String, percent: Int) -> Unit)? = null

    /** 任务快照（UI 渲染用） */
    data class Snapshot(
        val taskId: String?,
        val title: String,
        val stage: String,
        val percent: Int,
        val active: Boolean,
        val ok: Boolean,
        val isCancelled: Boolean,
        /** 所有任务 id（含状态摘要，点击切换用） */
        val all: List<Pair<String, String>>, // id to brief
    )

    // ---------------- 生命周期 ----------------

    /** UI 侧：预备任务（建对象 + 前台选中）。同 id 已在安装中返回 false（防重复安装） */
    @Synchronized
    fun prepare(id: String, title: String): Boolean {
        val ex = tasks[id]
        if (ex != null && ex.active) {
            selectedId = id
            dispatch()
            return false // 同一插件已在安装
        }
        tasks[id] = T(title)
        if (!order.contains(id)) order.add(id)
        selectedId = id
        dispatch()
        return true
    }

    /** 安装线程：绑定本线程到任务（ThreadLocal；任务不存在则创建） */
    fun attach(id: String, title: String = id) {
        local.set(id)
        tasks.putIfAbsent(id, T(title))
        if (!order.contains(id)) order.add(id)
    }

    /** 安装链路检查点：本线程绑定任务被请求取消时 true */
    fun isCancelled(): Boolean = cur()?.cancelled == true

    /** 旧 API：任务启动/重置（须先 attach 或由 UI prepare） */
    fun start(stage: String = "准备安装") {
        val t = cur() ?: run {
            val id = selectedId ?: "default"
            tasks.putIfAbsent(id, T("安装"))
            if (!order.contains(id)) order.add(id)
            tasks[id]!!
        }
        t.active = true
        t.cancelled = false
        t.stage = stage
        t.percent = 0
        dispatch()
    }

    /** 旧 API：更新本线程任务进度 */
    fun update(stage: String, percent: Int) {
        val t = cur() ?: return
        t.stage = stage
        t.percent = percent.coerceIn(0, 100)
        dispatch()
    }

    /** 旧 API：结束本线程任务（成功/失败/取消都调） */
    fun finish(ok: Boolean, message: String) {
        val t = cur() ?: return
        t.stage = message
        t.percent = 100
        t.active = false
        t.ok = ok
        t.cancelled = !ok && t.cancelled
        t.message = message
        dispatch()
    }

    /** 取消**前台显示**的任务（进度条按钮语义） */
    fun cancel() {
        val id = selectedId ?: return
        cancelTask(id)
    }

    /** 取消指定任务 */
    fun cancelTask(id: String) {
        val t = tasks[id] ?: return
        if (!t.active) return
        t.cancelled = true
        t.stage = "正在取消…"
        dispatch()
    }

    /** 是否有任一任务在跑（供外部判断"安装忙"） */
    val active: Boolean
        get() = tasks.values.any { it.active }

    /** 当前前台任务状态（旧 UI 直读 stage/percent/active 的兼容面） */
    val stage: String get() = tasks[selectedId]?.stage ?: ""
    val percent: Int get() = tasks[selectedId]?.percent ?: 0
    val cancelled: Boolean get() = tasks[selectedId]?.cancelled ?: false

    /** 任务是否正在安装（同 id 防重用） */
    fun isInstalling(id: String): Boolean = tasks[id]?.active == true

    // ---------------- 快照与切换（1.7.110 多任务 UI） ----------------

    /** 拉取快照：id=null → 前台任务 */
    fun snapshot(id: String? = null): Snapshot {
        val sel = id ?: selectedId
        val t = sel?.let { tasks[it] }
        val all = order.mapNotNull { oid ->
            val ot = tasks[oid] ?: return@mapNotNull null
            oid to (
                if (ot.active) {
                    "⏳${ot.percent}% ${ot.stage}"
                } else if (ot.ok) {
                    "✓ ${ot.title}"
                } else {
                    "✗ ${ot.title}"
                }
                )
        }
        return Snapshot(
            taskId = sel,
            title = t?.title ?: "准备安装",
            stage = t?.stage ?: "准备安装",
            percent = t?.percent ?: 0,
            active = t?.active ?: false,
            ok = t?.ok ?: true,
            isCancelled = t?.cancelled ?: false,
            all = all,
        )
    }

    /** 点击进度条：切换到下一个仍在进行的任务（无其它任务则切回列表循环） */
    @Synchronized
    fun selectNext() {
        val running = order.filter { tasks[it]?.active == true }
        val pool = if (running.isNotEmpty()) running else order.toList()
        if (pool.isEmpty()) return
        val curIdx = pool.indexOf(selectedId)
        selectedId = pool[(curIdx + 1).coerceAtMost(pool.size - 1) % pool.size]
        dispatch()
    }

    /** 直接选中（UI 点任务行） */
    fun select(id: String) {
        if (tasks.containsKey(id)) {
            selectedId = id
            dispatch()
        }
    }

    /** 任务完成后从列表移除记录（终态展示由 UI 延迟处理；调用 detachAfterShown） */
    fun remove(id: String) {
        tasks.remove(id)
        order.remove(id)
        if (selectedId == id) selectedId = order.lastOrNull()
    }

    /** 1.7.2xx 新增：当前线程绑定的任务 id（null=未绑定） */
    fun currentId(): String? = local.get()

    /** 1.7.2xx 新增：按 taskId 更新进度（taskId 为 null/空时回退到当前线程） */
    fun updateFor(taskId: String?, stage: String, percent: Int) {
        val t = if (taskId.isNullOrBlank()) cur() else tasks[taskId]
        if (t == null) return
        t.stage = stage
        t.percent = percent.coerceIn(0, 100)
        dispatch()
    }

    /** 1.7.2xx 新增：按 taskId 只更新阶段文字（百分比不变） */
    fun stageFor(taskId: String?, stage: String) {
        val t = if (taskId.isNullOrBlank()) cur() else tasks[taskId]
        if (t == null) return
        t.stage = stage
        dispatch()
    }

    private fun cur(): T? = local.get()?.let { tasks[it] }

    private fun dispatch() {
        val l = listener ?: return
        val s = stage
        val p = percent
        handler.post { l(s, p) }
    }
}

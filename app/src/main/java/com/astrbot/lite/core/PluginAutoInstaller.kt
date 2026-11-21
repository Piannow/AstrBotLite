package com.astrbot.lite.core

import android.app.Activity
import com.astrbot.lite.ui.InstallPanel

/**
 * 导入配置后，根据 AI 池特性分配，自动补齐缺失的媒体类插件。
 * - 扫描 AiPool.getFeaturePlugins()：image→text2image/imageengine/image2image；video→videogen；tts→tts
 * - 只看 assign 有值的特性；对应插件未安装的加入待装列表
 * - 从 Lite 市场 catalog 反查 PluginInfo；市场里没有的直接放弃
 * - 用 InstallPanel 显示进度，串行安装，装完后 AiPool.syncMediaPluginConfigs() 灌配置
 */
object PluginAutoInstaller {

    fun findMissingPlugins(): List<PluginInfo> {
        val needed = linkedSetOf<String>()
        try {
            for ((feature, pids) in AiPool.getFeaturePlugins()) {
                if (AiPool.assignedId(feature).isBlank()) continue
                for (pid in pids) {
                    try {
                        if (PluginManager.installed(pid) == null) needed.add(pid)
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        if (needed.isEmpty()) return emptyList()
        val catalog = try { PluginManager.loadCatalog() } catch (_: Throwable) { emptyList() }
        return needed.mapNotNull { pid -> catalog.firstOrNull { it.id == pid } }
    }

    fun installAll(act: Activity, plugins: List<PluginInfo>, onDone: (Int, List<String>) -> Unit) {
        if (plugins.isEmpty()) { onDone(0, emptyList()); return }
        val taskId = "import-auto-" + System.currentTimeMillis()
        val title = "导入配置·补齐插件"
        val total = plugins.size
        if (!InstallProgress.prepare(taskId, title)) {
            onDone(0, plugins.map { it.name })
            return
        }
        InstallPanel.show(act, title + "（" + total + " 个）", taskId)
        Thread {
            InstallProgress.attach(taskId, title)
            var ok = 0
            val fail = mutableListOf<String>()
            for ((idx, p) in plugins.withIndex()) {
                if (InstallProgress.isCancelled()) {
                    for (rest in plugins.drop(idx)) fail.add(rest.name)
                    break
                }
                InstallProgress.update("(" + (idx + 1) + "/" + total + ") 正在安装 " + p.name, idx * 100 / total)
                val r = try { PluginManager.installFromCatalog(p) } catch (_: Throwable) { false }
                if (r) ok++ else fail.add(p.name)
            }
            try { AiPool.syncMediaPluginConfigs() } catch (_: Throwable) {}
            InstallProgress.update("完成：成功 " + ok + " / 失败 " + fail.size, 100)
            InstallProgress.finish(fail.isEmpty(), if (fail.isEmpty()) "全部完成" else "部分失败")
            onDone(ok, fail)
        }.apply { isDaemon = true; name = "import-auto"; start() }
    }
}

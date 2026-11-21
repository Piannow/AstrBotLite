package com.astrbot.lite.core

/**
 * 游戏资源库引擎（1.7.207 重新引入）。
 *
 * 背景：本文件在源码快照中丢失，但 PluginManager 的「游戏查询」分支仍在调用
 * （列表 / 搜索 / 详情 / 资源直链）。为恢复可编译状态，这里保留完整的**数据结构**
 * 与**调用契约**，网络实现置为"不可用"：
 * - 所有查询返回 null / 空列表，调用方已有「站点繁忙或网络异常」的失败提示；
 * - 不伪造任何站点地址或直链，避免把不可用 URL 发给用户。
 *
 * 后续接入真实数据源时，只需在 [ranking] / [search] / [detail] 内实现抓取解析，
 * 上层 PluginManager 的分页、编号选择、资源列表逻辑无需改动。
 */
object GameEngine {

    /** 资源站点页面基址（未接入数据源前为空，页面链接退化为空串） */
    const val PAGE_BASE = ""

    /** 列表项 */
    data class GameItem(
        val id: Int = 0,
        val title: String = "",
        val versionInfo: String = ""
    )

    /** 网盘资源项 */
    data class GameRes(
        val provider: String = "",
        val version: String = "",
        val size: String = "",
        val directUrl: String = "",
        val panId: String = ""
    )

    /** 搜索/列表分页结果 */
    data class Page(
        val items: List<GameItem> = emptyList(),
        val total: Int = 0,
        /** 总数是否被站点截断（显示 "N+"） */
        val gte: Boolean = false
    )

    /** 详情结果 */
    data class Detail(
        val item: GameItem = GameItem(),
        val resources: List<GameRes> = emptyList(),
        val versionInfo: String = ""
    )

    private const val UNAVAILABLE = "游戏资源库数据源未接入（源码快照缺失，功能暂时不可用）"

    @Volatile
    private var warned = false

    private fun warnOnce() {
        if (warned) return
        warned = true
        Logs.warn("[游戏] $UNAVAILABLE")
    }

    /** 最新/热门榜 */
    fun ranking(limit: Int = 10): List<GameItem> {
        warnOnce()
        return emptyList()
    }

    /** 关键词搜索（page 从 1 开始） */
    fun search(keyword: String, page: Int = 1): Page? {
        warnOnce()
        return null
    }

    /** 详情 + 资源列表 */
    fun detail(id: Int): Detail? {
        warnOnce()
        return null
    }

    /** 列表行文案：「1. 游戏名 —— 版本信息」 */
    fun lineOf(index: Int, item: GameItem): String = buildString {
        append(index).append(". ").append(item.title)
        if (item.versionInfo.isNotBlank()) append("  ——  ").append(item.versionInfo.take(60))
    }
}

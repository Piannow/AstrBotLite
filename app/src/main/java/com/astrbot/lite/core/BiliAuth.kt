package com.astrbot.lite.core

/**
 * B 站免登录桩（1.7.213 废除 B 站登录）。
 *
 * 变更：移除扫码/账密/cookie 登录整套逻辑与登录页（BiliLoginActivity）。
 * 看番功能保留，降级为**免登录模式**——B 站未登录也能搜番剧、看免费内容；
 * 仅 720p 以上/会员番剧受限（vip 恒 false → 清晰度只到免费档）。
 *
 * 保留 [cookie]/[vip]/[cookieHeader] 三个签名，让 Netdisk 的看番代码零改动继续编译运行：
 * - cookie() 恒空 → 请求不带 Cookie 头（Netdisk 里 `isNotBlank()` 才加头）；
 * - vip() 恒 false → isQualityAvailable 走免费清晰度分支。
 */
object BiliAuth {

    /** 登录凭证：已废除登录，恒为空（未登录）。保留签名供看番代码调用。 */
    fun cookie(): String = ""

    /** 已废除登录：恒未登录 */
    fun isLoggedIn(): Boolean = false

    /** 是否大会员：已废除登录，恒 false（看番清晰度只到免费档） */
    fun vip(): Boolean = false

    /** 给 B 站 API 请求附 cookie 头：免登录恒空（调用方判空则不加头） */
    fun cookieHeader(): String = ""
}

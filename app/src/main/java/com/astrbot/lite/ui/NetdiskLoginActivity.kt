package com.astrbot.lite.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.astrbot.lite.R
import com.astrbot.lite.core.Logs
import com.astrbot.lite.core.Netdisk

/**
 * 网盘网页登录：内嵌 WebView 打开对应网盘登录页，登录后自动/手动保存 Cookie（Netdisk.setCookie）。
 *
 * v1.0.40 优化：
 * 1) 加载提速：硬件加速 + 高渲染优先级 + 应用缓存 + 拦截广告/统计/埋点请求 + 关闭安全浏览
 * 2) 第三方登录可用：支持 window.open 弹窗（QQ/微信扫码），放行第三方 Cookie
 * 3) 登录态不再失效：Cookie 实时从 CookieManager 读取（注入 Netdisk.cookieProvider），
 *    页面加载完成即自动保存，不再依赖「点一次按钮存一份快照」
 * 4) 加载可观测：进度条 + 慢加载提示 + 失败重试
 */
class NetdiskLoginActivity : Activity() {

    companion object {
        const val EXTRA_PLATFORM = "platform"

        /** 平台 -> (显示名, 登录页 URL) */
        val PLATFORMS = linkedMapOf(
            "123" to Pair("123网盘", "https://www.123pan.com/"),
            "quark" to Pair("夸克网盘", "https://pan.quark.cn/"),
            "uc" to Pair("UC网盘", "https://drive.uc.cn/"),
        )

        /** 采集 Cookie 的域名（父域 + 子域 + API 域，避免漏掉登录票据） */
        private val COOKIE_DOMAINS = mapOf(
            "quark" to listOf(
                "https://quark.cn",
                "https://pan.quark.cn",
                "https://drive.quark.cn",
                "https://drive-pc.quark.cn",
                "https://pc-api.quark.cn",
            ),
            "uc" to listOf(
                "https://uc.cn",
                "https://drive.uc.cn",
                "https://pan.uc.cn",
                "https://pc-api.uc.cn",
            ),
            "123" to listOf(
                "https://123pan.com",
                "https://www.123pan.com",
                "https://123pan.cn",
                "https://www.123pan.cn",
            ),
        )

        private val LOGIN_KEYS = mapOf(
            "quark" to listOf("b-user-id", "__pus"),
            "uc" to listOf("b-user-id", "__pus"),
            "123" to listOf("auth-token", "authortoken"),
        )

        /** 拖慢页面的广告 / 统计 / 埋点域名：直接拦截为空响应（2026-09 实测均存在） */
        private val BLOCK_HOST_SUFFIX = listOf(
            "google-analytics.com", "googletagmanager.com", "googlesyndication.com",
            "doubleclick.net", "analytics.google.com",
            "hm.baidu.com", "hmcdn.baidu.com", "hmma.baidu.com",
            "pos.baidu.com", "cpro.baidu.com", "eclick.baidu.com", "dup.baidustatic.com",
            "mmstat.com", "log.mmstat.com", "gm.mmstat.com", "spmm.mmstat.com",
            "ynuf.aliapp.org", "arms-retcode.aliyuncs.com", "retcode.aliyuncs.com",
            "log.uc.cn", "ad.uce.uc.cn",
            "jssdk.sensorsdata.cn", "clouds.sensorsdata.cn",
            "umeng.com", "umsns.com", "cnzz.com",
            "sentry.io", "sentry-cdn.com", "browser.sentry-cdn.com",
            "hotjar.com", "mixpanel.com", "amplitude.com",
            "adnxs.com", "criteo.com", "taboola.com",
        )
        private val BLOCK_HOST_KEYWORD = listOf(
            "analytics", "statistic", "track", "telemetry", "beacon", "monitor",
            "logreport", "apm", "sentry", "adsdk", "ad-sdk", "feedback",
            "retcode", "sensorsdata", "goldlog", "applog", "umeng",
        )

        /** 平台 -> 页面内可发起的登录校验接口（同源或网盘自身允许的跨域接口） */
        private val JS_VERIFY_URL = mapOf(
            "quark" to "https://drive-pc.quark.cn/1/clouddrive/member?pr=ucpro&fr=pc&uc_param_str=",
            "uc" to "https://pc-api.uc.cn/1/clouddrive/member?pr=UCBrowser&fr=pc&uc_param_str=",
        )

        /**
         * 合并多域 Cookie（父域 + 子域），确保登录态完整。
         * 定义为静态方法：注入给 Netdisk 时不会持有 Activity 引用。
         */
        fun collectCookie(platform: String): String {
            return try {
                val cm = CookieManager.getInstance() ?: return ""
                val domains = COOKIE_DOMAINS[platform] ?: emptyList()
                val parts = LinkedHashSet<String>()
                for (d in domains) {
                    val c = try {
                        cm.getCookie(d)
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    c.split(";").forEach { p ->
                        val s = p.trim()
                        if (s.isNotBlank()) parts.add(s)
                    }
                }
                parts.joinToString("; ")
            } catch (_: Throwable) {
                ""
            }
        }

        /** 是否命中登录关键 Cookie */
        fun looksLoggedIn(platform: String, cookie: String): Boolean {
            if (cookie.isBlank()) return false
            val keys = LOGIN_KEYS[platform] ?: emptyList()
            val lower = cookie.lowercase()
            return keys.any { lower.contains("${it.lowercase()}=") }
        }
    }

    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private lateinit var tvLoading: TextView
    private lateinit var progressBar: ProgressBar
    private var platform = "quark"
    private var name = "网盘"
    private var loginUrl = "https://pan.quark.cn/"
    private var popDialog: Dialog? = null
    private var finishedOnce = false

    /** 校验防抖：进行中标志 + 上次校验时间 */
    private var verifying = false
    private var lastVerifyAt = 0L

    /** 进度卡住检测：页面常驻长连接（推送/轮询）会让 onProgressChanged 停在 70~90，DOM 就绪即提示可操作 */
    private var pageStartAt = 0L
    private var lastReadyCheckAt = 0L

    /** 加载看门狗：20 秒未完成自动重载一次（防重型页面 JS 卡死） */
    private var autoReloaded = false

    /** 中途登录检测：登录后回跳重型首页可能卡死，不等加载完成，滚动中轮询一次登录票据 */
    private var lastMidCheckAt = 0L
    private var midNotified = false

    /** 周期重注入性能优化：SPA 路由（登录后进入网盘首页）不再触发 onPageStarted，需定时兜底 */
    private val perfInjectHandler = Handler(Looper.getMainLooper())
    private val perfInjectRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            injectPerfCss()
            perfInjectHandler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = intent.getStringExtra(EXTRA_PLATFORM) ?: "quark"
        val info = PLATFORMS[platform] ?: run {
            finish()
            return
        }
        name = info.first
        loginUrl = info.second

        // WebView 就绪后即可实时提供最新 Cookie，机器人线程取到的永远是新鲜登录态
        Netdisk.cookieProvider = { p -> collectCookie(p) }

        // 渲染器由 App.init 常驻化（application Context 构造），此处无需再注入/清空。
        // 登录页开没开、机器人后台取直链，渲染器都可用。

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(
            TextView(this).apply {
                text = "$name 网页登录"
                textSize = 16f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val btnCancel = Button(this).apply { text = "取消" }
        top.addView(btnCancel)
        val btnDone = Button(this).apply { text = "完成登录" }
        top.addView(btnDone)
        root.addView(top)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        root.addView(
            progressBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        root.addView(
            TextView(this).apply {
                text = if (platform == "123") {
                    "✅ $name 分享取直链无需登录、也不限文件大小。此处登录用于扩展能力；遇验证码难通过可直接点「取消」返回。"
                } else {
                    "⚠️ $name 分享直链可能受登录/大小限制，建议登录后使用。登录成功会自动保存，也可点「完成登录」立即保存。"
                }
                textSize = 12f
            },
        )

        tvStatus = TextView(this).apply { textSize = 12f }
        root.addView(tvStatus)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row)
        val btnClear = Button(this).apply { text = "清除登录" }
        row.addView(btnClear)
        val btnReload = Button(this).apply { text = "重新加载" }
        row.addView(btnReload)

        tvLoading = TextView(this).apply {
            text = "正在加载登录页..."
            textSize = 12f
            setTextColor(colorOf(R.color.textSub))
        }
        root.addView(tvLoading)

        webView = WebView(this)
        configure(webView)
        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                request ?: return super.shouldInterceptRequest(view, request)
                val host = request.url?.host?.lowercase().orEmpty()
                if (shouldBlock(host)) return emptyResponse()
                // 视频/直播流等大媒体资源：登录页不需要，拦截可显著提速（机顶盒性能有限）
                val path = request.url?.encodedPath?.lowercase().orEmpty()
                if (path.endsWith(".mp4") ||
                    path.endsWith(".m3u8") ||
                    path.endsWith(".ts") ||
                    path.endsWith(".webm") ||
                    path.endsWith(".flv")
                ) {
                    return emptyResponse()
                }
                // 字体文件（woff2 中文子集动辄几百 KB~MB）：登录页不依赖字体图标，拦截可明显提速
                // 例外：123 网盘界面重度依赖 iconfont，拦截会渲染空白，需放行
                if (platform != "123" &&
                    (
                        path.endsWith(".woff") ||
                            path.endsWith(".woff2") ||
                            path.endsWith(".ttf") ||
                            path.endsWith(".otf") ||
                            path.endsWith(".eot")
                        )
                ) {
                    return emptyResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }

            @Suppress("DEPRECATION")
            @Deprecated("兼容旧版 WebView")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                url ?: return super.shouldInterceptRequest(view, url)
                val host = try {
                    java.net.URI(url).host
                } catch (_: Exception) {
                    null
                }?.lowercase().orEmpty()
                if (shouldBlock(host)) return emptyResponse()
                val path = try {
                    java.net.URI(url).path.lowercase()
                } catch (_: Exception) {
                    ""
                }
                if (path.endsWith(".mp4") ||
                    path.endsWith(".m3u8") ||
                    path.endsWith(".ts") ||
                    path.endsWith(".webm") ||
                    path.endsWith(".flv")
                ) {
                    return emptyResponse()
                }
                // 123 网盘依赖 iconfont，不禁字体；其余平台拦截提速
                if (platform != "123" &&
                    (
                        path.endsWith(".woff") ||
                            path.endsWith(".woff2") ||
                            path.endsWith(".ttf") ||
                            path.endsWith(".otf") ||
                            path.endsWith(".eot")
                        )
                ) {
                    return emptyResponse()
                }
                return super.shouldInterceptRequest(view, url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                // 尽早注入性能优化：禁动画/过渡、禁文本选择/拖拽，避免 PC 版网盘页面在触屏上卡顿、滑动变框选
                pageStartAt = System.currentTimeMillis()
                injectPerfCss()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                tvLoading.text = "已加载：" + (url ?: "")
                finishedOnce = true
                injectPerfCss()
                autoSaveCookie(false)
                asyncVerifyLogin()
                // 空白页检测：页面加载完但无实质内容（老 WebView 不兼容/网络异常）时提示
                webView.postDelayed({
                    if (!isFinishing && !isDestroyed) checkBlankPage()
                }, 2500)
            }

            // 页面渲染进程异常退出（内存不足/页面过重）：提示后返回，避免白屏卡死
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                Toast.makeText(
                    this@NetdiskLoginActivity,
                    "$name 页面加载异常（可能页面过重或内存不足），已返回。请重试或改用其他网盘",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                    tvLoading.text = "加载完成"
                } else {
                    progressBar.visibility = View.VISIBLE
                    tvLoading.text = "正在加载登录页... $newProgress%"
                    // 页面常驻长连接会让进度长期卡在 70~90（如夸克停在 78%），DOM 已就绪即可提示可操作
                    val now = System.currentTimeMillis()
                    if (newProgress >= 70 && now - pageStartAt > 6000 && now - lastReadyCheckAt > 3000) {
                        lastReadyCheckAt = now
                        checkDomReady()
                    }
                    // 登录后回跳重型首页可能加载卡死：滚动中每 5 秒轮询一次登录票据，不等页面完成即通知保存
                    if (newProgress >= 40 && !midNotified && now - lastMidCheckAt > 5000) {
                        lastMidCheckAt = now
                        midCheckLogin()
                    }
                }
            }

            // 支持 window.open 的第三方登录（QQ / 微信扫码等）
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                val msg = resultMsg ?: return false
                val transport = msg.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(this@NetdiskLoginActivity)
                configure(popup)
                popup.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        autoSaveCookie(false)
                        asyncVerifyLogin()
                    }
                }
                val d = Dialog(this@NetdiskLoginActivity)
                d.setContentView(popup)
                d.setCancelable(true)
                d.setOnDismissListener {
                    try {
                        popup.stopLoading()
                        popup.destroy()
                    } catch (_: Exception) {
                    }
                    if (popDialog === d) popDialog = null
                    autoSaveCookie(false)
                }
                popDialog?.dismiss()
                popDialog = d
                transport.webView = popup
                msg.sendToTarget()
                d.show()
                d.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                popDialog?.dismiss()
            }
        }

        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        root.addView(
            TextView(this).apply {
                text = "提示：返回键可后退网页；如页面长时间无响应，点「重新加载」。"
                textSize = 11f
                setTextColor(colorOf(R.color.textSub))
                gravity = Gravity.CENTER
            },
        )

        setContentView(root)

        refreshStatus()

        btnCancel.setOnClickListener { finish() }
        btnReload.setOnClickListener {
            // 重新加载当前页面（登录后会跳到网盘首页，直接 reload 当前地址更稳）
            progressBar.visibility = View.VISIBLE
            val url = webView.url?.takeIf { it.startsWith("http") } ?: loginUrl
            webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            webView.loadUrl(url)
        }

        btnDone.setOnClickListener {
            val cookie = collectCookie(platform)
            if (cookie.isBlank()) {
                Toast.makeText(this, "未检测到 Cookie，请先在网页中完成登录", Toast.LENGTH_SHORT).show()
                refreshStatus()
                asyncVerifyLogin(force = true)
            } else {
                Netdisk.setCookie(platform, cookie)
                refreshStatus()
                val ok = looksLoggedIn(platform, cookie)
                Toast.makeText(
                    this,
                    if (ok) "$name 登录信息已保存 ✅" else "$name Cookie 已保存（未识别到登录票据，可能尚未登录成功）",
                    Toast.LENGTH_SHORT,
                ).show()
                asyncVerifyLogin(force = true)
            }
        }

        btnClear.setOnClickListener {
            Netdisk.clearCookie(platform)
            clearWebViewCookies()
            refreshStatus()
            Toast.makeText(this, "已清除 $name 登录信息", Toast.LENGTH_SHORT).show()
            asyncVerifyLogin(force = true)
        }

        // 123 网盘 SPA 用了可选链/空值合并等现代 JS 语法，老版本系统 WebView 解析即失败→白屏；
        // 先做语法兼容检测，不支持则明确提示（123 分享取直链本就不需要登录），避免无声白屏
        if (platform == "123") {
            check123Compat { ok ->
                if (ok) webView.loadUrl(loginUrl) else show123Incompatible()
            }
        } else {
            webView.loadUrl(loginUrl)
        }

        // 打开页面立即做一次真实登录校验（用户请求：刷新目前登录状态）
        asyncVerifyLogin()

        // 周期重注入性能优化：登录成功 SPA 路由进入网盘首页后 onPageFinished 不再触发，靠此兜底
        perfInjectHandler.postDelayed(perfInjectRunnable, 5000)

        // 加载看门狗：20 秒还没完成说明页面 JS 可能卡死，自动重载一次；
        // 重载后 40 秒仍未完成则给出失败提示，避免用户干等
        tvLoading.postDelayed({
            if (!isFinishing && !finishedOnce) {
                if (!autoReloaded) {
                    autoReloaded = true
                    tvLoading.text = "加载超时（可能页面过重），自动重新加载…"
                    try {
                        webView.reload()
                    } catch (_: Exception) {
                    }
                } else if (System.currentTimeMillis() - pageStartAt > 40000) {
                    tvLoading.text = "加载失败…可点「重新加载」，或检查网络后重试"
                }
            }
        }, 20000)
    }

    /** 统一的 WebView 提速 / 兼容配置 */
    private fun configure(wv: WebView) {
        val cm = CookieManager.getInstance()
        try {
            cm.setAcceptCookie(true)
            // 登录经常跨域，必须放行第三方 Cookie，否则登录态丢失
            cm.setAcceptThirdPartyCookies(wv, true)
        } catch (_: Exception) {
        }

        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.loadsImagesAutomatically = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setGeolocationEnabled(false)
        s.allowContentAccess = true
        s.allowFileAccess = false
        s.setNeedInitialFocus(false)
        // 阻止自动播放媒体（页面广告/推广视频自动播放是卡慢来源之一）
        s.mediaPlaybackRequiresUserGesture = true
        s.defaultTextEncodingName = "UTF-8"
        s.textZoom = 100

        // 缓存：LOAD_DEFAULT 尊重站点的 HTTP 缓存头。
        // 此前用 LOAD_CACHE_ELSE_NETWORK 会把「过期的 HTML/JS」也塞给页面，
        // 旧 HTML 引用的旧哈希资源 404 → 页面反复重试 → 又慢又卡，还可能误判登录态。
        s.cacheMode = WebSettings.LOAD_DEFAULT
        @Suppress("DEPRECATION")
        try {
            s.databasePath = cacheDir.absolutePath + "/webdb"
        } catch (_: Exception) {
        }

        // 混合内容：部分网盘的登录 iframe 仍是 http
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // 关闭安全浏览：省掉一次远端校验请求
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                s.safeBrowsingEnabled = false
            } catch (_: Exception) {
            }
        }
        @Suppress("DEPRECATION")
        try {
            s.setRenderPriority(WebSettings.RenderPriority.HIGH)
        } catch (_: Exception) {
        }

        s.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        try {
            wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        } catch (_: Exception) {
        }
        wv.setBackgroundColor(Color.WHITE)
        wv.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        wv.isHorizontalScrollBarEnabled = false
    }

    /**
     * 注入性能优化 CSS/JS（onPageStarted、onPageFinished 各注入一次 + 每 5 秒周期兜底，覆盖 SPA 路由）：
     * - 禁用 CSS 动画/过渡：PC 版网盘页面的动画是低端机顶盒卡顿的主要来源
     * - 事件级拦截文本选择/拖拽/右键菜单（capture 阶段 preventDefault）：
     *   比 `*{user-select:none}` 更可靠，且不触发全元素样式重算（避免增加低端设备交互延迟）；
     *   同时仅对 html/body 禁 user-select，输入框仍可编辑
     * - touch-action:manipulation：允许触屏滚动/双指缩放，禁用双击缩放与长按菜单
     */
    private fun injectPerfCss() {
        try {
            webView.evaluateJavascript(
                "(function(){try{" +
                    "var s=document.getElementById('ab-perf-css');" +
                    "if(!s){s=document.createElement('style');s.id='ab-perf-css';s.type='text/css';" +
                    "(document.head||document.documentElement).appendChild(s);}" +
                    "s.textContent='*,*::before,*::after{animation:none!important;transition:none!important;scroll-behavior:auto!important;-webkit-user-drag:none!important;}" +
                    "html,body{touch-action:manipulation!important;-webkit-user-select:none!important;user-select:none!important;-webkit-touch-callout:none!important;}" +
                    "input,textarea{-webkit-user-select:text!important;user-select:text!important;}';" +
                    "['selectstart','dragstart','gesturestart','contextmenu'].forEach(function(t){" +
                    "document.addEventListener(t,function(e){e.preventDefault();},true);});" +
                    "}catch(e){}})();",
                null,
            )
        } catch (_: Exception) {
        }
    }

    /** 进度卡在 70~90 时检查页面 DOM 是否已就绪，就绪则提前提示可操作（后台长连接资源仍在加载） */
    private fun checkDomReady() {
        try {
            webView.evaluateJavascript("document.readyState") { raw ->
                if (raw?.contains("complete") == true) {
                    progressBar.visibility = View.GONE
                    tvLoading.text = "已加载：页面已就绪（后台资源仍在加载）"
                }
            }
        } catch (_: Exception) {
        }
    }

    /** 中途登录检测：登录后回跳重型首页可能卡死，页面加载中就检查到登录票据则直接保存并通知，不等加载完成 */
    private fun midCheckLogin() {
        try {
            val cookie = collectCookie(platform)
            if (cookie.isBlank() || !looksLoggedIn(platform, cookie)) return
            val old = Netdisk.cookieOf(platform)
            if (cookie != old) {
                Netdisk.setCookie(platform, cookie)
                Logs.info("网盘 Cookie 已更新（$name，加载中检测到登录）")
            }
            midNotified = true
            refreshStatus()
            Toast.makeText(this, "$name 已检测到登录成功 ✅ 可点「完成登录」返回", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
        }
    }

    /** 空白页检测：加载完成后页面无实质内容（文本/图片几乎为零）则提示，常见于老 WebView 不兼容或网络异常 */
    private fun checkBlankPage() {
        try {
            webView.evaluateJavascript(
                "(function(){try{var t=document.body?document.body.innerText.trim().length:0;var i=document.images?document.images.length:0;return (t+i);}catch(e){return -1;}})()",
            ) { raw ->
                val n = try {
                    org.json.JSONTokener(raw).nextValue().toString().toIntOrNull() ?: -1
                } catch (_: Exception) {
                    -1
                }
                if (n in 0..20) {
                    tvLoading.text = "页面疑似空白（可能需升级系统 WebView，或网络异常），可点「重新加载」"
                }
            }
        } catch (_: Exception) {
        }
    }

    /** 现代 JS 语法兼容检测：老版本系统 WebView 不支持可选链/空值合并，123 网盘会白屏 */
    private fun check123Compat(onDone: (Boolean) -> Unit) {
        try {
            webView.loadUrl("about:blank")
            webView.postDelayed({
                webView.evaluateJavascript(
                    "(function(){try{eval('var a=1;a?.b');eval('var c=1;c??2');return 'OK'}catch(e){return 'OLD'}})()",
                ) { raw ->
                    onDone(!(raw?.contains("OLD") == true))
                }
            }, 600)
        } catch (_: Exception) {
            onDone(true)
        }
    }

    /** 123 网盘 WebView 不兼容提示：123 分享取直链无需登录，白屏不影响使用 */
    private fun show123Incompatible() {
        progressBar.visibility = View.GONE
        tvLoading.text = "123 网盘登录页需要较新的系统 WebView，当前设备不支持显示（不影响使用：123 网盘分享取直链无需登录）。" +
            "如需登录 123 网盘，请在系统应用商店更新「Android System WebView」后重试。"
    }

    private fun shouldBlock(host: String): Boolean {
        if (host.isBlank()) return false
        if (BLOCK_HOST_SUFFIX.any { host == it || host.endsWith(".$it") }) return true
        // 只在非主域上命中关键词时拦截，避免误伤网盘业务域名
        return BLOCK_HOST_KEYWORD.any { host.contains(it) }
    }

    private fun emptyResponse(): WebResourceResponse = WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())

    /** 页面加载完成后自动保存最新 Cookie（登录态续期也不会丢） */
    private fun autoSaveCookie(showToast: Boolean) {
        try {
            val cookie = collectCookie(platform)
            if (cookie.isBlank()) return
            val old = Netdisk.cookieOf(platform)
            val loggedIn = looksLoggedIn(platform, cookie)
            val oldLoggedIn = looksLoggedIn(platform, old)
            try {
                CookieManager.getInstance()?.flush()
            } catch (_: Exception) {
            }
            if (cookie != old) {
                Netdisk.setCookie(platform, cookie)
                Logs.info("网盘 Cookie 已更新（$name）")
            }
            val gained = loggedIn && !oldLoggedIn
            refreshStatus()
            if (gained) {
                Toast.makeText(
                    this,
                    "$name 检测到登录成功，已自动保存 ✅ 可点「完成登录」返回",
                    Toast.LENGTH_LONG,
                ).show()
            } else if (showToast) {
                Toast.makeText(this, "已保存当前网页的 Cookie", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Logs.err("自动保存网盘 Cookie 失败: ${e.message}")
        }
    }

    private fun clearWebViewCookies() {
        try {
            val cm = CookieManager.getInstance()
            val domains = COOKIE_DOMAINS[platform] ?: emptyList()
            for (d in domains) {
                val c = try {
                    cm.getCookie(d)
                } catch (_: Throwable) {
                    null
                } ?: continue
                for (p in c.split(";")) {
                    val n = p.substringBefore("=").trim()
                    if (n.isNotBlank()) {
                        try {
                            cm.setCookie(d, "$n=; Max-Age=0; path=/")
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
            cm.flush()
        } catch (_: Exception) {
        }
    }

    private fun refreshStatus() {
        val cookie = Netdisk.cookieOf(platform)
        when {
            cookie.isBlank() -> tvStatus.text = "状态：未登录（无 Cookie）"
            looksLoggedIn(platform, cookie) ->
                tvStatus.text = "状态：已登录 ✅（自动同步，网页续期后无需重复保存）"
            else -> tvStatus.text = "状态：已保存 Cookie，但未识别到登录票据（可能未真正登录）"
        }
    }

    /**
     * 真实登录验证（三重保障，专为规避误报设计）：
     * 1) 页面内 JS 校验：让网页自己带上「当前实时会话」去请求校验接口 —— 最准确。
     *    因为夸克/UC 的 Cookie（__pus/__puus 等）会随每次 API 请求轮换，
     *    外部 OkHttp 拿到的 Cookie 快照可能瞬间就失效，这是此前「明明登录了却报未登录」的根因；
     * 2) OkHttp 校验：失败（false）自动重试最多 3 次，每次重新取最新 Cookie；
     * 3) 防抖：校验进行中/1.5 秒内不重复触发（页面重定向会多次触发 onPageFinished）。
     * 接口异常一律回落 Cookie 判定，绝不因接口问题误报「未登录」。
     */
    private fun asyncVerifyLogin(force: Boolean = false) {
        if (verifying) return
        if (!force && android.os.SystemClock.elapsedRealtime() - lastVerifyAt < 1500) return
        verifying = true
        lastVerifyAt = android.os.SystemClock.elapsedRealtime()
        runOnUiThread { tvStatus.text = "状态：检查登录中…" }

        // 第 1 优先：页面内校验（网页会话实时、带最新轮换 Cookie）
        if (pageHostMatches() && JS_VERIFY_URL.containsKey(platform)) {
            jsVerify { r ->
                runOnUiThread {
                    when (r) {
                        true -> {
                            verifying = false
                            showVerified(true)
                        }
                        false -> {
                            // 页面自身会话都 401 → 确定未登录，直接下结论
                            verifying = false
                            showVerified(false)
                        }
                        null -> okhttpVerify() // CORS/网络异常 → 外部校验兜底
                    }
                }
            }
        } else {
            okhttpVerify()
        }
    }

    /** OkHttp 校验：未登录结果重试最多 3 次（每次重取最新 Cookie，规避轮换竞态） */
    private fun okhttpVerify() {
        Thread {
            var result: Boolean? = null
            for (i in 0 until 3) {
                result = try {
                    Netdisk.verifyLogin(platform)
                } catch (e: Exception) {
                    Logs.err("网盘登录校验失败($platform): ${e.message}")
                    null
                }
                if (result != false) break
                try {
                    Thread.sleep(900)
                } catch (_: InterruptedException) {
                }
            }
            runOnUiThread {
                verifying = false
                if (!isFinishing && !isDestroyed) showVerified(result)
            }
        }.start()
    }

    private fun showVerified(r: Boolean?) {
        if (isFinishing || isDestroyed) return
        when (r) {
            true -> tvStatus.text = "状态：已登录 ✅（已在线校验 $name）"
            false -> tvStatus.text = "状态：未登录 ❌（请在网页中完成登录后点「完成登录」保存）"
            null -> {
                refreshStatus()
                tvStatus.text = tvStatus.text.toString() + "（未能在线校验）"
            }
        }
    }

    /** 当前 WebView 页面是否属于该平台（决定能否用页面内 JS 校验） */
    private fun pageHostMatches(): Boolean {
        val host = try {
            webView.url?.let { java.net.URI(it).host }
        } catch (_: Exception) {
            null
        }?.lowercase().orEmpty()
        if (host.isBlank()) return false
        return when (platform) {
            "quark" -> host == "quark.cn" || host.endsWith(".quark.cn")
            "uc" -> host == "uc.cn" || host.endsWith(".uc.cn")
            "123" -> host.endsWith("123pan.com") || host.endsWith("123pan.cn")
            else -> false
        }
    }

    /**
     * 页面内异步 XHR 校验：请求由网页自身发出。
     * 必须 withCredentials=true——否则跨域（pan.quark.cn → drive-pc.quark.cn）不带 Cookie，
     * 服务器必然 401/31001，会被误判为「未登录」（1.0.43 的 bug）。
     * 同时必须用异步 XHR + 轮询结果，同步 XHR 会卡死 WebView 主线程（页面直接冻结）。
     */
    private fun jsVerify(onDone: (Boolean?) -> Unit) {
        val url = JS_VERIFY_URL[platform] ?: run {
            onDone(null)
            return
        }
        val inject = "(function(){try{" +
            "window.__abVerifyDone=false;window.__abVerifyResult='';" +
            "var x=new XMLHttpRequest();" +
            "x.open('GET','$url',true);" +
            "x.withCredentials=true;" +
            "x.timeout=8000;" +
            "x.onload=function(){window.__abVerifyResult=x.status+'|'+(x.responseText||'').substring(0,300);window.__abVerifyDone=true;};" +
            "x.onerror=function(){window.__abVerifyResult='ERR|net';window.__abVerifyDone=true;};" +
            "x.ontimeout=function(){window.__abVerifyResult='ERR|timeout';window.__abVerifyDone=true;};" +
            "x.send();return 'OK';}catch(e){return 'ERR|'+e;}})();"
        try {
            runOnUiThread {
                webView.evaluateJavascript(inject) { raw ->
                    val s = raw?.trim('"') ?: "ERR"
                    if (s.startsWith("OK")) pollVerifyResult(onDone, 0) else onDone(null)
                }
            }
        } catch (e: Exception) {
            Logs.err("页面内登录校验注入失败: ${e.message}")
            onDone(null)
        }
    }

    /** 轮询页面内校验结果（最多 ~10 秒） */
    private fun pollVerifyResult(onDone: (Boolean?) -> Unit, tries: Int) {
        if (tries > 20) {
            onDone(null)
            return
        }
        webView.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            try {
                webView.evaluateJavascript("window.__abVerifyDone ? window.__abVerifyResult : ''") { raw ->
                    val s = try {
                        org.json.JSONTokener(raw).nextValue().toString()
                    } catch (_: Exception) {
                        raw ?: ""
                    }
                    if (s.isBlank() || s == "null") {
                        pollVerifyResult(onDone, tries + 1)
                    } else {
                        val status = s.substringBefore('|').trim().toIntOrNull()
                        val body = s.substringAfter('|', "")
                        val loggedOut = body.contains("31001") ||
                            body.contains("require login") ||
                            body.contains("用户未登录") ||
                            body.contains("\"errno\":-6")
                        onDone(
                            when {
                                status == null -> null
                                status == 200 && !loggedOut -> true
                                status == 401 || status == 403 || loggedOut -> false
                                else -> null
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                onDone(null)
            }
        }, 500)
    }

    private fun colorOf(id: Int): Int = resources.getColor(id, null)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        perfInjectHandler.removeCallbacks(perfInjectRunnable)
        popDialog?.dismiss()
        try {
            if (::webView.isInitialized) {
                webView.stopLoading()
                webView.destroy()
            }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}

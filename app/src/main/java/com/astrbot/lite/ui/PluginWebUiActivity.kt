package com.astrbot.lite.ui

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.astrbot.lite.core.LocalWebServer
import com.astrbot.lite.core.Logs
import com.astrbot.lite.core.PythonRuntime

/**
 * 插件网页界面（1.7.92 HTTP 版）：本地 HTTP 服务器伺服插件 web 资源，
 * 页面经同源 fetch `/__api/{pid}/...` 调 Python 插件后端——
 * 替代已弃用的 addJavascriptInterface 桥（真机反复失效、原因不可观测）。
 *
 * 端口：LocalWebServer 探测**最小可用端口**（1024 起）。
 * 用法：[openWebUi] 传入插件 id，自动探测 web 视图并以 http://127.0.0.1:{port} 加载。
 */
class PluginWebUiActivity : Activity() {

    /**
     * 1.7.207：改为可空 —— 页面在离开可见状态时会被主动销毁（不访问时杀掉页面），
     * 回到前台再重建。这样 WebView 及其渲染进程内存不会在后台长期驻留，
     * 避免长时间运行后累积造成闪退。
     */
    private var web: WebView? = null

    /** 当前页面 URL（重建时重新加载） */
    private var pageUrl: String? = null

    /** WebView 的父容器（重建时挂回去；E5 起正常路径为 FrameLayout，可叠蓝球；自测页仍为 LinearLayout） */
    private var pageContainer: android.view.ViewGroup? = null

    // ---------- E5 蓝球（虚拟指针）：DPad 驱动移动，确定键合成触摸 ----------

    /** 蓝球 View（FrameLayout 叠加在 WebView 之上） */
    private var ballView: android.view.View? = null

    /** 蓝球所在 FrameLayout（与 WebView 同级） */
    private var ballFrame: android.widget.FrameLayout? = null

    /** 蓝球左上角坐标（相对 ballFrame）；-1 表示未初始化，由 ensureBallPosition 居中 */
    private var ballX: Int = -1
    private var ballY: Int = -1

    // E5b: 自定义长按重复定时器（不依赖系统 repeatCount，跨机型一致）
    private var repeatDir: Int = 0
    private var repeatStartAt: Long = 0L
    private val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (repeatDir == 0) return
            val elapsed = android.os.SystemClock.uptimeMillis() - repeatStartAt
            val mult = when {
                elapsed < 350 -> 1
                elapsed < 900 -> 2
                elapsed < 1600 -> 3
                else -> 4
            }
            val step = ballStepPx * mult
            val dx = when (repeatDir) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -step
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> step
                else -> 0
            }
            val dy = when (repeatDir) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> -step
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> step
                else -> 0
            }
            if (dx != 0 || dy != 0) moveBall(dx, dy)
            repeatHandler.postDelayed(this, 55)
        }
    }

    // E5c: 蓝球显隐（持久化）
    private var ballVisible: Boolean = true

    /** JS 桥异步执行器（1.7.207）：固定 2 条守护线程，替代旧版「每次调用 new Thread」 */
    private val bridgePool = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "webui-bridge").apply { isDaemon = true }
    }

    /** 桥健康检查：页面注入 window.__bridgeAlive = true，定期 evaluateJavascript 探测 */
    private val healthHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val healthRunnable = object : Runnable {
        override fun run() {
            web?.evaluateJavascript("window.__bridgeAlive === true") { r ->
                if (r != "true") {
                    Logs.warn("[WebUI-Health] 桥探测失败（r=$r），先唤醒 Python loop 再重建")
                    // 先尝试唤醒 Python loop，再重建
                    try {
                        com.astrbot.lite.core.PythonRuntime.ensureShimLoop()
                    } catch (e: Exception) {
                        Logs.warn("[WebUI-Health] Python loop 唤醒失败: ${e.message}")
                    }
                    rebuildWeb("health-check-failed")
                } else {
                    healthHandler.postDelayed(this, 15000)  // 15s 一次
                }
            }
        }
    }
    private var healthStarted = false

    /** 文件选择回调（1.7.104）：onShowFileChooser 持有 → onActivityResult 回传 */
    private var filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == FILE_CHOOSER_REQ) {
            val cb = filePathCallback
            filePathCallback = null
            if (cb != null) {
                val uris = if (resultCode == RESULT_OK) {
                    val clips = data?.clipData
                    if (clips != null && clips.itemCount > 0) {
                        (0 until clips.itemCount).mapNotNull { clips.getItemAt(it).uri }.toTypedArray()
                    } else {
                        data?.data?.let { arrayOf(it) }
                    }
                } else null
                cb.onReceiveValue(uris)
                Logs.info("[WebUI] 文件选择结果：${uris?.size ?: 0} 个")
                return
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // babel 预载（1.7.98 转译加载器按需托管 /_runtime/babel.min.js）
        try {
            if (LocalWebServer.babelBytes == null) {
                LocalWebServer.babelBytes = assets.open("webruntime/babel.min.js").readBytes()
                Logs.info("[WebUI] babel-standalone 已预载（${LocalWebServer.babelBytes?.size} 字节）")
            }
        } catch (e: Exception) {
            Logs.warn("[WebUI] babel 预载失败: ${e.message}")
        }
        // WebView 版本（1.7.98 根因相关：老 WebView 不支持 ?. / ?? 等 ES2020+ 语法）
        try {
            val pkg = android.webkit.WebView.getCurrentWebViewPackage()
            Logs.info("[WebUI] WebView包: ${pkg?.packageName} ${pkg?.versionName}")
        } catch (t: Throwable) {
            Logs.info("[WebUI] WebView版本获取失败（getCurrentWebViewPackage 不可用）: $t")
        }
        val pid = intent.getStringExtra("pid")
        val title = intent.getStringExtra("title")

        // 交互自测模式（1.7.97）：四段式二分定位（纯点击/fetch/JS桥/环境），不依赖插件与 webRoot
        if (intent.getBooleanExtra("selftest", false)) {
            val p = LocalWebServer.ensureStarted()
            if (p < 0) {
                finish()
                return
            }
            val tv = TextView(this).apply {
                text = "交互自测（四段全绿=双通道正常）"
                textSize = 14f
                setPadding(16, 10, 16, 8)
                setBackgroundColor(0x1A202225.toInt())
            }
            val wv = WebView(this).apply {
                settings.javaScriptEnabled = true
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                        cm?.let { Logs.info("[WebUI-JS] ${it.messageLevel()} ${it.message()} (${it.sourceId()}:${it.lineNumber()})") }
                        return true
                    }
                }
            }
            // ping 回推复用 __selfcb（自测页协议）
            web = wv
            pageUrl = "http://127.0.0.1:$p/__selftest.html"
            val selRoot = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv)
                addView(wv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ))
            }
            pageContainer = selRoot
            setContentView(selRoot)
            Logs.info("[WebUI] 打开交互自测页 → $pageUrl")
            wv.loadUrl("http://127.0.0.1:$p/__selftest.html")
            return
        }

        val webRoot = pid?.let { PythonRuntime.pythonPluginWebRoot(it) }

        if (webRoot == null) {
            setContentView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(TextView(this@PluginWebUiActivity).apply {
                    text = (title ?: "插件") + " 没有可用的网页界面资源（views/pages/webui/ui/web 目录）"
                    textSize = 16f
                })
                addView(Button(this@PluginWebUiActivity).apply {
                    text = "返回"
                    setOnClickListener { finish() }
                })
            })
            return
        }

        // 视图相对路径（多视图时 webRoot 可能是子视图目录）——canonicalPath 手工换算
        // （relativeTo.replace 组合在本项目 Kotlin 版本下类型推断异常，1.7.87/1.7.92 两次实证，绕开）
        val pRoot = pluginRootOf(webRoot)
        val rel = try {
            val base = pRoot.canonicalPath
            val cur = webRoot.canonicalPath
            val sep = java.io.File.separatorChar
            if (cur.startsWith(base)) {
                val sub = cur.substring(base.length).trim { it == sep }
                val sb = StringBuilder()
                for (ch in sub) sb.append(if (ch == sep) '/' else ch)
                sb.toString()
            } else ""
        } catch (_: Exception) {
            ""
        }

        // 启动本地服务：登记 pid → 插件根 + 最近视图（帮助页/兜底解析用）
        val port = LocalWebServer.ensureStarted(pid, pRoot, rel)
        if (port < 0) {
            setContentView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(TextView(this@PluginWebUiActivity).apply {
                    text = "本地服务启动失败（没有可用端口），请稍后重试"
                    textSize = 16f
                })
                addView(Button(this@PluginWebUiActivity).apply {
                    text = "返回"
                    setOnClickListener { finish() }
                })
            })
            return
        }

        val rootLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tvTitle = TextView(this).apply {
            text = title ?: "插件网页"
            textSize = 16f
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0x1A202225.toInt())
        }
        // E5：WebView 外套 FrameLayout（蓝球叠加在 WebView 之上）
        val contentFrame = FrameLayout(this)
        web = createWebView()
        rootLayout.addView(tvTitle)
        rootLayout.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        web?.let {
            contentFrame.addView(it, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        // E5 蓝球：叠加在 WebView 上层，DPad 驱动移动，确定键合成触摸
        try { makeBall(contentFrame) } catch (t: Throwable) { Logs.warn("[WebUI-Ball] 创建蓝球失败: ${t.message}") }
        setContentView(rootLayout)
        pageContainer = contentFrame

        val relEnc = if (rel.isEmpty()) "" else "/" + rel.split("/").joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val url = "http://127.0.0.1:$port/$pid$relEnc/index.html"
        pageUrl = url
        Logs.info("[WebUI] $pid → $url")
        web?.loadUrl(url)
    }

    /**
     * 通道B（webprogress 回调制）：JS `window.webview.post("api", json)` —— void 无返回值，
     * Native 异步执行 callWebApi 后经 evaluateJavascript 回推 `window.__djcb(callId, resultJson)`，
     * 桩侧回调注册表 resolve/reject（不依赖 @JavascriptInterface 同步返回）。
     */
    inner class NativeBridge {
        @android.webkit.JavascriptInterface
        fun post(cmd: String?, param: String?) {
            // 自测通道（1.7.97）：ping 不经插件，直接回推——单独验证桥本身通不通
            if (cmd == "ping") {
                try {
                    val cid = org.json.JSONObject(param ?: "{}").optLong("selfcb", -1)
                    Logs.info("[Web桥-JS] ping 收到 (callId=$cid) → 回推")
                    runOnUiThread {
                        try {
                            web?.evaluateJavascript(
                                "window.__selfcb && window.__selfcb({\"ok\":\"true\"});", null
                            )
                        } catch (e: Exception) {
                            Logs.warn("[Web桥-JS] ping 回推失败: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Logs.warn("[Web桥-JS] ping 失败: ${e.message}")
                }
                return
            }
            if (cmd != "api") {
                Logs.warn("[Web桥-JS] 未知命令: $cmd")
                return
            }
            try {
                val o = org.json.JSONObject(param ?: "{}")
                val callId = o.optLong("callId", -1)
                val pid = o.optString("pid", "")
                val method = o.optString("method", "GET")
                val endpoint = o.optString("endpoint", "")
                val query = o.optString("query", "{}")
                val body = o.optString("body", "")
                if (callId < 0 || pid.isBlank()) {
                    Logs.warn("[Web桥-JS] 参数不全: $param")
                    return
                }
                Logs.info("[Web桥-JS] post $method /$pid/$endpoint (callId=$callId)")
                // 1.7.207：旧版每次调用 new Thread —— 页面轮询叠加卡死接口时会无限攒线程。
                // 改用固定守护池（2 条），超时/异常由 callWebApi 内部兜底，不再新增线程。
                bridgePool.execute {
                    val result = try {
                        PythonRuntime.callWebApi(listOf(pid), method, "/" + pid + "/" + endpoint, query, body)
                    } catch (e: Exception) {
                        """{"ok":"false","error":${org.json.JSONObject.quote(e.message ?: "bridge error")}}"""
                    }
                    runOnUiThread {
                        try {
                            web?.evaluateJavascript(
                                "window.__djcb && window.__djcb($callId, ${org.json.JSONObject.quote(result)});",
                                null
                            )
                        } catch (e: Exception) {
                            Logs.warn("[Web桥-JS] 回推失败: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Logs.warn("[Web桥-JS] post 解析失败: ${e.message}")
            }
        }
    }

    /** 从视图目录向上找 views/pages/webui/ui/web 命名目录的父 = 插件根（找不到就地为根） */
    private fun pluginRootOf(page: java.io.File): java.io.File {
        val names = setOf("views", "pages", "webui", "ui", "web")
        var cur: java.io.File? = page
        var guard = 0
        while (cur != null && guard++ < 16) {
            if (cur.name in names) return cur.parentFile ?: cur
            cur = cur.parentFile
        }
        return page
    }

    /**
     * 构建 WebView（1.7.207 从 onCreate 抽出）：
     * 后台释放页面后回到前台需要重建，所以构造逻辑必须可复用。
     * 诊断三件套保留：
     * ① 页面加载完成后自检探针（桩/桥/环境一键体检）
     * ② 页面 console.* 全部接入 App 日志（页面自己的报错终于可见）
     * ③ 文件选择器（1.7.104）：老 WebView(70) 必须显式 onShowFileChooser，否则
     *    <input type=file> 点击无反应——ACTION_GET_CONTENT + onActivityResult 回传
     */
    private fun createWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webChromeClient = object : android.webkit.WebChromeClient() {
            // 日志节流：同源+同消息 2 秒内只记一次，防止 JS 侧循环/高频日志刷屏
            private val consoleThrottle = java.util.concurrent.ConcurrentHashMap<String, Long>()
            override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                cm?.let {
                    val key = "${it.sourceId()}:${it.lineNumber()}:${it.message()}"
                    val now = System.currentTimeMillis()
                    val last = consoleThrottle.getOrDefault(key, 0L)
                    if (now - last >= 2000) {
                        consoleThrottle[key] = now
                        Logs.info("[WebUI-JS] ${it.messageLevel()} ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                    }
                }
                return true
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                // 已有选择器在飞：取消旧的（防 callback 泄漏）
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
                if (callback == null) return false
                filePathCallback = callback
                val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    val accepts = params?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
                    if (accepts.isNotEmpty()) {
                        // accept 形态混合：MIME（application/json）与后缀（.json）并存
                        val mime = accepts.firstOrNull { it.contains("/") }
                        if (mime != null) type = mime
                        val extras = accepts.map { if (it.startsWith(".")) "application/octet-stream" else it }
                        putExtra(android.content.Intent.EXTRA_MIME_TYPES, extras.toTypedArray())
                    }
                    // isMultipleEnabled 是 API26+（minSdk 21 直调会 NoSuchMethod）→ 反射探测
                    val multi = try {
                        params?.javaClass?.getMethod("isMultipleEnabled")?.invoke(params) == true
                    } catch (_: Exception) {
                        false
                    }
                    if (multi) {
                        putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQ)
                } catch (e: Exception) {
                    Logs.warn("[WebUI] 打开文件选择器失败: ${e.message}")
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                }
                return true
            }
        }
        webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 未捕获异常/未处理 Promise 拒绝 → console.error → 被 onConsoleMessage 接住
                view?.evaluateJavascript(
                    "window.onerror=function(m,s,l){console.error('UNCAUGHT: '+m+' @'+s+':'+l);return false;};" +
                        "window.addEventListener('unhandledrejection',function(e){console.error('UNHANDLED: '+((e.reason&&e.reason.message)||e.reason));});",
                    null
                )
            }

            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                Logs.info("[WebUI] 页面加载完成：$url")
                // 自检探针：桩是否注入、桥是否存在、环境形态——一条日志定乾坤
                val probe = "(function(){try{return JSON.stringify({" +
                    "page:typeof window.AstrBotPluginPage," +
                    "webview:typeof window.webview," +
                    "fetch:typeof window.fetch," +
                    "href:location.href});}catch(e){return 'ERR:'+e.message;}})()"
                view?.evaluateJavascript(probe) { r ->
                    Logs.info("[WebUI] 页面自检: $r")
                }
            }
        }
        // 1.7.96 通道B（参考 webprogress 回调制）：window.webview.post(cmd, json) void 调用，
        // Native 异步执行后 evaluateJavascript 回推 —— 不依赖同步返回值（规避部分 WebView
        // 桥同步返回不可靠问题）。HTTP fetch 为主通道，失败自动降级本通道。
        addJavascriptInterface(NativeBridge(), "webview")
    }

    /**
     * 释放页面（1.7.207）：离开可见状态即销毁 WebView，归还渲染进程内存。
     * 页面 URL 保留，回到前台由 [rebuildWebIfNeeded] 重建并重新加载。
     * 文件选择器在飞时不销毁（否则选择结果回来时会崩在已销毁的 WebView 上）。
     */
    private fun releaseWeb(reason: String) {
        if (filePathCallback != null) {
            Logs.info("[WebUI] $reason：文件选择进行中，暂不释放页面")
            return
        }
        val w = web ?: return
        web = null
        try {
            try { (w.parent as? android.view.ViewGroup)?.removeView(w) } catch (_: Exception) {}
            try { w.stopLoading() } catch (_: Exception) {}
            try { w.loadUrl("about:blank") } catch (_: Exception) {}
            try { w.removeJavascriptInterface("webview") } catch (_: Exception) {}
            try { w.settings.javaScriptEnabled = false } catch (_: Exception) {}
            w.destroy()
            Logs.info("[WebUI] $reason：页面已销毁释放（下次进入自动重建）")
        } catch (t: Throwable) {
            Logs.warn("[WebUI] 释放页面失败: ${t.message}")
        }
    }

    // ==================== E5 蓝球（虚拟指针） ====================

    /** 蓝球直径（px），构造时按屏幕密度换算 */
    private val ballSizePx: Int by lazy { (24 * resources.displayMetrics.density).toInt() }

    /** 单次按键移动步长（px） */
    private val ballStepPx: Int by lazy { (28 * resources.displayMetrics.density).toInt() }

    /** 创建蓝球并挂到指定 FrameLayout 顶层 */
    private fun makeBall(frame: FrameLayout) {
        ballFrame = frame
        val size = ballSizePx
        val dot = android.view.View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xCC3AA0FF.toInt())
                setStroke((2 * resources.displayMetrics.density).toInt(), 0xFFFFFFFF.toInt())
            }
            isFocusable = false
            isClickable = false
            // 半透明阴影感（可选）：elevation 低版本兼容处理
            try { elevation = 4f * resources.displayMetrics.density } catch (_: Throwable) {}
        }
        val lp = FrameLayout.LayoutParams(size, size).apply {
            leftMargin = 0
            topMargin = 0
        }
        frame.addView(dot, lp)
        ballView = dot
        // 首帧拿到 frame 尺寸后再定位；否则宽高还是 0
        frame.post {
            loadBallPrefs()
            ensureBallPosition()
        }
        Logs.info("[WebUI-Ball] 蓝球已创建 size=${size}px step=${ballStepPx}px")
    }

    /** 未初始化时把蓝球放到 frame 中央；已初始化则钳制在可视区内 */
    private fun ensureBallPosition() {
        val f = ballFrame ?: return
        val v = ballView ?: return
        val maxX = (f.width - v.width).coerceAtLeast(0)
        val maxY = (f.height - v.height).coerceAtLeast(0)
        if (ballX < 0 || ballY < 0) {
            ballX = maxX / 2
            ballY = maxY / 2
        } else {
            ballX = ballX.coerceIn(0, maxX)
            ballY = ballY.coerceIn(0, maxY)
        }
        applyBallPosition()
    }

    private fun applyBallPosition() {
        val v = ballView ?: return
        val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.leftMargin = ballX
        lp.topMargin = ballY
        v.layoutParams = lp
    }

    // ---------- E5c: 蓝球位置/显隐持久化 ----------

    private fun ballPrefs() = getSharedPreferences("webui_ball", MODE_PRIVATE)

    /** 读取上次蓝球位置与显隐；无记录则不覆盖默认居中 */
    private fun loadBallPrefs() {
        try {
            val sp = ballPrefs()
            val x = sp.getInt("x", -1)
            val y = sp.getInt("y", -1)
            if (x >= 0 && y >= 0) {
                ballX = x
                ballY = y
            }
            ballVisible = sp.getBoolean("visible", true)
            ballView?.visibility = if (ballVisible) android.view.View.VISIBLE else android.view.View.INVISIBLE
            Logs.info("[WebUI-Ball] 已恢复位置 ($x, $y) visible=$ballVisible")
        } catch (t: Throwable) {
            Logs.warn("[WebUI-Ball] 恢复失败: ${t.message}")
        }
    }

    /** 保存当前蓝球位置与显隐 */
    private fun saveBallPrefs() {
        try {
            ballPrefs().edit()
                .putInt("x", ballX)
                .putInt("y", ballY)
                .putBoolean("visible", ballVisible)
                .apply()
        } catch (_: Throwable) {}
    }

    /** 切换蓝球显隐（菜单键触发） */
    private fun toggleBallVisibility() {
        val v = ballView ?: return
        ballVisible = !ballVisible
        v.visibility = if (ballVisible) android.view.View.VISIBLE else android.view.View.INVISIBLE
        saveBallPrefs()
        Logs.info("[WebUI-Ball] 显隐切换 → ${if (ballVisible) "显示" else "隐藏"}")
    }

    /** 相对移动蓝球（dx/dy 单位 px），钳制在 frame 内 */
    private fun moveBall(dx: Int, dy: Int) {
        val f = ballFrame ?: return
        val v = ballView ?: return
        if (ballX < 0 || ballY < 0) ensureBallPosition()
        val maxX = (f.width - v.width).coerceAtLeast(0)
        val maxY = (f.height - v.height).coerceAtLeast(0)
        ballX = (ballX + dx).coerceIn(0, maxX)
        ballY = (ballY + dy).coerceIn(0, maxY)
        applyBallPosition()
        // E5c: 移动后防抖保存（1s 内无新移动才落盘，避免高频写）
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, 1000)
    }

    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveRunnable = Runnable { saveBallPrefs() }

    /**
     * 在蓝球中心合成一次点击：计算蓝球中心相对 WebView 的坐标，
     * 依次 dispatch DOWN → UP（间隔 60ms，模拟真实点击）。
     */
    private fun dispatchTouchAtBall() {
        val wv = web ?: return
        val f = ballFrame ?: return
        val v = ballView ?: return
        if (ballX < 0 || ballY < 0) ensureBallPosition()
        // 蓝球中心在 frame 坐标系中的位置
        val cxInFrame = ballX + v.width / 2f
        val cyInFrame = ballY + v.height / 2f
        // 转成 WebView 内坐标：frame 与 WebView 同父（FrameLayout 直接子级），
        // 直接拿两者在 window 中的绝对坐标差
        val fLoc = IntArray(2).also { f.getLocationOnScreen(it) }
        val wLoc = IntArray(2).also { wv.getLocationOnScreen(it) }
        val cxInWeb = cxInFrame + (fLoc[0] - wLoc[0])
        val cyInWeb = cyInFrame + (fLoc[1] - wLoc[1])
        if (cxInWeb < 0 || cyInWeb < 0 || cxInWeb > wv.width || cyInWeb > wv.height) {
            Logs.warn("[WebUI-Ball] 蓝球超出 WebView 范围，跳过点击 ($cxInWeb, $cyInWeb)")
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, cxInWeb, cyInWeb, 0)
        val up = android.view.MotionEvent.obtain(now, now + 60, android.view.MotionEvent.ACTION_UP, cxInWeb, cyInWeb, 0)
        try {
            wv.dispatchTouchEvent(down)
            wv.postDelayed({ try { wv.dispatchTouchEvent(up) } catch (_: Throwable) {} }, 60)
            Logs.info("[WebUI-Ball] 合成点击 ($cxInWeb, $cyInWeb)")
        } catch (t: Throwable) {
            Logs.warn("[WebUI-Ball] 合成点击失败: ${t.message}")
        } finally {
            down.recycle()
            // up 在 postDelayed 里用完后回收
            wv.postDelayed({ try { up.recycle() } catch (_: Throwable) {} }, 200)
        }
    }

    /** 启动桥健康检查（15s 一次 evaluateJavascript 探测 window.__bridgeAlive） */
    private fun startHealthCheck() {
        if (healthStarted) return
        healthStarted = true
        healthHandler.postDelayed(healthRunnable, 15000)
        Logs.info("[WebUI-Health] 健康检查已启动（15s 间隔）")
    }

    /** 统一重建入口：reason 用于日志区分触发来源 */
    private fun rebuildWeb(reason: String) {
        Logs.info("[WebUI] 触发重建：$reason")
        // 先唤醒 Python 运行时 loop（防止 Chaquopy 被冻结导致桥调用超时）
        try {
            com.astrbot.lite.core.PythonRuntime.ensureShimLoop()
        } catch (e: Exception) {
            Logs.warn("[WebUI] 唤醒 Python loop 失败: ${e.message}")
        }
        // 重建 WebView
        val url = pageUrl ?: return
        val container = pageContainer ?: return
        // 先清理旧 WebView（如果有）
        if (web != null) {
            try { (web!!.parent as? android.view.ViewGroup)?.removeView(web!!) } catch (_: Exception) {}
            try { web!!.stopLoading() } catch (_: Exception) {}
            try { web!!.loadUrl("about:blank") } catch (_: Exception) {}
            try { web!!.removeJavascriptInterface("webview") } catch (_: Exception) {}
            try { web!!.settings.javaScriptEnabled = false } catch (_: Exception) {}
            try { web!!.destroy() } catch (_: Exception) {}
            web = null
        }
        val wv = createWebView()
        web = wv
        if (container is FrameLayout) {
            container.addView(wv, 0, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        } else {
            container.addView(wv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
        pageUrl = url
        Logs.info("[WebUI] 重建完成 → $url")
        wv.loadUrl(url)
        // 页面加载完成后注入桥存活标记 + 启动健康检查
        wv.setWebViewClient(object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript("window.__bridgeAlive = true", null)
                startHealthCheck()
            }
        })
    }

    private fun rebuildWebIfNeeded() {
        if (web != null) return
        val url = pageUrl ?: return
        val container = pageContainer ?: return
        val wv = createWebView()
        web = wv
        if (container is FrameLayout) {
            container.addView(wv, 0, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        } else {
            container.addView(wv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
        Logs.info("[WebUI] 重建页面 → $url")
        wv.loadUrl(url)
    }

    override fun onStart() {
        super.onStart()
        // 页面重新可见：若 WebView 被系统回收或桥失效，重建并启动健康检查
        web?.onResume()
        if (web == null || pageUrl == null) {
            rebuildWeb("onStart-null")
        } else {
            startHealthCheck()
        }
    }

    override fun onStop() {
        // E5b/c: 停止长按重复 + 落盘蓝球状态（防抖任务若未执行，这里兜底）
        repeatDir = 0
        repeatHandler.removeCallbacks(repeatRunnable)
        saveHandler.removeCallbacks(saveRunnable)
        saveBallPrefs()
        // 停止健康检查，但不销毁 WebView（保活，避免待久后桥失效需重建）
        healthHandler.removeCallbacks(healthRunnable)
        healthStarted = false
        // WebView 暂停：停止 JS 计时器、暂停渲染，不销毁
        web?.onPause()
        super.onStop()
    }

    /**
     * E5 蓝球按键拦截：方向键移动蓝球（长按按 repeatCount 加速），
     * 确定键在蓝球中心合成一次点击。BACK 键不拦，仍走 onBackPressed（web.canGoBack）。
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val ball = ballView
        val wv = web
        if (ball != null && wv != null) {
            val kc = event.keyCode
            val isDown = event.action == android.view.KeyEvent.ACTION_DOWN
            val isUp = event.action == android.view.KeyEvent.ACTION_UP

            // E5c: 菜单键切换蓝球显隐（无论当前显隐都拦截）
            if (kc == android.view.KeyEvent.KEYCODE_MENU) {
                if (isDown) toggleBallVisibility()
                return true
            }

            // E5c: 蓝球隐藏时，按键全部放行给 WebView（恢复原生遥控体验）
            if (!ballVisible) return super.dispatchKeyEvent(event)

            val isDpad = kc == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                kc == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
                kc == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                kc == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
                kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                kc == android.view.KeyEvent.KEYCODE_ENTER
            if (isDpad) {
                when (kc) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP,
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (isDown) {
                            // E5b: 按下立即走一步，同时启动自定义重复定时器
                            repeatDir = kc
                            repeatStartAt = android.os.SystemClock.uptimeMillis()
                            val step = ballStepPx
                            val dx = when (kc) {
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -step
                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> step
                                else -> 0
                            }
                            val dy = when (kc) {
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> -step
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> step
                                else -> 0
                            }
                            if (dx != 0 || dy != 0) moveBall(dx, dy)
                            repeatHandler.removeCallbacks(repeatRunnable)
                            repeatHandler.postDelayed(repeatRunnable, 350)
                        } else if (isUp) {
                            // E5b: 松手停止重复
                            repeatDir = 0
                            repeatHandler.removeCallbacks(repeatRunnable)
                        }
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        if (isUp) {
                            dispatchTouchAtBall()
                        }
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        releaseWeb("Activity 销毁")
        super.onDestroy()
    }

    companion object {
        /** 文件选择器请求码 */
        private const val FILE_CHOOSER_REQ = 7701

        /** 便捷打开入口 */
        fun openWebUi(act: Activity, pluginId: String, pluginName: String) {
            act.startActivity(android.content.Intent(act, PluginWebUiActivity::class.java).apply {
                putExtra("pid", pluginId)
                putExtra("title", pluginName)
            })
        }

        /** 交互自测页入口（1.7.97）：四段二分定位（纯点击/fetch/JS桥/环境探针） */
        fun openSelfTest(act: Activity) {
            act.startActivity(android.content.Intent(act, PluginWebUiActivity::class.java).apply {
                putExtra("selftest", true)
            })
        }
    }
}

package com.astrbot.lite.core

import com.astrbot.lite.ui.PluginWebUiBridge
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 本地 HTTP 服务器（1.7.92）：伺服 AstrBot 插件 web 资源 + 桥接插件 API。
 *
 * 架构动机：WebView 的 addJavascriptInterface 桥在真机反复失效（点击无响应、原因不可观测），
 * 改用最成熟的通道——页面 `fetch` 同源 `http://127.0.0.1:{port}/...`（天然无 CORS、可浏览器直连调试）。
 *
 * 路由（pid 段支持 converted_plugin 等别名，服务端经 [PythonRuntime] 解析）：
 * - `GET/POST /__api/{pid}/{endpoint...}?{query}` → PythonRuntime.callWebApi（插件后端 API 代理）
 * - `GET /{pid}/{relPath}` → 插件根静态文件（HTML 注入 bridge 桩 + 离线外链改写；越界拒绝）
 *
 * 端口：从 1024 起**探测最小可用端口**（bind 即测，失败秒换；Android 非特权进程不能 <1024）。
 */
object LocalWebServer {

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    var port: Int = -1
        private set

    /** pid → 插件根目录（Activity 打开页面时注册；key 为显示用 id，服务时按别名解析目录） */
    private val roots = java.util.concurrent.ConcurrentHashMap<String, File>()

    /** 最近打开的页面（pid/插件根）：无 pid 段的请求与 root 解析失败时兜底 */
    @Volatile
    private var lastPid: String? = null

    @Volatile
    private var lastRoot: File? = null

    /** 最近打开视图的相对路径（帮助页直达链接用，如 pages/pluginCenter） */
    @Volatile
    private var lastViewRel: String = ""

    /** babel-standalone 字节（Activity 打开页面时从 assets 预载；托管 /_runtime/babel.min.js） */
    @Volatile
    var babelBytes: ByteArray? = null

    /**
     * 1.7.207 修复「过久了网页打不开」：
     * 旧版是 newCachedThreadPool（无上限）—— 每个 HTTP 请求一条线程，页面轮询叠加
     * 卡死的 Python 调用时线程数可无限膨胀；同时 accept 线程只 catch Exception，
     * 一旦抛 Error（Python 原生崩溃/OOM 波及）该线程静默死亡，而 server 字段仍非 null、
     * port 仍有效，ensureStarted 会误判「服务已在跑」→ TCP 能连上但永远没人 accept，
     * 页面永久白屏。现改为：有界线程池 + 并发信号量 + accept 看门狗自动重建。
     */
    private const val MAX_WORKERS = 12
    private const val WATCHDOG_PERIOD_MS = 20_000L

    private val pool = Executors.newFixedThreadPool(MAX_WORKERS) { r ->
        Thread(r, "local-web").apply { isDaemon = true }
    }

    /** 在跑 + 排队的请求总数上限（超出直接拒绝，不排队堆内存） */
    private val inFlight = java.util.concurrent.Semaphore(MAX_WORKERS * 2)

    private val rejected = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var acceptThread: Thread? = null

    /** 看门狗：accept 线程死了就把服务重建一次（幂等，只在异常时触发） */
    private val watchdog: java.util.concurrent.ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "local-web-watch").apply { isDaemon = true }
        }

    @Volatile
    private var watchdogStarted = false

    /** 从 1024 起探测第一个可 bind 的端口（"最小且可用"） */
    private fun findFreePort(): Int {
        for (p in 1024..65535) {
            try {
                ServerSocket(p, 50, InetAddress.getByName("127.0.0.1")).use { return p }
            } catch (_: Exception) {
                // 被占用/受限 → 下一个
            }
        }
        return -1
    }

    /** 幂等启动；返回监听端口（失败 -1）。root 非空时登记该 pid 的插件根。viewRel=视图相对路径（帮助页直达） */
    @Synchronized
    fun ensureStarted(pid: String? = null, root: File? = null, viewRel: String = ""): Int {
        if (pid != null && root != null) {
            roots[pid] = root
            lastPid = pid
            lastRoot = root
            lastViewRel = viewRel
        }
        server?.let { if (!it.isClosed) return port }
        val p = findFreePort()
        if (p < 0) {
            Logs.err("[本地服务] 未找到可用端口（1024-65535 全占用？）")
            return -1
        }
        try {
            val ss = ServerSocket(p, 50, InetAddress.getByName("127.0.0.1"))
            server = ss
            port = p
            val t = Thread({
                Logs.info("[本地服务] 已监听 127.0.0.1:$p")
                while (!ss.isClosed) {
                    try {
                        val sock = ss.accept()
                        // 限流：超出在跑+排队上限就直接关掉，不让线程/内存无限堆积
                        if (!inFlight.tryAcquire()) {
                            val n = rejected.incrementAndGet()
                            if (n == 1 || n % 20 == 0) {
                                Logs.warn("[本地服务] 并发已满，拒绝新请求（累计 $n）。页面可能卡在慢接口上")
                            }
                            try { sock.close() } catch (_: Exception) {}
                            continue
                        }
                        try {
                            pool.execute {
                                try { handle(sock) } finally { inFlight.release() }
                            }
                        } catch (e: Exception) {
                            inFlight.release()
                            try { sock.close() } catch (_: Exception) {}
                            Logs.warn("[本地服务] 提交任务失败: ${e.message}")
                        }
                    } catch (t: Throwable) {
                        // 1.7.207：旧版只 catch Exception，Error 会让 accept 线程静默死亡
                        if (ss.isClosed) break
                        Logs.err("[本地服务] accept 异常（继续监听）: ${t.javaClass.simpleName} ${t.message}")
                        try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                    }
                }
                Logs.warn("[本地服务] accept 循环退出（端口 $p）")
            }, "local-web-accept")
            t.isDaemon = true
            acceptThread = t
            t.start()
            startWatchdogIfNeeded()
            return p
        } catch (e: Exception) {
            Logs.err("[本地服务] 启动失败: ${e.message}")
            return -1
        }
    }

    /**
     * 看门狗（1.7.207）：定期确认 accept 线程还活着。
     * 若监听套接字仍打开但 accept 线程已死（页面表现为能连上却永远无响应），
     * 就关掉旧套接字并重建，让「过久了网页打不开」自愈。
     *
     * 1.7.223 修复死锁：ensureStarted / stop 都是 @Synchronized，而本看门狗任务
     * 若再调 ensureStarted()，scheduleWithFixedDelay 会立即同步执行任务去抢同一把
     * monitor → 自锁死锁。故把「重建」放到独立方法，任务内先释放锁再重建。
     */
    @Synchronized
    private fun startWatchdogIfNeeded() {
        if (watchdogStarted) return
        watchdogStarted = true
        try {
            watchdog.scheduleWithFixedDelay({
                try {
                    rebuildIfAcceptDead()
                } catch (t: Throwable) {
                    Logs.err("[本地服务] 看门狗异常: ${t.message}")
                }
            }, WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Logs.warn("[本地服务] 看门狗启动失败: ${e.message}")
        }
    }

    /** 看门狗触发点：检测 accept 线程死亡并重建（不持 @Synchronized 锁，避免自锁死锁） */
    private fun rebuildIfAcceptDead() {
        val ss = server
        val th = acceptThread
        if (ss == null || ss.isClosed || (th != null && th.isAlive)) return
        Logs.err("[本地服务] 检测到 accept 线程已死，正在重建监听")
        stop()              // 内部 @Synchronized，会拿到锁后释放
        ensureStarted()     // 内部 @Synchronized，重新 bind + 起 accept + 复用已启动看门狗
    }

    @Synchronized
    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        acceptThread = null
        port = -1
        roots.clear()
        Logs.info("[本地服务] 已停止")
    }

    // ---------------- 请求处理 ----------------
    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 15000
            val raw = sock.getInputStream()
            // 逐字节读 header（到 \r\n\r\n）——不能用 BufferedReader：其内部缓冲会把 body 前段吞进字符缓冲，
            // 二进制 body（upload base64 前的 JSON 尚可，multipart 不行）与边界都会错位
            val headBytes = java.io.ByteArrayOutputStream()
            var state = 0 // 0=期待\r 1=\n 2=\r 3=\n（匹配 \r\n\r\n）
            while (state < 4) {
                val b = raw.read()
                if (b < 0) break
                headBytes.write(b)
                when {
                    state == 0 && b == '\r'.code -> state = 1
                    state == 1 && b == '\n'.code -> state = 2
                    state == 1 && b != '\r'.code -> state = 0
                    state == 2 && b == '\r'.code -> state = 3
                    state == 2 && b != '\r'.code -> state = 0
                    state == 3 && b == '\n'.code -> state = 4
                    else -> state = 0
                }
                if (headBytes.size() > 64 * 1024) break
            }
            val headerText = headBytes.toString("UTF-8")
            if (headerText.isEmpty()) { sock.close(); return }
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull() ?: run { sock.close(); return }
            var contentLength = 0
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.startsWith("Content-Length:", true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2) { sock.close(); return }
            val method = parts[0].uppercase()
            val rawPath = parts[1]
            // 精确读 body 字节（不经字符缓冲）
            val bodyBytes = if (contentLength > 0 && contentLength < 32 * 1024 * 1024) {
                val buf = ByteArray(contentLength)
                var off = 0
                while (off < contentLength) {
                    val n = raw.read(buf, off, contentLength - off)
                    if (n < 0) break
                    off += n
                }
                buf
            } else ByteArray(0)

            val path = rawPath.substringBefore("?")
            val query = rawPath.substringAfter("?", "")
            val out = sock.getOutputStream()

            // 请求级日志（1.7.95）：页面开没开/请求到没到/哪层失败，日志一眼可见
            val isApi = path.startsWith("/__api/")
            if (isApi) Logs.info("[本地服务] $method $path${if (query.isNotBlank()) "?$query" else ""}")

            when {
                // 自测页/自测接口（1.7.97）：不依赖插件，四段二分定位交互断点
                path == "/__selftest.html" -> writeText(out, "text/html; charset=utf-8", SELF_TEST_HTML)
                path == "/__selftest.json" -> writeJson(out, """{"ok":"true","payload":{"pong":1,"channel":"http"}}""")
                // babel-standalone（1.7.98 转译加载器按需拉取；1.7.101 no-store——旧 Babel6 曾被磁盘缓存命中）
                path == "/_runtime/babel.min.js" -> {
                    val b = babelBytes
                    if (b == null) writeText(out, "text/plain", "babel not loaded")
                    else {
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/javascript; charset=utf-8\r\nContent-Length: ${b.size}\r\nCache-Control: no-store, no-cache, must-revalidate\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(b)
                    }
                }
                isApi -> serveApi(method, path, query, bodyBytes, out)
                else -> serveStatic(path, out)
            }
            out.flush()
        } catch (t: Throwable) {
            // 1.7.207：旧版 `catch (_: Exception) {}` 会把请求层故障全部吞掉，页面白屏时
            // App 日志里没有任何线索。现在至少留一行（同名异常做节流，避免刷屏）
            val tag = t.javaClass.simpleName + ":" + (t.message ?: "")
            if (lastErrTag != tag) {
                lastErrTag = tag
                Logs.warn("[本地服务] 请求处理失败: $tag")
            }
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    @Volatile
    private var lastErrTag: String = ""

    /** /__api/{pid}/{endpoint...} → Python 插件 Web API（经垫片 call_web_api） */
    private fun serveApi(method: String, path: String, query: String, body: ByteArray, out: OutputStream) {
        val rest = path.removePrefix("/__api/")
        val pid = rest.substringBefore("/").let { java.net.URLDecoder.decode(it, "UTF-8") }
        val endpoint = rest.substringAfter("/", "")
        if (pid.isBlank() || endpoint.isBlank()) {
            writeJson(out, """{"ok":"false","error":"缺少 pid 或 endpoint"}""")
            return
        }
        val bodyStr = try { String(body, Charsets.UTF_8) } catch (_: Exception) { "" }
        val raw = PythonRuntime.callWebApi(
            pluginIds = listOf(pid),
            method = method,
            path = "/" + pid + "/" + endpoint,
            queryJson = queryToJson(query),
            bodyJson = bodyStr
        )
        // 垫片响应里可能带 config 写回字段——callWebApi 内部已剥离并持久化，这里原样透传
        writeJson(out, raw)
    }

    /** `a=1&b=x` → JSON 字符串 {"a":"1","b":"x"}（多值取并逗号连接，够页面用） */
    private fun queryToJson(query: String): String {
        if (query.isBlank()) return "{}"
        val o = org.json.JSONObject()
        try {
            for (pair in query.split("&")) {
                if (pair.isBlank()) continue
                val k = java.net.URLDecoder.decode(pair.substringBefore("="), "UTF-8")
                val v = java.net.URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8")
                if (o.has(k)) o.put(k, o.optString(k) + "," + v) else o.put(k, v)
            }
        } catch (_: Exception) {
        }
        return o.toString()
    }

    /** 静态文件：/{pid}/{relPath}（pid 经别名解析；HTML 注入桩+离线改写；越界拒绝） */
    private fun serveStatic(path: String, out: OutputStream) {
        val clean = java.net.URLDecoder.decode(path.removePrefix("/"), "UTF-8")
        var pid = clean.substringBefore("/")
        var rel = clean.substringAfter("/", "")
        // 优先 Activity 登记的插件根；其次按 pid 经别名解析（pluginDir 内部 aliasDir）
        var root: File? = if (pid.isBlank()) null else (roots[pid] ?: PythonRuntime.pluginDir(pid))
        // 兜底（1.7.93）：pid 段解析不出根（直接访问根路径 /、或无效 pid）→ 用最近打开的插件根，
        // 并丢弃无效 pid 段重解析 rel——"直接开根路径/资源缺 pid 段"两种场景都自愈
        if (root == null && lastRoot != null) {
            val altRel = if (pid.isBlank()) clean else clean.substringAfter("/", "")
            val candidate = File(lastRoot, altRel)
            if (altRel.isNotBlank() && candidate.isFile) {
                root = lastRoot
                rel = altRel
                pid = lastPid ?: pid
            }
        }
        if (rel.isBlank()) {
            // 根路径/裸端口访问 → 帮助页（列出可用入口），而不是生硬的 Not Found
            Logs.warn("[本地服务] 根路径访问（无 pid/rel）：path=$path，返回帮助页（已注册=${roots.keys}）")
            writeText(out, "text/html; charset=utf-8", helpHtml())
            return
        }
        if (root == null) {
            Logs.warn("[本地服务] 静态未命中 pid=$pid rel=$rel roots=${roots.keys} last=$lastPid path=$path")
            writeText(out, "text/plain; charset=utf-8",
                "Not Found (plugin root)：无法解析插件目录 pid=$pid。请从 App 内插件页的「Web」入口打开。")
            return
        }
        val relFinal = if (rel.endsWith("/")) rel + "index.html" else rel
        val f = File(root, relFinal)
        val sep = File.separator
        if (!f.isFile || !f.canonicalPath.startsWith(root.canonicalPath + sep)) {
            Logs.warn("[本地服务] 文件不存在/越界 root=${root.name} rel=$relFinal")
            writeText(out, "text/plain; charset=utf-8", "Not Found: $relFinal")
            return
        }
        val name = f.name
        val ext = name.substringAfterLast('.', "").lowercase()
        // 页面级请求记日志（js/css 等资源不记，避免刷屏；404 已有 warn）
        if (ext == "html" || ext == "htm") {
            Logs.info("[本地服务] GET /$pid/$relFinal → 200（插件根 ${root.name}）")
        }
        when {
            ext == "html" || ext == "htm" -> {
                var html = f.readText(Charsets.UTF_8)
                html = WebConverter.rewrite(html, loadManifest(root))
                writeText(out, "text/html; charset=utf-8", PluginWebUiBridge.inject(html))
            }
            ext == "css" -> {
                val css = WebConverter.rewrite(f.readText(Charsets.UTF_8), loadManifest(root))
                writeText(out, "text/css; charset=utf-8", css)
            }
            else -> {
                val mime = mimeFor(ext)
                val bytes = f.readBytes()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(bytes)
            }
        }
    }

    /** 根路径帮助页：列出可用插件入口（替代生硬的 Not Found，方便浏览器调试直达） */
    private fun helpHtml(): String {
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset='utf-8'><title>AstrBotLite 本地服务</title></head><body style='font-family:sans-serif;padding:24px;background:#1B2226;color:#ECEFF1'>")
        sb.append("<h3>AstrBotLite 插件本地服务运行中（端口 $port）</h3>")
        sb.append("<p>请从 App 内「插件 → Web」入口打开页面；或直接访问：</p><ul>")
        if (lastPid != null && lastViewRel.isNotBlank()) {
            sb.append("<li><a style='color:#4FC3F7' href='/$lastPid/$lastViewRel/index.html'>打开最近视图：$lastPid/$lastViewRel</a></li>")
        }
        for ((p, _) in roots) {
            if (p == lastPid) continue
            sb.append("<li><code style='color:#90A4AE'>/$p/…</code>（该插件根需带视图路径，如 /$p/pages/视图名/index.html）</li>")
        }
        if (roots.isEmpty()) {
            sb.append("<li>（暂无已注册插件——请先在 App 内打开一次插件网页）</li>")
        }
        sb.append("</ul><p style='color:#90A4AE'>URL 形态：/{插件id}/{相对路径}，后端 API：/__api/{插件id}/{endpoint}</p></body></html>")
        return sb.toString()
    }

    /**
     * 交互自测页（1.7.97）：四段二分定位——
     * ① 纯 DOM 点击（验触摸/JS执行）② fetch 本服（验通道A）
     * ③ webview.post 回调（验通道B）④ 环境探针（桩/桥/UA）。
     * 访问：http://127.0.0.1:{port}/__selftest.html
     */
    private val SELF_TEST_HTML = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>交互自测</title></head><body style="font-family:sans-serif;padding:16px;background:#1B2226;color:#ECEFF1">
<h3>AstrBotLite 交互自测</h3>
<p style="color:#90A4AE">四段全绿=WebView与双通道都正常，问题在插件页面自身；哪段红=断在哪层。</p>
<button id="b1" style="padding:10px 16px">① 纯点击测试</button> <span id="r1" style="color:#EF5350">未点</span><br><br>
<button id="b2" style="padding:10px 16px">② HTTP通道(fetch)</button> <span id="r2" style="color:#EF5350">未测</span><br><br>
<button id="b3" style="padding:10px 16px">③ JS桥通道(post)</button> <span id="r3" style="color:#EF5350">未测</span><br><br>
<button id="b5" style="padding:10px 16px">④ Babel转译能力</button> <span id="r5" style="color:#EF5350">未测</span><br><br>
<pre id="env" style="background:#263238;padding:10px;color:#4FC3F7;font-size:12px"></pre>
<script>
var n=0;
document.getElementById('b1').onclick=function(){n++;document.getElementById('r1').textContent='OK 第'+n+'次点击';};
document.getElementById('b2').onclick=function(){
  var el=document.getElementById('r2'); el.textContent='请求中…';
  fetch('/__selftest.json').then(function(r){return r.text();}).then(function(t){
    try{var o=JSON.parse(t); el.textContent=(o.ok==='true'||o.ok===true)?'OK 通道A通':'异常:'+t.slice(0,60);}
    catch(e){el.textContent='解析失败';}
  }).catch(function(e){el.textContent='FAIL: '+e.message;});
};
document.getElementById('b3').onclick=function(){
  var el=document.getElementById('r3');
  if(!window.webview||typeof window.webview.post!=='function'){el.textContent='FAIL: window.webview缺失';return;}
  el.textContent='等待回推…';
  window.__selfto=setTimeout(function(){el.textContent='FAIL: 20秒无回推';},20000);
  window.__selfcb=function(raw){clearTimeout(window.__selfto);
    try{var o=JSON.parse(raw); el.textContent=(o.ok==='true'||o.ok===true)?'OK 通道B通':'异常';}
    catch(e){el.textContent='回推解析失败';}};
  window.webview.post('ping','{}');
};
document.getElementById('b5').onclick=function(){
  var el=document.getElementById('r5'); el.textContent='加载Babel…';
  function run(){
    try{
      var out=Babel.transform('const x=(obj?.val) ?? ""; const s=x.replaceAll("&",";");', {presets:[['env',{targets:{chrome:'55'}}]]}).code;
      var ok=out.indexOf('?.')<0 && out.indexOf('??')<0 && out.indexOf('replaceAll')<0;
      el.textContent=ok?'OK 新语法已降级':'FAIL 输出仍含新语法';
    }catch(e){el.textContent='FAIL: '+e.message;}
  }
  if(window.Babel){run();return;}
  var s=document.createElement('script'); s.src='/_runtime/babel.min.js';
  s.onload=run; s.onerror=function(){el.textContent='FAIL: babel加载失败';};
  document.head.appendChild(s);
};
document.getElementById('env').textContent=JSON.stringify({
  ua:navigator.userAgent.slice(0,80),
  bridge:typeof window.AstrBotPluginView,
  page:typeof window.AstrBotPluginPage,
  webview:typeof window.webview,
  fetch:typeof window.fetch,
  href:location.href
},null,1);
</script></body></html>"""

    private fun loadManifest(root: File): Map<String, String> = try {
        WebConverter.loadManifest(root)
    } catch (_: Exception) {
        emptyMap()
    }

    private fun mimeFor(ext: String): String = when (ext) {
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "txt", "md" -> "text/plain; charset=utf-8"
        "xml" -> "application/xml"
        else -> "application/octet-stream"
    }

    private fun writeText(out: OutputStream, mime: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
    }

    private fun writeJson(out: OutputStream, json: String) {
        writeText(out, "application/json; charset=utf-8", json)
    }
}

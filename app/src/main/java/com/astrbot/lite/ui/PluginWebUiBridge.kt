package com.astrbot.lite.ui

/**
 * 插件页面 bridge 桩（1.7.92 HTTP 版）：
 * - `window.AstrBotPluginView/Page` 本地就绪（ready/context/t/onContext 官方契约）；
 * - apiGet/apiPost/upload 走**同源 fetch** `/{pid}/__api/{endpoint}` → 本地 HTTP 服务 →
 *   Python 插件注册的 Web API（不再依赖 addJavascriptInterface——真机反复失效的旧桥已弃用）；
 * - 返回值按官方规则解包（{status:"ok",data}→data、error→reject、普通 JSON 原样 resolve）；
 * - download/SSE 本地版明确拒绝（文案诚实）。
 * pid 取自页面自身 URL 首段（静态页 URL 形如 /{pid}/views/xxx/index.html）。
 * 全 ES5 写法（机顶盒老 WebView 兼容）。
 */
object PluginWebUiBridge {

    const val JS = """(function(){
  if (window.AstrBotPluginView) return;
  var ctx = {pluginName:"", displayName:"插件视图", pageName:"", pageTitle:"插件视图", locale:"zh-CN", i18n:{}, isDark:false};
  var PID = (location.pathname.split("/")[1] || "");
  function toB64(buf){
    var bytes = new Uint8Array(buf), bin = "", chunk = 0x8000;
    for (var i = 0; i < bytes.length; i += chunk) {
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
    }
    try { return btoa(bin); } catch (e) { throw new Error("文件编码失败"); }
  }
  function apiBase(){ return "/__api/" + encodeURIComponent(PID) + "/"; }
  function unwrap(res){
    if (!res || (res.ok !== true && res.ok !== "true")) {
      throw new Error((res && res.error) || "插件接口调用失败");
    }
    var p = res.payload;
    if (p && typeof p === "object") {
      if (p.status === "error") { throw new Error(p.message || "插件接口错误"); }
      if (p.status === "ok" && ("data" in p)) { return p.data; }
    }
    return p;
  }
  // ---- 通道A（主）：同源 fetch → 本地 HTTP 服务 ----
  function viaFetch(method, endpoint, params, bodyInit){
    var url = apiBase() + String(endpoint || "").replace(/^\/+/, "");
    if (params) {
      var qs = [];
      for (var k in params) {
        if (Object.prototype.hasOwnProperty.call(params, k)) {
          var v = params[k];
          if (v !== undefined && v !== null) qs.push(encodeURIComponent(k) + "=" + encodeURIComponent(v));
        }
      }
      if (qs.length) url += (url.indexOf("?") >= 0 ? "&" : "?") + qs.join("&");
    }
    var opt = { method: method, headers: { "Content-Type": "application/json" } };
    if (bodyInit !== undefined && bodyInit !== null) opt.body = bodyInit;
    return fetch(url, opt).then(function(resp){
      return resp.text().then(function(txt){
        var res = null;
        try { res = JSON.parse(txt); } catch (e) { res = null; }
        if (!res) { throw new Error("本地服务返回无法解析（HTTP " + resp.status + "）"); }
        return unwrap(res);
      });
    });
  }
  // ---- 通道B（备，参考 webprogress 回调制）：window.webview.post void 调用 + Native 回推 ----
  var seq = 0;
  var pending = {};
  window.__djcb = function(id, raw){
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    try { p.resolve(unwrap(JSON.parse(raw))); } catch (e) { p.reject(e); }
  };
  function viaJsBridge(method, endpoint, params, bodyText){
    return new Promise(function(resolve, reject){
      if (!window.webview || typeof window.webview.post !== "function") {
        reject(new Error("JS桥未注入（window.webview 缺失）"));
        return;
      }
      var id = ++seq;
      pending[id] = { resolve: resolve, reject: reject };
      var payload = {
        callId: id, pid: PID, method: method,
        endpoint: String(endpoint || "").replace(/^\/+/, ""),
        query: params || {}, body: bodyText || ""
      };
      try { window.webview.post("api", JSON.stringify(payload)); }
      catch (e) { delete pending[id]; reject(e); return; }
      setTimeout(function(){
        if (pending[id]) { delete pending[id]; reject(new Error("JS桥超时（20 秒无回推）")); }
      }, 20000);
    });
  }
  // ---- 统一入口：A 失败自动降级 B（任一通道可用即可交互）----
  function request(method, endpoint, params, bodyInit){
    var bodyText = (bodyInit === undefined || bodyInit === null) ? null : bodyInit;
    return viaFetch(method, endpoint, params, bodyText)["catch"](function(errA){
      if (typeof console !== "undefined" && console.warn) {
        console.warn("[bridge] HTTP通道失败，降级JS桥:", errA && errA.message);
      }
      return viaJsBridge(method, endpoint, params, bodyText);
    });
  }
  var bridge = {
    ready: function(){ return Promise.resolve(ctx); },
    getContext: function(){ return ctx; },
    getLocale: function(){ return ctx.locale || "zh-CN"; },
    getI18n: function(){ return ctx.i18n || {}; },
    t: function(k, f){ return f || k; },
    onContext: function(handler){
      if (typeof handler === "function") { try { handler(ctx); } catch (e) {} }
      return function(){};
    },
    __setInitialContext: function(c){
      if (c && typeof c === "object") { for (var k in c) { if (Object.prototype.hasOwnProperty.call(c, k)) ctx[k] = c[k]; } }
    },
    apiGet: function(endpoint, params){ return request("GET", endpoint, params, null); },
    apiPost: function(endpoint, body){
      // FormData 直传（部分插件标准写法）→ 归一化为 __upload JSON（复用单文件+字段形态）
      if (typeof FormData !== "undefined" && body instanceof FormData) {
        var fields = {}, fileObj = null;
        body.forEach(function(v, k){
          if (v && typeof v === "object" && "name" in v && "size" in v) {
            if (!fileObj) fileObj = { file: v };
            else fields[k] = v.name;
          } else { fields[k] = v; }
        });
        if (fileObj) return uploadAs(fileObj.file, endpoint, fields);
        return request("POST", endpoint, null, JSON.stringify(fields));
      }
      return request("POST", endpoint, null, JSON.stringify(body === undefined ? {} : body));
    },
    upload: function(endpoint, file){
      return uploadAs(file, endpoint, {});
    },
    download: function(){ return Promise.reject(new Error("本地版暂不支持文件下载（bridge.download）")); },
    subscribeSSE: function(){ return Promise.reject(new Error("本地版暂不支持 SSE（bridge.subscribeSSE）")); },
    unsubscribeSSE: function(){ return Promise.resolve("ok"); }
  };
  // 文件 → ArrayBuffer：File.arrayBuffer 是 Chrome 76+，设备 WebView 70 用 FileReader 兜底
  function fileToBuf(file){
    if (file && typeof file.arrayBuffer === "function") return file.arrayBuffer();
    return new Promise(function(resolve, reject){
      var fr = new FileReader();
      fr.onload = function(){ resolve(fr.result); };
      fr.onerror = function(){ reject(fr.error || new Error("读取文件失败")); };
      fr.readAsArrayBuffer(file);
    });
  }
  function uploadAs(file, endpoint, fields){
    if (!file) return Promise.reject(new Error("Missing uploaded file payload."));
    return fileToBuf(file).then(function(buf){
      var payload = {"__upload": {
        filename: file.name || "upload.bin",
        content_type: file.type || "application/octet-stream",
        data_b64: toB64(buf)
      }};
      if (fields && Object.keys(fields).length) payload.fields = fields;
      return request("POST", endpoint, null, JSON.stringify(payload));
    });
  }
  window.AstrBotPluginView = bridge;
  window.AstrBotPluginPage = bridge;
})();"""

    /**
     * 现代 JS 转译加载器（1.7.98 根因修复）：
     * 真机实证 `app.js:103` 的 `??`、34 处 `?.`、`replaceAll` 等 ES2020+ 语法在老 WebView
     * （Chromium<80）直接 SyntaxError → module 崩溃 → 按钮 handler 全没绑（"有动效无响应"）。
     * 方案：HTML 注入时把 `type="module"` 降级为 text/plain（浏览器不执行）→ 本加载器接管：
     * 拉依赖图 → Babel(env→chrome55) 统一降级转译 → 拓扑执行（babel 失败才回退原生 module）。
     * chrome55 目标：`?./??/replaceAll` 被转译、async/await 保持原生（避免 regenerator 依赖）。
     */
    const val MOD_LOADER_JS = """(function(){
  if (window.__modLoaderOn) return;
  window.__modLoaderOn = true;
  // ---- ES2017+ API polyfill（1.7.103 实证：WebView 70 无 Promise.allSettled → 首屏加载中断；
  // Babel preset-env 只转语法不补 API）----
  if (!Promise.allSettled) {
    Promise.allSettled = function(arr){
      return Promise.all(Array.prototype.map.call(arr, function(p){
        return Promise.resolve(p).then(
          function(v){ return {status:"fulfilled", value:v}; },
          function(e){ return {status:"rejected", reason:e}; }
        );
      }));
    };
  }
  if (!Object.fromEntries) {
    Object.fromEntries = function(iter){
      var o = {};
      Array.prototype.forEach.call(iter, function(kv){ o[kv[0]] = kv[1]; });
      return o;
    };
  }
  if (!String.prototype.matchAll) {
    String.prototype.matchAll = function(re){
      var g = new RegExp(re.source, (re.flags || "") + "g");
      var out = [], m;
      while ((m = g.exec(this)) !== null) out.push(m);
      return out;
    };
  }
  if (!String.prototype.replaceAll) {
    // 1.7.105 实证：WebView 70 无 replaceAll（Chrome 85+）——escapeHtml 一用就崩渲染
    String.prototype.replaceAll = function(search, replacement){
      return this.split(search).join(replacement);
    };
  }
  if (typeof globalThis === "undefined") { window.globalThis = window; }
  // ---- 转译结果缓存（1.7.111 卡顿根修：app.js 2MB Babel 需 18 秒 → 命中缓存毫秒级）----
  function cacheKey(u){ return "__tc__" + u; }
  function loadCached(u, srcLen){
    try {
      var raw = localStorage.getItem(cacheKey(u));
      if (!raw) return null;
      var o = JSON.parse(raw);
      if (o && o.s === srcLen && typeof o.c === "string" && o.c.length) return o.c;
    } catch(e) {}
    return null;
  }
  function saveCached(u, srcLen, code){
    try {
      localStorage.setItem(cacheKey(u), JSON.stringify({s: srcLen, c: code}));
    } catch(e) {
      // 配额溢出：清本键重试（单文件 >5MB 则放弃缓存，下次仍现场转译）
      try { localStorage.removeItem(cacheKey(u)); } catch(_) {}
      try { localStorage.setItem(cacheKey(u), JSON.stringify({s: srcLen, c: code})); } catch(_) {}
    }
  }
  function resolve(rel, base){ return new URL(rel, base).href; }
  function loadBabel(){
    if (window.Babel) {
      console.log("[mod-loader] Babel 已就绪 version=" + (Babel.version || "unknown"));
      return Promise.resolve();
    }
    return new Promise(function(res, rej){
      var s = document.createElement("script");
      // 1.7.101：URL 带版本参数——旧版 791KB Babel6 曾被 WebView 磁盘缓存（max-age 未过期直接命中），
      // 版本化 URL 让缓存键失效；配合服务端 no-store 双保险
      s.src = "/_runtime/babel.min.js?v=7264";
      s.onload = function(){
        if (window.Babel) { console.log("[mod-loader] Babel 已加载 version=" + (Babel.version || "unknown")); res(); }
        else rej(new Error("Babel 未就绪"));
      };
      s.onerror = function(){ rej(new Error("babel.min.js 加载失败")); };
      document.head.appendChild(s);
    });
  }
  function loadGraph(entry){
    var files = {};
    var queue = [entry];
    return new Promise(function(res, rej){
      function step(){
        if (!queue.length){ res(files); return; }
        var u = queue.shift();
        if (files[u]) { step(); return; }
        fetch(u).then(function(r){ if(!r.ok) throw new Error("HTTP " + r.status + " " + u); return r.text(); })
          .then(function(code){
            files[u] = code;
            var re = /(?:import|export)\s+(?:[\w*{},\s]+?\s+from\s+)?["'](\.[^"']+)["']|import\s*\(\s*["'](\.[^"']+)["']\s*\)/g;
            var m;
            while ((m = re.exec(code))) {
              var dep = m[1] || m[2];
              if (dep) {
                var abs = resolve(dep, u);
                if (!files[abs] && queue.indexOf(abs) < 0) queue.push(abs);
              }
            }
            step();
          })["catch"](rej);
      }
      step();
    });
  }
  function transpileExec(files, entry, t0){
    var mods = {};
    var order = [];
    var visited = {};
    function visit(u){
      if (visited[u]) return;
      visited[u] = true;
      var out;
      // 转译缓存命中（1.7.111）：跳过该文件的 Babel
      var hit = loadCached(u, files[u].length);
      if (hit) {
        out = hit;
        console.log("[mod-loader] 命中转译缓存 " + u.split("/").pop());
      } else {
        out = Babel.transform(files[u], {
          presets: [["env", { modules: "commonjs", targets: { chrome: "55" } }]],
          sourceType: "module", filename: u
        }).code;
        saveCached(u, files[u].length, out);
        console.log("[mod-loader] 转译 " + u.split("/").pop() + ": " + files[u].length + "→" + out.length + " 字节");
      }
      var deps = [];
      var re = /require\((['"])(\.[^'"]+)\1\)/g, m;
      while ((m = re.exec(out))) {
        var abs = resolve(m[2], u);
        if (files[abs]) deps.push(abs);
      }
      mods[u] = { code: out, deps: deps };
      deps.forEach(visit);
      order.push(u);
    }
    visit(entry);
    var cache = {};
    function requireOf(base){
      return function(rel){
        var abs = resolve(rel, base);
        var m = cache[abs];
        if (!m) throw new Error("依赖未预执行: " + rel);
        return m.exports;
      };
    }
    for (var i = 0; i < order.length; i++){
      var u = order[i];
      var mod = mods[u];
      var module = { exports: {} };
      cache[u] = module;
      (new Function("module", "exports", "require", mod.code))(module, module.exports, requireOf(u));
    }
    console.log("[mod-loader] 转译执行完成 " + order.length + " 模块，耗时 " + (Date.now() - (t0 || Date.now())) + "ms（ES新语法已降级）");
  }
  function hasNewSyntax(code){
    // 老 WebView(70) 必崩的现代语法特征（宁多勿漏；漏检=回退原生也崩，不比现状差）
    return /\?\?|\?\.|\.replaceAll\s*\(|Promise\.allSettled|Object\.fromEntries|[^.\w]globalThis/.test(code);
  }
  function runAsScript(code, entry){
    // blob script 执行（最接近原生 <script src> 语义：独立作用域、全局 var 可见）
    return new Promise(function(resolve, reject){
      var url;
      try { url = URL.createObjectURL(new Blob([code], {type: "application/javascript"})); }
      catch (e) { reject(e); return; }
      var s = document.createElement("script");
      s.src = url;
      s.onload = function(){ try { URL.revokeObjectURL(url); } catch(_){} resolve(); };
      s.onerror = function(){ reject(new Error("blob 脚本执行失败: " + entry)); };
      (document.head || document.documentElement).appendChild(s);
    });
  }
  function processOne(tag){
    var entry = tag.src;
    if (!entry) return Promise.resolve();
    var t0 = Date.now();
    if (tag.hasAttribute("data-mod")) {
      // module：依赖图 + CJS 转译 + 拓扑执行（原有链路）
      return loadGraph(entry).then(function(files){
        var n = Object.keys(files).length, bytes = 0;
        Object.keys(files).forEach(function(u){ bytes += files[u].length; });
        console.log("[mod-loader] 依赖图就绪: " + n + " 文件 " + bytes + " 字节，准备转译…");
        return loadBabel().then(function(){ transpileExec(files, entry, t0); });
      });
    }
    // classic（1.7.108 新增）：单文件 fetch → 语法检测 → 按需转译 → blob 顺序执行
    return fetch(entry).then(function(r){
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.text();
    }).then(function(code){
      // 转译缓存命中（1.7.111）：源大小一致直接执行缓存产物，跳过 18 秒 Babel
      var hit = loadCached(entry, code.length);
      if (hit) {
        console.log("[mod-loader] 命中转译缓存 " + entry.split("/").pop() + "（源 " + code.length + "B）→ 直接执行");
        return runAsScript(hit, entry);
      }
      if (!hasNewSyntax(code)) {
        console.log("[mod-loader] classic 原样执行 " + entry.split("/").pop() + "（" + code.length + "B）");
        return runAsScript(code, entry);
      }
      console.log("[mod-loader] classic 检出新语法，转译 " + entry.split("/").pop() + "（" + code.length + "B）…");
      return loadBabel().then(function(){
        var out = Babel.transform(code, {
          presets: [["env", { modules: false, targets: { chrome: "55" } }]],
          sourceType: "script", filename: entry
        }).code;
        saveCached(entry, code.length, out);
        console.log("[mod-loader] 转译完成并已缓存 " + code.length + "→" + out.length + "B，耗时 " + (Date.now() - t0) + "ms");
        return runAsScript(out, entry);
      });
    });
  }
  function boot(){
    // 接管范围（1.7.108）：module(data-mod) + classic 外部脚本(data-lazy)。
    // companion 实证：app.js 是 classic 2.3MB 现代语法，旧版只接管 module → classic 直接
    // SyntaxError → 初始化中断 → 页面显示"加载失败：{error}"。
    var tags = document.querySelectorAll('script[type="text/plain"][data-mod], script[type="text/plain"][data-lazy]');
    if (!tags.length) { console.log("[mod-loader] 无需接管的脚本"); return; }
    console.log("[mod-loader] 接管 " + tags.length + " 个脚本（按 HTML 顺序串行执行）…");
    var seq = Promise.resolve();
    for (var i = 0; i < tags.length; i++){
      (function(tag){
        seq = seq.then(function(){ return processOne(tag); })["catch"](function(e){
          console.error("[mod-loader] 脚本失败 " + String(tag.src || "").split("/").pop() + ": " + e.message);
        });
      })(tags[i]);
    }
    seq.then(function(){ console.log("[mod-loader] 全部脚本处理完毕"); });
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();"""

    /**
     * HTML 注入（每页统一，全部插件受益）：
     * ① 清除触摸焦点橙环（浏览器默认 :focus outline）；
     * ② **sticky hover 根治**（1.7.100 用户实证"点击后卡在鼠标停留样式"——触摸屏 Chrome 的
     *    :hover 滞留，插件 CSS 有 20+ 条 :hover 且无 @media (hover:none) 处理）：
     *    触摸结束后合成一次负坐标 mousemove，把 hover 从被点元素迁走；
     * ③ module 标签降级 ④ 转译 loader ⑤ bridge 桩（head 前置）。
     */
    fun inject(html: String): String {
        // ① type=module → data-mod（依赖图转译链路）
        var processed = html.replace(
            Regex("""type\s*=\s*["']module["']""", RegexOption.IGNORE_CASE),
            """type="text/plain" data-mod="1"""",
        )
        // ② 无 type 且带 src 的 classic <script> → data-lazy（顺序接管：检测新语法按需转译）
        //    （companion 实证：app.js 是 classic、2.3MB 现代语法——只接管 module 会漏掉它）
        processed = processed.replace(
            Regex("""<script(?![^>]*\stype\s*=)[^>]*\ssrc\s*=\s*["'][^"']+["'][^>]*>""", RegexOption.IGNORE_CASE),
        ) { m ->
            m.value.replaceFirst("<script", """<script type="text/plain" data-lazy="1"""")
        }
        // ③ viewport 统一改写（1.7.111 "必须适配屏幕不可超出"）：
        //    无论原 content 是什么（device-width/initial-scale 等），一律改写为
        //    `width=1280, initial-scale=1` 桌面布局基准——配合 useWideViewPort+loadWithOverviewMode，
        //    WebView 按 1280 宽渲染后整体缩放至屏宽，横竖屏均完整可见、不横向溢出。
        processed = processed.replace(
            Regex("""(<meta[^>]*name=["']viewport["'][^>]*content=["'])[^"']*""", RegexOption.IGNORE_CASE),
            "$1width=1280, initial-scale=1",
        )
        val noModule = processed
        val style = "<style>*:focus{outline:none!important;-webkit-tap-highlight-color:transparent;}" +
            "button:focus,a:focus,[tabindex]:focus{outline:none!important;}" +
            // TV/DPad 焦点样式（1.7.111 焦点方案）：仅遥控器导航时由 JS 加 __dpad_focus 类——
            // 触摸设备永不出现（保持之前的触摸零残留），遥控器有醒目橙色焦点框
            ".__dpad_focus{outline:3px solid #FF9800 !important;outline-offset:1px;position:relative;z-index:9999;}</style>"
        val dpadNav = """<script>(function(){
  // DPad/遥控器网页导航（1.7.111 焦点方案）：首个遥控器按键后进入焦点模式——
  // 方向键在可交互元素间移动橙色焦点、确认键触发点击；触摸操作始终不显示焦点环。
  var mode = false, cur = null;
  function list(){
    var els = document.querySelectorAll('button, a[href], input, select, textarea, [tabindex]:not([tabindex="-1"])');
    return Array.prototype.filter.call(els, function(e){
      return !e.disabled && e.offsetParent !== null;
    });
  }
  function mark(el){
    if (cur) cur.classList.remove("__dpad_focus");
    cur = el;
    if (cur) {
      cur.classList.add("__dpad_focus");
      try { cur.focus({preventScroll:true}); } catch(e) { try{ cur.focus(); }catch(_){} }
      if (cur.scrollIntoView) { try { cur.scrollIntoView({block:"nearest"}); } catch(_){} }
    }
  }
  function move(dir){
    var items = list();
    if (!items.length) { return; }
    if (!cur || items.indexOf(cur) < 0) { mark(items[0]); return; }
    var r = cur.getBoundingClientRect();
    var best = null, bestD = Infinity;
    for (var i = 0; i < items.length; i++) {
      var e = items[i]; if (e === cur) continue;
      var b = e.getBoundingClientRect();
      var dx = (b.left + b.width/2) - (r.left + r.width/2);
      var dy = (b.top + b.height/2) - (r.top + r.height/2);
      var ok = (dir==="up" && dy < -4) || (dir==="down" && dy > 4) || (dir==="left" && dx < -4) || (dir==="right" && dx > 4);
      if (!ok) continue;
      var d = (dir==="up"||dir==="down") ? Math.abs(dy) + Math.abs(dx)*0.45 : Math.abs(dx) + Math.abs(dy)*0.45;
      if (d < bestD) { bestD = d; best = e; }
    }
    if (best) mark(best);
  }
  document.addEventListener("keydown", function(ev){
    var k = ev.keyCode;
    var dir = k===38?"up":k===40?"down":k===37?"left":k===39?"right":null;
    if (dir) { mode = true; ev.preventDefault(); move(dir); return; }
    // 确认键：DPAD_CENTER(23)/ENTER(13)/NUMPAD_ENTER(144)/SPACE(32)/BUTTON_A(96)
    if (!mode) return;
    if (k===23||k===13||k===144||k===32||k===96) {
      if (cur) { ev.preventDefault(); try { cur.click(); } catch(e) {} }
    }
  }, true);
})();"""
        val touchFix = """<script>(function(){
  // 触摸设备 sticky :hover 根治：touchend 后把 hover 迁到 body，被点元素的"鼠标停留样式"立即失效
  if (!("ontouchstart" in window)) return;
  document.addEventListener("touchend", function(){
    setTimeout(function(){
      try {
        var el = document.body;
        el.dispatchEvent(new MouseEvent("mousemove", {bubbles: true, clientX: -8, clientY: -8}));
        el.dispatchEvent(new MouseEvent("mouseover", {bubbles: true, clientX: -8, clientY: -8}));
      } catch (e) {}
    }, 80);
  }, {passive: true});
})();</script>"""
        // Pre-inject JS-side console.log throttling (page-level, survives refresh): same message once per 2 seconds
        val consoleThrottleJS = """<script>(function(){
      if (window.__consoleThrottled) return;
      window.__consoleThrottled = true;
      var _log = console.log, _warn = console.warn, _err = console.error;
      var _map = {};
      function _throttle(fn, level){
        return function(){
          var msg = Array.prototype.slice.call(arguments).join(' ');
          var now = Date.now();
          var k = level + '|' + msg;
          if (!_map[k] || now - _map[k] >= 2000) { _map[k] = now; return fn.apply(console, arguments); }
        };
      }
      console.log = _throttle(_log, 'LOG');
      console.warn = _throttle(_warn, 'WARN');
      console.error = _throttle(_err, 'ERROR');
    })();</script>"""
        val tag = style + touchFix + dpadNav + consoleThrottleJS +
            "<script>" + MOD_LOADER_JS + "</script><script>" + JS + "</script>"
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
        return if (head.containsMatchIn(noModule)) {
            // quoteReplacement：脚本内的 $、\ 按字面处理（否则 $1/$0 引用会破坏脚本）
            head.replaceFirst(noModule, java.util.regex.Matcher.quoteReplacement(tag))
        } else {
            tag + noModule
        }
    }
}

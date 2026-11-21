package com.astrbot.lite.core

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.PrintStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock

/**
 * AstrBot 市场 Python 插件的统一运行环境（Chaquopy，v15 API）。
 *
 * 设计（对应"给一个环境"的诉求）：
 * - 全部 AstrBot 市场 Python 插件共享**同一个常驻 CPython 解释器**（APK 内置）+ 共享 site-packages；
 * - 每个插件解压到 filesDir/apy/{id}/（保留原始 main.py / webui 资源，不转换）；
 * - 垫片 astralite_shim 导入各插件注册命令/hook，每条消息由垫片分发；
 * - 带 webui 的插件**不启动 http 服务**，App 端单独入口用 WebView 直读其本地资源（[pythonPluginWebRoot]）；
 * - 插件要求更高 AstrBot/astralcore 版本 → 垫片提供最小 API 面，降级为"命令可用、webui 不启动"。
 *
 * 线程模型：Chaquopy 解释器线程安全（GIL 在 native 层），首次 init 创建，之后各线程直接调用。
 */
object PythonRuntime {

    private var ctxRef: Context? = null
    private lateinit var apyDir: File
    private val ready = AtomicBoolean(false)
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /** App 启动时调一次（惰性创建解释器；失败仅记录，不阻断启动） */
    fun init(context: Context) {
        if (ready.get()) return
        val ctx = context.applicationContext
        ctxRef = ctx
        apyDir = File(ctx.filesDir, "apy").apply { mkdirs() }
        // 释放垫片
        val shimDir = File(apyDir, "_shim").apply { mkdirs() }
        try {
            ctx.assets.open("apy/astralite_shim.py").bufferedReader().use { src ->
                File(shimDir, "astralite_shim.py").writeText(src.readText())
            }
        } catch (e: Exception) {
            Logs.err("释放 Python 垫片失败: ${e.message}")
        }
        // 启动解释器（后台线程，避免阻塞主线程）
        Thread {
            lock.withLock {
                if (ready.get()) return@Thread
                try {
                    if (!Python.isStarted()) {
                        Python.start(AndroidPlatform(ctx))
                    }
                    val py = Python.getInstance()
                    // 把垫片目录加进 sys.path，让 astralite_shim 可被 import
                    val shimDir = File(apyDir, "_shim")
                    if (shimDir.exists()) {
                        py.getModule("sys")?.get("path")?.callAttr("insert", 0, shimDir.absolutePath)
                    }
                    ready.set(true)
                    Logs.info("Python 统一环境就绪")
                } catch (e: Exception) {
                    Logs.err("Python 环境初始化失败: ${e.message}")
                }
            }
        }.start()
    }

    /** 环境是否就绪（UI 展示用） */
    fun isReady(): Boolean = ready.get()

    /**
     * 确保 Python 统一环境的 asyncio loop 处于可用状态（1.7.222 补丁 3）。
     * Android 后台冻结后，Chaquopy 的 loop 线程可能被挂起/冻结，
     * 导致后续 call_web_api / dispatch 调用时 loop 无响应或抛 "no current event loop"。
     * 此方法尝试唤醒/重建 loop，供 WebUI 健康检查失败时调用。
     */
    fun ensureShimLoop(): Boolean {
        if (!ready.get()) return false
        return try {
            val py = Python.getInstance()
            // 尝试在 Python 侧执行一个简单的协程任务，验证 loop 可用
            val fn = py.getModule("astralite_shim")?.get("ensure_loop_alive")
            if (fn == null) {
                Logs.warn("[PythonRuntime] 垫片缺 ensure_loop_alive，尝试直接运行简单协程")
                // 回退：直接运行一个简单协程测试 loop
                val loopTest = py.getModule("asyncio")?.get("get_event_loop")?.call()?.get("run_until_complete")
                if (loopTest != null) {
                    val sleepFn = py.getModule("asyncio")?.get("sleep")
                    if (sleepFn != null) {
                        val sleepTask = sleepFn.call(0.01)
                        if (sleepTask != null) loopTest.call(sleepTask)
                    }
                    return true
                }
                return false
            }
            val result = fn.call()?.toString()
            "true" == result
        } catch (e: Exception) {
            Logs.warn("[PythonRuntime] ensureShimLoop 失败: ${e.message}")
            false
        }
    }

    // ---------------- 插件安装 ----------------

    /** 把 AstrBot 插件 zip（原始）装进统一 Python 目录。返回是否成功。
     *  onEntry(已解压条目, 总条目[-1 未知]) 供安装进度显示（1.7.87） */
    fun installZip(id: String, bytes: ByteArray, onEntry: ((Int, Int) -> Unit)? = null, shouldCancel: () -> Boolean = { false }): Boolean {
        val dir = File(apyDir, id).apply {
            deleteRecursively()
            mkdirs()
        }
        return try {
            // 先数条目总数（zip 目录可随机访问；失败则总进度用 -1 表示未知）
            val total = try {
                java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                    var c = 0
                    while (z.nextEntry != null) c++
                    c
                }
            } catch (_: Exception) {
                -1
            }
            val zis = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes))
            var e = zis.nextEntry
            val base = dir.canonicalPath
            var count = 0
            while (e != null) {
                if (shouldCancel()) {
                    try { zis.close() } catch (_: Exception) {}
                    return false
                }
                if (!e.isDirectory) {
                    val f = File(dir, e.name)
                    val target = f.canonicalPath
                    if (target == base || target.startsWith(base + File.separator)) {
                        f.parentFile?.mkdirs()
                        f.writeBytes(zis.readBytes())
                    }
                }
                count++
                onEntry?.invoke(count, total)
                zis.closeEntry()
                e = zis.nextEntry
            }
            true
        } catch (ex: Exception) {
            Logs.err("Python 插件 ${id} 解压失败: ${ex.message}")
            false
        }
    }

    fun uninstall(id: String) {
        File(apyDir, id).deleteRecursively()
    }

    // ---------------- 插件 id → 安装目录别名 ----------------
    // 根因（1.7.81 实测日志）：转换器生成的 id（如 localimage_plugin 回退值）≠ installZip 的目录名
    // （entry.name，如 astrbot_plugin_get_px）→ webui 探测与垫片分发都在找不存在的目录。
    @Volatile
    private var aliases: Map<String, String>? = null

    private fun aliasMap(): Map<String, String> {
        aliases?.let { return it }
        synchronized(this) {
            aliases?.let { return it }
            val f = File(apyDir, "_aliases.json")
            val m = if (f.isFile) {
                try {
                    val o = JSONObject(f.readText())
                    val out = mutableMapOf<String, String>()
                    for (k in o.keys()) out[k] = o.optString(k)
                    out
                } catch (_: Exception) {
                    emptyMap()
                }
            } else emptyMap()
            aliases = m
            return m
        }
    }

    /** 登记 插件id → 安装目录名（安装时调用；同目录可被多个 id 引用） */
    fun putAlias(pluginId: String, dirName: String) {
        if (pluginId.isBlank() || dirName.isBlank() || pluginId == dirName) return
        val m = aliasMap().toMutableMap()
        if (m[pluginId] == dirName) return
        m[pluginId] = dirName
        try {
            File(apyDir, "_aliases.json").writeText(JSONObject(m as Map<*, *>).toString())
            aliases = m
            Logs.info("[WebUI] 登记插件目录别名：$pluginId → $dirName")
        } catch (e: Exception) {
            Logs.warn("[WebUI] 别名登记失败: ${e.message}")
        }
    }

    private fun aliasDir(id: String): String = aliasMap()[id] ?: id

    /** 插件 webui 资源目录（不启动 http 服务，App 内 WebView 直读）；无 webui 返回 null */
    fun pythonPluginWebRoot(id: String): File? {
        // 对齐 AstrBot 官方规范（docs/zh/dev/star/guides/plugin-pages.md）：
        // 只扫描 views/（兼容旧 pages/）下每个一级子目录的 index.html；webui/ui/web 为历史布局兼容
        val names = listOf("views", "pages", "webui", "ui", "web")
        fun resolve(base: File): File? {
            val dir = names.firstOrNull { File(base, it).isDirectory }?.let { File(base, it) } ?: return null
            if (File(dir, "index.html").isFile) return dir
            val subs = dir.listFiles()?.filter {
                it.isDirectory && File(it, "index.html").isFile && !it.name.startsWith(".")
            }.orEmpty()
            if (subs.size == 1) return subs[0]
            if (subs.size > 1) return dir // 多视图：返回根，由界面弹“选择视图”
            return null // 无可加载视图 → 不出按钮（诚实）
        }
        // 先按别名解析真实安装目录（id 可能是转换器回退值，与目录名不一致）
        val d = File(apyDir, aliasDir(id))
        // ① 标准布局：apy/{id}/webui 等
        resolve(d)?.let { return it }
        // ② zip 带顶层目录（与垫片入口同样的回退）：apy/{id}/插件目录/webui 等
        val children = d.listFiles() ?: run {
            // 诊断（1.7.81）：目录整个不存在 → 多为“未装进 Python 环境 / id 与目录名不一致”
            if (!d.exists()) Logs.warn("[WebUI] $id 的 Python 目录不存在（apy/$id）：走③按 metadata 匹配，仍失败则需用「AstrBot 市场→安装到 Python 环境」重装")
            null
        }
        if (children != null) {
            for (c in children.sortedBy { it.name }) {
                if (!c.isDirectory || c.name.startsWith(".")) continue
                resolve(c)?.let { return it }
            }
        }
        // ③ 安装目录名与插件 id 不一致：扫 apy/* 按 metadata.yaml 的 name 匹配（含一层顶层目录）
        val all = apyDir.listFiles() ?: return null
        for (c in all) {
            if (!c.isDirectory || c.name.equals(id, true) || c.name.startsWith("_")) continue
            val metaFiles = listOf(File(c, "metadata.yaml")) +
                (c.listFiles()?.filter { it.isDirectory }?.map { File(it, "metadata.yaml") } ?: emptyList())
            val matched = metaFiles.firstOrNull { it.isFile && it.readText().contains(id) }
            if (matched != null) {
                Logs.info("[WebUI] $id 按 metadata 匹配到目录：${c.name}")
                resolve(c)?.let { return it }
                // resolve 从 c 出发；若 web 在更深层（metadata 所在层）再试 metadata 的父目录
                resolve(matched.parentFile)?.let { return it }
            }
        }
        Logs.info("[WebUI] $id 未找到 web 资源（已探测 webui/ui/web/pages，根目录与一层子目录、metadata 匹配均无）")
        return null
    }

    // ---------------- 消息分发 ----------------

    /** 插件在统一 Python 环境的根目录（按别名解析；不存在返回 null）。Web 转换/探测用 */
    fun pluginDir(id: String): File? =
        File(apyDir, aliasDir(id)).takeIf { it.isDirectory }

    /** 判断某插件是否装进了 Python 环境（有目录即视为"原生 Python 插件"） */
    fun hasPlugin(id: String): Boolean = File(apyDir, aliasDir(id)).exists()

    /** 已记过日志的导入失败（去重：同一 pid+原因只打一次，避免刷屏） */
    private val importErrorLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 把一条聊天文本交给 Python 垫片分发：
     * 1) 逐个 import 各已装插件 main.py（垫片内缓存，只导一次）注册其命令/hook；
     * 2) 垫片把文本按注册表分发到命中插件的 handler，返回 JSON {ok, reply|error}。
     * 1.7.55：把插件页保存的配置（key=value）随分发注入，插件内 context.get_plugin_config 可读；
     *         垫片报回的 import_errors 记入日志（市场插件触发失败时可据此定位，不再静默）。
     * @return 回复文本；null 表示无命中/未就绪（交给其他处理链）
     */
    /** 分发结果：文本回复 + 可选的按钮行 JSON（QQ keyboard rows） */
    data class PyDispatch(val reply: String?, val buttons: String?)

    fun dispatch(pluginIds: List<String>, text: String, sessionKey: String): String? =
        dispatchEx(pluginIds, text, sessionKey)?.reply

    fun dispatchEx(pluginIds: List<String>, text: String, sessionKey: String): PyDispatch? {
        if (!ready.get() || pluginIds.isEmpty()) return null
        return try {
            val py = Python.getInstance()
            val shimMod = py.getModule("astralite_shim") ?: throw IllegalStateException("垫片未加载")
            val dispatchFn = shimMod.get("dispatch") ?: throw IllegalStateException("垫片缺 dispatch")
            // 分发前把插件 id 映射为真实安装目录名（别名），垫片才能找到 main.py
            val idsJson = JSONArray().apply {
                pluginIds.map { aliasDir(it) }.distinct().forEach { put(it) }
            }.toString()
            // 各插件的配置（plugin.json config → {key: value}），注入垫片 context.config
            val cfgJson = JSONObject()
            for (pid in pluginIds) {
                val m = PluginManager.configMap(pid)
                if (m.isNotEmpty()) cfgJson.put(pid, JSONObject(m))
            }
            val out = dispatchFn.call(idsJson, text, sessionKey, cfgJson.toString())
            val outStr = consumeLogs(out.toString())
            if (outStr.isBlank()) return null
            val o = JSONObject(outStr)
            // 垫片报回的导入失败：记日志（每个 pid+原因只记一次），便于定位“市场插件无法触发”
            o.optJSONObject("import_errors")?.let { ie ->
                for (k in ie.keys()) {
                    val msg = ie.optString(k).take(160)
                    if (msg.isNotBlank() && importErrorLogged.add("$k|$msg")) {
                        Logs.warn("[Python] 插件 $k 导入失败: $msg")
                    }
                }
            }
            val buttons = o.optJSONArray("buttons")?.toString()
            if (o.optString("ok") == "true") {
                val reply = o.optString("reply").takeIf { it.isNotEmpty() }
                if (reply != null || buttons != null) PyDispatch(reply, buttons) else null
            } else if (o.optString("error").isNotBlank()) {
                PyDispatch("插件执行失败：${o.optString("error").take(160)}", null)
            } else null
        } catch (e: Exception) {
            Logs.err("Python 插件分发异常: ${e.message}")
            PyDispatch("插件执行异常：${e.message?.take(120)}", null)
        }
    }

    /** 按钮点击回调分发：返回文本 + 新按钮，格式与 dispatchEx 一致 */
    fun handleButtonInteraction(pluginIds: List<String>, payloadJson: String): PyDispatch? {
        if (!ready.get() || pluginIds.isEmpty()) return null
        return try {
            val py = Python.getInstance()
            val shimMod = py.getModule("astralite_shim") ?: return null
            val fn = shimMod.get("handle_button_interaction") ?: return null
            val idsJson = JSONArray().apply {
                pluginIds.map { aliasDir(it) }.distinct().forEach { put(it) }
            }.toString()
            val out = fn.call(idsJson, payloadJson)
            val o = JSONObject(consumeLogs(out.toString()))
            o.optJSONObject("import_errors")?.let { ie ->
                for (k in ie.keys()) Logs.warn("[Python] 按钮回调插件 $k 导入失败: ${ie.optString(k).take(160)}")
            }
            val buttons = o.optJSONArray("buttons")?.toString()
            val reply = o.optString("reply").takeIf { it.isNotEmpty() }
            if (reply != null || buttons != null) PyDispatch(reply, buttons) else null
        } catch (e: Exception) {
            Logs.warn("[按钮] 回调分发异常: ${e.message}")
            null
        }
    }

    /**
     * 插件 Web API 调用（1.7.87：PluginWebUiActivity 的 JS 桥入口）。
     * 同步返回桥接线格式 JSON：{ok:"true",payload,status} / {ok:"false",error}。
     * 在独立线程执行 + 15s 超时，避免 JavaBridge 线程被慢插件接口拖死。
     *
     * 1.7.207 修复「过久了网页打不开 + 闪退」：
     * 旧版每次调用 `Thread { ... }.start()` 新建一条**非守护**线程；Python 侧一旦卡住
     * （插件网络无超时 / GIL 被占），超时只会让调用方返回错误，那条线程永远不退出。
     * 页面每轮询一次就漏一个线程 → 线程数随时长线性增长（实测 40 次超时 = 40 条遗留）
     * → 最终 pthread_create 失败，所有新请求都建不出线程（网页彻底打不开），
     * 随后进程被 OOM 杀死（闪退）。现改为**固定守护池 + 卡死熔断**：
     * 池大小固定，超时只记账不新增线程；累计卡死达上限即整体熔断，后续调用秒回错误，
     * 不再堆积线程，也不再让页面无限等待。
     */
    fun callWebApi(
        pluginIds: List<String>,
        method: String,
        path: String,
        queryJson: String,
        bodyJson: String,
        configsJson: String = "{}"
    ): String {
        fun err(msg: String) = """{"ok":"false","error":${JSONObject.quote(msg)}}"""
        if (!ready.get()) return err("Python 环境未就绪")
        if (pluginIds.isEmpty()) return err("未指定插件")
        // 熔断：桥接已被证明卡死过多次，别再往里塞任务（否则页面每个请求都要等满 15s）
        if (bridgeStuck.get() >= BRIDGE_MAX_STUCK) {
            return err("Python 桥接卡死已达上限（插件接口无响应），请到设置页重启应用")
        }
        return submitBridge(BRIDGE_TIMEOUT_SEC) {
            try {
                val py = Python.getInstance()
                val fn = py.getModule("astralite_shim")?.get("call_web_api")
                    ?: return@submitBridge err("垫片缺 call_web_api")
                // 1.7.89 修复：与 dispatch 同款别名映射——转换插件 id 常为 converted_plugin
                // 等兜底值 ≠ 安装目录名；不映射则垫片查不到目录 → 注册表空 → 页面按钮全 404
                val ids = JSONArray().apply { pluginIds.distinct().forEach { put(aliasDir(it)) } }.toString()
                val out = fn.call(ids, method, path, queryJson, bodyJson, configsJson)
                val outStr = consumeLogs(out?.toString() ?: err("插件接口空响应"))
                // 可观测性（1.7.90）：垫片报回的 import 失败必须落日志——真机"页面点了没反应"
                // 的根因（缺第三方包等）此前只藏在 JS Promise 里，用户与排查都看不到
                try {
                    val o = JSONObject(outStr)
                    o.optJSONObject("import_errors")?.let { ie ->
                        for (k in ie.keys()) Logs.warn("[Web桥] 插件 $k 导入失败: ${ie.optString(k).take(300)}")
                    }
                    if (o.optString("ok") != "true") {
                        Logs.warn("[Web桥] $method $path 失败: ${o.optString("error").take(200)}")
                    }
                } catch (_: Exception) {
                }
                outStr
            } catch (e: Exception) {
                err("插件接口异常：${e.message?.take(160) ?: "unknown"}")
            }
        } ?: err("插件接口超时（${BRIDGE_TIMEOUT_SEC} 秒）")
    }

    // ---------------- Python 桥接执行器（1.7.207） ----------------
    /** 桥接线程池大小：固定值，绝不随调用次数增长 */
    private const val BRIDGE_THREADS = 3
    /** 单次桥接超时（秒） */
    private const val BRIDGE_TIMEOUT_SEC = 15L
    /** LLM hook 超时（秒）：与旧版一致，失败静默保持原回复 */
    private const val HOOK_TIMEOUT_SEC = 10L
    /** 累计卡死次数上限：达到即熔断，避免页面每个请求都等满超时 */
    private const val BRIDGE_MAX_STUCK = 6

    private val bridgeStuck = java.util.concurrent.atomic.AtomicInteger(0)

    /** 固定守护线程池：卡住的任务只占住固定名额，不会越堆越多 */
    private val bridgePool: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(BRIDGE_THREADS) { r ->
            Thread(r, "py-bridge").apply { isDaemon = true }
        }

    /** 提交一次桥接调用并等待；超时/拒绝返回 null（调用方补错误文案），成功则清零卡死计数 */
    private fun <T> submitBridge(timeoutSec: Long, body: () -> T): T? {
        val future = try {
            bridgePool.submit(java.util.concurrent.Callable { body() })
        } catch (e: Exception) {
            Logs.err("[Py桥] 提交失败（池已关闭）: ${e.message}")
            return null
        }
        return try {
            val v = future.get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            if (bridgeStuck.get() > 0) {
                bridgeStuck.set(0)   // 一次成功即证明桥还活着，解除熔断
                Logs.info("[Py桥] 已恢复正常，卡死计数清零")
            }
            v
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            val n = bridgeStuck.incrementAndGet()
            Logs.err("[Py桥] 调用超时 ${timeoutSec}s（累计卡死 $n/$BRIDGE_MAX_STUCK，池=$BRIDGE_THREADS）")
            if (n >= BRIDGE_MAX_STUCK) {
                Logs.err("[Py桥] 已熔断：后续插件网页请求将立即返回错误，请重启应用恢复")
            }
            null
        } catch (e: Exception) {
            Logs.warn("[Py桥] 调用异常: ${e.message}")
            null
        }
    }

    /**
     * 1.7.105 日志桥：垫片/插件的 print 输出随响应回传的 "logs" 数组 → 落到 App 日志并从响应剥离。
     * 没有它，设备侧 [GetPx]/[lifecycle] 等插件日志只进 logcat，App 查看器全黑盒。
     */
    private fun consumeLogs(outStr: String): String {
        return try {
            val o = JSONObject(outStr)
            val logs = o.optJSONArray("logs") ?: return outStr
            for (i in 0 until logs.length()) {
                Logs.info("[Py] ${logs.optString(i)}")
            }
            o.remove("logs")
            o.toString()
        } catch (_: Exception) {
            outStr
        }
    }

    /**
     * 同步 Lite AI 池配置到垫片（1.7.88）：插件 context.llm_generate 据此直连
     * OpenAI 兼容接口。对话前调用一次，配置改动即时生效（幂等、轻量）。
     */
    fun syncAiBridge() {
        if (!ready.get()) return
        try {
            val cfg = AiPool.bridgeChatConfig().toString()
            Python.getInstance().getModule("astralite_shim")?.get("set_ai_bridge")?.call(cfg)
        } catch (e: Exception) {
            Logs.warn("[LLM桥] 注入 AI 配置失败: ${e.message}")
        }
    }

    /**
     * hook 通道（1.7.88）：对已装 Python 插件跑 kind（on_llm_request / on_llm_response）钩子。
     * payloadJson 形态见垫片 run_plugin_hooks；返回改写后的完整 JSON，失败/超时返回 null（调用方保持原样）。
     */
    fun runLlmHooks(kind: String, payloadJson: String): String? {
        if (!ready.get()) return null
        // 1.7.207：同款有界池，避免旧版“每次 hook 新建非守护线程”在长会话里累积
        if (bridgeStuck.get() >= BRIDGE_MAX_STUCK) return null
        return submitBridge(HOOK_TIMEOUT_SEC) {
            try {
                val fn = Python.getInstance().getModule("astralite_shim")?.get("run_plugin_hooks")
                    ?: return@submitBridge null
                fn.call(kind, payloadJson)?.toString()
            } catch (e: Exception) {
                null
            }
        }
    }

    /** 环境自检（UI 展示：Python 版本 + 就绪状态） */
    fun envInfo(): String = if (ready.get()) {
        "Python 统一环境已就绪（AstrBot 市场插件共享 site-packages）"
    } else {
        "Python 环境初始化中或不可用，请重启 App 后再试"
    }
}

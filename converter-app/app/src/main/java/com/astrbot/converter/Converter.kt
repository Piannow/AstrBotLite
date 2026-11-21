package com.astrbot.converter

/**
 * 转换引擎：把电脑上 AstrBot 的 Python 插件（main.py + metadata.yaml）解析为
 * AstrBot Lite 可用的轻量插件（prompt / 固定回复 两类命令）。
 *
 * 说明：安卓端无法运行 Python，因此只能识别“固定回复”和“可转为 AI 指令”的模式；
 * 含复杂逻辑（网络/循环/富媒体）的部分会转为 AI 指令模式并在日志中提示人工检查。
 */
object Converter {

    /** 解析 main.py（+ 可选的 metadata.yaml）为转换结果 */
    fun convert(mainPy: String, metaYaml: String?): ConvResult {
        val log = mutableListOf<String>()
        val meta = parseMeta(metaYaml, mainPy, log)
        val commands = mutableListOf<ConvCommand>()
        val seen = mutableSetOf<String>()

        // 1) self.register("cmd", "desc", handler)
        val simple = Regex("""self\.register\(\s*(['"])([^'"]+)\1\s*,\s*(['"])([^'"]*)\3\s*,\s*([A-Za-z_][A-Za-z0-9_]*)""")
        for (m in simple.findAll(mainPy)) {
            val cmd = m.groupValues[2]
            val desc = m.groupValues[4]
            val handler = m.groupValues[5]
            if (!seen.add(cmd)) continue
            commands.add(buildCommand(mainPy, cmd, desc, handler, meta.name, log))
        }

        // 2) self.register(("cmd","desc",fn), ("cmd2",...))  元组批量注册
        val tuple = Regex("""\(\s*(['"])([^'"]+)\1\s*,\s*(['"])([^'"]*)\3\s*,\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)""")
        for (ri in Regex("""register\s*\(""").findAll(mainPy)) {
            val seg = mainPy.substring(ri.range.first, (ri.range.first + 600).coerceAtMost(mainPy.length))
            for (m in tuple.findAll(seg)) {
                val cmd = m.groupValues[2]
                val desc = m.groupValues[4]
                val handler = m.groupValues[5]
                if (!seen.add(cmd)) continue
                commands.add(buildCommand(mainPy, cmd, desc, handler, meta.name, log))
            }
        }

        if (commands.isEmpty()) {
            log.add("⚠ 未识别到命令注册，已生成一个示例命令供手动编辑（也可直接编辑下方命令）")
            commands.add(ConvCommand(
                "/" + slug(meta.name).ifBlank { "cmd" },
                "插件主命令",
                "",
                "当用户输入此命令时，请根据插件「${meta.name.ifBlank { "未知" }}」的用途提供帮助。",
                "未识别到 self.register，已创建示例命令，请手动修改"
            ))
        }

        val trigger = detectTrigger(mainPy, log)
        return ConvResult(
            id = slug(meta.name).ifBlank { "converted_plugin" },
            name = meta.name.ifBlank { "转换的插件" },
            version = meta.version.ifBlank { "1.0.0" },
            author = meta.author,
            desc = meta.desc,
            commands = commands,
            log = log,
            trigger = trigger
        )
    }

    /** 根据源码特征推断触发方式（可在界面手动修改） */
    private fun detectTrigger(mainPy: String, log: MutableList<String>): String {
        val hookPatterns = arrayOf(
            "on_decorating_result", "on_llm_request", "on_llm_response",
            "on_message", "on_group_message", "on_c2c_message", "def hook"
        )
        if (hookPatterns.any { mainPy.contains(it) }) {
            log.add("检测到 hook 处理器，触发方式默认：总是触发(hook)")
            return "always"
        }
        if (Regex("""register_llm_tool|llm_tool|tools\s*=|on_llm_request""").containsMatchIn(mainPy)) {
            log.add("检测到 LLM 工具/技能注册，触发方式默认：LLM触发(技能)")
            return "llm"
        }
        log.add("默认触发方式：被动触发(命令)；可在下方修改")
        return "passive"
    }

    private fun buildCommand(mainPy: String, cmd: String, desc: String, handler: String, pluginName: String, log: MutableList<String>): ConvCommand {
        val block = methodBlock(mainPy, handler)
        val cmdMatch = if (cmd.startsWith("/")) cmd else "/$cmd"
        if (block.isBlank()) {
            log.add("命令 $cmdMatch：未找到处理器函数体，已转为 AI 指令模式")
            return ConvCommand(cmdMatch, desc, "", aiPrompt(cmdMatch, desc, pluginName), "未识别到处理器，已转为 AI 指令模式，请在手机上检查")
        }
        // f-string 动态回复
        val fRe = Regex("""plain_result\(\s*f(['"])((?:\\.|(?!\1).)*)\1\s*\)""")
        fRe.find(block)?.let { m ->
            log.add("命令 $cmdMatch：检测到动态回复（含变量），已提取文本模板，请手动检查")
            return ConvCommand(cmdMatch, desc, unescape(m.groupValues[2]), "", "动态回复（f-string），无法还原变量，请手动修改")
        }
        // 常量固定回复
        val constRe = Regex("""plain_result\(\s*(['"])((?:\\.|(?!\1).)*)\1\s*\)""")
        constRe.find(block)?.let { m ->
            log.add("命令 $cmdMatch：✅ 已提取固定回复")
            return ConvCommand(cmdMatch, desc, unescape(m.groupValues[2]), "", "")
        }
        // 消息段/富媒体
        if (Regex("""message_result\s*\(""").containsMatchIn(block)) {
            log.add("命令 $cmdMatch：原插件返回富媒体消息（图片等），已转为 AI 指令模式")
            return ConvCommand(cmdMatch, desc, "", aiPrompt(cmdMatch, desc, pluginName), "原插件返回富媒体，已转为 AI 指令模式")
        }
        log.add("命令 $cmdMatch：未识别到固定回复，已转为 AI 指令模式")
        return ConvCommand(cmdMatch, desc, "", aiPrompt(cmdMatch, desc, pluginName), "未识别到固定回复，已转为 AI 指令模式，请在手机上检查")
    }

    private fun aiPrompt(cmd: String, desc: String, pluginName: String): String =
        "当用户输入 $cmd 时：${desc.ifBlank { "执行插件 ${pluginName.ifBlank { "未知" }} 的功能" }}。请根据命令意图给出有帮助的回答。"

    /** 截取某方法 def 的函数体 */
    private fun methodBlock(src: String, handler: String): String {
        val re = Regex("""(?:def|async def)\s+${Regex.escape(handler)}\s*\([^)]*\)\s*:""")
        val m = re.find(src) ?: return ""
        val before = src.substring(0, m.range.last + 1)
        val firstLineIdx = before.count { it == '\n' }
        val lines = src.lines()
        if (firstLineIdx + 1 >= lines.size) return ""
        val bodyIndent = lines[firstLineIdx + 1].takeWhile { it == ' ' || it == '\t' }.length
        val sb = StringBuilder()
        for (i in firstLineIdx + 1 until lines.size) {
            val l = lines[i]
            if (l.isBlank()) { sb.append(l).append('\n'); continue }
            if (l.takeWhile { it == ' ' || it == '\t' }.length <= bodyIndent) break
            sb.append(l).append('\n')
        }
        return sb.toString()
    }

    private fun parseMeta(yaml: String?, mainPy: String, log: MutableList<String>): Meta {
        val m = Meta()
        yaml?.let { y ->
            for (line in y.lines()) {
                val t = line.trim()
                if (t.startsWith("#") || !t.contains(":")) continue
                val idx = t.indexOf(":")
                val key = t.substring(0, idx).trim()
                val value = t.substring(idx + 1).trim().trim('"', '\'')
                when (key) {
                    "name" -> m.name = value
                    "display_name" -> if (m.name.isBlank()) m.name = value
                    "desc" -> m.desc = value
                    "description" -> if (m.desc.isBlank()) m.desc = value
                    "author" -> m.author = value
                    "version" -> m.version = value
                }
            }
        }
        val regRe = Regex("""@register\(\s*(['"])([^'"]+)\1\s*,\s*(['"])([^'"]*)\3\s*,\s*(['"])([^'"]*)\5\s*,\s*(['"])([^'"]*)\7""")
        regRe.find(mainPy)?.let {
            if (m.name.isBlank()) m.name = it.groupValues[2]
            if (m.author.isBlank()) m.author = it.groupValues[4]
            if (m.desc.isBlank()) m.desc = it.groupValues[6]
            if (m.version.isBlank()) m.version = it.groupValues[8]
        }
        log.add("插件信息：${m.name.ifBlank { "未知" }} v${m.version.ifBlank { "?" }} by ${m.author.ifBlank { "?" }}")
        return m
    }

    private fun unescape(s: String): String =
        s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")

    private fun slug(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]"), "_").trim('_')

    private class Meta {
        var name = ""
        var desc = ""
        var author = ""
        var version = ""
    }
}

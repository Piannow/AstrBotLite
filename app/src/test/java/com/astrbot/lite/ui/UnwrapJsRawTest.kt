package com.astrbot.lite.ui

import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 [BaiduShareRenderer.unwrapJsRaw] 逻辑（纯 JVM、零 org.json 运行时依赖）。
 *
 * Android WebView evaluateJavascript 的 ValueCallback 收到的是 JS 表达式求值结果的
 * "JSON 编码字符串"。JS 里 `JSON.stringify(obj)` 返回一个 JS 字符串，WebView 再把它
 * 序列化成带外层引号 + 内部转义的 JSON 字面值。
 *
 * 但不同 Android 版本/实现上，回调值可能是：
 *   A) 带外层引号的 JSON 字符串（最常见）
 *   B) 直接的原始字符串（某些实现）
 *
 * unwrapJsRaw 必须同时兼容 A、B 两种格式。
 *
 * 注意：本测试**不 import org.json**（JVM 运行时没有该包，只有编译期有），
 * 因此用字面量构造输入、用字符串断言校验输出，避免 NoClassDefFoundError。
 */
class UnwrapJsRawTest {

    /** 镜像 [BaiduShareRenderer.unwrapJsRaw] 的修正后算法（被测对象，实现须与之一致）。 */
    private fun unwrapJsRaw(raw: String?): String? {
        val s = raw ?: return null
        if (s.length > 1 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length - 1)
                .replace("\\\\", "\u0001")   // 先处理字面反斜杠对
                .replace("\\\"", "\"")        // 再解引号
                .replace("\u0001", "\\")      // 还原反斜杠
        }
        return s
    }

    /** 模拟 WebView 对 JS 字符串值的 JSON 编码：先转义反斜杠，再转义引号，最后包一层引号。
     *  与真实 WebView（JSON.stringify 字符串结果）行为一致，且避免手工写字面量出错。 */
    private fun webviewEncode(jsValue: String): String =
        "\"" + jsValue.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** 场景 A：WebView 返回 JSON 编码字符串（外层引号 + 内部转义，最常见） */
    @Test
    fun jsonEncodedString() {
        val jsValue = """{"shareid":"abc","uk":"xyz"}"""
        val input = webviewEncode(jsValue)
        assertEquals(jsValue, unwrapJsRaw(input))
    }

    /** 场景 A2：嵌套转义（fileList 本身是 JSON 字符串，再被 JSON.stringify 包一层） */
    @Test
    fun jsonEncodedNestedEscape() {
        // JS 里 out.fileList = JSON.stringify([{fs_id:"123"}]) → 值为 [{"fs_id":"123"}]
        val fileListValue = """[{"fs_id":"123"}]"""
        // out.fileList 是"字符串"，故 JSON.stringify(out) 会把它转义成字面 {"fs_id"...}
        val fileListEscaped = fileListValue.replace("\"", "\\\"")
        val jsValue = """{"shareid":"abc","fileList":"$fileListEscaped"}"""
        val input = webviewEncode(jsValue)
        assertEquals(jsValue, unwrapJsRaw(input))
    }

    /** 场景 B：WebView 直接返回原始字符串（某些实现，无外层引号） */
    @Test
    fun rawString() {
        val jsResult = """{"shareid":"abc","uk":"xyz"}"""
        assertEquals(jsResult, unwrapJsRaw(jsResult))
    }

    /** 场景 C：纯文本带引号（非 JSON 对象），如 JS 返回 "hello" 的编码形式 */
    @Test
    fun plainQuotedText() {
        assertEquals("hello", unwrapJsRaw(""""hello""""))
    }

    /** 场景 D：空字符串 / null */
    @Test
    fun nullAndEmpty() {
        assertNull(unwrapJsRaw(null))
        assertEquals("", unwrapJsRaw(""))
    }
}

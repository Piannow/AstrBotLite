package com.astrbot.lite.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 可复用的「模板图 + 文字叠加」本地渲染器：
 * 把一段用户文字画到一张底图（角色图/举牌图）的指定区域内，输出干净的 PNG 字节。
 *
 * 支持「举牌 / 表情包 / 文字图」这一类插件（模板底图 + 用户输入文字），与具体插件解耦，
 * 其它同类型插件可直接复用本组件。
 *
 * 渲染语义（对齐 sketchbook-py 的 TextFitDrawer）：
 * - 文字自动缩放：短文字放大到 maxFontHeight 撑满气泡，长文字自动缩小以适应区域；
 * - `\n` 换行；
 * - 中括号【】内的内容（含中括号本身）渲染为 bracketColor（紫色），其余为 textColor。
 */
object ImageRender {

    /** 渲染规格：区域用绝对像素坐标，颜色用 0xAARRGGBB 形式的整型 */
    data class Spec(
        val regionX1: Int,
        val regionY1: Int,
        val regionX2: Int,
        val regionY2: Int,
        val textColor: Int,
        val bracketColor: Int,
        val maxFontHeight: Int,
    )

    /**
     * 把 text 画到 templateBytes 的区域内，返回 PNG 字节；失败（底图无法解码等）返回 null。
     * @param fontFile 可选的字体文件（.ttf/.ttc），不存在则用系统粗体
     */
    fun render(templateBytes: ByteArray, text: String, spec: Spec, fontFile: File? = null): ByteArray? {
        return try {
            val base = BitmapFactory.decodeByteArray(templateBytes, 0, templateBytes.size) ?: return null
            if (base.width <= 0 || base.height <= 0) {
                base.recycle()
                return null
            }
            // 复制为可写位图（原图解码可能已不可变）
            val bmp = base.copy(Bitmap.Config.ARGB_8888, true)
            base.recycle()
            val canvas = Canvas(bmp)

            // 区域（像素），并钳制到底图边界内
            val x1 = spec.regionX1.coerceIn(0, bmp.width - 1)
            val y1 = spec.regionY1.coerceIn(0, bmp.height - 1)
            val x2 = spec.regionX2.coerceIn(x1 + 1, bmp.width)
            val y2 = spec.regionY2.coerceIn(y1 + 1, bmp.height)
            val regionW = (x2 - x1).toFloat()
            val regionH = (y2 - y1).toFloat()
            val cx = ((x1 + x2) / 2f)
            val cy = ((y1 + y2) / 2f)

            // 用户文字里的字面 "\n" 还原为真实换行，再按 \n 切行
            val rawLines = text.replace("\\n", "\n").replace("\r", "").split('\n')
            val lines = if (rawLines.isEmpty()) listOf("") else rawLines

            val tf = loadTypeface(fontFile)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            paint.typeface = tf
            paint.textAlign = Paint.Align.LEFT

            // 自动缩放字号：从 maxFontHeight 起，缩放到整块能放进区域为止
            var size = spec.maxFontHeight.coerceAtLeast(8).toFloat()
            while (size > 8f) {
                paint.textSize = size
                val fm = paint.fontMetrics
                val lineH = -fm.ascent + fm.descent
                val blockH = lineH * lines.size
                var maxW = 0f
                for (l in lines) maxW = maxOf(maxW, paint.measureText(l))
                if (blockH <= regionH && maxW <= regionW) break
                size *= 0.95f
            }
            paint.textSize = size

            // 垂直居中整块文字
            val fm = paint.fontMetrics
            val ascent = -fm.ascent
            val lineH = ascent + fm.descent
            val blockH = lineH * lines.size
            var top = cy - blockH / 2f

            for (l in lines) {
                val baseline = top + ascent
                drawCenteredLine(l, cx, baseline, canvas, paint, spec)
                top += lineH
            }

            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Logs.err("[渲染] 生成图片失败：${e.message}")
            null
        }
    }

    /** 把一行文字水平居中绘制，行内【...】片段用 bracketColor，其余用 textColor */
    private fun drawCenteredLine(line: String, centerX: Float, baseline: Float, canvas: Canvas, paint: Paint, spec: Spec) {
        if (line.isEmpty()) return
        val segs = parseSegments(line)
        var total = 0f
        for (s in segs) total += paint.measureText(s.text)
        var x = centerX - total / 2f
        for (s in segs) {
            paint.color = if (s.bracket) spec.bracketColor else spec.textColor
            canvas.drawText(s.text, x, baseline, paint)
            x += paint.measureText(s.text)
        }
    }

    private data class Seg(val text: String, val bracket: Boolean)

    /** 把一行切分为 [文本段, 中括号段] 交替的片段（【...】整段标记为 bracket） */
    private fun parseSegments(line: String): List<Seg> {
        val out = ArrayList<Seg>()
        val n = line.length
        var i = 0
        while (i < n) {
            if (line[i] == '【') {
                val start = i
                i++
                while (i < n && line[i] != '】') i++
                val end = if (i < n) i else n // 含 】（若没有 】 则到行尾）
                if (i < n) i++
                if (end > start) out.add(Seg(line.substring(start, end), true))
            } else {
                val start = i
                while (i < n && line[i] != '【') i++
                if (i > start) out.add(Seg(line.substring(start, i), false))
            }
        }
        return out
    }

    private fun loadTypeface(f: File?): Typeface? {
        if (f != null && f.exists()) {
            try {
                val tf = Typeface.createFromFile(f)
                if (tf != null) return tf
            } catch (_: Exception) {
            }
        }
        return Typeface.DEFAULT_BOLD
    }

    /** 把 "45,38,35" / "45,38,35,255" 形式的颜色字符串解析为 0xAARRGGBB 整型；失败退回 defaultCsv */
    fun parseColor(raw: String?, defaultCsv: String): Int {
        fun parse(s: String): Int? = try {
            val p = s.split(',')
            if (p.size < 3) {
                null
            } else {
                Color.argb(
                    (if (p.size > 3) p[3] else "255").trim().toInt(),
                    p[0].trim().toInt(),
                    p[1].trim().toInt(),
                    p[2].trim().toInt(),
                )
            }
        } catch (_: Exception) {
            null
        }
        val t = raw?.trim()
        if (!t.isNullOrBlank()) parse(t)?.let { return it }
        return parse(defaultCsv) ?: Color.argb(255, 45, 38, 35)
    }

    /** 把 "150,150,620,470" 形式的区域字符串解析为 4 个 Int；为空/失败返回默认 (d1,d2,d3,d4) */
    fun parseRegion(raw: String?, d1: Int, d2: Int, d3: Int, d4: Int): IntArray {
        val s = raw?.trim()
        if (s.isNullOrBlank()) return intArrayOf(d1, d2, d3, d4)
        val p = s.split(',')
        if (p.size != 4) return intArrayOf(d1, d2, d3, d4)
        val v = p.map { it.trim().toIntOrNull() }
        if (v.any { it == null }) return intArrayOf(d1, d2, d3, d4)
        return intArrayOf(v[0]!!, v[1]!!, v[2]!!, v[3]!!)
    }
}

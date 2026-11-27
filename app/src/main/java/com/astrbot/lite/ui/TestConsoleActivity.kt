package com.astrbot.lite.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.astrbot.lite.R
import com.astrbot.lite.core.BotEngine
import com.astrbot.lite.core.BotState
import com.astrbot.lite.core.Config
import com.astrbot.lite.core.LogLine
import com.astrbot.lite.core.Logs
import com.astrbot.lite.core.PluginManager
import com.astrbot.lite.core.QQPanelSync
import com.astrbot.lite.core.QqMessageEvent
import com.astrbot.lite.ui.DpadFocus.scrollDescendantToBottom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 测试对话页（模拟 QQ 环境，不连真实 QQ）。
 *
 * 布局：左边上方是环境切换 + 会话列表（可手动新建对话，新建时选私聊/群聊），
 * 左边下方是运行日志；右边仿 QQ 聊天界面，可发消息、@ 机器人、引用机器人消息。
 * 消息只走引擎处理链路，不写聊天记录 / 记忆 / 知识库（BotEngine.testMode）。
 */
class TestConsoleActivity : Activity() {

    /** 一条测试消息 */
    private data class Msg(
        val from: String, // user / bot / sys
        val text: String,
        val time: Long,
        val msgId: String = "",
        /** 语音文件路径（🔊 voice:// 标记解析而来；null=纯文本） */
        val media: String? = null,
        /** 用户发送的图片（content:// uri；引用该消息发图生图用） */
        val image: String? = null,
    )

    /** 一个测试会话（对应一个 QQ 会话环境） */
    private class Conv(
        val kind: String, // c2c / group
        val openid: String,
        var nick: String,
    ) {
        val key: String get() = "$kind:$openid"
        val messages = mutableListOf<Msg>()
        val title: String get() = (if (kind == "group") "群聊 · " else "私聊 · ") + openid
    }

    private val reqImagePick = 3101
    private val handler = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val fmtSec = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 单线程执行：测试消息按顺序处理，和真实引擎一致（不并发） */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "test-console").apply { isDaemon = true } }

    private val convs = mutableListOf<Conv>()
    private var current: Conv? = null

    /** 待引用（被选中的）机器人消息 */
    private var quoted: Msg? = null

    /** 群聊下是否 @ 机器人 */
    private var atBot = false

    /** 待发送的图片（点「图片」选择，随下一条消息发出一次） */
    private var pendingImageUri: android.net.Uri? = null

    /** 附件图片预览缩略图 */
    private lateinit var previewImg: ImageView

    /** 接管 videoFailNotify 的兜底 lambda（服务引擎没跑时安装；关闭时只清理自己那份） */
    private var asyncFallback: ((String, String) -> Unit)? = null
    private var filterKind = "c2c"
    private var seq = 0

    // 控件
    private lateinit var etSender: EditText
    private lateinit var etMessage: EditText
    private lateinit var tvConvList: TextView
    private lateinit var tagBox: LinearLayout
    private lateinit var chatBox: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var tvHeader: TextView
    private lateinit var tvThinking: TextView
    private lateinit var btnAtBot: Button
    private lateinit var btnQuote: Button
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnPrivate: Button
    private lateinit var btnGroup: Button

    private lateinit var engine: BotEngine

    private val replyListener = BotEngine.TestReplyListener { convKey, text ->
        handler.post { onBotReply(convKey, text) }
    }

    private val thoughtListener = BotEngine.TestThoughtListener { convKey, thought ->
        handler.post { onBotThought(convKey, thought) }
    }

    private val logListener: (LogLine) -> Unit = { line ->
        handler.post {
            logText.append("[" + fmtSec.format(Date(line.time)) + "] [" + line.level + "] " + line.text + "\n")
            // 1.7.237：限长（约 4 万字），测试台长时间开着也不再无限堆积
            val t = logText.text
            if (t.length > 40_000) logText.text = t.subSequence(t.length - 40_000, t.length)
            logScroll.post { logScroll.scrollDescendantToBottom() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 竖屏适配（1.7.61）：横屏保持左(320dp配置)+右(聊天)分栏；竖屏改为上下堆叠各占一半，
        // 否则左栏 320dp 挤占全宽、右栏只剩几十 dp，整页"挤在一起"。
        val portrait = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        val root = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        if (portrait) {
            root.addView(
                buildLeftPanel(),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            root.addView(
                View(this).apply {
                    setBackgroundColor(color(R.color.divider))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                },
            )
            root.addView(
                buildRightPanel(),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        } else {
            root.addView(buildLeftPanel(), LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(dividerV())
            root.addView(buildRightPanel(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        setContentView(root)
        setupSlashMenuWatcher()
        // 异步插件结果订阅（看番/云播列表、视频完成等）：命中本控制台会话才显示；
        // 服务引擎没跑时接管 videoFailNotify，保证机器人关闭也能看到异步结果
        PluginManager.testNotify = { sk, t -> handler.post { onAsyncNotice(sk, t) } }
        if (PluginManager.videoFailNotify == null) {
            val fb: (String, String) -> Unit = { sk, t -> handler.post { onAsyncNotice(sk, t) } }
            asyncFallback = fb
            PluginManager.videoFailNotify = fb
        }

        engine = BotEngine(this)
        engine.testMode = true
        engine.addTestReplyListener(replyListener)
        engine.addTestThoughtListener(thoughtListener)
        Logs.addListener(logListener)
        // 情绪/生活状态要走人格化注入，这里确保状态已从磁盘加载（幂等；机器人没启动时也有效）
        BotState.init(this)

        // 默认建一个私聊会话，进来就能发消息
        newConv("c2c", "test_user_001", "我", silent = true)
        setFilter("c2c")
    }

    override fun onDestroy() {
        super.onDestroy()
        Logs.removeListener(logListener)
        PluginManager.testNotify = null
        if (asyncFallback != null && PluginManager.videoFailNotify === asyncFallback) {
            PluginManager.videoFailNotify = null
        }
        asyncFallback = null
        engine.removeTestReplyListener(replyListener)
        engine.removeTestThoughtListener(thoughtListener)
        worker.shutdownNow()
        // 清掉 120s 超时等所有待执行回调，避免退出后仍持有页面引用
        handler.removeCallbacksAndMessages(null)
    }

    // ---------------- 左侧：环境切换 + 会话列表 + 日志 ----------------

    private fun buildLeftPanel(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            TextView(this).apply {
                text = "测试对话"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.textMain))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        top.addView(smallButton("返回") { finish() })
        col.addView(top)

        col.addView(
            TextView(this).apply {
                text = "模拟 QQ 环境，消息不会发到真实 QQ，也不写聊天记录/记忆"
                textSize = 11f
                setTextColor(0xFFE65100.toInt())
                setPadding(0, dp(4), 0, dp(6))
            },
        )

        col.addView(tinyLabel("环境"))
        val envRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnPrivate = smallButton("私聊", 1f) { setFilter("c2c") }
        btnGroup = smallButton("群聊", 1f) { setFilter("group") }
        envRow.addView(btnPrivate)
        envRow.addView(btnGroup)
        col.addView(envRow)

        col.addView(smallButton("＋ 新建对话") { showNewConvDialog() })
        col.addView(tinyLabel("会话"))

        tvConvList = TextView(this).apply {
            textSize = 12f
            setTextColor(color(R.color.textMain))
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(0xFFF7F8FA.toInt())
            isFocusable = true
            setOnClickListener { showConvPicker() }
        }
        DpadFocus.highlight(tvConvList)
        col.addView(
            tvConvList,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        col.addView(tinyLabel("运行日志"))
        val logTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logTop.addView(smallButton("清空日志", 1f) { logText.text = "" })
        logTop.addView(
            smallButton("复制日志", 1f) {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("test_log", logText.text.toString()))
                Toast.makeText(this, "日志已复制（${logText.length()} 字）", Toast.LENGTH_SHORT).show()
            },
        )
        col.addView(logTop)
        logText = TextView(this).apply {
            setBackgroundColor(color(R.color.termBg))
            setTextColor(color(R.color.termText))
            textSize = 10f
            setPadding(dp(6), dp(6), dp(6), dp(6))
            typeface = Typeface.MONOSPACE
        }
        logScroll = ScrollView(this).apply { addView(logText) }
        col.addView(
            logScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        return col
    }

    private fun dividerV(): View = View(this).apply {
        setBackgroundColor(color(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
    }

    // ---------------- 右侧：QQ 风格聊天 ----------------

    private fun buildRightPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        tvHeader = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF4C6EF5.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        col.addView(tvHeader)

        chatBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        chatScroll = ScrollView(this).apply {
            setBackgroundColor(0xFFEDEFF3.toInt())
            addView(chatBox)
        }
        col.addView(
            chatScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        tvThinking = TextView(this).apply {
            textSize = 12f
            setTextColor(color(R.color.textSub))
            setPadding(dp(12), 0, dp(12), dp(2))
            visibility = View.GONE
        }
        col.addView(tvThinking)

        // 待引用/@ 提示
        tagBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFFFF7E6.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
        }
        col.addView(tagBox)
        // 附件图片预览：选中即显示缩略图（用户要求“照片可以预览看到”）
        previewImg = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        col.addView(previewImg, LinearLayout.LayoutParams(dp(96), dp(96)).apply { topMargin = dp(4) })

        // 快捷操作
        val actRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(0))
        }
        btnAtBot = smallButton("@机器人", 1f) { toggleAtBot() }
        btnQuote = smallButton("引用选中", 1f) { clearQuote() }
        actRow.addView(btnAtBot)
        actRow.addView(btnQuote)
        actRow.addView(smallButton("图片", 1f) { pickImage() })
        actRow.addView(smallButton("清空上下文", 1f) { clearContext() })
        col.addView(actRow)

        // 输入区
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        etMessage = EditText(this).apply {
            hint = "输入消息，回车发送"
            textSize = 14f
            minLines = 1
            maxLines = 3
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        DpadFocus.highlight(etMessage)
        inputRow.addView(etMessage, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btnSend = smallButton("发送") { send() }
        DpadFocus.highlight(btnSend)
        inputRow.addView(btnSend)
        col.addView(inputRow)

        // 发送者（私聊=对方QQ号；群聊=群号）与昵称
        val idRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        etSender = EditText(this).apply {
            textSize = 12f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        DpadFocus.highlight(etSender)
        idRow.addView(etSender, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(idRow)

        return col
    }

    /** 渲染一条消息气泡（机器人消息可选中为引用目标） */
    private fun bubble(msg: Msg): View {
        val isUser = msg.from == "user"
        val isBot = msg.from == "bot"
        val isThought = msg.from == "thought"

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.RIGHT else Gravity.LEFT
            setPadding(0, dp(4), 0, dp(4))
        }

        val avatar = TextView(this).apply {
            text = if (isUser) {
                "我"
            } else if (isThought) {
                "🧠"
            } else {
                "AI"
            }
            textSize = if (isThought) 14f else 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    if (isUser) {
                        0xFF0099FF.toInt()
                    } else if (isThought) {
                        0xFF9C27B0.toInt()
                    } else {
                        0xFF7C8DB5.toInt()
                    },
                )
            }
        }
        val avLp = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
            if (isUser) rightMargin = dp(6) else leftMargin = dp(6)
            topMargin = dp(2)
        }

        val bubbleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val body = TextView(this).apply {
            text = if (isThought) "💭 ${msg.text}" else msg.text
            textSize = 13f
            setTextColor(
                if (isUser) {
                    Color.WHITE
                } else if (isThought) {
                    0xFF7B1FA2.toInt()
                } else {
                    0xFF1F2329.toInt()
                },
            )
            setPadding(dp(10), dp(7), dp(10), dp(7))
            if (isBot || isThought) maxWidth = dp(520)
            if (isThought) {
                setTypeface(typeface, android.graphics.Typeface.ITALIC)
            }
        }

        val info = TextView(this).apply {
            text = fmt.format(Date(msg.time))
            textSize = 10f
            setTextColor(color(R.color.textSub))
            gravity = if (isUser) Gravity.RIGHT else Gravity.LEFT
        }

        if (isBot) {
            // 机器人消息：整块可聚焦，确定键 = 选中/取消引用（引用后机器人会回你）
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            fun paint(focused: Boolean) {
                val selected = quoted === msg
                holder.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (selected) 0xFFE3F0FF.toInt() else Color.WHITE)
                    setStroke(
                        dp(2),
                        if (selected) {
                            0xFF0099FF.toInt()
                        } else if (focused) {
                            0xFF4C6EF5.toInt()
                        } else {
                            0x22000000
                        },
                    )
                }
            }
            paint(false)
            holder.setOnFocusChangeListener { _, has -> paint(has) }
            holder.setOnClickListener {
                quoted = if (quoted === msg) null else msg
                renderChat()
                Logs.info(if (quoted == null) "[测试] 已取消引用" else "[测试] 已选中一条机器人消息用于引用")
            }
            // 长按 = 复制 AI 回复（点击=引用，长按=复制，互不冲突）
            holder.setOnLongClickListener {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ai_reply", msg.text))
                Toast.makeText(this@TestConsoleActivity, "已复制 AI 回复（${msg.text.length} 字）", Toast.LENGTH_SHORT).show()
                true
            }
            holder.addView(
                body,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            bubbleCol.addView(holder)
            msg.image?.let { uri ->
                val bmp = decodeThumb(uri, 640)
                if (bmp != null) {
                    bubbleCol.addView(
                        ImageView(this).apply {
                            setImageBitmap(bmp)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        },
                        LinearLayout.LayoutParams(dp(200), dp(200)).apply { topMargin = dp(4) },
                    )
                }
            }
            if (msg.media != null) {
                bubbleCol.addView(
                    TextView(this).apply {
                        text = "▶ 播放语音"
                        textSize = 12f
                        setTextColor(0xFF1A73E8.toInt())
                        isFocusable = true
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        setOnClickListener { playVoice(msg.media!!) }
                    },
                )
            }
            bubbleCol.addView(info)
        } else {
            body.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(
                    if (isUser) {
                        0xFF0099FF.toInt()
                    } else if (isThought) {
                        0xFFF3E5F5.toInt()
                    } else {
                        0xFFFFE0E0.toInt()
                    },
                )
            }
            bubbleCol.addView(body)
            // 用户发的图片：气泡内显示缩略图（可看到照片本身，便于选中引用后图生图）
            msg.image?.let { uri ->
                val bmp = decodeThumb(uri, 640)
                if (bmp != null) {
                    bubbleCol.addView(
                        ImageView(this).apply {
                            setImageBitmap(bmp)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        },
                        LinearLayout.LayoutParams(dp(200), dp(200)).apply { topMargin = dp(4) },
                    )
                }
            }
            bubbleCol.addView(info)
        }

        val colLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        if (isUser) {
            row.addView(bubbleCol, colLp)
            row.addView(avatar, avLp)
        } else {
            row.addView(avatar, avLp)
            row.addView(bubbleCol, colLp)
        }
        return row
    }

    private fun renderChat() {
        val conv = current ?: return
        // 重建前记录焦点是否在聊天区内：removeAllViews 会销毁原控件导致焦点丢失，重建后要还回去
        val hadFocusInside = chatScroll.findFocus() != null
        // 私聊不显示「@机器人」开关（@ 只在群聊有意义；状态栏/tag 同样只在群聊显示）
        btnAtBot.visibility = if (conv.kind == "group") View.VISIBLE else View.GONE
        val env = if (conv.kind == "group") "群聊" else "私聊"
        val extra = if (conv.kind == "group") {
            if (atBot) "　·　已@机器人（会回复）" else "　·　未@（按设置只接收不回复）"
        } else {
            ""
        }
        tvHeader.text = "当前会话：${conv.title}　·　$env$extra"
        chatBox.removeAllViews()
        if (conv.messages.isEmpty()) {
            chatBox.addView(
                TextView(this).apply {
                    text = "还没有消息。在下面输入框发一条试试。（点机器人消息=引用，长按=复制内容；群聊里可开「@机器人」）"
                    textSize = 12f
                    setTextColor(color(R.color.textSub))
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                },
            )
        } else {
            conv.messages.forEach { chatBox.addView(bubble(it)) }
        }
        renderTags()
        updateConvLabel()
        chatScroll.post { chatScroll.scrollDescendantToBottom() }
        if (hadFocusInside) {
            // 焦点还给聊天区（重建后气泡里的可聚焦控件同理可用），遥控器不用重新找焦点
            chatScroll.isFocusableInTouchMode = true
            chatScroll.post { chatScroll.requestFocus() }
        }
    }

    private fun renderTags() {
        tagBox.removeAllViews()
        val q = quoted
        val img = pendingImageUri
        val atTag = current?.kind == "group" && atBot
        val has = q != null || img != null || atTag
        tagBox.visibility = if (has) View.VISIBLE else View.GONE
        if (!has) return
        fun addTag(text: String) {
            tagBox.addView(
                TextView(this).apply {
                    this.text = text
                    textSize = 12f
                    setTextColor(0xFF8A6100.toInt())
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        if (img != null) addTag("📷 将发送图片")
        if (q != null) {
            addTag("引用：" + q.text.replace("\n", " ").take(40))
        } else if (atTag) {
            addTag("将 @ 机器人")
        }
    }

    private fun updateConvLabel() {
        val conv = current
        if (conv == null) {
            tvConvList.text = "（无会话，点「新建对话」）"
            etSender.setText("")
            etSender.hint = "会话ID"
            return
        }
        tvConvList.text = "当前：" + conv.title + "　（点此切换）"
        etSender.hint = if (conv.kind == "group") "群号" else "对方QQ号"
        etSender.setText(conv.openid)
    }

    // ---------------- 会话管理 ----------------

    private fun setFilter(kind: String) {
        filterKind = kind
        btnPrivate.isEnabled = kind != "c2c"
        btnGroup.isEnabled = kind != "group"
        // 切环境时自动切到该环境下的第一个会话，没有就新建一个
        val c = convs.firstOrNull { it.kind == kind } ?: newConv(kind, defaultId(kind), "我")
        selectConv(c)
    }

    private fun defaultId(kind: String): String = if (kind == "group") {
        "test_group_" + String.format("%03d", seq + 1)
    } else {
        "test_user_" + String.format("%03d", seq + 1)
    }

    private fun newConv(kind: String, openid: String, nick: String, silent: Boolean = false): Conv {
        seq++
        val c = Conv(kind, openid, nick)
        convs.add(c)
        if (!silent) Logs.info("[测试] 新建${if (kind == "group") "群聊" else "私聊"}会话：$openid")
        return c
    }

    private fun selectConv(c: Conv) {
        current = c
        quoted = null
        atBot = false
        renderChat()
    }

    private fun showConvPicker() {
        val list = convs.filter { it.kind == filterKind }
        if (list.isEmpty()) return
        val names = list.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("切换会话")
            .setItems(names) { _, which -> selectConv(list[which]) }
            .setPositiveButton("新建对话") { _, _ -> showNewConvDialog() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 新建对话：先问环境（私聊/群聊），再按环境问对应信息 */
    private fun showNewConvDialog() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        col.addView(tinyLabel("环境"))
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbPrivate = RadioButton(this).apply {
            text = "私聊"
            id = 1
        }
        val rbGroup = RadioButton(this).apply {
            text = "群聊"
            id = 2
        }
        rg.addView(rbPrivate)
        rg.addView(rbGroup)
        if (filterKind == "group") rg.check(2) else rg.check(1)
        col.addView(rg)

        val tvIdLabel = tinyLabel("对方QQ号")
        col.addView(tvIdLabel)
        val etId = EditText(this).apply {
            hint = "测试用ID，随便填（如 test_user_002）"
            isSingleLine = true
            setText(defaultId(filterKind))
        }
        col.addView(etId)

        val tvNickLabel = tinyLabel("我的昵称")
        col.addView(tvNickLabel)
        val etNick = EditText(this).apply {
            hint = "群里显示的名字（如 小明）"
            isSingleLine = true
            setText("我")
        }
        col.addView(etNick)

        rg.setOnCheckedChangeListener { _, id ->
            val group = id == 2
            tvIdLabel.text = if (group) "群号" else "对方QQ号"
            etId.setText(defaultId(if (group) "group" else "c2c"))
            tvNickLabel.text = if (group) "我在群里的昵称" else "我的昵称"
        }

        AlertDialog.Builder(this)
            .setTitle("新建对话")
            .setView(col)
            .setPositiveButton("创建") { _, _ ->
                val kind = if (rg.checkedRadioButtonId == 2) "group" else "c2c"
                val id = etId.text.toString().trim().ifBlank { defaultId(kind) }
                val nick = etNick.text.toString().trim().ifBlank { "我" }
                val c = newConv(kind, id, nick)
                filterKind = kind
                btnPrivate.isEnabled = kind != "c2c"
                btnGroup.isEnabled = kind != "group"
                selectConv(c)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearContext() {
        val conv = current ?: return
        engine.clearHistory(conv.key)
        conv.messages.clear()
        quoted = null
        renderChat()
        toast("已清空该会话的上下文与显示")
    }

    private fun toggleAtBot() {
        val conv = current ?: return
        if (conv.kind != "group") {
            toast("私聊不需要 @，机器人一定会回")
            return
        }
        atBot = !atBot
        renderChat()
    }

    private fun clearQuote() {
        if (quoted == null) {
            toast("先点一条机器人的消息选中它")
            return
        }
        quoted = null
        renderChat()
    }

    // ---------------- 图片附件 / 语音播放 / 异步结果订阅 ----------------

    /** 选一张本地图片（系统文件选择器），作为下一条消息的附件（content:// 直接塞给插件） */
    private fun pickImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        startActivityForResult(i, reqImagePick)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqImagePick && resultCode == Activity.RESULT_OK && data?.data != null) {
            pendingImageUri = data.data
            renderTags()
            // 预览缩略图（可直接看到选了哪张照片）
            val bmp = decodeThumb(data.data.toString())
            if (bmp != null) {
                previewImg.setImageBitmap(bmp)
                previewImg.visibility = View.VISIBLE
            }
            Toast.makeText(this, "📷 图片已附加（随下一条消息发送）", Toast.LENGTH_SHORT).show()
            Logs.info("[测试] 已附加图片：${data.data}")
        }
    }

    /** 异步插件结果（search/云播/视频…）落到对应控制台会话；非本控制台的会话忽略 */
    private fun onAsyncNotice(convKey: String, text: String) {
        val c = convs.firstOrNull { it.key == convKey } ?: return
        c.messages.add(Msg("bot", text, System.currentTimeMillis(), "test_async_" + System.currentTimeMillis()))
        if (current === c) renderChat()
        tvThinking.visibility = View.GONE
    }

    /** 按 uri 形态开流：content://（选图）/ file:// / 本地绝对路径（TempMedia 语音图片）；远程图不在此下载 */
    private fun openThumbStream(uriStr: String): java.io.InputStream? = try {
        when {
            uriStr.startsWith("content://") -> contentResolver.openInputStream(android.net.Uri.parse(uriStr))
            uriStr.startsWith("file://") -> java.io.FileInputStream(java.io.File(android.net.Uri.parse(uriStr).path ?: ""))
            uriStr.startsWith("http") -> null // 引擎回传前已落地为本地文件，这里不联网
            else -> java.io.FileInputStream(java.io.File(uriStr))
        }
    } catch (_: Exception) {
        null
    }

    /** 解码图片缩略图（inSampleSize 降采样，避免大图 OOM）；失败返回 null */
    private fun decodeThumb(uriStr: String, maxDim: Int = 512): android.graphics.Bitmap? {
        try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val s1 = openThumbStream(uriStr) ?: return null
            s1.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val s2 = openThumbStream(uriStr) ?: return null
            return s2.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            Logs.warn("[测试] 图片解码失败: ${e.message}")
            return null
        }
    }

    /** 播放测试台收到的 TTS 语音（TempMedia 临时文件） */
    private fun playVoice(path: String) {
        try {
            val mp = android.media.MediaPlayer()
            mp.setDataSource(path)
            mp.setOnCompletionListener { mp.release() }
            mp.prepare()
            mp.start()
            Toast.makeText(this, "▶ 播放中…", Toast.LENGTH_SHORT).show()
            Logs.info("[测试] 播放语音：$path")
        } catch (e: Exception) {
            Toast.makeText(this, "语音播放失败：${e.message}", Toast.LENGTH_SHORT).show()
            Logs.warn("[测试] 语音播放失败: ${e.message}")
        }
    }

    // ---------------- "/" 快捷指令菜单（仿 QQ：输入框打 / 即弹出，与 QQ 指令面板同一份数据） ----------------

    private var slashMenuShown = false

    private fun setupSlashMenuWatcher() {
        etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val t = s?.toString() ?: ""
                if (t == "/") {
                    if (!slashMenuShown) {
                        slashMenuShown = true
                        showSlashMenu()
                    }
                } else {
                    slashMenuShown = false
                }
            }
        })
    }

    private fun showSlashMenu() {
        val items = try {
            QQPanelSync.panelPairs()
        } catch (_: Exception) {
            emptyList()
        }
        if (items.isEmpty()) return
        val labels = items.map { (n, d) -> if (d.isBlank()) n else "$n　$d" }
        AlertDialog.Builder(this)
            .setTitle("快捷指令（与 QQ 指令面板一致）")
            .setItems(labels.toTypedArray()) { _, i ->
                // 选中后填 /命令 + 尾空格：光标落最后，直接补关键词即可
                etMessage.setText("/" + items[i].first.trim() + " ")
                etMessage.setSelection(etMessage.text.length)
                slashMenuShown = false
            }
            .setNegativeButton("取消") { _, _ -> slashMenuShown = false }
            .show()
    }

    // ---------------- 发送 ----------------

    private fun send() {
        val conv = current ?: return
        val raw = etMessage.text.toString().trim()
        val imgUri = pendingImageUri
        if (raw.isBlank() && imgUri == null) return
        // 收件人改了 → 视为切到另一个会话（不同 openid 上下文天然分开）
        val typedId = etSender.text.toString().trim()
        if (typedId.isNotBlank() && typedId != conv.openid) {
            val exist = convs.firstOrNull { it.kind == conv.kind && it.openid == typedId }
            val target = exist ?: newConv(conv.kind, typedId, conv.nick)
            selectConv(target)
        }
        val c = current ?: return

        val q = quoted
        val isGroup = c.kind == "group"
        val at = isGroup && atBot
        // 群聊里 @ 会在正文前面带 @机器人，和真实 QQ 里 @ 后发消息一致
        val content = if (at) "@机器人 " + raw else raw

        etMessage.text.clear()
        quoted = null
        val userMsg = Msg(
            "user",
            if (imgUri == null) {
                raw
            } else if (raw.isBlank()) {
                "📷"
            } else {
                "$raw 📷"
            },
            System.currentTimeMillis(),
            image = imgUri?.toString(),
        )
        c.messages.add(userMsg)
        tvThinking.visibility = View.VISIBLE
        tvThinking.text = "机器人正在思考…"
        renderChat()

        val event = QqMessageEvent(
            kind = c.kind,
            openid = c.openid,
            memberOpenid = c.openid,
            content = content,
            msgId = "test_msg_" + System.currentTimeMillis(),
            sender = c.nick,
            atMe = at,
            atOpenids = emptyList(),
            // 当条附件图 或 引用消息里的图（本地 uri 直通——测试模式跳过在线拉取，引用图生图靠这里）
            imageUrls = imgUri?.let { listOf(it.toString()) }
                ?: q?.image?.let { listOf(it) }
                ?: emptyList(),
            replyMsgId = q?.msgId ?: "",
        )
        if (imgUri != null) {
            pendingImageUri = null // 一次性附件：发完即清，后续命令靠会话图片记忆
            previewImg.visibility = View.GONE
            renderTags()
        }
        Logs.info("[测试] 发送（${if (isGroup) "群聊" else "私聊"}，${if (at) "@机器人" else "未@"}${if (q != null) "，引用一条消息" else ""}${if (imgUri != null) "，带图片" else ""}）：${raw.take(60)}")

        // 群聊未@也未引用且开了「群聊仅@回复」→ 引擎只接收不回复，先提示一下，免得以为坏了
        if (isGroup && Config.groupOnlyAtReply && !at && q == null) {
            c.messages.add(Msg("sys", "群聊仅@回复已开启：这条没@机器人也没引用，机器人只接收、不回复（会进知识库/内心思考）", System.currentTimeMillis()))
        }

        val future = worker.submit {
            try {
                engine.submitTestMessage(event)
            } catch (e: Exception) {
                Logs.err("[测试] 处理异常：${e.javaClass.simpleName}: ${e.message}")
            } finally {
                handler.post { tvThinking.visibility = View.GONE }
            }
        }
        handler.postDelayed({
            if (!future.isDone) {
                future.cancel(true)
                Logs.warn("[测试] 消息处理超时（120s），已取消")
                handler.post { tvThinking.visibility = View.GONE }
            }
        }, 120_000)
    }

    /** 引擎产生回复 → 落到对应会话的气泡里（按 convKey 匹配，避免串会话） */
    private fun onBotReply(convKey: String, text: String) {
        tvThinking.visibility = View.GONE
        val c = convs.firstOrNull { it.key == convKey }
        if (c == null) {
            Logs.warn("[测试] 收到回复但找不到会话：$convKey")
            return
        }
        // TTS 语音：🔊 voice://<路径>；机器人图片：🖼 image://<路径>（测试台落地后回传，气泡直接渲染）
        val voicePath = if (text.startsWith("🔊 voice://")) text.removePrefix("🔊 voice://") else null
        val imagePath = if (text.startsWith("🖼 image://")) text.removePrefix("🖼 image://") else null
        val display = when {
            voicePath != null -> "🔊 语音消息（点「▶ 播放语音」收听）"
            imagePath != null -> "🖼 图片消息"
            else -> text
        }
        c.messages.add(Msg("bot", display, System.currentTimeMillis(), "test_bot_" + System.currentTimeMillis(), media = voicePath, image = imagePath))
        if (current === c) renderChat()
    }

    private fun onBotThought(convKey: String, thought: String) {
        val c = convs.firstOrNull { it.key == convKey } ?: return
        c.messages.add(Msg("thought", thought, System.currentTimeMillis()))
        if (current === c) renderChat()
    }

    // ---------------- 小工具 ----------------

    private fun smallButton(t: String, weight: Float = 0f, onClick: () -> Unit): Button = Button(this).apply {
        text = t
        textSize = 12f
        isFocusable = true
        setOnClickListener { onClick() }
        if (weight > 0f) {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        }
        DpadFocus.highlight(this)
    }

    private fun tinyLabel(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.textMain))
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int): Int = resources.getColor(id, null)
}

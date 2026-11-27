package com.astrbot.lite.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.astrbot.lite.R
import com.astrbot.lite.core.ChatEngine
import com.astrbot.lite.core.Config
import com.astrbot.lite.core.Logs
import com.astrbot.lite.core.Persona
import com.astrbot.lite.core.PersonaManager
import com.astrbot.lite.core.QqBotClient
import com.astrbot.lite.service.BotService

/**
 * 配置向导：QQ 机器人 → AI 模型 → 人设卡
 */
class WizardActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var etAppId: EditText
    private lateinit var etSecret: EditText
    private lateinit var etApiBase: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModel: EditText
    private lateinit var spProvider: Spinner
    private lateinit var spPersona: Spinner
    private lateinit var spEnv: Spinner
    private lateinit var tvQqResult: TextView
    private lateinit var tvAiResult: TextView
    private lateinit var tvModelsResult: TextView

    /** 模型选择器（获取列表后出现，点一下即选用），运行时插到 tvModelsResult 下面，不用改布局文件 */
    private lateinit var modelPicker: LinearLayout
    private lateinit var modelList: ListView
    private lateinit var modelHint: TextView
    private var modelNames: List<String> = emptyList()

    private var providerInit = false
    private var envInit = false
    private var personas: List<Persona> = emptyList()

    /** E6：非空表示编辑指定账号（账号页「设置账号」传入 account_id）；空=首次配置/新建 */
    private var editAccountId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wizard)

        etAppId = findViewById(R.id.etAppId)
        etSecret = findViewById(R.id.etSecret)
        etApiBase = findViewById(R.id.etApiBase)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etApiKey = findViewById(R.id.etApiKey)
        findViewById<Button>(R.id.btnCopyApiKey).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@setOnClickListener
            cm.setPrimaryClip(ClipData.newPlainText("API Key", etApiKey.text.toString()))
            Toast.makeText(this, "已复制「API Key」到剪贴板", Toast.LENGTH_SHORT).show()
        }
        etModel = findViewById(R.id.etModel)
        spProvider = findViewById(R.id.spProvider)
        spPersona = findViewById(R.id.spPersona)
        spEnv = findViewById(R.id.spEnv)
        tvQqResult = findViewById(R.id.tvQqResult)
        tvAiResult = findViewById(R.id.tvAiResult)
        tvModelsResult = findViewById(R.id.tvModelsResult)
        setupModelPicker()

        // E6：编辑模式识别——账号页「设置账号」传入 account_id
        editAccountId = intent.getStringExtra("account_id") ?: ""
        val editing = editAccountId.isNotBlank()
        val editingAcc = if (editing) com.astrbot.lite.core.AccountManager.get(editAccountId) else null
        if (editing && editingAcc == null) {
            Toast.makeText(this, "账号不存在或已删除", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 回填已有配置：编辑模式回填该账号字段，否则回填全局配置
        if (editingAcc != null) {
            etAppId.setText(editingAcc.appId)
            etSecret.setText(editingAcc.secret)
            etApiBase.setText(editingAcc.apiBase)
            // 环境下拉：prod=0 / sandbox=1
            spEnv.setSelection(if (editingAcc.env == "sandbox") 1 else 0)
            // AI 配置是全局的，编辑账号时仍回填全局 AI 项
            etApiKey.setText(Config.aiApiKey)
            etModel.setText(Config.aiModel)
            // 按钮文案改为「保存」（布局无独立标题 id，跳过标题修改）
            try {
                findViewById<Button>(R.id.btnSave)?.text = "保存"
            } catch (_: Throwable) {}
            Logs.info("[向导] 编辑账号模式：${editingAcc.name}（$editAccountId）")
        } else {
            etAppId.setText(Config.qqAppId)
            etSecret.setText(Config.qqSecret)
            etApiBase.setText(Config.qqApiBase)
            etApiKey.setText(Config.aiApiKey)
            etModel.setText(Config.aiModel)
        }

        setupEnvSpinner()
        setupProviderSpinner()
        refreshPersonaSpinner()

        findViewById<Button>(R.id.btnTestQq).setOnClickListener { testQq() }
        findViewById<Button>(R.id.btnListModels).setOnClickListener { listModels() }
        findViewById<Button>(R.id.btnTestAi).setOnClickListener { testAi() }
        findViewById<Button>(R.id.btnNewPersona).setOnClickListener { newPersonaDialog() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAndStart() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }

        // 机顶盒 DPad：全页控件加焦点高亮 + 上下键沿焦点链移动并自动滚动。
        // （获取模型列表 / 测试AI 这些按钮此前既没有焦点高亮、也没有滚动链，遥控器上看不清焦点、也到不了底部；
        //   下拉框的"方向键离开"由焦点链统一处理，不再单独 escapeSpinner）
        val sv = findViewById<android.widget.ScrollView>(R.id.wizardScroll)
        DpadFocus.apply(sv)
        DpadFocus.chainScrollView(sv)
    }

    /** 在 tvModelsResult 下方插一个模型选择列表（点一下即填进模型框） */
    private fun setupModelPicker() {
        val parent = tvModelsResult.parent as? LinearLayout ?: return
        modelHint = TextView(this).apply {
            textSize = 12f
            setTextColor(resources.getColor(R.color.textSub, null))
            setPadding(0, dp(6), 0, dp(2))
        }
        modelList = ListView(this).apply {
            setBackgroundColor(resources.getColor(R.color.card, null))
            isFocusable = true
            // 触摸修复（1.7.59）：本列表嵌在向导 ScrollView 内——父级会在竖向滑动时抢走手势，
            // 若页面已无可滚余量（列表在底部）则"两边都不动"，表现为模型列表划不动。
            // 每次按下都声明手势归列表消费（disallow intercept），列表内部自己滚；方向键链不受影响。
            setOnTouchListener { v, _ ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
            setOnItemClickListener { _, _, position, _ ->
                val name = modelNames.getOrNull(position) ?: return@setOnItemClickListener
                etModel.setText(name)
                Toast.makeText(this@WizardActivity, "已选用模型：$name", Toast.LENGTH_SHORT).show()
                Logs.info("[向导] 已从列表选用模型：$name")
                showModelPicker(modelNames.joinToString("\n")) // 刷新"✓ 当前"标记
            }
        }
        modelPicker = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(modelHint)
            addView(
                modelList,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(180),
                ),
            )
        }
        parent.addView(modelPicker, parent.indexOfChild(tvModelsResult) + 1)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setupEnvSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("正式环境（默认）", "沙箱环境"))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spEnv.adapter = adapter
        // E6：编辑模式回填该账号的 env；新建/首次配置用全局 Config.qqEnv
        val envValue = if (editAccountId.isNotBlank()) {
            (com.astrbot.lite.core.AccountManager.get(editAccountId)?.env) ?: Config.qqEnv
        } else {
            Config.qqEnv
        }
        spEnv.setSelection(if (envValue == "sandbox") 1 else 0)
        spEnv.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!envInit) return
                Config.qqEnv = if (position == 1) "sandbox" else "prod"
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        envInit = true
    }

    private fun setupProviderSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, Config.PROVIDER_NAMES)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spProvider.adapter = adapter
        spProvider.setSelection(Config.aiProvider)
        spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!providerInit) return
                Config.aiProvider = position
                val base = Config.PROVIDER_BASE[position]
                val model = Config.PROVIDER_MODEL[position]
                if (base.isNotBlank()) etBaseUrl.setText(base)
                if (model.isNotBlank()) etModel.setText(model)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // 首次回填
        handler.post {
            providerInit = true
            etBaseUrl.setText(Config.aiBaseUrl.ifEmpty { Config.PROVIDER_BASE[Config.aiProvider] })
        }
    }

    private fun refreshPersonaSpinner() {
        personas = PersonaManager.list()
        val names = personas.map { it.name }.toTypedArray()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spPersona.adapter = adapter
        val idx = personas.indexOfFirst { it.id == Config.activePersonaId }.coerceAtLeast(0)
        spPersona.setSelection(idx)
    }

    private fun currentPersona(): Persona = personas.getOrNull(spPersona.selectedItemPosition) ?: PersonaManager.active()

    private fun saveFields() {
        // E6：编辑模式下不写全局 QQ 字段（只更新账号记录本身）；AI 配置始终全局
        if (editAccountId.isBlank()) {
            Config.qqAppId = etAppId.text.toString().trim()
            Config.qqSecret = etSecret.text.toString().trim()
            val base = etApiBase.text.toString().trim()
            if (base.isNotBlank()) Config.qqApiBase = base
            Config.qqEnv = if (spEnv.selectedItemPosition == 1) "sandbox" else "prod"
        }
        Config.aiBaseUrl = etBaseUrl.text.toString().trim()
        Config.aiApiKey = etApiKey.text.toString().trim()
        Config.aiModel = etModel.text.toString().trim()
        Config.aiProvider = spProvider.selectedItemPosition
    }

    /** E6：把当前表单的 QQ 字段写入指定账号（编辑模式） */
    private fun saveToAccount(id: String): Boolean {
        val old = com.astrbot.lite.core.AccountManager.get(id) ?: return false
        val newAppId = etAppId.text.toString().trim()
        val newSecret = etSecret.text.toString().trim()
        val newBase = etApiBase.text.toString().trim().ifBlank { old.apiBase }
        val newEnv = if (spEnv.selectedItemPosition == 1) "sandbox" else "prod"
        return com.astrbot.lite.core.AccountManager.update(
            id = id,
            name = old.name,
            appId = newAppId,
            secret = newSecret,
            apiBase = newBase,
            env = newEnv,
        )
    }

    private fun testQq() {
        val appId = etAppId.text.toString().trim()
        val secret = etSecret.text.toString().trim()
        val base = etApiBase.text.toString().trim().ifEmpty { Config.qqApiBase }
        tvQqResult.text = "正在获取..."
        Thread {
            try {
                val r = QqBotClient.testToken(appId, secret, base)
                handler.post { tvQqResult.text = r }
                Logs.info(r)
            } catch (e: Exception) {
                handler.post {
                    tvQqResult.setTextColor(resources.getColor(R.color.err, null))
                    tvQqResult.text = "❌ ${e.message}"
                }
                Logs.err("测试连接失败: ${e.message}")
            }
        }.start()
    }

    private fun testAi() {
        saveFields()
        tvAiResult.text = "正在测试..."
        Thread {
            try {
                val r = ChatEngine.testChat("你好，请只回复四个字：连接成功")
                handler.post {
                    tvAiResult.setTextColor(resources.getColor(R.color.ok, null))
                    tvAiResult.text = "✅ 模型回复：${r.take(200)}"
                }
                Logs.info("AI 测试成功: ${r.take(60)}")
            } catch (e: Exception) {
                handler.post {
                    tvAiResult.setTextColor(resources.getColor(R.color.err, null))
                    tvAiResult.text = "❌ ${e.message}"
                }
                Logs.err("AI 测试失败: ${e.message}")
            }
        }.start()
    }

    private fun listModels() {
        val baseUrl = etBaseUrl.text.toString().trim()
        val apiKey = etApiKey.text.toString().trim()
        if (baseUrl.isBlank()) {
            tvModelsResult.setTextColor(resources.getColor(R.color.textSub, null))
            tvModelsResult.text = "无（未填写 API 地址）"
            hideModelPicker()
            return
        }
        if (apiKey.isBlank()) {
            tvModelsResult.setTextColor(resources.getColor(R.color.err, null))
            tvModelsResult.text = "❌ 未填写 API Key，无法获取模型列表"
            hideModelPicker()
            return
        }
        tvModelsResult.setTextColor(resources.getColor(R.color.textSub, null))
        tvModelsResult.text = "正在获取..."
        hideModelPicker()
        Thread {
            try {
                val result = ChatEngine.listModels(baseUrl, apiKey)
                handler.post {
                    tvModelsResult.setTextColor(resources.getColor(R.color.textSub, null))
                    tvModelsResult.text = result
                    showModelPicker(result)
                }
            } catch (e: Exception) {
                handler.post {
                    tvModelsResult.setTextColor(resources.getColor(R.color.err, null))
                    tvModelsResult.text = "❌ ${e.message}"
                    hideModelPicker()
                }
            }
        }.start()
    }

    /** 把「获取模型列表」的结果渲染成可点的列表，点一下就填进模型框（比手打省事、也不会打错） */
    private fun showModelPicker(result: String) {
        modelNames = result.split("\n").map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("无") && !it.startsWith("获取失败") }
        if (modelNames.isEmpty()) {
            hideModelPicker()
            return
        }
        val cur = etModel.text.toString().trim()
        modelList.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            modelNames.map { if (it == cur) "$it　✓ 当前" else it },
        )
        modelHint.text = "点一个模型即可选用（共 ${modelNames.size} 个）"
        modelPicker.visibility = View.VISIBLE
    }

    private fun hideModelPicker() {
        if (::modelPicker.isInitialized) modelPicker.visibility = View.GONE
    }

    private fun newPersonaDialog() {
        val name = EditText(this).apply { hint = "人设名称（如：学姐）" }
        val prompt = EditText(this).apply {
            hint = "人设描述 / 系统提示词"
            minLines = 3
            maxLines = 6
            isSingleLine = false
        }
        val v = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(name)
            addView(
                prompt,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 16 },
            )
        }
        AlertDialog.Builder(this)
            .setTitle("新建人设卡")
            .setView(v)
            .setPositiveButton("保存") { _, _ ->
                val n = name.text.toString().trim()
                val p = prompt.text.toString().trim()
                if (n.isEmpty()) {
                    Toast.makeText(this, "请填写人设名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val persona = Persona(
                    id = PersonaManager.newPersonaId(),
                    name = n,
                    avatar = "🎭",
                    description = "",
                    prompt = p.ifEmpty { "你是$n。请用中文自然、友好地聊天。" },
                    greeting = "",
                    createdAt = System.currentTimeMillis(),
                )
                PersonaManager.save(persona)
                PersonaManager.setActive(persona.id)
                refreshPersonaSpinner()
                Toast.makeText(this, "已创建并启用“$n”", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveAndStart() {
        if (editAccountId.isNotBlank()) {
            // E6：编辑模式——只更新该账号，不启动服务/不跳主页
            saveFields() // 仍保存 AI 配置（全局）
            val ok = saveToAccount(editAccountId)
            if (ok) {
                Toast.makeText(this, "账号已更新", Toast.LENGTH_SHORT).show()
                Logs.info("[向导] 账号已更新：$editAccountId")
            } else {
                Toast.makeText(this, "保存失败：账号不存在", Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }
        saveFields()
        currentPersona().let { PersonaManager.setActive(it.id) }
        Config.initialized = true
        Config.botEnabled = true
        BotService.start(this)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

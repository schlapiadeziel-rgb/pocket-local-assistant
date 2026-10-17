package cn.pocketai.assistant

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cn.pocketai.assistant.core.ModuleRegistry

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val ink = Color.rgb(25, 42, 39)
    private val muted = Color.rgb(98, 113, 109)
    private val accent = Color.rgb(23, 92, 86)
    private val pageColor = Color.rgb(243, 245, 242)
    private lateinit var body: LinearLayout
    private lateinit var stateLabel: TextView
    private var tab = 0
    private var modelStatus: TextView? = null
    private var logsView: TextView? = null
    private var updateStatus: TextView? = null
    private var question: EditText? = null
    private var answerView: TextView? = null
    private var latestAnswer = "回答会在这里显示。生成完全在手机上进行。"
    private var draftQuestion = ""
    private var includeScreen = false
    private val refresh = object : Runnable {
        override fun run() {
            stateLabel.text = if (AssistantState.service == null) "屏幕服务未开启" else if (AssistantState.running) "正在观察 · ${AssistantState.status}" else AssistantState.status
            modelStatus?.text = modelDescription()
            updateStatus?.text = AppUpdater.status
            logsView?.text = "识别 ${AssistantState.frameCount} 帧 · 点击 ${AssistantState.tapCount} 次\n\n${AssistantState.logs()}"
            main.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildShell(); main.post(refresh)
    }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onResume() { super.onResume(); if (::body.isInitialized && tab == 0) render() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 18) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(text: String, size: Float = 14f, color: Int = ink, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun gap(parent: LinearLayout, size: Int = 12) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(size))) }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14f
        setTextColor(if (primary) Color.WHITE else accent)
        background = rounded(if (primary) accent else Color.rgb(231, 239, 234), 12)
        minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(12), dp(5), dp(12), dp(5)); setOnClickListener { action() }
        contentDescription = text
    }
    private fun rowButtons(parent: LinearLayout, vararg values: Pair<String, () -> Unit>) {
        val row = LinearLayout(this)
        values.forEachIndexed { i, pair ->
            row.addView(button(pair.first, i == 0, pair.second), LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        parent.addView(row)
    }
    private fun card(parent: LinearLayout, block: (LinearLayout) -> Unit) {
        val card = column().apply { setPadding(dp(18), dp(18), dp(18), dp(18)); background = rounded(Color.WHITE) }
        block(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }
    private fun buildShell() {
        val shell = column().apply { setBackgroundColor(pageColor) }
        shell.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val header = column().apply { setPadding(dp(22), dp(18), dp(22), dp(12)) }
        header.addView(label("随身 AI", 25f, bold = true))
        header.addView(label("LOCAL INTELLIGENCE  /  你的手机，你的助手", 10f, muted))
        gap(header, 10)
        stateLabel = label("准备就绪", 12f, accent)
        header.addView(stateLabel)
        shell.addView(header)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = column().apply { setPadding(dp(18), dp(6), dp(18), dp(16)) }
        scroll.addView(body)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { setPadding(dp(12), dp(8), dp(12), dp(12)) }
        listOf("功能", "本地 AI", "模型", "记录").forEachIndexed { index, title ->
            nav.addView(button(title) {
                draftQuestion = question?.text?.toString() ?: draftQuestion
                tab = index; render(); scroll.scrollTo(0, 0)
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (index > 0) leftMargin = dp(5) })
        }
        shell.addView(nav)
        setContentView(shell); render()
    }
    private fun render() {
        body.removeAllViews(); modelStatus = null; logsView = null; question = null; answerView = null; updateStatus = null
        when (tab) { 0 -> features(); 1 -> chat(); 2 -> models(); else -> logs() }
    }
    private fun features() {
        body.addView(label("看懂屏幕，\n做好眼前的事。", 30f, bold = true)); gap(body, 8)
        body.addView(label("识别与推理留在本机。你选择应用，也掌握每一次开始与停止。", 14f, muted)); gap(body, 20)
        val prefs = AssistantState.prefs(this)
        card(body) { c ->
            c.addView(label("01  选择观察对象", 16f, bold = true)); gap(c, 8)
            c.addView(label(prefs.getString("targetLabel", "尚未选择应用") ?: "", 14f, muted)); gap(c)
            rowButtons(c, "选择应用" to { selectApp() }, "打开应用" to { launchTarget() })
        }
        card(body) { c ->
            c.addView(label("02  开启屏幕服务", 16f, bold = true)); gap(c, 8)
            c.addView(label("首次使用：在系统的无障碍设置中开启「随身 AI 屏幕助手」。仅观察你选中的应用。", 13f, muted)); gap(c)
            c.addView(button(if (AssistantState.service == null) "前往系统设置" else "服务已连接 · 查看设置") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        ModuleRegistry.available().forEach { module ->
            card(body) { c ->
                c.addView(label(module.title, 18f, bold = true)); gap(c, 8)
                c.addView(label(module.description, 13f, muted)); gap(c)
                c.addView(button("开始${module.title}", true) {
                    val service = AssistantState.service
                    if (service == null) { message("请先在系统设置中开启屏幕助手服务"); return@button }
                    if (prefs.getString("target", "").isNullOrBlank()) { selectApp(); return@button }
                    service.startSession(module.id); launchTarget()
                }, LinearLayout.LayoutParams(-1, dp(48)))
            }
        }
        card(body) { c ->
            rowButtons(c, "暂停观察" to { AssistantState.service?.pause() }, "停止助手" to { AssistantState.service?.stopSession() })
            gap(c)
            c.addView(button("切换手机媒体静音") { AssistantState.service?.toggleMute() ?: message("请先开启屏幕服务") }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        body.addView(label("课程模块需亮屏、课程应用在前台，并保持播放时间与下一节按钮可见。它不加速学习进度；题目与验证需本人处理。其他功能可通过模块接口继续加入。", 12f, muted))
    }
    private fun selectApp() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString() }
        if (apps.isEmpty()) { message("未找到可启动应用"); return }
        AlertDialog.Builder(this).setTitle("选择你要观察的应用")
            .setItems(apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()) { _, which ->
                AssistantState.service?.stopSession("观察对象已更改，请重新开始")
                val selected = apps[which]
                AssistantState.prefs(this).edit().putString("target", selected.activityInfo.packageName)
                    .putString("targetLabel", selected.loadLabel(packageManager).toString()).apply()
                render()
            }.setNegativeButton("取消", null).show()
    }
    private fun launchTarget() {
        val target = AssistantState.prefs(this).getString("target", "") ?: ""
        val intent = packageManager.getLaunchIntentForPackage(target)
        if (intent == null) { message("请先选择已安装的应用"); return }
        startActivity(intent)
    }
    private fun chat() {
        body.addView(label("本地 AI", 28f, bold = true)); gap(body, 8)
        body.addView(label("问答、整理和总结，均在手机上完成。长文本会截取开头，以控制内存和等待时间。", 14f, muted)); gap(body, 18)
        card(body) { c ->
            question = EditText(this).apply {
                hint = "输入你想问的问题"; textSize = 15f; minLines = 3; maxLines = 8; gravity = Gravity.TOP
                setText(draftQuestion); setTextColor(ink)
            }
            c.addView(question, LinearLayout.LayoutParams(-1, -2)); gap(c)
            val screenButton = button(if (includeScreen) "已附带最近识别文字 · 点击取消" else "附带最近识别的屏幕文字") {
                draftQuestion = question?.text?.toString() ?: ""
                if (AssistantState.lastScreenText.isBlank()) { message("请先在所选应用中运行屏幕观察，再返回这里"); return@button }
                includeScreen = !includeScreen; render()
            }
            c.addView(screenButton, LinearLayout.LayoutParams(-1, dp(48))); gap(c)
            c.addView(button("在手机上生成回答", true) { ask() }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        card(body) { c ->
            c.addView(label("回答", 16f, bold = true)); gap(c)
            answerView = label(latestAnswer, 15f).apply { setTextIsSelectable(true) }
            c.addView(answerView)
        }
        body.addView(label("首版用本地 OCR 提取屏幕文字，再由文字模型理解；不宣称具备完整图像推理。AI 结果可能有误，也不会代你点击或提交。", 12f, muted))
    }
    private fun ask() {
        val prompt = question?.text?.toString()?.trim().orEmpty()
        if (prompt.isBlank()) { message("请先输入问题"); return }
        draftQuestion = prompt
        latestAnswer = "正在本机生成，请稍候……"; answerView?.text = latestAnswer
        val screen = if (includeScreen) AssistantState.lastScreenText.takeIf { it.isNotBlank() } else null
        LocalBrain.ask(applicationContext, prompt, screen) { result ->
            latestAnswer = result
            if (!isDestroyed) answerView?.text = result
        }
    }
    private fun modelDescription(): String {
        val file = LocalBrain.modelFile(this)
        return "${LocalBrain.status}\n${if (file.isFile) "模型文件：${file.length() / 1024 / 1024} MB" else "尚未安装大语言模型"}"
    }
    private fun models() {
        body.addView(label("模型与设备", 28f, bold = true)); gap(body, 18)
        val info = ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        card(body) { c ->
            c.addView(label("${Build.MANUFACTURER} ${Build.MODEL}", 18f, bold = true)); gap(c, 8)
            c.addView(label("Android ${Build.VERSION.RELEASE} · 内存 ${info.totalMem / 1024 / 1024 / 1024} GB\n当前可用 ${info.availMem / 1024 / 1024} MB\nARM64 · CPU 双线程 · 观察间隔 4 秒", 13f, muted))
        }
        card(body) { c ->
            c.addView(label(LocalBrain.MODEL_NAME, 18f, bold = true)); gap(c)
            c.addView(label("推荐文件约 585 MB。首次下载需要联网，之后识别和推理可离线运行。下载会校验固定 SHA-256。", 13f, muted)); gap(c)
            modelStatus = label(modelDescription(), 13f, accent); c.addView(modelStatus); gap(c)
            c.addView(button("下载推荐模型", true) {
                AlertDialog.Builder(this).setTitle("下载约 585 MB 模型")
                    .setMessage("从 LiteRT Community 的模型仓库下载到本机。建议使用 Wi-Fi，并保持本应用前台。下载完成后不会再为推理联网。")
                    .setPositiveButton("开始下载") { _, _ -> LocalBrain.download(applicationContext) { message(it) } }
                    .setNegativeButton("取消", null).show()
            }, LinearLayout.LayoutParams(-1, dp(48))); gap(c)
            rowButtons(c, "导入模型" to {
                if (LocalBrain.isBusy()) { message("模型正忙，请稍后重试"); return@to }
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 101)
            }, "取消下载" to { LocalBrain.cancelDownload() })
            gap(c)
            rowButtons(c, "加载并自检" to { LocalBrain.load(applicationContext) { message(it) } }, "释放模型内存" to { LocalBrain.unload { message(it) } })
        }
        card(body) { c ->
            c.addView(label("屏幕文字模型已内置", 16f, bold = true)); gap(c, 8)
            c.addView(label("中文 OCR 随 APK 安装，无需额外下载。课程模块不依赖大模型猜测完成状态。导入模型需为兼容的 .litertlm 格式，不能直接使用 GGUF。", 13f, muted))
        }
        card(body) { c ->
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            c.addView(label("应用更新 · $version", 18f, bold = true)); gap(c, 8)
            c.addView(label("从项目的 GitHub 正式发布页获取更新。下载后校验 SHA-256、包名、版本和安装签名，再由系统确认安装。", 13f, muted)); gap(c)
            updateStatus = label(AppUpdater.status, 13f, accent); c.addView(updateStatus); gap(c)
            rowButtons(c, "检查更新" to { AppUpdater.check(applicationContext) { message(it) } }, "下载更新" to { AppUpdater.download(applicationContext) { message(it) } })
            gap(c)
            rowButtons(c, "安装更新" to { AppUpdater.install(this)?.let { message(it) } }, "发布页面" to { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cn.pocketai.assistant.core.UpdatePolicy.PAGE))) })
        }
        body.addView(label("iQOO 8 Pro 首次使用建议先加载自检，再试短文本。持续运行会耗电、发热；本版本尚未完成该机型的实机验证。", 12f, muted))
    }
    @Deprecated("Platform compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 101 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        var name = ""
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)
        }
        if (!name.endsWith(".litertlm", true)) { message("请选择 .litertlm 模型文件"); return }
        LocalBrain.importModel(applicationContext, uri) { message(it) }
    }
    private fun logs() {
        body.addView(label("运行记录", 28f, bold = true)); gap(body, 8)
        body.addView(label("仅保留本次运行的状态记录。屏幕图像不落盘、不上传。", 13f, muted)); gap(body, 18)
        card(body) { c ->
            logsView = label(AssistantState.logs(), 13f).apply { setTextIsSelectable(true) }
            c.addView(logsView)
        }
        card(body) { c ->
            c.addView(button("查看最近识别文字") {
                AlertDialog.Builder(this).setTitle("最近识别文字（仅内存）")
                    .setMessage(AssistantState.lastScreenText.ifBlank { "暂无识别结果" }).setPositiveButton("关闭", null).show()
            }, LinearLayout.LayoutParams(-1, dp(48))); gap(c)
            c.addView(button("清除识别文字") { AssistantState.clearScreen(); message("已清除内存中的屏幕文字") }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        body.addView(label("隐私：可见屏幕文字按你的授权在本机处理，不保存屏幕图像，不注入网页脚本。GitHub 更新、模型下载需要联网。ML Kit SDK 可能向 Google 发送性能和使用指标；图像、文字和识别结果不随指标上传。源码与模型许可证分开提供。", 12f, muted))
    }
    private fun message(text: String) {
        if (!isFinishing && !isDestroyed) AlertDialog.Builder(this).setMessage(text).setPositiveButton("知道了", null).show()
    }
}

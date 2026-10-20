package cn.pocketai.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import cn.pocketai.assistant.core.AssistantModule
import cn.pocketai.assistant.core.Box
import cn.pocketai.assistant.core.Decision
import cn.pocketai.assistant.core.ModuleRegistry
import cn.pocketai.assistant.core.ScreenFrame
import cn.pocketai.assistant.core.ScreenLine
import cn.pocketai.assistant.core.TextAccent
import cn.pocketai.assistant.core.RowMarkerVision
import cn.pocketai.assistant.core.RowMark
import cn.pocketai.assistant.core.ScreenMarker
import cn.pocketai.assistant.core.PracticeAction
import cn.pocketai.assistant.core.PracticeQuizController
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executors

class VisionService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private var module: AssistantModule = ModuleRegistry.create("observe")
    private val practice = PracticeQuizController()
    private var overlay: LinearLayout? = null
    private var caption: TextView? = null
    private var pauseButton: Button? = null
    private var muteButton: Button? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var dragging = false
    private var scanBusy = false
    private var sessionId = 0
    private var screenshotFailures = 0
    private var originalVolume: Int? = null
    private var lastReason = ""
    private var lastForeground = ""
    private var capturedAt = 0L
    private var captureWidth = 0
    private var captureHeight = 0
    private val interval = 4000L
    private val loop = object : Runnable {
        override fun run() {
            if (AssistantState.running) scan()
            main.postDelayed(this, interval)
        }
    }
    override fun onServiceConnected() {
        // Decorative status ImageViews have no spoken label; request their visible bounds too.
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        AssistantState.service = this
        AssistantState.log("屏幕服务已连接，等待手动开始")
        main.post(loop)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (AssistantState.running && event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val foreground = foregroundPackage()
            val target = AssistantState.prefs(this).getString("target", "") ?: ""
            if (foreground != lastForeground && foreground != target) {
                sessionId++; module.reset(); practice.reset()
            }
            lastForeground = foreground
            if (foreground.isNotEmpty() && foreground != target && foreground != packageName) {
                AssistantState.clearScreen()
                // System permission dialogs and other apps must never receive our gestures.
                lastReason = "当前不是所选应用，等待返回"
                updateOverlay()
            }
        }
    }
    override fun onInterrupt() { pause("无障碍服务被系统中断") }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sessionId++; module.reset(); practice.reset()
        overlay?.post { positionOverlay() }
    }
    override fun onDestroy() {
        stopSession("屏幕服务已关闭")
        main.removeCallbacksAndMessages(null)
        captureExecutor.shutdownNow(); recognizer.close()
        if (AssistantState.service === this) AssistantState.service = null
        super.onDestroy()
    }
    private fun foregroundPackage(): String = rootInActiveWindow?.packageName?.toString() ?: ""
    fun prepareSession(moduleId: String) {
        val target = AssistantState.prefs(this).getString("target", "") ?: ""
        if (target.isBlank() || target == packageName) { AssistantState.log("请先选择要观察的应用"); return }
        sessionId++; AssistantState.running = false; practice.reset()
        module = ModuleRegistry.create(moduleId); module.reset()
        AssistantState.frameCount = 0; AssistantState.tapCount = 0; AssistantState.clearScreen()
        AssistantState.log("${module.title}已准备；进入课程并播放后，点浮窗「开始」")
        showOverlay(); overlay?.keepScreenOn = false
    }
    fun startSession(moduleId: String) {
        val target = AssistantState.prefs(this).getString("target", "") ?: ""
        if (target.isBlank() || target == packageName) { AssistantState.log("请先选择要观察的应用"); return }
        module = ModuleRegistry.create(moduleId); module.reset(); practice.reset(); sessionId++
        AssistantState.running = true; screenshotFailures = 0; lastReason = ""
        AssistantState.frameCount = 0; AssistantState.tapCount = 0; AssistantState.clearScreen()
        showOverlay(); AssistantState.log("已开始${module.title}；请打开所选应用并手动开始播放")
    }
    fun pause(reason: String = "已暂停") {
        sessionId++; AssistantState.running = false; practice.reset()
        AssistantState.log(reason); updateOverlay()
        overlay?.keepScreenOn = false
    }
    fun stopSession(reason: String = "已停止") {
        pause(reason); restoreVolume(); AssistantState.clearScreen()
        overlay?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: IllegalArgumentException) {} }
        overlay = null; caption = null; pauseButton = null; muteButton = null; overlayParams = null; dragging = false
    }
    private fun scan() {
        if (scanBusy || dragging) return
        if ((getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { pause("手机已锁屏，观察已暂停"); return }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) { pause("手机温度偏高，已暂停以降低负载"); return }
        val target = AssistantState.prefs(this).getString("target", "") ?: ""
        if (foregroundPackage() != target) {
            if (foregroundPackage() != packageName) AssistantState.clearScreen()
            if (lastReason != "请返回所选应用，当前不执行操作") { lastReason = "请返回所选应用，当前不执行操作"; AssistantState.log(lastReason) }
            return
        }
        scanBusy = true
        val generation = sessionId
        capturedAt = SystemClock.elapsedRealtime()
        try { takeScreenshot(Display.DEFAULT_DISPLAY, captureExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                val bitmap = try { hardware?.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware?.recycle(); buffer.close() }
                main.post {
                    if (bitmap == null || !isCurrent(generation, target)) { bitmap?.recycle(); scanBusy = false; return@post }
                    recognize(bitmap, generation, target)
                }
            }
            override fun onFailure(errorCode: Int) {
                main.post {
                    scanBusy = false
                    if (!isCurrent(generation, target)) return@post
                    screenshotFailures++
                    if (screenshotFailures >= 3) pause("屏幕无法读取（代码 $errorCode），可能是受保护画面，请手动操作")
                }
            }
        }) } catch (_: SecurityException) { scanBusy = false; pause("截图权限已失效，请重新检查屏幕服务") }
    }
    private fun isCurrent(generation: Int, target: String) = generation == sessionId && !dragging && AssistantState.running && foregroundPackage() == target
    private fun recognize(original: Bitmap, generation: Int, target: String) {
        val ratio = minOf(1f, 1600f / maxOf(original.width, original.height))
        val image = if (ratio < 1f) Bitmap.createScaledBitmap(original, (original.width * ratio).toInt(), (original.height * ratio).toInt(), true) else original
        val width = original.width; val height = original.height
        captureWidth = width; captureHeight = height
        val overlayBounds = Rect()
        overlay?.let { panel ->
            // getGlobalVisibleRect uses this overlay window's origin, while OCR boxes use display coordinates.
            val location = IntArray(2)
            panel.getLocationOnScreen(location)
            overlayBounds.set(location[0], location[1], location[0] + panel.width, location[1] + panel.height)
        }
        val icons = visibleRowIcons(target)
        val markers = icons.mapNotNull { rect ->
            if (Rect.intersects(overlayBounds, rect) || rect.left < 0 || rect.top < 0 || rect.right > width || rect.bottom > height) return@mapNotNull null
            val pixels = IntArray(rect.width() * rect.height())
            original.getPixels(pixels, 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
            ScreenMarker(RowMarkerVision.classify(rect.width(), rect.height(), pixels), Box(rect.left, rect.top, rect.right, rect.bottom))
        }
        recognizer.process(InputImage.fromBitmap(image, 0))
            .addOnSuccessListener { result ->
                if (!isCurrent(generation, target)) return@addOnSuccessListener
                screenshotFailures = 0
                AssistantState.directoryStatus = if (target == "com.able.wisdomtree")
                    "可见图标 ${icons.size} · 已完成 ${markers.count { it.kind == RowMark.COMPLETED }} · 待播 ${markers.count { it.kind == RowMark.PLAYABLE }} · 播放中 ${markers.count { it.kind == RowMark.PLAYING }} · 不明 ${markers.count { it.kind == RowMark.UNKNOWN }} · 遮挡或越界 ${icons.size - markers.size}"
                else "当前应用不使用目录图标识别"
                val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val rect = line.boundingBox ?: return@mapNotNull null
                    val box = Box((rect.left / ratio).toInt(), (rect.top / ratio).toInt(), (rect.right / ratio).toInt(), (rect.bottom / ratio).toInt())
                    if (Rect.intersects(overlayBounds, Rect(box.left, box.top, box.right, box.bottom))) return@mapNotNull null
                    ScreenLine(line.text, box, hasGreenText(image, rect))
                }
                val frame = ScreenFrame(lines, width, height, target, SystemClock.elapsedRealtime(), markers)
                AssistantState.lastScreenText = frame.text; AssistantState.recognizedLines = lines.size; AssistantState.frameCount++
                val autoPractice = module.id != "observe" && AssistantState.prefs(this).getBoolean("autoPractice", false)
                val action = if (autoPractice) practice.observe(frame) else null
                val decision = when (action) {
                    is PracticeAction.Solve -> {
                        module.reset()
                        if (!LocalBrain.modelFile(this).isFile) {
                            pause("请先安装本地答题模型"); return@addOnSuccessListener
                        }
                        if (LocalBrain.isBusy()) {
                            pause("本地模型正忙，请稍后重新开始"); return@addOnSuccessListener
                        }
                        LocalBrain.solvePractice(applicationContext, action.question.prompt()) { answer ->
                            if (isCurrent(generation, target)) practice.provideAnswer(action.request, answer)
                        }
                        Decision.Wait("本地模型正在解答随堂练习")
                    }
                    is PracticeAction.Observe -> { module.reset(); action.decision }
                    null -> module.observe(frame)
                }
                when (decision) {
                    is Decision.Pause -> pause(decision.reason)
                    is Decision.Wait -> { if (decision.reason != lastReason) { lastReason = decision.reason; AssistantState.log(decision.reason) } }
                    is Decision.Tap -> {
                        if (!isCurrent(generation, target)) return@addOnSuccessListener
                        tap(decision, generation, target)
                    }
                }
                updateOverlay()
            }
            .addOnFailureListener { if (generation == sessionId) pause("文字识别失败，请调整页面后重试") }
            .addOnCompleteListener {
                if (image !== original) image.recycle()
                original.recycle(); scanBusy = false
            }
    }
    @Suppress("DEPRECATION")
    private fun visibleRowIcons(target: String): List<Rect> {
        if (target != "com.able.wisdomtree") return emptyList()
        val root = rootInActiveWindow ?: return emptyList()
        val nodes = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>(); nodes.add(root)
        val result = mutableListOf<Rect>(); var visited = 0
        while (nodes.isNotEmpty() && visited++ < 1200) {
            val node = nodes.removeFirst()
            if (node.isVisibleToUser && node.packageName?.toString() == target && node.viewIdResourceName == "$target:id/ico") {
                val rect = Rect(); node.getBoundsInScreen(rect)
                if (rect.width() in 8..512 && rect.height() in 8..512) result += rect
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { nodes.add(it) }
            node.recycle()
        }
        nodes.forEach { it.recycle() }
        return result
    }
    private fun hasGreenText(image: Bitmap, box: Rect): Boolean {
        var green = 0; var sampled = 0
        val step = maxOf(1, minOf(box.width(), box.height()) / 18)
        for (y in box.top.coerceAtLeast(0) until box.bottom.coerceAtMost(image.height) step step)
            for (x in box.left.coerceAtLeast(0) until box.right.coerceAtMost(image.width) step step) {
                sampled++; if (TextAccent.green(image.getPixel(x, y))) green++
            }
        return green >= 5 && green * 100 >= sampled * 3
    }
    private fun tap(decision: Decision.Tap, generation: Int, target: String) {
        // Confirm immediately before dispatch; models never provide coordinates or invoke this method.
        if (!isCurrent(generation, target)) return
        val screen = (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
        if (SystemClock.elapsedRealtime() - capturedAt > 8000 || screen.width() != captureWidth || screen.height() != captureHeight) {
            module.reset(); AssistantState.log("画面已过期或方向改变，重新观察后再操作"); return
        }
        val path = Path().apply { moveTo(decision.box.x.toFloat(), decision.box.y.toFloat()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (generation == sessionId) { AssistantState.tapCount++; AssistantState.log(decision.reason) }
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (generation == sessionId) pause("触控被系统取消，请手动检查")
            }
        }, main)
        if (!accepted) pause("系统未接受点击，请检查无障碍权限")
    }
    fun toggleMute() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        try {
            if (originalVolume == null) {
                originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                AssistantState.log("手机媒体已静音，停止助手时尝试恢复原音量")
            } else { restoreVolume(); AssistantState.log("已退出静音") }
        } catch (_: SecurityException) { originalVolume = null; AssistantState.log("系统不允许调整媒体音量，请使用音量键") }
        updateOverlay()
    }
    private fun restoreVolume() {
        val previous = originalVolume ?: return
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        try { if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) audio.setStreamVolume(AudioManager.STREAM_MUSIC, previous, 0) }
        catch (_: SecurityException) {} finally { originalVolume = null }
    }
    private fun showOverlay() {
        if (overlay != null) { updateOverlay(); return }
        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8); setBackgroundColor(Color.rgb(23, 65, 61)); alpha = 0.94f
        }
        caption = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f; maxLines = 3; setTypeface(null, Typeface.BOLD)
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            contentDescription = "状态与浮窗拖动区域"
        }
        panel.addView(caption, LinearLayout.LayoutParams((230 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        val buttons = LinearLayout(this)
        pauseButton = Button(this).apply {
            textSize = 12f
            setOnClickListener { if (AssistantState.running) pause() else startSession(module.id) }
        }
        buttons.addView(pauseButton, LinearLayout.LayoutParams(0, (45 * density).toInt(), 1f))
        muteButton = Button(this).apply { text = "静音"; textSize = 12f; setOnClickListener { toggleMute() } }
        buttons.addView(muteButton, LinearLayout.LayoutParams(0, (45 * density).toInt(), 1f))
        buttons.addView(Button(this).apply { text = "停止"; textSize = 12f; setOnClickListener { stopSession() } }, LinearLayout.LayoutParams(0, (45 * density).toInt(), 1f))
        panel.addView(buttons)
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = AssistantState.prefs(this@VisionService).getInt("overlayX", resources.displayMetrics.widthPixels - (240 * density).toInt())
                y = AssistantState.prefs(this@VisionService).getInt("overlayY", (48 * density).toInt())
            }
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(panel, params)
        overlay = panel; overlayParams = params
        installDragHandle(caption!!)
        panel.post { positionOverlay() }
        updateOverlay()
    }
    private fun positionOverlay() {
        val panel = overlay ?: return
        val params = overlayParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = wm.currentWindowMetrics
        val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        params.x = params.x.coerceIn(0, (metrics.bounds.width() - bars.left - bars.right - panel.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.bounds.height() - bars.top - bars.bottom - panel.height).coerceAtLeast(0))
        wm.updateViewLayout(panel, params)
    }
    private fun installDragHandle(handle: TextView) {
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0; var moved = false
        val threshold = ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnClickListener { AssistantState.log("拖动浮窗顶部可调整位置"); updateOverlay() }
        handle.setOnTouchListener { view, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y
                    moved = false; dragging = true
                    // Invalidate the screenshot before moving its exclusion mask.
                    sessionId++; module.reset(); practice.reset()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > threshold || kotlin.math.abs(dy) > threshold) moved = true
                    if (moved) { params.x = originX + dx; params.y = originY + dy; positionOverlay() }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    if (moved) AssistantState.prefs(this).edit().putInt("overlayX", params.x).putInt("overlayY", params.y).apply()
                    else if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    true
                }
                else -> false
            }
        }
    }
    fun updateOverlay() {
        val text = "随身 AI · 拖动顶部可移动\n${AssistantState.status}"
        if (caption?.text?.toString() != text) caption?.text = text
        val action = if (AssistantState.running) "暂停" else "开始"
        if (pauseButton?.text?.toString() != action) pauseButton?.text = action
        val mute = if (originalVolume == null) "静音" else "恢复"
        if (muteButton?.text?.toString() != mute) muteButton?.text = mute
        overlay?.keepScreenOn = AssistantState.running
    }
}

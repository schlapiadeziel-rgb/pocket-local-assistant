package cn.pocketai.assistant.core

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val x get() = (left + right) / 2
    val y get() = (top + bottom) / 2
    fun inside(width: Int, height: Int) = left >= 0 && top >= 0 && right <= width && bottom <= height && right > left && bottom > top
}
data class ScreenLine(val text: String, val box: Box)
data class ScreenFrame(val lines: List<ScreenLine>, val width: Int, val height: Int, val packageName: String, val at: Long) {
    val text get() = lines.joinToString("\n") { it.text }
}
sealed class Decision {
    data class Wait(val reason: String) : Decision()
    data class Pause(val reason: String) : Decision()
    data class Tap(val box: Box, val reason: String) : Decision()
}
interface AssistantModule {
    val id: String
    val title: String
    val description: String
    fun reset()
    fun observe(frame: ScreenFrame): Decision
}

/** OCR text is untrusted content. It never grants permission or becomes model instructions. */
object ScreenGuard {
    private val blockers = listOf("异常脚本", "安全验证", "滑块", "验证码", "人脸识别", "身份验证", "登录已过期", "重新登录", "同时播放多个", "考试中", "开始考试", "提交试卷", "支付密码", "确认支付", "转账", "单选题", "多选题", "判断题", "请作答", "随堂测验", "随堂测试", "课中测试", "给你出题", "提交答案")
    fun blocked(text: String): String? {
        val normalized = text.replace(Regex("\\s+"), "")
        return blockers.firstOrNull { normalized.contains(it) }
    }
}

class CourseModule : AssistantModule {
    override val id = "course"
    override val title = "课程播放辅助"
    override val description = "观察真实播放时间；播完后点击可见的下一节。题目、验证和异常提示会暂停。"
    private var lastProgress = -1
    private var lastTotal = -1
    private var advanced = false
    private var endedFrames = 0
    private var lastTap = Long.MIN_VALUE
    private var awaitingNewLesson = false
    private var previousEnd = -1
    private var endedTarget: Box? = null
    override fun reset() {
        lastProgress = -1; lastTotal = -1; advanced = false; endedFrames = 0
        lastTap = Long.MIN_VALUE; awaitingNewLesson = false; previousEnd = -1; endedTarget = null
    }
    private fun seconds(s: String): Int? {
        val p = s.trim().split(':').map { it.toIntOrNull() ?: return null }
        if (p.size !in 2..3 || p.drop(1).any { it !in 0..59 }) return null
        return if (p.size == 2) p[0] * 60 + p[1] else p[0] * 3600 + p[1] * 60 + p[2]
    }
    private val clock = Regex("(\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*[/／]\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?)")
    override fun observe(frame: ScreenFrame): Decision {
        ScreenGuard.blocked(frame.text)?.let { return Decision.Pause("检测到「$it」，请手动处理后重新开始") }
        val match = clock.find(frame.text)
        val position = match?.groupValues?.get(1)?.let(::seconds)
        val total = match?.groupValues?.get(2)?.let(::seconds)
        if (position != null && total != null && total > 0 && position <= total) {
            if (awaitingNewLesson) {
                if (position < previousEnd - 2 || total != lastTotal) {
                    awaitingNewLesson = false; advanced = false; lastProgress = -1; endedFrames = 0
                } else if (frame.at - lastTap > 30_000) {
                    return Decision.Pause("点击下一节后未确认切换，请手动检查")
                } else return Decision.Wait("等待下一节开始，暂不重复点击")
            }
            if (total == lastTotal && position > lastProgress && lastProgress >= 0 && position - lastProgress <= 30) advanced = true
            if (total != lastTotal || position < lastProgress) { advanced = false; endedFrames = 0 }
            lastProgress = position; lastTotal = total
        }
        if (awaitingNewLesson) {
            if (frame.at - lastTap > 30_000) return Decision.Pause("未确认下一节开始，请手动检查")
            return Decision.Wait("等待下一节加载")
        }
        val explicitEnd = frame.lines.any { it.text.replace(" ", "") in setOf("播放结束", "本节视频已播放完毕", "本节学习已完成") }
        // Never use a directory's generic 已完成 or a model guess as completion evidence.
        val ended = explicitEnd || (advanced && position != null && total != null && position == total && total > 0)
        val targets = frame.lines.filter {
            it.text.replace(Regex("\\s+"), "") in setOf("下一节", "下一集", "下一个视频") && it.box.inside(frame.width, frame.height)
        }
        val candidate = targets.singleOrNull()?.box
        val old = endedTarget
        val stable = candidate != null && old != null && kotlin.math.abs(candidate.x - old.x) <= 24 && kotlin.math.abs(candidate.y - old.y) <= 24
        endedFrames = if (!ended || candidate == null) 0 else if (stable) endedFrames + 1 else 1
        endedTarget = if (ended) candidate else null
        if (ended && targets.size != 1) return Decision.Wait("已识别播放结束，等待唯一可见的下一节按钮")
        if (endedFrames < 2) return Decision.Wait(if (position != null) "观察播放 $position / $total 秒" else "观察中：请让播放时间和下一节按钮可见")
        if (lastTap != Long.MIN_VALUE && frame.at - lastTap < 15_000) return Decision.Wait("切换冷却中")
        val target = targets.single()
        lastTap = frame.at; awaitingNewLesson = true; previousEnd = position ?: lastProgress
        return Decision.Tap(target.box, "视频已结束，点击「${target.text}」")
    }
}

class ObserveModule : AssistantModule {
    override val id = "observe"
    override val title = "屏幕文字观察"
    override val description = "本地识别屏幕文字，不执行点击。可把识别结果交给本地 AI 总结。"
    override fun reset() {}
    override fun observe(frame: ScreenFrame): Decision = ScreenGuard.blocked(frame.text)?.let {
        Decision.Pause("检测到「$it」，已停止观察")
    } ?: Decision.Wait("已在本机识别 ${frame.lines.size} 行文字")
}
object ModuleRegistry {
    fun available(): List<AssistantModule> = listOf(CourseModule(), ObserveModule())
    fun create(id: String): AssistantModule = available().firstOrNull { it.id == id } ?: ObserveModule()
}

package cn.pocketai.assistant.core

import java.security.MessageDigest
import java.text.Normalizer

enum class PracticeKind { SINGLE, BOOLEAN }
data class PracticeOption(val label: String, val text: String, val box: Box)
data class PracticeQuestion(
    val kind: PracticeKind, val stem: String, val options: List<PracticeOption>,
    val close: Box?, val correctAnswer: String? = null
) {
    val id: String get() = MessageDigest.getInstance("SHA-256").digest(
        (kind.name + "|" + compact(stem) + options.joinToString("|") { it.label + compact(it.text) }).toByteArray()
    ).joinToString("") { "%02x".format(it) }
    fun prompt(): String = buildString {
        append(if (kind == PracticeKind.BOOLEAN) "判断题\n" else "单选题\n")
        append(stem); append('\n')
        options.forEach { append(it.label); append(". "); append(it.text); append('\n') }
    }
}

private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
private fun compact(text: String) = normalized(text).replace(Regex("\\s+"), "")

/** A profile for the visible, explicitly ungraded in-video practice dialog. */
object PracticeQuizParser {
    private val option = Regex("^[（(]?([A-F])[）)]?(?:[.、:：．)）\\s]+(.*)|([\\p{IsHan}0-9].*)|$)")
    private val result = Regex("^正确答案\\s*[:：]\\s*([A-F]?)$")
    fun supported(frame: ScreenFrame) = frame.packageName in setOf("com.able.wisdomtree", "cn.pocketai.fixture") && frame.height > frame.width
    fun hasQuestion(frame: ScreenFrame) = frame.lines.any { compact(it.text) in setOf("单选题", "判断题") }
    fun read(frame: ScreenFrame): PracticeQuestion? {
        if (!supported(frame)) return null
        val header = frame.lines.singleOrNull { compact(it.text) in setOf("单选题", "判断题") } ?: return null
        // OCR blocks may be returned out of reading order, including wrapped footer text.
        val text = compact(frame.lines.sortedWith(compareBy({ it.box.top }, { it.box.left })).joinToString(" ") { it.text })
        // Neither exams nor arbitrary screens can opt themselves in with a question label alone.
        if (!text.contains("弹题是为了帮助同学巩固知识点") || !text.contains("不会影响") || !text.contains("成绩")) return null
        if (header.box.y !in frame.height / 8..frame.height / 2) return null
        val footer = frame.lines.filter { it.box.top > header.box.bottom &&
            (compact(it.text).contains("上一题") || compact(it.text).contains("下一题") || compact(it.text).matches(Regex("\\d{1,2}/\\d{1,2}")) || it.text.contains("弹题")) }
            .minOfOrNull { it.box.top } ?: return null
        val body = frame.lines.filter { it.box.inside(frame.width, frame.height) &&
            it.box.top > header.box.bottom && it.box.bottom < footer &&
            it.box.left >= frame.width / 16 && it.box.right <= frame.width * 15 / 16 }
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val resultLine = body.singleOrNull { result.matches(normalized(it.text)) }
        val labels = body.filter { resultLine == null || it.box.top < resultLine.box.top }.mapNotNull { line -> option.matchEntire(normalized(line.text))?.let { match ->
            Triple(line, match.groupValues[1], match.groupValues[2].ifBlank { match.groupValues[3] })
        } }
        val sequences = labels.indices.filter { labels[it].second == "A" }.mapNotNull { start ->
            val sequence = labels.drop(start)
            sequence.takeIf { it.size in 2..6 && it.map { row -> row.second } == (0 until it.size).map { n -> ('A' + n).toString() } }
        }
        val sequence = sequences.singleOrNull() ?: return null
        if (sequence.zipWithNext().any { (a, b) -> b.first.box.y - a.first.box.y < frame.height / 35 }) return null
        val stem = body.filter { it.box.bottom < sequence.first().first.box.top }.joinToString(" ") { it.text }.trim()
        if (stem.length !in 5..1000) return null
        val correct = resultLine?.let { reference ->
            result.matchEntire(normalized(reference.text))!!.groupValues[1].ifBlank {
                body.filter { it !== reference && kotlin.math.abs(it.box.y - reference.box.y) <= (reference.box.bottom - reference.box.top) / 2 &&
                    normalized(it.text).matches(Regex("[A-F]")) }.singleOrNull()?.text?.trim().orEmpty()
            }.takeIf { it.matches(Regex("[A-F]")) }
        }
        val options = sequence.mapIndexed { index, row ->
            val (line, letter, tail) = row
            val bottom = sequence.getOrNull(index + 1)?.first?.box?.top ?: resultLine?.box?.top ?: footer
            val fragments = body.filter { other -> other !== line && other.box.y >= line.box.y - (line.box.bottom - line.box.top) / 2 &&
                other.box.top < bottom && other !== resultLine && option.matchEntire(normalized(other.text)) == null }
            val value = (listOf(tail) + fragments.map { it.text }).filter { it.isNotBlank() }.joinToString(" ")
            if (value.length !in 1..240) return null
            val right = maxOf(line.box.right, fragments.maxOfOrNull { it.box.right } ?: line.box.right)
            val end = maxOf(line.box.bottom, fragments.maxOfOrNull { it.box.bottom } ?: line.box.bottom)
            PracticeOption(letter, value, Box(line.box.left, line.box.top, right, end))
        }
        val kind = if (compact(header.text) == "判断题") PracticeKind.BOOLEAN else PracticeKind.SINGLE
        if (kind == PracticeKind.BOOLEAN && (options.size != 2 || options.map { compact(it.text) }.toSet() !in
                listOf(setOf("对", "错"), setOf("正确", "错误"), setOf("True", "False")))) return null
        if (stem.length + options.sumOf { it.text.length } > 1500) return null
        val close = frame.lines.filter { compact(it.text) == "关闭" && it.box.inside(frame.width, frame.height) &&
            it.box.x > frame.width / 2 && it.box.bottom < header.box.top && it.box.top > frame.height / 12 }.singleOrNull()?.box
        return PracticeQuestion(kind, stem, options, close, correct)
    }
}

object PracticeAnswer {
    fun read(raw: String, labels: Set<String>): String? {
        val value = normalized(raw).replace(Regex("^<think>\\s*</think>\\s*"), "")
        val letter = when {
            value.matches(Regex("[A-F]")) -> value
            value.matches(Regex("\"[A-F]\"")) -> value.substring(1, 2)
            else -> Regex("\\{\\s*\"answer\"\\s*:\\s*\"([A-F])\"\\s*}").matchEntire(value)?.groupValues?.get(1)
        }
        return letter?.takeIf { it in labels }
    }
}

sealed class PracticeAction {
    data class Observe(val decision: Decision) : PracticeAction()
    data class Solve(val request: Long, val question: PracticeQuestion) : PracticeAction()
}

/** Inference supplies a label only. Coordinates always come from a fresh, revalidated frame. */
class PracticeQuizController {
    private data class Active(val question: PracticeQuestion, val request: Long, val started: Long,
        var returned: Boolean = false, var answer: String? = null, var submitted: Long = -1, var closing: Long = -1)
    private var serial = 0L
    private var active: Active? = null
    private var candidate: PracticeQuestion? = null
    private var stable = 0
    private var absent = 0
    private var resultFrames = 0
    private val attempted = linkedMapOf<String, String>()
    fun reset() { serial++; active = null; candidate = null; stable = 0; absent = 0; resultFrames = 0 }
    fun provideAnswer(request: Long, raw: String) {
        val current = active?.takeIf { it.request == request && it.submitted < 0 } ?: return
        current.returned = true
        current.answer = PracticeAnswer.read(raw, current.question.options.map { it.label }.toSet())
    }
    private fun wait(reason: String) = PracticeAction.Observe(Decision.Wait(reason))
    private fun pause(reason: String): PracticeAction { reset(); return PracticeAction.Observe(Decision.Pause(reason)) }
    fun observe(frame: ScreenFrame): PracticeAction? {
        if (!PracticeQuizParser.supported(frame)) return null
        ScreenGuard.nonPracticeBlocked(frame.text)?.let { return pause("检测到「$it」，自动答题暂停") }
        val current = active
        val question = PracticeQuizParser.read(frame)
        if (question == null) {
            if (current != null && !PracticeQuizParser.hasQuestion(frame)) {
                if (++absent < 2) return wait("正在确认练习弹窗是否关闭")
                val closed = current.closing >= 0
                reset()
                return if (closed) wait("练习弹窗已关闭，继续观察播放") else PracticeAction.Observe(Decision.Pause("练习画面改变，已暂停核对"))
            }
            if (PracticeQuizParser.hasQuestion(frame)) return pause("练习题目或选项不完整，请手动检查")
            return null
        }
        absent = 0
        if (current == null) {
            if (question.id in attempted) return pause("此题已尝试作答，请手动确认结果")
            if (question.correctAnswer != null) return pause("已有答题结果，请手动关闭弹窗")
            val old = candidate
            stable = if (old?.id == question.id && samePositions(old, question)) stable + 1 else 1
            candidate = question
            if (stable < 2) return wait("正在核对随堂练习题目和选项")
            serial++
            active = Active(question, serial, frame.at)
            return PracticeAction.Solve(serial, question)
        }
        if (current.closing >= 0 && current.question.id != question.id) {
            reset()
            return wait("上一题已关闭，正在核对新练习")
        }
        if (current.question.id != question.id) return pause("题目已经改变，已丢弃旧答案")
        if (!samePositions(current.question, question)) return pause("选项位置改变，请重新开始核对")
        if (current.closing >= 0) return if (frame.at - current.closing > 12_000) pause("弹窗未关闭，请手动检查") else wait("等待练习弹窗关闭")
        if (current.submitted >= 0) {
            if (question.correctAnswer != null) {
                if (question.correctAnswer != current.answer) return pause("模型答案与参考不一致，请手动检查")
                val close = question.close ?: return if (frame.at - current.submitted > 16_000) pause("请移开浮窗并手动关闭练习") else wait("已确认练习结果，等待关闭按钮")
                if (++resultFrames < 2) return wait("正在核对随堂练习结果")
                current.closing = frame.at
                return PracticeAction.Observe(Decision.Tap(close, "练习结果已确认，关闭弹窗"))
            }
            return if (frame.at - current.submitted > 16_000) pause("作答后未确认结果，请手动检查") else wait("已选择选项，等待练习结果")
        }
        if (frame.at - current.started > 90_000) return pause("本地答题等待超时，已暂停")
        if (!current.returned) return wait("本地模型正在解答随堂练习")
        val letter = current.answer ?: return pause("本地模型未给出明确选项，已暂停")
        val target = question.options.singleOrNull { it.label == letter } ?: return pause("答案与当前选项不匹配，已暂停")
        current.submitted = frame.at
        attempted[question.id] = letter
        if (attempted.size > 64) attempted.remove(attempted.keys.first())
        return PracticeAction.Observe(Decision.Tap(target.box, "随堂练习选择 $letter，等待结果"))
    }
    private fun samePositions(a: PracticeQuestion, b: PracticeQuestion) = a.options.size == b.options.size &&
        a.options.zip(b.options).all { (x, y) -> x.label == y.label && kotlin.math.abs(x.box.x - y.box.x) <= 24 && kotlin.math.abs(x.box.y - y.box.y) <= 24 }
}

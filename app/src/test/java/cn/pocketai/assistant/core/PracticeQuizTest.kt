package cn.pocketai.assistant.core

import org.junit.Assert.*
import org.junit.Test

class PracticeQuizTest {
    private fun frame(at: Long = 0, result: String? = null, split: Boolean = true, truth: Boolean = false,
                      stem: String = "2 + 3 的计算结果是多少？", notice: Boolean = true, shift: Int = 0): ScreenFrame {
        val lines = mutableListOf(ScreenLine("关闭", Box(870, 310, 970, 355)),
            ScreenLine(if (truth) "判断题" else "单选题", Box(110, 400, 270, 450)),
            ScreenLine(stem, Box(110, 570, 920, 630)))
        val values = if (truth) listOf("对", "错") else listOf("4", "5", "6")
        values.forEachIndexed { index, text ->
            val y = 800 + index * 200 + shift
            val label = ('A' + index).toString()
            if (split) {
                lines += ScreenLine(label, Box(110, y, 150, y + 50))
                lines += ScreenLine(text, Box(240, y, 550, y + 50))
            } else lines += ScreenLine("$label $text", Box(110, y, 550, y + 50))
        }
        result?.let { lines += ScreenLine("正确答案：$it", Box(110, 1420, 600, 1470)) }
        lines += ScreenLine("上一题 1/1 下一题", Box(250, 1700, 850, 1750))
        if (notice) lines += ScreenLine("弹题是为了帮助同学巩固知识点，不会影响到大家作业和考试的成绩。", Box(110, 2020, 990, 2120))
        return ScreenFrame(lines, 1080, 2400, "com.able.wisdomtree", at)
    }
    private fun solving(controller: PracticeQuizController): PracticeAction.Solve {
        assertTrue(controller.observe(frame()) is PracticeAction.Observe)
        return controller.observe(frame(4000)) as PracticeAction.Solve
    }
    private fun decision(action: PracticeAction?) = (action as PracticeAction.Observe).decision
    @Test fun readsSplitLetterAndTextRows() {
        val q = PracticeQuizParser.read(frame())!!
        assertEquals(listOf("4", "5", "6"), q.options.map { it.text })
        assertEquals(listOf("A", "B", "C"), q.options.map { it.label })
    }
    @Test fun readsMergedRowsAndJudgment() {
        val q = PracticeQuizParser.read(frame(truth = true, split = false))!!
        assertEquals(PracticeKind.BOOLEAN, q.kind); assertEquals(listOf("对", "错"), q.options.map { it.text })
    }
    @Test fun requiresExplicitUngradedNoticeAndKnownPortraitApp() {
        assertNull(PracticeQuizParser.read(frame(notice = false)))
        assertNull(PracticeQuizParser.read(frame().copy(packageName = "unrelated.app")))
        assertNull(PracticeQuizParser.read(frame().copy(width = 2400, height = 1080)))
    }
    @Test fun ambiguousOrMissingOptionLetterIsRejected() {
        val f = frame(); assertNull(PracticeQuizParser.read(f.copy(lines = f.lines.filterNot { it.text == "B" })))
    }
    @Test fun partialQuestionNeverRequestsInference() {
        assertTrue(decision(PracticeQuizController().observe(frame(notice = false))) is Decision.Pause)
    }
    @Test fun stemBeginningWithArticleDoesNotBecomeOptionA() {
        val q = PracticeQuizParser.read(frame(stem = "A learner adds two and three. What is the result?"))!!
        assertTrue(q.stem.startsWith("A learner")); assertEquals(3, q.options.size)
    }
    @Test fun correctAnswerLineIsNotAppendedToLastOption() {
        val a = PracticeQuizParser.read(frame())!!; val b = PracticeQuizParser.read(frame(result = "B"))!!
        assertEquals(a.id, b.id); assertEquals("B", b.correctAnswer); assertEquals("6", b.options.last().text)
    }
    @Test fun splitReferenceLetterIsNotAnExtraOption() {
        val f = frame(result = "B")
        val rows = f.lines.map { if (it.text == "正确答案：B") it.copy(text = "正确答案：", box = Box(110, 1420, 350, 1470)) else it } +
            ScreenLine("B", Box(370, 1420, 420, 1470))
        assertEquals("B", PracticeQuizParser.read(f.copy(lines = rows))!!.correctAnswer)
    }
    @Test fun onlyStrictExistingAnswerLabelIsAccepted() {
        val labels = setOf("A", "B", "C")
        assertEquals("B", PracticeAnswer.read("Ｂ", labels))
        assertEquals("B", PracticeAnswer.read("{\"answer\":\"B\"}", labels))
        for (text in listOf("?", "A or B", "B because it is five", "D", "{\"answer\":\"B\",\"x\":4}", "tap(5,6)"))
            assertNull(text, PracticeAnswer.read(text, labels))
    }
    @Test fun requiresTwoStableObservationsAndRequestsModelOnce() {
        val c = PracticeQuizController(); solving(c)
        assertTrue(decision(c.observe(frame(8000))) is Decision.Wait)
    }
    @Test fun modelChoiceClicksOnlyFreshScreenOption() {
        val c = PracticeQuizController(); val request = solving(c)
        c.provideAnswer(request.request, "B")
        val click = decision(c.observe(frame(8000))) as Decision.Tap
        assertEquals(PracticeQuizParser.read(frame(8000))!!.options[1].box, click.box)
        assertTrue(decision(c.observe(frame(12000))) is Decision.Wait)
    }
    @Test fun staleModelCallbackCannotSubmitAfterReset() {
        val c = PracticeQuizController(); val old = solving(c); c.reset()
        val fresh = solving(c); assertNotEquals(old.request, fresh.request)
        c.provideAnswer(old.request, "B")
        assertTrue(decision(c.observe(frame(8000))) is Decision.Wait)
    }
    @Test fun changedQuestionDiscardsAnswer() {
        val c = PracticeQuizController(); val request = solving(c); c.provideAnswer(request.request, "B")
        assertTrue(decision(c.observe(frame(8000, stem = "另一道完全不同的练习问题是什么？"))) is Decision.Pause)
    }
    @Test fun movingOptionsPreventsSubmission() {
        val c = PracticeQuizController(); val request = solving(c); c.provideAnswer(request.request, "B")
        assertTrue(decision(c.observe(frame(8000, shift = 100))) is Decision.Pause)
    }
    @Test fun invalidResponseAndInferenceTimeoutPause() {
        val c = PracticeQuizController(); val request = solving(c); c.provideAnswer(request.request, "Maybe B")
        assertTrue(decision(c.observe(frame(8000))) is Decision.Pause)
        val waiting = PracticeQuizController(); solving(waiting)
        assertTrue(decision(waiting.observe(frame(96_000))) is Decision.Pause)
    }
    @Test fun resultMustMatchAndBeObservedTwiceBeforeClose() {
        val c = PracticeQuizController(); val request = solving(c); c.provideAnswer(request.request, "B")
        c.observe(frame(8000))
        assertTrue(decision(c.observe(frame(12000, result = "B"))) is Decision.Wait)
        val close = decision(c.observe(frame(16000, result = "B"))) as Decision.Tap
        assertEquals(PracticeQuizParser.read(frame())!!.close, close.box)
        assertTrue(decision(c.observe(frame(20000, result = "B"))) is Decision.Wait)
    }
    @Test fun wrongAnswerPausesWithoutRetryingDifferentOption() {
        val c = PracticeQuizController(); val request = solving(c); c.provideAnswer(request.request, "A")
        c.observe(frame(8000))
        assertTrue(decision(c.observe(frame(12000, result = "B"))) is Decision.Pause)
    }
    @Test fun timeoutAfterSubmissionDoesNotResubmitEvenOnRestart() {
        val c = PracticeQuizController(); val request = solving(c); c.provideAnswer(request.request, "B")
        c.observe(frame(8000))
        assertTrue(decision(c.observe(frame(28000))) is Decision.Pause)
        c.reset()
        assertTrue(decision(c.observe(frame(32000))) is Decision.Pause)
    }
    @Test fun noQuizAfterConfirmedCloseReturnsControlToPlayback() {
        val c = PracticeQuizController(); val request = solving(c); c.provideAnswer(request.request, "B")
        c.observe(frame(8000)); c.observe(frame(12000, result = "B")); c.observe(frame(16000, result = "B"))
        val empty = frame(20000).copy(lines = emptyList())
        assertTrue(decision(c.observe(empty)) is Decision.Wait)
        assertTrue(decision(c.observe(empty.copy(at = 24000))) is Decision.Wait)
        assertNull(c.observe(empty.copy(at = 28000)))
    }
    @Test fun verificationAndExamGuardsHavePriority() {
        for (text in listOf("安全验证", "开始考试", "支付密码", "多选题")) {
            val c = PracticeQuizController(); val f = frame().let { it.copy(lines = it.lines + ScreenLine(text, Box(30, 30, 300, 60))) }
            assertTrue(text, decision(c.observe(f)) is Decision.Pause)
        }
    }
    @Test fun togglingOffDoesNotChangeDefaultCourseQuizGuard() {
        assertTrue(CourseModule().observe(frame()) is Decision.Pause)
        assertTrue(DirectoryCourseModule().observe(frame()) is Decision.Pause)
    }
    @Test fun newQuestionImmediatelyAfterCloseStartsFreshInference() {
        val c = PracticeQuizController(); val old = solving(c); c.provideAnswer(old.request, "B")
        c.observe(frame(8000)); c.observe(frame(12000, result = "B")); c.observe(frame(16000, result = "B"))
        val next = frame(20000, truth = true, stem = "2 × 3 = 6，这个等式正确吗？")
        assertTrue(decision(c.observe(next)) is Decision.Wait)
        assertTrue(decision(c.observe(next.copy(at = 24000))) is Decision.Wait)
        val solve = c.observe(next.copy(at = 28000)) as PracticeAction.Solve
        assertNotEquals(old.request, solve.request)
        c.provideAnswer(old.request, "B")
        assertTrue(decision(c.observe(next.copy(at = 32000))) is Decision.Wait)
        c.provideAnswer(solve.request, "A")
        assertTrue(decision(c.observe(next.copy(at = 36000))) is Decision.Tap)
    }
}

package cn.pocketai.assistant.core

import org.junit.Assert.*
import org.junit.Test

class DirectoryCourseTest {
    private val currentBox = Box(110, 820, 750, 875)
    private val nextBox = Box(110, 1020, 750, 1075)
    private fun frame(at: Long = 0, clock: String? = null, current: String = "1.3.1", next: String? = "1.3.2", extra: List<ScreenLine> = emptyList()): ScreenFrame {
        val lines = mutableListOf(
            ScreenLine("教程", Box(20, 590, 120, 630)),
            ScreenLine("课程提醒", Box(180, 590, 390, 630)),
            ScreenLine("$current Current lesson", currentBox, true),
            ScreenLine("时长 01:00", Box(110, 890, 350, 930)),
            ScreenLine("资料", Box(280, 1740, 390, 1790)),
            ScreenLine("缓存", Box(600, 1740, 710, 1790))
        )
        clock?.let { lines += ScreenLine(it, Box(50, 480, 430, 530)) }
        next?.let { lines += ScreenLine("$it Next lesson", nextBox) }
        val markers = listOf(ScreenMarker(RowMark.PLAYING, Box(900, 820, 980, 900))) +
            if (next != null) listOf(ScreenMarker(RowMark.PLAYABLE, Box(900, 1020, 980, 1100))) else emptyList()
        return ScreenFrame(lines + extra, 1080, 1920, "com.able.wisdomtree", at, markers)
    }
    private fun finish(m: DirectoryCourseModule): Decision {
        m.observe(frame(0, "00:52 / 01:00")); m.observe(frame(4000, "00:56 / 01:00"))
        m.observe(frame(8000, "01:00 / 01:00")); return m.observe(frame(12000, "01:00 / 01:00"))
    }
    @Test fun directoryUsesAdjacentVisibleRowAfterPlaybackEnd() {
        val decision = finish(DirectoryCourseModule())
        assertTrue(decision is Decision.Tap); assertEquals(nextBox, (decision as Decision.Tap).box)
    }
    @Test fun completedDirectoryRowsAloneDoNotSkip() {
        val m = DirectoryCourseModule()
        val d = m.observe(frame(0, "已完成")); assertTrue(d is Decision.Wait)
        val result = m.observe(frame(4000, "已完成"))
        assertFalse(result is Decision.Tap && result.box == nextBox)
    }
    @Test fun hiddenClockGetsOneControlRevealAtSafePlayerPoint() {
        val m = DirectoryCourseModule(); assertTrue(m.observe(frame()) is Decision.Wait)
        val d = m.observe(frame(4000)); assertTrue(d is Decision.Tap)
        assertTrue((d as Decision.Tap).box.y in 100..580); assertTrue(d.box.x < 1080 / 3)
        assertTrue(m.observe(frame(8000)) is Decision.Wait)
    }
    @Test fun revealRetriesAreBounded() {
        val m = DirectoryCourseModule(); m.observe(frame())
        assertTrue(m.observe(frame(4000)) is Decision.Tap)
        assertTrue(m.observe(frame(16000)) is Decision.Tap)
        assertTrue(m.observe(frame(28000)) is Decision.Tap)
        assertTrue(m.observe(frame(36000)) is Decision.Pause)
    }
    @Test fun validationPreventsEvenControlReveal() {
        val m = DirectoryCourseModule(); m.observe(frame())
        assertTrue(m.observe(frame(4000, extra = listOf(ScreenLine("安全验证", Box(10, 200, 900, 300))))) is Decision.Pause)
    }
    @Test fun hiddenControlsBetweenEndReadingsDoNotLoseRealEvidence() {
        val m = DirectoryCourseModule()
        m.observe(frame(0, "00:56 / 01:00")); m.observe(frame(4000, "01:00 / 01:00"))
        m.observe(frame(8000)); m.observe(frame(12000))
        val d = m.observe(frame(16000, "01:00 / 01:00"))
        assertTrue(d is Decision.Tap); assertEquals(nextBox, (d as Decision.Tap).box)
    }
    @Test fun directoryClickWaitsForHighlightInsteadOfRepeating() {
        val m = DirectoryCourseModule(); finish(m)
        assertTrue(m.observe(frame(16000, "01:00 / 01:00")) is Decision.Wait)
        assertTrue(m.observe(frame(32000, "01:00 / 01:00")) is Decision.Wait)
        assertTrue(m.observe(frame(44000, "01:00 / 01:00")) is Decision.Pause)
    }
    @Test fun changedHighlightStartsFreshPlaybackEvidence() {
        val m = DirectoryCourseModule(); finish(m)
        assertTrue(m.observe(frame(16000, "00:01 / 01:00", "1.3.2", "1.3.3")) is Decision.Wait)
        repeat(3) { assertTrue(m.observe(frame(20000L + it * 4000, "01:00 / 01:00", "1.3.2", "1.3.3")) is Decision.Wait) }
    }
    @Test fun ambiguousHighlightedRowsAreRejected() {
        val f = frame(extra = listOf(ScreenLine("1.3.3 Other selected", Box(100, 1210, 700, 1250), true)))
        assertNull(TutorialDirectory.read(f))
    }
    @Test fun unhighlightedDirectoryIsRejected() {
        assertNull(TutorialDirectory.read(frame().let { it.copy(lines = it.lines.map { l -> l.copy(accented = false) }) }))
    }
    @Test fun duplicateIdsAreRejected() {
        assertNull(TutorialDirectory.read(frame(extra = listOf(ScreenLine("1.3.2 Duplicate", Box(110, 1210, 750, 1260))))))
    }
    @Test fun hiddenIntermediateLessonIsNotSkipped() { assertNull(TutorialDirectory.read(frame(next = "1.3.3"))!!.next) }
    @Test fun nextUnitFirstLessonIsAllowed() { assertEquals("1.4.1", TutorialDirectory.read(frame(current = "1.3.3", next = "1.4.1"))!!.next!!.id) }
    @Test fun nextUnitSecondLessonIsNotAllowed() { assertNull(TutorialDirectory.read(frame(next = "1.4.2"))!!.next) }
    @Test fun foreignAppsAndLandscapeCannotReceiveDirectoryActions() {
        assertNull(TutorialDirectory.read(frame().copy(packageName = "another.app")))
        assertNull(TutorialDirectory.read(frame().copy(width = 1920, height = 1080)))
    }
    @Test fun footerAndHeadersCannotBecomeLessonTargets() {
        val f = frame(next = null, extra = listOf(ScreenLine("1.4 Header", nextBox), ScreenLine("1.3.2 Footer", Box(110, 1810, 750, 1860))))
        assertNull(TutorialDirectory.read(f)!!.next)
    }
    @Test fun directoryClockCannotBecomePlaybackEvidence() {
        val m = DirectoryCourseModule()
        val e = listOf(ScreenLine("01:00 / 01:00", Box(100, 1200, 800, 1260)))
        assertTrue(m.observe(frame(0, extra = e)) is Decision.Wait)
        val reveal = m.observe(frame(4000, extra = e)) as Decision.Tap
        assertNotEquals(nextBox, reveal.box)
    }
    @Test fun oldEvidenceExpiresAcrossLongObservationGap() {
        val m = DirectoryCourseModule()
        m.observe(frame(0, "00:56 / 01:00")); m.observe(frame(4000, "01:00 / 01:00"))
        assertTrue(m.observe(frame(40000, "01:00 / 01:00")) is Decision.Wait)
    }
    @Test fun staticEndClockDoesNotTriggerDirectorySwitch() {
        val m = DirectoryCourseModule()
        repeat(5) { assertTrue(m.observe(frame(it * 4000L, "01:00 / 01:00")) is Decision.Wait) }
    }
    @Test fun playerTapIsNotIssuedWhenClockIsVisible() {
        val m = DirectoryCourseModule()
        repeat(4) { assertTrue(m.observe(frame(it * 4000L, "00:05 / 01:00")) is Decision.Wait) }
    }
    @Test fun mergedOcrTabLineStillIdentifiesPlayerBoundary() {
        val f = frame().let { it.copy(lines = it.lines.filterNot { l -> l.text in setOf("教程", "课程提醒") } + ScreenLine("教程 课程提醒", Box(20, 590, 390, 630))) }
        assertEquals("1.3.1", TutorialDirectory.read(f)!!.current.id)
    }
    @Test fun greenTextClassifierRejectsPurpleBackgroundAndGrayCheckmarks() {
        assertTrue(TextAccent.green(0xff34b993.toInt()))
        assertFalse(TextAccent.green(0xfff0e7ff.toInt()))
        assertFalse(TextAccent.green(0xffa5a5a5.toInt()))
        assertFalse(TextAccent.green(0xffffffff.toInt()))
    }
    private fun completed(at: Long, nextMark: RowMark = RowMark.PLAYABLE): ScreenFrame = frame(at).copy(markers = listOf(
        ScreenMarker(RowMark.COMPLETED, Box(900, 820, 980, 900)), ScreenMarker(nextMark, Box(900, 1020, 980, 1100))))
    @Test fun completedCurrentSelectsUnfinishedOnlyAfterTwoObservations() {
        val m = DirectoryCourseModule(); assertTrue(m.observe(completed(0)) is Decision.Wait)
        val d = m.observe(completed(4000)); assertTrue(d is Decision.Tap); assertEquals(nextBox, (d as Decision.Tap).box)
    }
    @Test fun allCompletedRowsWaitWithoutReplayingOrRevealingPlayer() {
        val m = DirectoryCourseModule()
        repeat(6) { assertTrue(m.observe(completed(it * 4000L, RowMark.COMPLETED)) is Decision.Wait) }
    }
    @Test fun unknownMarkerPreventsSelection() {
        val m = DirectoryCourseModule()
        repeat(3) { assertTrue(m.observe(completed(it * 4000L, RowMark.UNKNOWN)) is Decision.Wait) }
        assertTrue(m.observe(frame().copy(markers = emptyList())) is Decision.Wait)
    }
    @Test fun completedRowsCanBeSkippedOnlyWhenEveryIntermediateRowIsKnown() {
        val third = ScreenLine("1.3.3 Unfinished", Box(110, 1220, 750, 1275))
        val base = completed(0, RowMark.COMPLETED)
        val f = base.copy(lines = base.lines + third, markers = base.markers + ScreenMarker(RowMark.PLAYABLE, Box(900, 1220, 980, 1300)))
        assertEquals("1.3.3", TutorialDirectory.read(f)!!.next!!.id)
        assertNull(TutorialDirectory.read(f.copy(markers = f.markers.map { if (it.box.top == 1020) it.copy(kind = RowMark.UNKNOWN) else it }))!!.next)
    }
    @Test fun completedRowsDoNotBypassQuizGuard() {
        val f = completed(4000).let { it.copy(lines = it.lines + ScreenLine("判断题", Box(30, 220, 700, 260))) }
        val m = DirectoryCourseModule(); m.observe(completed(0)); assertTrue(m.observe(f) is Decision.Pause)
    }
}

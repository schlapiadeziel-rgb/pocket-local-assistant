package cn.pocketai.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class RowMarkerVisionTest {
    private val gray = 0xffa4a7aa.toInt()
    private fun canvas() = IntArray(45 * 45) { 0xfff9f9f9.toInt() }
    private fun put(p: IntArray, x: Int, y: Int, color: Int = gray) { if (x in 0..44 && y in 0..44) p[y * 45 + x] = color }
    private fun check(p: IntArray) {
        for (x in 15..19) for (t in 0..1) put(p, x, 24 + x - 15 + t)
        for (x in 19..28) for (t in 0..1) put(p, x, 28 - (x - 19) + t)
    }
    private fun play(p: IntArray) {
        for (y in 17..27) for (x in 17..(25 - abs(y - 22))) put(p, x, y)
    }
    @Test fun grayCheckIsCompleted() { val p = canvas(); check(p); assertEquals(RowMark.COMPLETED, RowMarkerVision.classify(45, 45, p)) }
    @Test fun filledRightTriangleIsUnfinished() { val p = canvas(); play(p); assertEquals(RowMark.PLAYABLE, RowMarkerVision.classify(45, 45, p)) }
    @Test fun blankOrPurpleBackgroundDoesNotBecomeAStatus() {
        assertEquals(RowMark.UNKNOWN, RowMarkerVision.classify(45, 45, canvas()))
        assertEquals(RowMark.UNKNOWN, RowMarkerVision.classify(45, 45, IntArray(45 * 45) { 0xfff0e7ff.toInt() }))
    }
    @Test fun greenPlayingBarsAreDistinctFromCompleted() {
        val p = canvas(); for (x in listOf(12, 18, 24, 30)) for (y in (14 + x % 5)..30) { put(p, x, y, 0xff34b993.toInt()); put(p, x + 1, y, 0xff34b993.toInt()) }
        assertEquals(RowMark.PLAYING, RowMarkerVision.classify(45, 45, p))
    }
    @Test fun solidBlockAndFilledCircleAreNotPlayButtons() {
        val block = canvas(); val circle = canvas()
        for (y in 12..32) for (x in 12..32) { put(block, x, y); if ((x - 22) * (x - 22) + (y - 22) * (y - 22) < 100) put(circle, x, y) }
        assertEquals(RowMark.UNKNOWN, RowMarkerVision.classify(45, 45, block))
        assertEquals(RowMark.UNKNOWN, RowMarkerVision.classify(45, 45, circle))
    }
    @Test fun malformedPixelBufferIsRejected() {
        assertEquals(RowMark.UNKNOWN, RowMarkerVision.classify(45, 45, intArrayOf()))
        assertEquals(RowMark.UNKNOWN, RowMarkerVision.classify(0, 0, intArrayOf()))
    }
}

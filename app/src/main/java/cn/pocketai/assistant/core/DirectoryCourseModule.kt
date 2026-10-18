package cn.pocketai.assistant.core

/** Only the visible portrait tutorial layout is supported. No hidden nodes, media APIs or model actions. */
class DirectoryCourseModule : AssistantModule {
    override val id = "directory"
    override val title = "目录连播"
    override val description = "知到竖屏目录：自动跳过右侧带 √ 的已完成小节，只选择明确的未完成播放图标；看不清就等待。进入课程播放后点浮窗开始。"
    private val playback = CourseModule()
    private var lesson = ""
    private var lastAt = -1L
    private var lastClockAt = -1L
    private var lastRevealAt = -1L
    private var missingFrames = 0
    private var revealAttempts = 0
    private var pendingAt = -1L
    private var pendingLesson = ""
    private var completedFrames = 0
    private var completedTarget: Box? = null

    override fun reset() {
        playback.reset(); lesson = ""; lastAt = -1L; lastClockAt = -1L
        lastRevealAt = -1L; missingFrames = 0; revealAttempts = 0; pendingAt = -1L; pendingLesson = ""
        completedFrames = 0; completedTarget = null
    }

    override fun observe(frame: ScreenFrame): Decision {
        ScreenGuard.blocked(frame.text)?.let { return Decision.Pause("检测到「$it」，请手动处理后重新开始") }
        if (lastAt >= 0 && (frame.at < lastAt || frame.at - lastAt > 30_000)) reset()
        lastAt = frame.at
        val page = TutorialDirectory.read(frame) ?: run {
            playback.reset(); missingFrames = 0; lastClockAt = -1L
            return Decision.Wait("请回到竖屏教程目录，让绿色当前节与下一条目录可见")
        }
        if (pendingAt >= 0) {
            if (page.current.id != pendingLesson) {
                playback.reset(); pendingAt = -1L; lastClockAt = -1L; lastRevealAt = -1L
            } else {
                if (frame.at - pendingAt > 30_000) return Decision.Pause("点击目录后未确认小节改变，请手动检查")
                return Decision.Wait("等待目录高亮切到下一节，暂不重复点击")
            }
        }
        if (lesson != page.current.id) {
            playback.reset(); lesson = page.current.id; lastClockAt = -1L
            lastRevealAt = -1L; missingFrames = 0; revealAttempts = 0
            completedFrames = 0; completedTarget = null
        }
        if (page.current.mark == RowMark.COMPLETED) {
            val next = page.next ?: return Decision.Wait("当前节已学完；请滚动目录，找到右侧为播放图标的未完成小节")
            val old = completedTarget
            completedFrames = if (old != null && kotlin.math.abs(old.x - next.box.x) <= 24 && kotlin.math.abs(old.y - next.box.y) <= 24) completedFrames + 1 else 1
            completedTarget = next.box
            if (completedFrames < 2) return Decision.Wait("已学完 ${page.current.id}；正在核对未完成小节 ${next.id}")
            pendingAt = frame.at; pendingLesson = page.current.id
            return Decision.Tap(next.box, "跳过带勾的已完成小节，打开未完成目录 ${next.id}")
        }
        completedFrames = 0; completedTarget = null
        if (page.current.mark != RowMark.PLAYING) {
            playback.reset()
            return Decision.Wait("当前节的完成/播放图标不清楚，请移开遮挡后重试")
        }
        val player = frame.lines.filter { it.box.bottom <= page.playerBottom && it.box.top >= frame.height / 40 }
        val clocks = player.filter { CLOCK.containsMatchIn(it.text) }
        val explicitEnd = player.any { it.text.replace(Regex("\\s+"), "") in END_LABELS }
        if (clocks.size > 1) { playback.reset(); return Decision.Wait("播放器有多个时间读数，请调整页面后重试") }
        if (clocks.isEmpty() && !explicitEnd) {
            missingFrames++
            if (lastClockAt >= 0 && frame.at - lastClockAt > 30_000) playback.reset()
            if (revealAttempts >= 3 && lastRevealAt >= 0 && frame.at - lastRevealAt >= 8_000)
                return Decision.Pause("多次轻触后仍看不到播放进度，请手动显示控制条后重新开始")
            if (missingFrames >= 2 && (lastRevealAt < 0 || frame.at - lastRevealAt >= 12_000)) {
                lastRevealAt = frame.at; revealAttempts++
                // The center play/pause button, seek bar, title and edge controls are deliberately outside this point.
                val x = frame.width * 18 / 100
                val y = page.playerTop + (page.playerBottom - page.playerTop) * 58 / 100
                return Decision.Tap(Box(x - 2, y - 2, x + 2, y + 2), "轻触播放器左侧显示进度，不拖动进度条")
            }
            return Decision.Wait("${page.current.id} · 等待播放器显示真实进度")
        }
        lastClockAt = frame.at; missingFrames = 0; revealAttempts = 0
        val next = page.next ?: run {
            playback.reset()
            return Decision.Wait("当前 ${page.current.id}；请滚动目录，让当前节与紧邻的下一节同时可见")
        }
        // The synthetic label represents the next adjacent numbered row, never a model-provided coordinate.
        val observed = ScreenFrame(player + ScreenLine("下一节", next.box), frame.width, frame.height, frame.packageName, frame.at)
        return when (val decision = playback.observe(observed)) {
            is Decision.Tap -> {
                pendingAt = frame.at; pendingLesson = page.current.id
                Decision.Tap(next.box, "${page.current.id} 已结束，点击目录 ${next.id}，等待高亮确认")
            }
            else -> decision
        }
    }

    companion object {
        private val CLOCK = Regex("\\d{1,2}:\\d{2}(?::\\d{2})?\\s*[/／]\\s*\\d{1,2}:\\d{2}(?::\\d{2})?")
        private val END_LABELS = setOf("播放结束", "本节视频已播放完毕", "本节学习已完成")
    }
}

data class DirectoryEntry(val id: String, val box: Box, val accented: Boolean, val mark: RowMark = RowMark.UNKNOWN)
data class TutorialDirectory(val current: DirectoryEntry, val next: DirectoryEntry?, val playerTop: Int, val playerBottom: Int) {
    companion object {
        private val LEAF = Regex("^(\\d{1,3}(?:\\.\\d{1,3}){2,4})(?:\\s|[A-Za-z\\p{IsHan}]).+")
        private val DURATION = Regex("时长\\s*\\d{1,2}[:：]\\d{2}")
        private fun intactMarker(box: Box, frame: ScreenFrame): Boolean {
            val width = box.right - box.left; val height = box.bottom - box.top
            // This profile uses square status icons. The next row can expose only a thin clipped strip.
            return box.inside(frame.width, frame.height) && width in 8..512 && height in 8..512 &&
                width * 4 >= height * 3 && height * 4 >= width * 3
        }
        fun read(frame: ScreenFrame): TutorialDirectory? {
            if (frame.packageName !in setOf("com.able.wisdomtree", "cn.pocketai.fixture") || frame.height <= frame.width) return null
            val merged = frame.lines.singleOrNull { it.text.replace(Regex("\\s+"), "") == "教程课程提醒" }
            val tutorial = merged ?: frame.lines.singleOrNull { it.text.trim() == "教程" } ?: return null
            val reminder = if (merged != null) merged else frame.lines.singleOrNull { it.text.trim() == "课程提醒" } ?: return null
            val boundary = minOf(tutorial.box.top, reminder.box.top)
            if (boundary !in frame.height / 8..frame.height / 2 ||
                kotlin.math.abs(tutorial.box.y - reminder.box.y) > frame.height / 40 ||
                tutorial.box.left > frame.width / 3 || (merged == null && reminder.box.left <= tutorial.box.left)) return null
            val footer = frame.lines.filter { it.text.replace(Regex("\\s+"), "") in setOf("资料", "缓存", "资料缓存") && it.box.top > boundary }
                .minOfOrNull { it.box.top } ?: frame.height * 9 / 10
            val entries = frame.lines.mapNotNull { line ->
                if (!line.box.inside(frame.width, frame.height) || line.box.top <= tutorial.box.bottom ||
                    line.box.bottom >= footer || line.box.left > frame.width / 3) return@mapNotNull null
                val id = LEAF.find(line.text.trim())?.groupValues?.get(1) ?: return@mapNotNull null
                DirectoryEntry(id, line.box, line.accented)
            }.sortedBy { it.box.top }
            if (entries.map { it.id }.distinct().size != entries.size) return null
            val marked = entries.mapIndexed { index, entry ->
                val duration = frame.lines.filter { it.box.top > entry.box.top && it.box.top < entry.box.top + frame.height / 8 &&
                    it.box.left < frame.width / 2 && DURATION.containsMatchIn(it.text) }.minByOrNull { it.box.top }
                val rowBottom = minOf(entries.getOrNull(index + 1)?.box?.top ?: footer,
                    duration?.box?.bottom?.plus(entry.box.bottom - entry.box.top) ?: (entry.box.top + frame.height / 12))
                val marker = frame.markers.filter { intactMarker(it.box, frame) && it.box.x > frame.width * 3 / 4 && it.box.y >= entry.box.top - 12 && it.box.y < rowBottom }.singleOrNull()
                entry.copy(mark = marker?.kind ?: RowMark.UNKNOWN)
            }
            val current = marked.singleOrNull { it.accented } ?: return null
            var previous = current
            var next: DirectoryEntry? = null
            for (entry in marked.drop(marked.indexOf(current) + 1)) {
                if (!adjacent(previous.id, entry.id)) break
                if (entry.mark == RowMark.PLAYABLE) { next = entry; break }
                if (entry.mark != RowMark.COMPLETED) break
                previous = entry
            }
            return TutorialDirectory(current, next, frame.height / 25, boundary)
        }
        private fun adjacent(current: String, next: String): Boolean {
            val a = current.split('.').map { it.toInt() }; val b = next.split('.').map { it.toInt() }
            if (a.size != b.size) return false
            val changed = a.indices.firstOrNull { a[it] != b[it] } ?: return false
            return b[changed] == a[changed] + 1 && b.drop(changed + 1).all { it == 1 }
        }
    }
}

object TextAccent {
    fun green(argb: Int): Boolean {
        val r = argb ushr 16 and 255; val g = argb ushr 8 and 255; val b = argb and 255
        return g in 90..240 && r < 180 && g * 100 > r * 120 && g * 100 > b * 108 && b > 35
    }
}

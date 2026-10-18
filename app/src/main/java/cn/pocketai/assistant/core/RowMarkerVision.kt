package cn.pocketai.assistant.core

/** Classifies pixels only inside a visible course-row status icon. Unknown shapes are never actionable. */
object RowMarkerVision {
    fun classify(width: Int, height: Int, pixels: IntArray): RowMark {
        if (width !in 8..512 || height !in 8..512 || pixels.size != width * height) return RowMark.UNKNOWN
        val ink = BooleanArray(pixels.size)
        var green = 0
        pixels.forEachIndexed { index, p ->
            val r = p ushr 16 and 255; val g = p ushr 8 and 255; val b = p and 255
            ink[index] = maxOf(r, g, b) - minOf(r, g, b) <= 28 && (r + g + b) / 3 in 95..225
            if (TextAccent.green(p)) green++
        }
        val seen = BooleanArray(ink.size)
        val queue = IntArray(ink.size)
        val found = mutableListOf<RowMark>()
        for (start in ink.indices) {
            if (!ink[start] || seen[start]) continue
            var head = 0; var tail = 1; queue[0] = start; seen[start] = true
            var left = width; var right = 0; var top = height; var bottom = 0
            while (head < tail) {
                val index = queue[head++]; val x = index % width; val y = index / width
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (ink[next] && !seen[next]) { seen[next] = true; queue[tail++] = next }
                }
            }
            val w = right - left + 1; val h = bottom - top + 1
            if (w < width * 0.16 || h < height * 0.12 || w > width * 0.85 || h > height * 0.85) continue
            val ratio = w.toDouble() / h; val density = tail.toDouble() / (w * h)
            val first = (0 until tail).filter { queue[it] % width <= left + w * 0.2 }.map { queue[it] / width }
            val last = (0 until tail).filter { queue[it] % width >= right - w * 0.2 }.map { queue[it] / width }
            if (first.isEmpty() || last.isEmpty()) continue
            val firstY = (first.average() - top) / h; val lastY = (last.average() - top) / h
            if (ratio in 1.15..2.3 && density in 0.15..0.55 && firstY > 0.3 && lastY < 0.35 && firstY - lastY > 0.2)
                found += RowMark.COMPLETED
            else if (ratio in 0.65..1.15 && density in 0.45..0.85 && lastY in 0.3..0.7 &&
                (first.max() - first.min() + 1).toDouble() / h >= 0.65 &&
                (last.max() - last.min() + 1).toDouble() / h < 0.65)
                found += RowMark.PLAYABLE
        }
        if (found.size == 1) return found.single()
        if (found.isEmpty() && green >= maxOf(5, width * height / 150)) return RowMark.PLAYING
        return RowMark.UNKNOWN
    }
}

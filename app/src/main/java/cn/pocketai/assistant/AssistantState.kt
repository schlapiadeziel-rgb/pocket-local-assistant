package cn.pocketai.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AssistantState {
    private val main = Handler(Looper.getMainLooper())
    private val entries = ArrayDeque<String>()
    @Volatile var running = false
    @Volatile var status = "尚未开始"
    @Volatile var lastScreenText = ""
    @Volatile var recognizedLines = 0
    @Volatile var frameCount = 0
    @Volatile var tapCount = 0
    @Volatile var service: VisionService? = null
    fun prefs(context: Context) = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    @Synchronized fun log(text: String) {
        status = text
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        entries.addFirst("$time  $text")
        while (entries.size > 80) entries.removeLast()
        main.post { service?.updateOverlay() }
    }
    @Synchronized fun logs(): String = entries.joinToString("\n")
    fun clearScreen() { lastScreenText = ""; recognizedLines = 0 }
}

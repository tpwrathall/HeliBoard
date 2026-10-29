// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.os.Build
import android.os.SystemClock

/**
 * Custom build: a short in-memory log of what the keyboard asked the app and what came back
 * (selections, cursor positions, gestures). The "Debug log" toolbar key types it into the text field,
 * so it can be sent for debugging. Nothing is written to disk.
 */
object DebugLog {
    private const val MAX_LINES = 400
    private const val MAX_REPORT_CHARS = 7000
    private val lines = ArrayDeque<String>()

    @JvmStatic
    fun log(message: String) {
        val t = SystemClock.uptimeMillis()
        val stamp = String.format(java.util.Locale.US, "%03d.%03d", (t / 1000) % 1000, t % 1000)
        synchronized(lines) {
            lines.addLast("$stamp $message")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    /** the log as text (newest last, trimmed to fit a message), and clear it */
    @JvmStatic
    fun takeReport(app: String?): String {
        val body = synchronized(lines) {
            val text = lines.joinToString("\n")
            lines.clear()
            text
        }
        val trimmed = if (body.length > MAX_REPORT_CHARS) "…" + body.takeLast(MAX_REPORT_CHARS) else body
        return "[HeliBoard debug] app: ${app ?: "?"} | Android ${Build.VERSION.SDK_INT} | ${BuildConfig.VERSION_NAME}\n" +
                trimmed.ifEmpty { "(nothing logged since the last report)" }
    }
}

/** Selection changes reported by the app (onUpdateSelection), for code that needs to wait for the app. */
object SelectionWatcher {
    private val listeners = mutableListOf<(Int, Int) -> Unit>()

    @JvmStatic
    fun onUpdate(newSelStart: Int, newSelEnd: Int) {
        if (listeners.isEmpty()) return
        listeners.toList().forEach { it(minOf(newSelStart, newSelEnd), maxOf(newSelStart, newSelEnd)) }
    }

    fun add(listener: (Int, Int) -> Unit) { listeners.add(listener) }
    fun remove(listener: (Int, Int) -> Unit) { listeners.remove(listener) }
}

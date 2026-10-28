// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.CursorAnchorInfo

/**
 * Keeps the latest cursor position the app reported (CursorAnchorInfo), in screen coordinates.
 * Not all apps report it (e.g. the Claude app doesn't, browsers and normal text fields do).
 */
object CursorTracker {
    /** cursor position on screen: x, top and bottom of the cursor line */
    data class Marker(val x: Float, val top: Float, val bottom: Float) {
        val centerY get() = (top + bottom) / 2
        val height get() = bottom - top
    }

    private val handler = Handler(Looper.getMainLooper())
    private val waiting = mutableListOf<Pair<Long, (Marker?) -> Unit>>()

    var lastInfo: CursorAnchorInfo? = null
        private set
    var lastUpdateTime = 0L
        private set
    private val listeners = mutableListOf<(Marker?) -> Unit>()

    @JvmStatic
    fun onCursorAnchorInfo(info: CursorAnchorInfo) {
        lastInfo = info
        lastUpdateTime = SystemClock.uptimeMillis()
        val marker = marker()
        if (waiting.isNotEmpty()) {
            val toCall = waiting.map { it.second }
            waiting.clear()
            toCall.forEach { it(marker) }
        }
        listeners.toList().forEach { it(marker) }
    }

    @JvmStatic
    fun reset() {
        lastInfo = null
        lastUpdateTime = 0L
    }

    /** current cursor position on screen, or null if the app doesn't report it */
    fun marker(): Marker? {
        val info = lastInfo ?: return null
        val x = info.insertionMarkerHorizontal
        if (x.isNaN()) return null
        val pts = floatArrayOf(x, info.insertionMarkerTop, x, info.insertionMarkerBottom)
        info.matrix.mapPoints(pts)
        if (pts[3] <= pts[1]) return null
        return Marker(pts[0], pts[1], pts[3])
    }

    /** calls [callback] with the next reported cursor position, or with null after [timeoutMs] */
    fun awaitNextUpdate(timeoutMs: Long, callback: (Marker?) -> Unit) {
        val entry = SystemClock.uptimeMillis() to callback
        waiting.add(entry)
        handler.postDelayed({
            if (waiting.remove(entry)) callback(null)
        }, timeoutMs)
    }

    fun addListener(listener: (Marker?) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (Marker?) -> Unit) { listeners.remove(listener) }
}

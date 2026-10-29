// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.HandwritingGesture
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SelectGesture
import android.view.inputmethod.TextBoundsInfo
import android.view.inputmethod.TextBoundsInfoResult
import androidx.annotation.RequiresApi
import helium314.keyboard.latin.DebugLog
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.SelectionWatcher
import kotlin.math.abs

/**
 * Finds the text offset at a point on screen (screen coordinates).
 *
 * 1. If the app reports character positions (requestTextBoundsInfo, e.g. normal Android text fields), the
 *    offset is computed from them directly: exact, and nothing is selected in the app.
 * 2. Otherwise the app's "select at point" support (Android 14 handwriting gestures, [SelectGesture]) is used,
 *    e.g. browsers and the Claude app: everything left of the point on that line is selected and the end of that
 *    selection is the position. Before that, any selection is collapsed, because some apps (browsers) replace
 *    the selection with nothing when a gesture finds no text. The keyboard waits until the app reports the new
 *    selection, as some apps apply it a little later.
 *
 * Callers set the wanted selection afterwards.
 */
object CursorPlacer {
    private const val WIDE = 10000f
    private const val APP_WAIT_MS = 300L
    private val handler = Handler(Looper.getMainLooper())

    /** top of the keyboard (including toolbar / suggestion strip) on screen, or null if not shown */
    fun keyboardTopOnScreen(): Float? {
        val switcher = KeyboardSwitcher.getInstance()
        val pos = IntArray(2)
        var top: Float? = null
        val keyboardView = switcher.mainKeyboardView
        if (keyboardView != null && keyboardView.isShown) {
            keyboardView.getLocationOnScreen(pos)
            top = pos[1].toFloat()
        }
        val strip = switcher.stripContainer
        if (strip != null && strip.isShown) {
            strip.getLocationOnScreen(pos)
            top = minOf(top ?: Float.MAX_VALUE, pos[1].toFloat())
        }
        return top
    }

    /** whether the app supports "select at point" */
    fun isSupported(ime: LatinIME): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val editorInfo = ime.currentInputEditorInfo ?: return false
        return try {
            editorInfo.supportedHandwritingGestures.contains(SelectGesture::class.java)
        } catch (e: Exception) { false }
    }

    /**
     * Calls [callback] with the cursor offset closest to the screen point ([x], [y]), or null if there is no
     * text there. [lineHeight] is the approximate height of a text line.
     */
    fun offsetAt(ime: LatinIME, x: Float, y: Float, lineHeight: Float, callback: (Int?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            callback(null)
            return
        }
        boundsOffsetAt(ime, x, y, lineHeight) { exact ->
            if (exact != null) {
                DebugLog.log("place: bounds ${x.toInt()},${y.toInt()} -> $exact")
                callback(exact)
            } else if (!isSupported(ime)) {
                DebugLog.log("place: no positions and no select-at-point")
                callback(null)
            } else {
                gestureOffsetAt(ime, x, y, lineHeight, 0, callback)
            }
        }
    }

    // ---- 1: character positions ----

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun boundsOffsetAt(ime: LatinIME, x: Float, y: Float, lineHeight: Float, callback: (Int?) -> Unit) {
        val ic = ime.currentInputConnection
        if (ic == null) {
            callback(null)
            return
        }
        val w = ime.resources.displayMetrics.widthPixels.toFloat()
        val area = RectF(x - w, y - 3 * lineHeight, x + w, y + 3 * lineHeight)
        try {
            ic.requestTextBoundsInfo(area, ime.mainExecutor) { result ->
                val info = result.textBoundsInfo
                callback(if (result.resultCode == TextBoundsInfoResult.CODE_SUCCESS && info != null) nearestOffset(info, x, y) else null)
            }
        } catch (e: Exception) {
            callback(null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun nearestOffset(info: TextBoundsInfo, x: Float, y: Float): Int? {
        val first = info.startIndex
        val end = info.endIndex
        if (end <= first) return null
        val r = RectF()
        // the line closest to y
        var bestTop = 0f
        var bestBottom = 0f
        var bestDistance = Float.MAX_VALUE
        for (i in first until end) {
            info.getCharacterBounds(i, r)
            if (r.height() <= 0) continue
            val d = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
            if (d < bestDistance) {
                bestDistance = d
                bestTop = r.top
                bestBottom = r.bottom
            }
        }
        if (bestDistance == Float.MAX_VALUE) return null
        // closest boundary on that line
        var best: Int? = null
        var bestDx = Float.MAX_VALUE
        var last = -1
        for (i in first until end) {
            info.getCharacterBounds(i, r)
            val cy = r.centerY()
            if (cy < bestTop || cy > bestBottom) continue
            last = i
            val linefeed = (info.getCharacterFlags(i) and TextBoundsInfo.FLAG_CHARACTER_LINEFEED) != 0
            val dx = abs(x - r.left)
            if (dx < bestDx) {
                bestDx = dx
                best = i
            }
            if (linefeed) break
        }
        if (last >= 0) {
            info.getCharacterBounds(last, r)
            val linefeed = (info.getCharacterFlags(last) and TextBoundsInfo.FLAG_CHARACTER_LINEFEED) != 0
            val whitespace = (info.getCharacterFlags(last) and TextBoundsInfo.FLAG_CHARACTER_WHITESPACE) != 0
            // after the last character, unless that position belongs to the next line (line break, wrapped space)
            if (!linefeed && !(whitespace && last + 1 < end) && abs(x - r.right) < bestDx) best = last + 1
        }
        return best
    }

    // ---- 2: select at point ----

    private val tries = floatArrayOf(0f, -0.5f, 0.5f) // in line heights: at the point, then just above and below

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun gestureOffsetAt(ime: LatinIME, x: Float, y: Float, h: Float, attempt: Int, callback: (Int?) -> Unit) {
        if (attempt >= tries.size) {
            DebugLog.log("place: gesture found no text near ${x.toInt()},${y.toInt()}")
            callback(null)
            return
        }
        val ty = y + tries[attempt] * h
        val band = (h / 4).coerceAtLeast(1f)
        collapseSelection(ime) {
            selectArea(ime, RectF(x - WIDE, ty - band, x, ty + band)) { left ->
                if (left != null) {
                    val end = left.second
                    if (!isWhitespaceAfterSelection(ime)) {
                        DebugLog.log("place: gesture ${x.toInt()},${ty.toInt()} -> $end")
                        callback(end)
                        return@selectArea
                    }
                    // apps leave out spaces at the edge of a selection: check whether the point is right before the
                    // next word, by selecting a little to the right of the point
                    collapseSelection(ime) {
                        selectArea(ime, RectF(x, ty - band, x + 0.35f * h, ty + band)) { right ->
                            val result = if (right != null && right.first > end) right.first else end
                            DebugLog.log("place: gesture ${x.toInt()},${ty.toInt()} -> $result (space check ${right?.first})")
                            callback(result)
                        }
                    }
                } else {
                    // nothing left of the point: start of a line, or no line here
                    collapseSelection(ime) {
                        selectArea(ime, RectF(x, ty - band, x + WIDE, ty + band)) { right ->
                            if (right != null) {
                                DebugLog.log("place: gesture line start ${x.toInt()},${ty.toInt()} -> ${right.first}")
                                callback(right.first)
                            } else {
                                gestureOffsetAt(ime, x, y, h, attempt + 1, callback)
                            }
                        }
                    }
                }
            }
        }
    }

    /** the app's current selection (start <= end), or null */
    fun appSelection(ime: LatinIME): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val st = try { ime.currentInputConnection?.getSurroundingText(0, 0, 0) } catch (e: Exception) { null } ?: return null
        if (st.offset < 0 || st.selectionStart < 0) return null
        val a = st.offset + st.selectionStart
        val b = st.offset + st.selectionEnd
        return minOf(a, b) to maxOf(a, b)
    }

    private fun isWhitespaceAfterSelection(ime: LatinIME): Boolean {
        val after = try { ime.currentInputConnection?.getTextAfterCursor(1, 0) } catch (e: Exception) { null }
        return !after.isNullOrEmpty() && after[0].isWhitespace() && after[0] != '\n'
    }

    /** makes sure there is no selection, so a gesture that finds nothing can't replace selected text */
    private fun collapseSelection(ime: LatinIME, then: () -> Unit) {
        val sel = appSelection(ime)
        if (sel == null || sel.first == sel.second) {
            then()
            return
        }
        val ic = ime.currentInputConnection
        if (ic == null) {
            then()
            return
        }
        waitForSelection(ime, sel) { then() }
        ic.setSelection(sel.second, sel.second)
    }

    /** calls [then] once the app reports a selection different from [before], or after a timeout */
    private fun waitForSelection(ime: LatinIME, before: Pair<Int, Int>?, then: (Pair<Int, Int>?) -> Unit) {
        var done = false
        lateinit var listener: (Int, Int) -> Unit
        val finish = { sel: Pair<Int, Int>? ->
            if (!done) {
                done = true
                SelectionWatcher.remove(listener)
                then(sel)
            }
        }
        listener = { s, e -> if (s to e != before) handler.post { finish(appSelection(ime) ?: (s to e)) } }
        SelectionWatcher.add(listener)
        handler.postDelayed({ finish(appSelection(ime)) }, APP_WAIT_MS)
    }

    /** selects characters in [area] (screen coordinates) and returns the new selection, or null if nothing was selected */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun selectArea(ime: LatinIME, area: RectF, callback: (Pair<Int, Int>?) -> Unit) {
        val ic = ime.currentInputConnection
        if (ic == null) {
            callback(null)
            return
        }
        val gesture = try {
            SelectGesture.Builder()
                .setSelectionArea(area)
                .setGranularity(HandwritingGesture.GRANULARITY_CHARACTER)
                .build()
        } catch (e: Exception) {
            callback(null)
            return
        }
        val before = appSelection(ime)
        try {
            var resultOk = false
            waitForSelection(ime, before) { sel ->
                val changed = sel != null && sel != before && sel.second > sel.first
                DebugLog.log("select ${area.left.toInt().coerceAtLeast(-1)}..${area.right.toInt().coerceAtMost(99999)},${area.centerY().toInt()}: " +
                        "ok=$resultOk before=$before after=$sel")
                callback(if (changed) sel else null)
            }
            ic.performHandwritingGesture(gesture, ime.mainExecutor) { result ->
                resultOk = result == InputConnection.HANDWRITING_GESTURE_RESULT_SUCCESS
                if (!resultOk) DebugLog.log("select gesture result $result")
            }
        } catch (e: Exception) {
            DebugLog.log("select gesture failed: ${e.message}")
            callback(null)
        }
    }
}

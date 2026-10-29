// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.graphics.RectF
import android.os.Build
import android.view.inputmethod.HandwritingGesture
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SelectGesture
import androidx.annotation.RequiresApi
import helium314.keyboard.latin.LatinIME

/**
 * Finds the text offset at a point on screen using the app's "select at point" support
 * (Android 14 handwriting gestures, [SelectGesture]). Works in apps that don't report character
 * positions, e.g. browsers and the Claude app.
 */
object CursorPlacer {
    private const val WIDE = 10000f

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

    fun isSupported(ime: LatinIME): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val editorInfo = ime.currentInputEditorInfo ?: return false
        return try {
            editorInfo.supportedHandwritingGestures.contains(SelectGesture::class.java)
        } catch (e: Exception) { false }
    }

    /**
     * Calls [callback] with the cursor offset closest to the screen point ([x], [y]) on the line at [y],
     * or null if there is no text there. [lineHeight] is used to hit the line.
     * Note: this temporarily selects text in the app, the caller should set the wanted selection afterwards.
     */
    fun offsetAt(ime: LatinIME, x: Float, y: Float, lineHeight: Float, callback: (Int?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            callback(null)
            return
        }
        val h = (lineHeight / 4).coerceAtLeast(1f)
        // everything on that line left of the point: the end of that selection is the wanted position
        selectArea(ime, RectF(x - WIDE, y - h, x, y + h)) { leftEnd ->
            if (leftEnd != null) {
                callback(leftEnd.second)
                return@selectArea
            }
            // nothing left of the point (start of line): use the start of what's right of it
            selectArea(ime, RectF(x, y - h, x + WIDE, y + h)) { right ->
                callback(right?.first)
            }
        }
    }

    /** selects characters in [area] (screen coordinates), and returns the new selection, or null if nothing was selected */
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
        try {
            ic.performHandwritingGesture(gesture, ime.mainExecutor) { result ->
                if (result != InputConnection.HANDWRITING_GESTURE_RESULT_SUCCESS) {
                    callback(null)
                    return@performHandwritingGesture
                }
                val current = ime.currentInputConnection
                val surrounding = try { current?.getSurroundingText(0, 0, 0) } catch (e: Exception) { null }
                if (surrounding == null || surrounding.offset < 0 || surrounding.selectionStart < 0) {
                    callback(null)
                    return@performHandwritingGesture
                }
                val start = surrounding.offset + surrounding.selectionStart
                val end = surrounding.offset + surrounding.selectionEnd
                callback(if (end > start) start to end else null)
            }
        } catch (e: Exception) {
            callback(null)
        }
    }
}

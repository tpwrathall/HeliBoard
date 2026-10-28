// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SelectGesture
import android.view.inputmethod.TextBoundsInfoResult
import android.widget.Toast
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/**
 * Test feature: when a text field is opened, briefly shows which cursor-related features the app supports:
 * - character positions (requestTextBoundsInfo, used for keeping the cursor column)
 * - cursor position reporting (CursorAnchorInfo insertion marker)
 * - "place cursor at a point" (SelectGesture, part of Android 14 handwriting gestures)
 */
object TextFieldDiagnostics {
    private val handler = Handler(Looper.getMainLooper())
    private var lastAnchor: CursorAnchorInfo? = null
    private var lastAnchorTime = 0L
    private var lastShownKey: String? = null

    @JvmStatic
    fun onCursorAnchorInfo(info: CursorAnchorInfo) {
        lastAnchor = info
        lastAnchorTime = SystemClock.uptimeMillis()
    }

    @JvmStatic
    fun onFinishInput() {
        lastShownKey = null
    }

    @JvmStatic
    fun onStartInputView(ime: LatinIME, editorInfo: EditorInfo?, restarting: Boolean) {
        if (editorInfo == null || restarting) return
        if (!ime.prefs().getBoolean(Settings.PREF_SHOW_TEXT_FIELD_DIAGNOSTICS, Defaults.PREF_SHOW_TEXT_FIELD_DIAGNOSTICS)) return
        val key = "${editorInfo.packageName}/${editorInfo.fieldId}"
        if (key == lastShownKey) return
        lastShownKey = key
        handler.postDelayed({ probe(ime, editorInfo) }, 300)
    }

    private fun probe(ime: LatinIME, editorInfo: EditorInfo) {
        val ic = ime.currentInputConnection ?: return
        val startTime = SystemClock.uptimeMillis()

        // keep monitor mode, HeliBoard requests it anyway
        val cursorAccepted = try {
            ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR)
        } catch (e: Exception) { false }

        var positions = "needs Android 14"
        var placeAtPoint = "needs Android 14"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            placeAtPoint = if (editorInfo.supportedHandwritingGestures.contains(SelectGesture::class.java)) "YES" else "no"
            positions = "no reply"
            try {
                val dm = ime.resources.displayMetrics
                val rect = RectF(0f, 0f, dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
                ic.requestTextBoundsInfo(rect, ime.mainExecutor) { result ->
                    positions = if (result.resultCode == TextBoundsInfoResult.CODE_SUCCESS) "YES" else "no"
                }
            } catch (e: Exception) {
                positions = "no (error)"
            }
        }

        handler.postDelayed({
            val anchor = lastAnchor
            val cursor = when {
                !cursorAccepted -> "no"
                anchor == null || lastAnchorTime < startTime -> "no reply"
                anchor.insertionMarkerHorizontal.isNaN() -> "partly (no cursor point)"
                else -> "YES"
            }
            val app = editorInfo.packageName ?: "unknown app"
            val text = "HeliBoard test – $app\n" +
                    "1. Character positions: $positions\n" +
                    "2. Cursor position: $cursor\n" +
                    "3. Place cursor at point: $placeAtPoint"
            Toast.makeText(ime, text, Toast.LENGTH_LONG).show()
        }, 600)
    }
}

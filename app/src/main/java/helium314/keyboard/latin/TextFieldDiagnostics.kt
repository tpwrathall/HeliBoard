// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SelectGesture
import android.view.inputmethod.TextBoundsInfoResult

/**
 * Test feature (toolbar key): types into the current text field which cursor-related features the app supports:
 * - character positions (requestTextBoundsInfo, used for keeping the cursor column)
 * - cursor position reporting (CursorAnchorInfo insertion marker)
 * - "place cursor at a point" (SelectGesture, part of Android 14 handwriting gestures)
 */
object TextFieldDiagnostics {
    private val handler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun typeReport(ime: LatinIME) {
        val ic = ime.currentInputConnection ?: return
        val editorInfo = ime.currentInputEditorInfo ?: return
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
                    positions = if (result.resultCode == TextBoundsInfoResult.CODE_SUCCESS) "YES"
                        else "no (code ${result.resultCode})"
                }
            } catch (e: Exception) {
                positions = "no (error)"
            }
        }

        handler.postDelayed({
            val anchor = CursorTracker.lastInfo
            val cursor = when {
                !cursorAccepted -> "no"
                anchor == null || CursorTracker.lastUpdateTime < startTime -> "no reply"
                anchor.insertionMarkerHorizontal.isNaN() -> "partly (no cursor point)"
                else -> "YES"
            }
            val app = editorInfo.packageName ?: "unknown app"
            val report = "[HeliBoard test] app: $app | Android ${Build.VERSION.SDK_INT} | " +
                    "character positions: $positions | cursor position: $cursor | place cursor at point: $placeAtPoint"
            // the field may have changed in the meantime, so get the current connection
            ime.currentInputConnection?.commitText(report, 1)
        }, 500)
    }
}

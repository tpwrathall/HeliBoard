// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import helium314.keyboard.latin.CursorTracker
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Trackpad page (toolbar key): covers the keys with a trackpad. A floating caret is shown over the app,
 * starting at the real cursor if the app reports its position, otherwise just above the keyboard.
 * Moving a finger on the trackpad moves the caret, and on release the real cursor is placed at the caret
 * using the app's "select at point" support.
 */
@SuppressLint("ViewConstructor")
class TrackpadOverlay private constructor(
    context: Context,
    private val ime: LatinIME,
    /** called before the cursor is placed, e.g. to commit the composing word */
    private val beforePlace: () -> Unit,
    /** sets the cursor to this offset */
    private val place: (Int) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val padRect = RectF()
    private val exitRect = RectF()
    private var frameTop = 0f // top of keyboard incl. toolbar, in this view's coordinates
    private val myScreenPos = IntArray(2)
    private val tmpPos = IntArray(2)

    private var caretX = Float.NaN
    private var caretY = Float.NaN // center
    private var caretHeight = 22 * density
    private var dragging = false
    private var exitPressed = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var placing = false
    private val supported = CursorPlacer.isSupported(ime)

    private val colors = Settings.getValues().mColors
    private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.get(ColorType.MAIN_BACKGROUND) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.get(ColorType.KEY_TEXT)
        alpha = 140
        textAlign = Paint.Align.CENTER
        textSize = 16 * density
    }
    private val exitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.get(ColorType.KEY_TEXT)
        alpha = 40
    }
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.get(ColorType.GESTURE_TRAIL) or 0xff000000.toInt() }
    private val caretOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 0, 0, 0) }

    private val cursorListener: (CursorTracker.Marker?) -> Unit = { marker ->
        // after placing, show the caret exactly where the real cursor ended up
        if (!dragging && marker != null) {
            setCaretFromScreen(marker)
            invalidate()
        }
    }

    init {
        isClickable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CursorTracker.addListener(cursorListener)
    }

    override fun onDetachedFromWindow() {
        CursorTracker.removeListener(cursorListener)
        super.onDetachedFromWindow()
    }

    private fun updateRects(): Boolean {
        val keyboardView = KeyboardSwitcher.getInstance().mainKeyboardView ?: return false
        if (!keyboardView.isShown) return false
        getLocationOnScreen(myScreenPos)
        keyboardView.getLocationOnScreen(tmpPos)
        val left = (tmpPos[0] - myScreenPos[0]).toFloat()
        val top = (tmpPos[1] - myScreenPos[1]).toFloat()
        padRect.set(left, top, left + keyboardView.width, top + keyboardView.height)
        frameTop = top
        val strip = KeyboardSwitcher.getInstance().stripContainer
        if (strip != null && strip.isShown) {
            strip.getLocationOnScreen(tmpPos)
            frameTop = min(frameTop, (tmpPos[1] - myScreenPos[1]).toFloat())
        }
        val size = 48 * density
        exitRect.set(padRect.right - size, padRect.top, padRect.right, padRect.top + size)
        return true
    }

    private fun setCaretFromScreen(marker: CursorTracker.Marker) {
        getLocationOnScreen(myScreenPos)
        caretX = marker.x - myScreenPos[0]
        caretY = marker.centerY - myScreenPos[1]
        caretHeight = max(marker.height, 12 * density)
    }

    private fun initCaret() {
        val marker = CursorTracker.marker()
        if (marker != null) {
            setCaretFromScreen(marker)
        } else {
            // the app doesn't say where its cursor is: start just above the keyboard, in the middle
            caretX = width / 2f
            caretY = frameTop - 40 * density
        }
        clampCaret()
    }

    private fun clampCaret() {
        caretX = caretX.coerceIn(0f, width.toFloat())
        val maxY = frameTop - caretHeight / 2 - 2 * density
        caretY = caretY.coerceIn(caretHeight / 2, max(caretHeight / 2, maxY))
    }

    override fun onDraw(canvas: Canvas) {
        if (!updateRects()) return
        if (caretX.isNaN()) initCaret()
        val r = 12 * density
        canvas.drawRoundRect(padRect, r, r, padPaint)
        val cx = padRect.centerX()
        val cy = padRect.centerY()
        canvas.drawText("Trackpad", cx, cy, textPaint)
        if (!supported)
            canvas.drawText("This app can't place the cursor from here", cx, cy + 24 * density, textPaint)
        // exit button
        canvas.drawCircle(exitRect.centerX(), exitRect.centerY(), exitRect.width() / 3, exitPaint)
        canvas.drawText("✕", exitRect.centerX(), exitRect.centerY() + textPaint.textSize / 3, textPaint)
        // floating caret
        val w = 1.5f * density
        val top = caretY - caretHeight / 2
        val bottom = caretY + caretHeight / 2
        canvas.drawRoundRect(caretX - w - density, top - density, caretX + w + density, bottom + density, w * 2, w * 2, caretOutline)
        canvas.drawRoundRect(caretX - w, top, caretX + w, bottom, w, w, caretPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!updateRects()) return false
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!padRect.contains(x, y)) return false // e.g. toolbar: let it handle the touch
                if (exitRect.contains(x, y)) {
                    exitPressed = true
                    return true
                }
                if (caretX.isNaN()) initCaret()
                dragging = true
                lastTouchX = x
                lastTouchY = y
                beforePlace()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                caretX += (x - lastTouchX) * GAIN
                caretY += (y - lastTouchY) * GAIN
                lastTouchX = x
                lastTouchY = y
                clampCaret()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (exitPressed) {
                    exitPressed = false
                    if (exitRect.contains(x, y)) hide()
                    return true
                }
                if (dragging) {
                    dragging = false
                    placeCursorAtCaret()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                exitPressed = false
            }
        }
        return true
    }

    private fun placeCursorAtCaret() {
        if (!supported || placing) return
        getLocationOnScreen(myScreenPos)
        val sx = caretX + myScreenPos[0]
        val sy = caretY + myScreenPos[1]
        placing = true
        CursorPlacer.offsetAt(ime, sx, sy, caretHeight) { offset ->
            placing = false
            if (offset != null) place(offset)
        }
    }

    companion object {
        private const val TAG = "TrackpadOverlay"
        private const val GAIN = 1.3f
        private var current: TrackpadOverlay? = null

        @JvmStatic
        fun isShowing() = current != null

        @JvmStatic
        fun toggle(ime: LatinIME, beforePlace: () -> Unit, place: (Int) -> Unit) {
            if (current != null) {
                hide()
                return
            }
            val keyboardView = KeyboardSwitcher.getInstance().mainKeyboardView ?: return
            val content = keyboardView.rootView?.findViewById<ViewGroup>(android.R.id.content)
            if (content == null) {
                Log.w(TAG, "no content view for the trackpad")
                return
            }
            try {
                val overlay = TrackpadOverlay(keyboardView.context, ime, beforePlace, place)
                content.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                current = overlay
            } catch (e: Exception) {
                Log.w(TAG, "could not show trackpad", e)
            }
        }

        @JvmStatic
        fun hide() {
            val overlay = current ?: return
            current = null
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
    }
}

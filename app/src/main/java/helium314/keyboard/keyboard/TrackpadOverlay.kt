// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import helium314.keyboard.latin.CursorTracker
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Floating cursor (like iOS), in two modes:
 * - trackpad page (toolbar key): covers the keys with a trackpad; moving a finger on it moves the floating caret,
 *   on release the real cursor is placed there. Tap, then touch again and drag, to select.
 * - caret only (long press on space): the keyboard sends the finger movement, nothing to touch here.
 *
 * The caret starts at the real cursor if the app reports its position, otherwise just above the keyboard.
 * The cursor is placed using the app's "select at point" support.
 */
@SuppressLint("ViewConstructor")
class TrackpadOverlay private constructor(
    context: Context,
    private val ime: LatinIME,
    private val callbacks: Callbacks,
    private val caretOnly: Boolean,
) : View(context) {

    class Callbacks(
        /** called before the cursor is moved, e.g. to commit the composing word */
        val beforePlace: () -> Unit,
        /** set the cursor to this offset */
        val place: (Int) -> Unit,
        /** select from the first to the second offset */
        val select: (Int, Int) -> Unit,
        /** current cursor offset */
        val currentOffset: () -> Int,
    )

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
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var movedDistance = 0f
    private var placing = false
    private val supported = CursorPlacer.isSupported(ime)

    // double tap and drag to select
    private var lastTapUpTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastPlacedOffset = -1
    private var selecting = false
    private var selectAnchor = -1
    private var lastLiveSelectTime = 0L

    private val colors = Settings.getValues().mColors
    private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.get(ColorType.MAIN_BACKGROUND) or 0xff000000.toInt()
        if (caretOnly) alpha = 215
    }
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
        if (!dragging && !caretOnly && marker != null) {
            setCaretFromScreen(marker)
            invalidate()
        }
    }

    init {
        isClickable = !caretOnly
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
        if (!caretOnly) {
            canvas.drawText("Trackpad – tap then drag to select", cx, cy, textPaint)
            // exit button
            canvas.drawCircle(exitRect.centerX(), exitRect.centerY(), exitRect.width() / 3, exitPaint)
            canvas.drawText("✕", exitRect.centerX(), exitRect.centerY() + textPaint.textSize / 3, textPaint)
        }
        if (!supported)
            canvas.drawText("This app can't place the cursor from here", cx, cy + 24 * density, textPaint)
        // floating caret
        val w = 1.5f * density
        val top = caretY - caretHeight / 2
        val bottom = caretY + caretHeight / 2
        canvas.drawRoundRect(caretX - w - density, top - density, caretX + w + density, bottom + density, w * 2, w * 2, caretOutline)
        canvas.drawRoundRect(caretX - w, top, caretX + w, bottom, w, w, caretPaint)
    }

    /** caret mode: move the caret by the finger movement */
    private fun moveBy(dx: Float, dy: Float) {
        if (caretX.isNaN()) {
            if (!updateRects()) return
            initCaret()
        }
        caretX += dx * GAIN
        caretY += dy * GAIN
        movedDistance += abs(dx) + abs(dy)
        clampCaret()
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (caretOnly) return false
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
                val now = SystemClock.uptimeMillis()
                selecting = now - lastTapUpTime < DOUBLE_TAP_MS && hypot(x - lastTapX, y - lastTapY) < 60 * density
                if (selecting) {
                    selectAnchor = if (lastPlacedOffset >= 0) lastPlacedOffset else callbacks.currentOffset()
                    if (selectAnchor < 0) selecting = false
                }
                dragging = true
                lastTouchX = x
                lastTouchY = y
                downX = x
                downY = y
                downTime = now
                callbacks.beforePlace()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                caretX += (x - lastTouchX) * GAIN
                caretY += (y - lastTouchY) * GAIN
                lastTouchX = x
                lastTouchY = y
                clampCaret()
                invalidate()
                if (selecting) {
                    // show the selection while moving, but not too often
                    val now = SystemClock.uptimeMillis()
                    if (now - lastLiveSelectTime > LIVE_SELECT_INTERVAL_MS) {
                        lastLiveSelectTime = now
                        selectToCaret()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (exitPressed) {
                    exitPressed = false
                    if (exitRect.contains(x, y)) hide()
                    return true
                }
                if (!dragging) return true
                dragging = false
                val now = SystemClock.uptimeMillis()
                val isTap = now - downTime < TAP_MS && hypot(x - downX, y - downY) < 12 * density
                if (selecting) {
                    selecting = false
                    lastTapUpTime = 0L
                    selectToCaret()
                } else {
                    if (isTap) {
                        lastTapUpTime = now
                        lastTapX = x
                        lastTapY = y
                    } else {
                        lastTapUpTime = 0L
                    }
                    placeCursorAtCaret()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                exitPressed = false
                selecting = false
            }
        }
        return true
    }

    private fun caretOnScreen(): Pair<Float, Float> {
        getLocationOnScreen(myScreenPos)
        return (caretX + myScreenPos[0]) to (caretY + myScreenPos[1])
    }

    private fun placeCursorAtCaret() {
        if (!supported || placing) return
        val (sx, sy) = caretOnScreen()
        placing = true
        lastPlacedOffset = -1
        CursorPlacer.offsetAt(ime, sx, sy, caretHeight) { offset ->
            placing = false
            if (offset != null) {
                lastPlacedOffset = offset
                callbacks.place(offset)
            }
        }
    }

    private fun selectToCaret() {
        if (!supported || placing || selectAnchor < 0) return
        val anchor = selectAnchor
        val (sx, sy) = caretOnScreen()
        placing = true
        CursorPlacer.offsetAt(ime, sx, sy, caretHeight) { offset ->
            placing = false
            if (offset != null) callbacks.select(anchor, offset)
            else callbacks.select(anchor, anchor)
        }
    }

    companion object {
        private const val TAG = "TrackpadOverlay"
        private const val GAIN = 1.3f
        private const val DOUBLE_TAP_MS = 350L
        private const val TAP_MS = 250L
        private const val LIVE_SELECT_INTERVAL_MS = 200L
        private var current: TrackpadOverlay? = null

        @JvmStatic
        fun isShowing() = current != null

        private fun add(ime: LatinIME, callbacks: Callbacks, caretOnly: Boolean): TrackpadOverlay? {
            val keyboardView = KeyboardSwitcher.getInstance().mainKeyboardView ?: return null
            val content = keyboardView.rootView?.findViewById<ViewGroup>(android.R.id.content)
            if (content == null) {
                Log.w(TAG, "no content view for the floating cursor")
                return null
            }
            return try {
                val overlay = TrackpadOverlay(keyboardView.context, ime, callbacks, caretOnly)
                content.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                current = overlay
                overlay
            } catch (e: Exception) {
                Log.w(TAG, "could not show floating cursor", e)
                null
            }
        }

        /** trackpad page */
        @JvmStatic
        fun toggle(ime: LatinIME, callbacks: Callbacks) {
            if (current != null) {
                hide()
                return
            }
            add(ime, callbacks, false)
        }

        /** caret mode for long press on space */
        @JvmStatic
        fun startCaret(ime: LatinIME, callbacks: Callbacks): Boolean {
            hide()
            val overlay = add(ime, callbacks, true) ?: return false
            callbacks.beforePlace()
            overlay.invalidate()
            return true
        }

        @JvmStatic
        fun moveCaret(dx: Int, dy: Int) {
            val overlay = current ?: return
            if (!overlay.caretOnly) return
            overlay.moveBy(dx.toFloat(), dy.toFloat())
        }

        @JvmStatic
        fun endCaret(apply: Boolean) {
            val overlay = current ?: return
            if (!overlay.caretOnly) return
            // only move the cursor if the finger actually moved
            if (apply && overlay.movedDistance > 8 * overlay.density) overlay.placeCursorAtCaret()
            hide()
        }

        @JvmStatic
        fun hide() {
            val overlay = current ?: return
            current = null
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
    }
}

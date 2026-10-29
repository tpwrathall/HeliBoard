// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Edit menu shown when swiping up from the space bar: the keys are covered and a semicircle of edit actions
 * appears around the point where the swipe started (so in landscape it's on that side, reachable with one thumb).
 * Move the finger onto an action and release to run it. Releasing anywhere else does nothing.
 */
@SuppressLint("ViewConstructor")
class EditMenuOverlay private constructor(
    context: Context,
    /** swipe start in screen coordinates */
    private val startScreenX: Float,
    private val startScreenY: Float,
    private val onHighlight: () -> Unit,
) : View(context) {

    private class Item(val key: ToolbarKey, val code: Int, val icon: Drawable?) {
        var x = 0f
        var y = 0f
    }

    private val density = resources.displayMetrics.density
    private val colors = Settings.getValues().mColors
    private val items = listOf(
        ToolbarKey.UNDO to KeyCode.UNDO,
        ToolbarKey.CUT to KeyCode.CLIPBOARD_CUT,
        ToolbarKey.COPY to KeyCode.CLIPBOARD_COPY,
        ToolbarKey.PASTE to KeyCode.CLIPBOARD_PASTE,
        ToolbarKey.SELECT_WORD to KeyCode.CLIPBOARD_SELECT_WORD,
        ToolbarKey.SELECT_ALL to KeyCode.CLIPBOARD_SELECT_ALL,
        ToolbarKey.REDO to KeyCode.REDO,
    ).map { (key, code) ->
        Item(key, code, KeyboardIconsSet.instance.getNewDrawable(key.name, context)?.apply {
            setTint(colors.get(ColorType.KEY_TEXT))
        })
    }

    private val padRect = RectF()
    private val myScreenPos = IntArray(2)
    private val tmpPos = IntArray(2)
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var itemRadius = 0f
    private var layoutDone = false
    private var highlighted: Item? = null

    private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.get(ColorType.MAIN_BACKGROUND) or 0xff000000.toInt() }
    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.get(ColorType.KEY_BACKGROUND) or 0xff000000.toInt() }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.get(ColorType.GESTURE_TRAIL) or 0xff000000.toInt() }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.get(ColorType.KEY_TEXT)
        alpha = 60
    }

    private fun doLayout(): Boolean {
        val keyboardView = KeyboardSwitcher.getInstance().mainKeyboardView ?: return false
        if (!keyboardView.isShown) return false
        getLocationOnScreen(myScreenPos)
        keyboardView.getLocationOnScreen(tmpPos)
        val left = (tmpPos[0] - myScreenPos[0]).toFloat()
        val top = (tmpPos[1] - myScreenPos[1]).toFloat()
        padRect.set(left, top, left + keyboardView.width, top + keyboardView.height)
        itemRadius = min(26 * density, padRect.height() / 7)
        radius = min(padRect.height() - itemRadius * 1.6f, 150 * density)
        centerY = (startScreenY - myScreenPos[1]).coerceIn(padRect.top + radius + itemRadius, padRect.bottom - itemRadius / 2)
        centerX = (startScreenX - myScreenPos[0]).coerceIn(padRect.left + radius + itemRadius, padRect.right - radius - itemRadius)
        items.forEachIndexed { i, item ->
            // from left (180°) to right (0°) over the top
            val angle = PI - PI * i / (items.size - 1)
            item.x = centerX + (radius * cos(angle)).toFloat()
            item.y = centerY - (radius * sin(angle)).toFloat()
        }
        layoutDone = true
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (!doLayout()) return
        val r = 12 * density
        canvas.drawRoundRect(padRect, r, r, padPaint)
        canvas.drawCircle(centerX, centerY, itemRadius / 2, centerPaint)
        val iconSize = (itemRadius * 1.1f).toInt()
        items.forEach { item ->
            canvas.drawCircle(item.x, item.y, itemRadius, if (item === highlighted) highlightPaint else itemPaint)
            item.icon?.let {
                it.setBounds((item.x - iconSize / 2).toInt(), (item.y - iconSize / 2).toInt(),
                    (item.x + iconSize / 2).toInt(), (item.y + iconSize / 2).toInt())
                it.draw(canvas)
            }
        }
    }

    /** finger position in screen coordinates */
    private fun moveTo(screenX: Float, screenY: Float) {
        if (!layoutDone && !doLayout()) return
        getLocationOnScreen(myScreenPos)
        val x = screenX - myScreenPos[0]
        val y = screenY - myScreenPos[1]
        // the gap between the space bar and the semicircle does nothing
        val item = if (hypot(x - centerX, y - centerY) < radius * 0.45f) null
            else items.minByOrNull { hypot(x - it.x, y - it.y) }?.takeIf { hypot(x - it.x, y - it.y) < itemRadius * 1.6f }
        if (item !== highlighted) {
            highlighted = item
            if (item != null) onHighlight()
            invalidate()
        }
    }

    companion object {
        private const val TAG = "EditMenuOverlay"
        private var current: EditMenuOverlay? = null
        private val keyboardPos = IntArray(2)

        private fun toScreen(x: Int, y: Int): Pair<Float, Float>? {
            val keyboardView = KeyboardSwitcher.getInstance().mainKeyboardView ?: return null
            keyboardView.getLocationOnScreen(keyboardPos)
            return (x + keyboardPos[0]).toFloat() to (y + keyboardPos[1]).toFloat()
        }

        /** [x], [y]: swipe start in keyboard view coordinates */
        @JvmStatic
        fun show(x: Int, y: Int, onHighlight: () -> Unit): Boolean {
            hide()
            val keyboardView = KeyboardSwitcher.getInstance().mainKeyboardView ?: return false
            val content = keyboardView.rootView?.findViewById<ViewGroup>(android.R.id.content) ?: return false
            val (sx, sy) = toScreen(x, y) ?: return false
            return try {
                val overlay = EditMenuOverlay(keyboardView.context, sx, sy, onHighlight)
                content.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                current = overlay
                true
            } catch (e: Exception) {
                Log.w(TAG, "could not show edit menu", e)
                false
            }
        }

        /** [x], [y]: finger in keyboard view coordinates */
        @JvmStatic
        fun move(x: Int, y: Int) {
            val overlay = current ?: return
            val (sx, sy) = toScreen(x, y) ?: return
            overlay.moveTo(sx, sy)
        }

        /** closes the menu and returns the key code of the highlighted item, or null */
        @JvmStatic
        fun finish(): Int? {
            val overlay = current ?: return null
            val code = overlay.highlighted?.code
            hide()
            return code
        }

        @JvmStatic
        fun hide() {
            val overlay = current ?: return
            current = null
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
    }
}

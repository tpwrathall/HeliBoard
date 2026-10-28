// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.inputmethod.TextBoundsInfo
import android.view.inputmethod.TextBoundsInfoResult
import androidx.annotation.RequiresApi
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputConnection

/**
 * Moves the cursor up / down by visual lines while keeping the horizontal position (like iOS),
 * instead of sending arrow keys and letting the app decide where the cursor ends up.
 *
 * Uses [android.view.inputmethod.InputConnection.requestTextBoundsInfo] (Android 14+) to get the
 * on-screen position of every visible character. The remembered horizontal position ("goal x") is
 * kept across several up / down moves and reset whenever the cursor moves horizontally.
 * If the app doesn't support this (or the target line isn't visible), arrow keys are sent instead.
 */
class VerticalCursorMover(
    private val latinIME: LatinIME,
    private val connection: RichInputConnection,
    /** called right before the cursor is moved, e.g. to commit the composing word */
    private val beforeMove: () -> Unit,
    /** called after the cursor was moved */
    private val afterMove: () -> Unit,
    /** fallback: send this many arrow key presses (negative = up) */
    private val sendArrows: (lines: Int) -> Unit,
) {
    private var info: TextBoundsInfo? = null
    private var lastUseTime = 0L
    private var requestInFlight = false
    private var generation = 0
    private var pendingLines = 0
    private var pendingTarget: Target? = null

    /** what is moved: the cursor, or the moving end of a selection */
    class Target(
        /** current offset of the moving position */
        val from: () -> Int,
        /** set the new offset; [changed] is false if the position stays the same */
        val apply: (offset: Int, changed: Boolean) -> Unit,
        /** fallback when the app doesn't help: send this many arrow key presses (negative = up) */
        val fallback: (lines: Int) -> Unit,
    )

    private val cursorTarget = Target(
        from = { connection.expectedSelectionEnd },
        apply = { offset, changed ->
            if (changed || connection.hasSelection()) {
                beforeMove()
                connection.setSelection(offset, offset)
                afterMove()
            }
        },
        fallback = { sendArrows(it) },
    )

    private var goalX: Float? = null
    private var lastPlacedOffset = -1
    // arrow keys were sent as fallback, so the cursor position is not known but the goal x is still valid
    private var keepGoalOnce = false

    val isSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** forget the remembered horizontal position (cursor moved sideways) */
    fun resetGoal() {
        goalX = null
        keepGoalOnce = false
    }

    /** text may have changed, so cached character positions can't be trusted any more */
    fun invalidate() {
        info = null
        generation++
        if (requestInFlight) {
            requestInFlight = false
            flushPendingAsArrows()
        }
    }

    /** move the cursor up (negative) or down by [lines] */
    fun move(lines: Int) = move(lines, cursorTarget)

    fun move(lines: Int, target: Target) {
        if (lines == 0) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            target.fallback(lines)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastUseTime > CACHE_TIMEOUT_MS) info = null // could be outdated
        lastUseTime = now
        val cached = info
        if (cached != null) {
            applyMoves(cached, lines, target)
            return
        }
        if (pendingTarget != null && pendingTarget !== target) flushPendingAsArrows()
        pendingTarget = target
        pendingLines += lines
        if (!requestInFlight) requestBounds()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun requestBounds() {
        val ic = latinIME.currentInputConnection
        if (ic == null) {
            flushPendingAsArrows()
            return
        }
        requestInFlight = true
        val gen = ++generation
        // the visible screen, plus one screen above and below so moving a bit off-screen still works
        val dm = latinIME.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val bounds = RectF(-w, -h, 2 * w, 2 * h)
        try {
            ic.requestTextBoundsInfo(bounds, latinIME.mainExecutor) { result: TextBoundsInfoResult ->
                if (gen != generation) return@requestTextBoundsInfo // invalidated in the meantime
                requestInFlight = false
                val boundsInfo = result.textBoundsInfo
                if (result.resultCode != TextBoundsInfoResult.CODE_SUCCESS || boundsInfo == null) {
                    flushPendingAsArrows()
                    return@requestTextBoundsInfo
                }
                info = boundsInfo
                lastUseTime = SystemClock.uptimeMillis()
                val p = pendingLines
                val t = pendingTarget ?: cursorTarget
                pendingLines = 0
                pendingTarget = null
                applyMoves(boundsInfo, p, t)
            }
        } catch (e: Exception) {
            requestInFlight = false
            flushPendingAsArrows()
        }
    }

    private fun flushPendingAsArrows() {
        val p = pendingLines
        val t = pendingTarget ?: cursorTarget
        pendingLines = 0
        pendingTarget = null
        if (p != 0) t.fallback(p)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun applyMoves(info: TextBoundsInfo, lines: Int, target: Target) {
        if (lines == 0) return
        val start = target.from()
        if (start < 0) {
            target.fallback(lines)
            return
        }
        if (start != lastPlacedOffset && !keepGoalOnce) goalX = null // cursor was moved by something else
        keepGoalOnce = false
        var offset = start
        var remaining = lines
        // whether the text bounds info reaches the end of the text (only needed at the bottom, so computed lazily)
        val reachesEndOfText by lazy {
            // text after cursor is counted from the end of the selection
            val charsToEnd = info.endIndex - connection.expectedSelectionEnd
            charsToEnd >= 0 && (connection.getTextAfterCursor(charsToEnd + 1, 0)?.length ?: Int.MAX_VALUE) <= charsToEnd
        }
        while (remaining != 0) {
            val next = lineMove(info, offset, remaining < 0) { reachesEndOfText } ?: break
            if (next == STAY) {
                // already on the first / last line: do nothing, sending an arrow key would jump to the start / end
                remaining = 0
                break
            }
            offset = next
            remaining -= if (remaining < 0) -1 else 1
        }
        target.apply(offset, offset != start)
        lastPlacedOffset = offset
        if (remaining != 0) {
            // target line not available (e.g. scrolled out of view or empty last line): let the app handle it
            this.info = null
            keepGoalOnce = goalX != null
            target.fallback(remaining)
        }
    }

    /** returns the offset on the line above / below [offset] closest to the goal x, or null if not possible */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun lineMove(info: TextBoundsInfo, offset: Int, up: Boolean, reachesEndOfText: () -> Boolean): Int? {
        val first = info.startIndex
        val end = info.endIndex // exclusive
        val r = RectF()
        val cursorX: Float
        val curTop: Float
        val curBottom: Float
        if (offset in first until end) {
            info.getCharacterBounds(offset, r)
            cursorX = r.left
        } else if (offset == end && offset - 1 >= first) {
            if (isLinefeed(info, offset - 1)) {
                // cursor on an empty last line
                return if (!up && reachesEndOfText()) STAY else null
            }
            info.getCharacterBounds(offset - 1, r)
            cursorX = r.right
        } else {
            return null
        }
        curTop = r.top
        curBottom = r.bottom
        if (curBottom <= curTop) return null
        val goal = goalX ?: cursorX.also { goalX = it }

        // find the nearest line above / below: the character closest to the current line in that direction
        var lineTop = Float.NaN
        var lineBottom = Float.NaN
        for (i in first until end) {
            info.getCharacterBounds(i, r)
            val cy = r.centerY()
            if (up) {
                if (cy < curTop && (lineBottom.isNaN() || r.bottom > lineBottom)) {
                    lineTop = r.top; lineBottom = r.bottom
                }
            } else {
                if (cy > curBottom && (lineTop.isNaN() || r.top < lineTop)) {
                    lineTop = r.top; lineBottom = r.bottom
                }
            }
        }
        if (lineTop.isNaN()) {
            // no line in that direction among the known characters
            if (up) return if (first == 0) STAY else null // first line of the text, or text above is not known
            if (!reachesEndOfText()) return null // more text below that is not known
            // last line of the text, but if the text ends with a line break there is an empty line below it
            return if (end - 1 >= first && isLinefeed(info, end - 1) && offset < end) end else STAY
        }

        // characters on that line, in index order
        var lo = -1
        var hi = -1
        for (i in first until end) {
            info.getCharacterBounds(i, r)
            val cy = r.centerY()
            if (cy >= lineTop && cy <= lineBottom) {
                if (lo == -1) lo = i
                hi = i
            } else if (lo != -1) {
                break // a visual line is a contiguous range
            }
        }
        if (lo == -1) return null

        for (i in lo..hi) {
            if (isLinefeed(info, i)) return i // goal is past the end of a line that ends in a line break
            info.getCharacterBounds(i, r)
            if (goal < r.centerX()) return i
        }
        // goal is to the right of the last character
        val lastIsWhitespace = (info.getCharacterFlags(hi) and TextBoundsInfo.FLAG_CHARACTER_WHITESPACE) != 0
        // after a soft-wrapped line the position after the trailing space is shown on the next line,
        // so stay before the space
        return if (lastIsWhitespace && hi + 1 < end) hi else hi + 1
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun isLinefeed(info: TextBoundsInfo, i: Int) =
        (info.getCharacterFlags(i) and TextBoundsInfo.FLAG_CHARACTER_LINEFEED) != 0

    companion object {
        private const val CACHE_TIMEOUT_MS = 2000L
        /** returned by [lineMove] when the cursor is already on the first / last line */
        private const val STAY = -2
    }
}

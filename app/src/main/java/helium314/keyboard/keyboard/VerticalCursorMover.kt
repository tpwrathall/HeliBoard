// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.inputmethod.TextBoundsInfo
import android.view.inputmethod.TextBoundsInfoResult
import androidx.annotation.RequiresApi
import helium314.keyboard.latin.CursorTracker
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
        gestureGoalX = null
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
                    // no character positions, try the app's cursor position + "select at point" instead
                    val p = pendingLines
                    val t = pendingTarget ?: cursorTarget
                    pendingLines = 0
                    pendingTarget = null
                    gestureOrFallback(p, t)
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

    /**
     * Start / end of the visual line containing [offset], using the app's character positions.
     * Calls [callback] with null if the app doesn't provide them.
     */
    fun visualLineEdge(offset: Int, toStart: Boolean, callback: (Int?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            callback(null)
            return
        }
        val cached = info
        if (cached != null && SystemClock.uptimeMillis() - lastUseTime < CACHE_TIMEOUT_MS) {
            callback(lineEdgeIn(cached, offset, toStart))
            return
        }
        val ic = latinIME.currentInputConnection
        if (ic == null) {
            callback(null)
            return
        }
        val dm = latinIME.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        try {
            ic.requestTextBoundsInfo(RectF(-w, -h, 2 * w, 2 * h), latinIME.mainExecutor) { result ->
                val boundsInfo = result.textBoundsInfo
                if (result.resultCode != TextBoundsInfoResult.CODE_SUCCESS || boundsInfo == null) callback(null)
                else callback(lineEdgeIn(boundsInfo, offset, toStart))
            }
        } catch (e: Exception) {
            callback(null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun lineEdgeIn(info: TextBoundsInfo, offset: Int, toStart: Boolean): Int? {
        val first = info.startIndex
        val end = info.endIndex
        val ref = when {
            offset in first until end -> offset
            offset == end && offset - 1 >= first -> offset - 1
            else -> return null
        }
        val r = RectF()
        info.getCharacterBounds(ref, r)
        val top = r.top
        val bottom = r.bottom
        if (bottom <= top) return null
        fun onLine(i: Int): Boolean {
            info.getCharacterBounds(i, r)
            val cy = r.centerY()
            return cy >= top && cy <= bottom
        }
        if (toStart) {
            var i = ref
            while (i - 1 >= first && onLine(i - 1) && !isLinefeed(info, i - 1)) i--
            return i
        }
        if (isLinefeed(info, ref)) return ref
        var i = ref
        while (i + 1 < end && onLine(i + 1) && !isLinefeed(info, i + 1)) i++
        if (i + 1 < end && isLinefeed(info, i + 1)) return i + 1 // before the line break
        val lastIsWhitespace = (info.getCharacterFlags(i) and TextBoundsInfo.FLAG_CHARACTER_WHITESPACE) != 0
        // soft-wrapped line: the position after the trailing space is on the next line
        return if (lastIsWhitespace && i + 1 < end) i else i + 1
    }

    // ---- second way, for apps without character positions (e.g. browsers): ----
    // cursor position reported by the app (CursorAnchorInfo) + "select at point" (SelectGesture)
    private var gestureBusy = false
    private var gesturePendingLines = 0
    private var gesturePendingTarget: Target? = null
    private var gestureGoalX: Float? = null // screen x
    private var lastGestureOffset = -1
    // screen position of lastGestureOffset, so a selection doesn't need to be collapsed to find it again
    private var lastGestureMarker: CursorTracker.Marker? = null

    private fun gestureOrFallback(lines: Int, target: Target) {
        if (lines == 0) return
        if (!CursorPlacer.isSupported(latinIME) || CursorTracker.lastInfo == null) {
            target.fallback(lines)
            return
        }
        if (gestureBusy) {
            if (gesturePendingTarget != null && gesturePendingTarget !== target) return // switched target mid-way, drop
            gesturePendingTarget = target
            gesturePendingLines += lines
            return
        }
        gestureBusy = true
        // more than one line at once: do them one by one
        val step = lines.coerceIn(-1, 1)
        if (lines != step) {
            gesturePendingTarget = target
            gesturePendingLines += lines - step
        }
        gestureStep(step, target)
    }

    private fun finishGesture() {
        gestureBusy = false
        val lines = gesturePendingLines
        val target = gesturePendingTarget
        gesturePendingLines = 0
        gesturePendingTarget = null
        if (lines != 0 && target != null) gestureOrFallback(lines, target)
    }

    private fun gestureStep(lines: Int, target: Target) {
        val active = target.from()
        if (active < 0) {
            gestureBusy = false
            val all = lines + gesturePendingLines
            gesturePendingLines = 0
            gesturePendingTarget = null
            target.fallback(all)
            return
        }
        val remembered = if (active == lastGestureOffset) lastGestureMarker else null
        if (active != lastGestureOffset) gestureGoalX = null // moved by something else
        val proceed: (CursorTracker.Marker?) -> Unit = proceed@{ marker ->
            if (marker == null) {
                // app doesn't report the cursor position after all
                target.apply(active, true)
                gestureBusy = false
                val all = lines + gesturePendingLines
                gesturePendingLines = 0
                gesturePendingTarget = null
                target.fallback(all)
                return@proceed
            }
            val goal = gestureGoalX ?: marker.x
            gestureTryLine(target, active, goal, marker, lines, 0)
        }
        val current = CursorTracker.marker()
        when {
            remembered != null -> proceed(remembered)
            !connection.hasSelection() && connection.expectedSelectionEnd == active && current != null -> proceed(current)
            else -> {
                // the app only reports the position of a plain cursor, so put the cursor at the moving end once
                connection.setSelection(active, active)
                CursorTracker.awaitNextUpdate(300) { proceed(it ?: CursorTracker.marker()) }
            }
        }
    }

    private fun gestureTryLine(target: Target, active: Int, goal: Float, marker: CursorTracker.Marker, lines: Int, extra: Int) {
        val direction = if (lines < 0) -1 else 1
        val y = marker.centerY + (lines + extra * direction) * marker.height
        if (!isUsablePoint(y)) {
            // outside the text field or the visible screen: never "select" there, some apps (browsers) then
            // replace the selection with nothing. Scroll the text instead, if there is more.
            scrollStep(target, active, goal, marker, direction)
            return
        }
        CursorPlacer.offsetAt(latinIME, clampX(goal), y, marker.height) { offset ->
            when {
                offset != null && offset != active -> gestureDone(target, offset, goal, y, marker.height)
                extra == 0 -> gestureTryLine(target, active, goal, marker, lines, 1) // maybe an empty line, try the next
                else -> gestureStay(target, active, goal, marker) // first / last line: stay, keep the selection
            }
        }
    }

    private fun gestureDone(target: Target, offset: Int, goal: Float, y: Float, height: Float) {
        target.apply(offset, true)
        gestureGoalX = goal
        lastGestureOffset = offset
        lastGestureMarker = CursorTracker.Marker(goal, y - height / 2, y + height / 2)
        finishGesture()
    }

    private fun gestureStay(target: Target, active: Int, goal: Float, marker: CursorTracker.Marker) {
        target.apply(active, true)
        gestureGoalX = goal
        lastGestureOffset = active
        lastGestureMarker = marker
        // the rest of the lines would also go nowhere
        gesturePendingLines = 0
        gesturePendingTarget = null
        finishGesture()
    }

    /**
     * The target line is out of view: put the cursor just past the start / end of the current line, which makes
     * the app scroll it into view, then continue from the new cursor position.
     */
    private fun scrollStep(target: Target, active: Int, goal: Float, marker: CursorTracker.Marker, direction: Int) {
        val bounds = CursorTracker.editorBounds()
        val edgeX = if (direction < 0) (bounds?.left ?: 0f) + 1f
            else (bounds?.right ?: latinIME.resources.displayMetrics.widthPixels.toFloat()) - 1f
        CursorPlacer.offsetAt(latinIME, edgeX, marker.centerY, marker.height) { lineEdge ->
            if (lineEdge == null) {
                gestureStay(target, active, goal, marker)
                return@offsetAt
            }
            val temp = if (direction < 0) lineEdge - 1 else lineEdge + 1
            if (temp < 0) {
                gestureStay(target, active, goal, marker)
                return@offsetAt
            }
            connection.setSelection(temp, temp)
            CursorTracker.awaitNextUpdate(400) { newMarker ->
                val m = newMarker ?: CursorTracker.marker()
                // if the app didn't put the cursor there, there is no more text in that direction
                if (m == null || appCursorOffset() != temp || !isUsablePoint(m.centerY)) {
                    // no further line (end of text) or still not visible
                    gestureStay(target, active, goal, marker)
                    return@awaitNextUpdate
                }
                CursorPlacer.offsetAt(latinIME, clampX(goal), m.centerY, m.height) { offset ->
                    if (offset != null) gestureDone(target, offset, goal, m.centerY, m.height)
                    else gestureDone(target, temp, goal, m.centerY, m.height)
                }
            }
        }
    }

    private fun appCursorOffset(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return connection.expectedSelectionEnd
        val st = try { latinIME.currentInputConnection?.getSurroundingText(0, 0, 0) } catch (e: Exception) { null }
            ?: return null
        if (st.offset < 0 || st.selectionStart < 0) return null
        return st.offset + st.selectionEnd
    }

    /** whether y (screen) is inside the text field and visible above the keyboard */
    private fun isUsablePoint(y: Float): Boolean {
        val bounds = CursorTracker.editorBounds()
        if (bounds != null && (y < bounds.top || y > bounds.bottom)) return false
        if (y < 0) return false
        val keyboardTop = CursorPlacer.keyboardTopOnScreen()
        if (keyboardTop != null && y > keyboardTop) return false
        return true
    }

    private fun clampX(x: Float): Float {
        val bounds = CursorTracker.editorBounds() ?: return x
        return x.coerceIn(bounds.left + 1f, bounds.right - 1f)
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

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.text.InputType
import android.util.SparseArray
import android.view.KeyEvent
import android.view.inputmethod.InputMethodSubtype
import androidx.core.util.forEach
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.compat.CompatBreakIterator
import helium314.keyboard.event.Event
import helium314.keyboard.event.HangulEventDecoder
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.event.HardwareEventDecoder
import helium314.keyboard.event.HardwareKeyboardEventDecoder
import helium314.keyboard.keyboard.internal.LayoutDirective
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.EmojiAltPhysicalKeyDetector
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.TextFieldDiagnostics
import helium314.keyboard.latin.DebugLog
import helium314.keyboard.latin.SelectionWatcher
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.common.WordBreakIterator
import helium314.keyboard.latin.common.combiningRange
import helium314.keyboard.latin.common.stepsToCharCount
import helium314.keyboard.latin.inputlogic.InputLogic
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.BackgroundGatheringCache
import helium314.keyboard.latin.utils.GestureDataGatheringSettings
import helium314.keyboard.latin.utils.RecapitalizeMode
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs

class KeyboardActionListenerImpl(private val latinIME: LatinIME, private val inputLogic: InputLogic) : KeyboardActionListener {

    private val connection = inputLogic.mConnection
    private val emojiAltPhysicalKeyDetector by lazy { EmojiAltPhysicalKeyDetector(latinIME.resources) }

    // We expect to have only one decoder in almost all cases, hence the default capacity of 1.
    // If it turns out we need several, it will get grown seamlessly.
    private val hardwareEventDecoders: SparseArray<HardwareEventDecoder> = SparseArray(1)

    private val keyboardSwitcher = KeyboardSwitcher.getInstance()
    private val settings = Settings.getInstance()
    private val audioAndHapticFeedbackManager = AudioAndHapticFeedbackManager.getInstance()

    // up / down cursor movement that keeps the horizontal position
    private var bypassVerticalCursorMover = false
    private val verticalCursorMover = VerticalCursorMover(
        latinIME, connection,
        beforeMove = {
            inputLogic.setExpectCursorMove()
            inputLogic.finishInput()
        },
        afterMove = { inputLogic.restartSuggestionsOnWordTouchedByCursor(settings.current) },
        sendArrows = { lines -> sendVerticalArrows(lines) },
    )

    // floating cursor (trackpad page and long press on space)
    private val floatingCursorCallbacks by lazy {
        TrackpadOverlay.Callbacks(
            beforePlace = { inputLogic.finishInput() },
            place = { offset ->
                inputLogic.setExpectCursorMove()
                connection.setSelection(offset, offset)
                verticalCursorMover.resetGoal()
                inputLogic.restartSuggestionsOnWordTouchedByCursor(settings.current)
            },
            select = { anchor, offset ->
                inputLogic.setExpectCursorMove()
                selectRange(anchor, offset)
                verticalCursorMover.resetGoal()
                // continue this selection with delete / shift swipes
                selAnchor = anchor
                selActive = offset
                selActiveUnknown = false
            },
            currentOffset = { maxOf(connection.expectedSelectionStart, connection.expectedSelectionEnd) },
        )
    }

    // selection made by swiping from delete or shift: the anchor stays, the active end moves.
    // Kept after the swipe, so the next swipe continues moving the same end if the selection is unchanged.
    private var selAnchor = -1
    private var selActive = -1
    private var selGestureActive = false
    // after shift + arrow fallback the app moved the active end, so it has to be read from the selection
    private var selActiveUnknown = false
    private val selectionTarget = VerticalCursorMover.Target(
        from = { resolveSelActive(); selActive },
        apply = { offset, changed ->
            if (changed) {
                selActive = offset
                selectRange(selAnchor, selActive)
            }
        },
        fallback = { lines -> sendShiftArrows(lines) },
    )

    // language slide state
    private var initialSubtype: InputMethodSubtype? = null
    private var subtypeSwitchCount = 0

    override fun onPressKey(primaryCode: Int, repeatCount: Int, pointerCount: Int, hapticEvent: HapticEvent) {
        metaOnPressKey(primaryCode)
        keyboardSwitcher.onPressKey(primaryCode, pointerCount, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
        // we need to use LatinIME for handling of key-down audio and haptics
        latinIME.hapticAndAudioFeedback(primaryCode, repeatCount, hapticEvent)
    }

    override fun onLongPressKey(primaryCode: Int) {
        metaOnLongPressKey(primaryCode)
        performHapticFeedback(HapticEvent.KEY_LONG_PRESS)
    }

    override fun onReleaseKey(primaryCode: Int, withSliding: Boolean) {
        metaOnReleaseKey(primaryCode)
        keyboardSwitcher.onReleaseKey(primaryCode, withSliding, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
    }

    override fun onKeyUp(keyCode: Int, keyEvent: KeyEvent): Boolean {
        emojiAltPhysicalKeyDetector.onKeyUp(keyEvent)
        if (!settings.current.mEnableHardwareKeyboard)
            return false

        val keyIdentifier = keyEvent.deviceId.toLong() shl 32 + keyEvent.keyCode
        return inputLogic.mCurrentlyPressedHardwareKeys.remove(keyIdentifier)
    }

    override fun onKeyDown(keyCode: Int, keyEvent: KeyEvent): Boolean {
        emojiAltPhysicalKeyDetector.onKeyDown(keyEvent)
        if (!settings.current.mEnableHardwareKeyboard)
            return false

        val event: Event
        if (settings.current.mLocale.language == "ko") { // todo: this does not appear to be the right place
            val subtype = keyboardSwitcher.keyboard?.mId?.subtype ?: RichInputMethodManager.getInstance().currentSubtype
            event = HangulEventDecoder.decodeHardwareKeyEvent(subtype, keyEvent) {
                getHardwareKeyEventDecoder(keyEvent.deviceId).decodeHardwareKey(keyEvent)
            }
        } else {
            event = getHardwareKeyEventDecoder(keyEvent.deviceId).decodeHardwareKey(keyEvent)
        }

        if (event.isHandled) {
            inputLogic.onCodeInput(
                settings.current, event,
                keyboardSwitcher.getKeyboardCapsMode(), // TODO: this is not necessarily correct for a hardware keyboard right now
                latinIME.mHandler
            )
            return true
        }
        return false
    }

    override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
        if (primaryCode == KeyCode.DELETE || primaryCode == KeyCode.ARROW_UP || primaryCode == KeyCode.ARROW_DOWN)
            DebugLog.log("kb key ${Constants.printableCode(primaryCode)}, sel ${connection.expectedSelectionStart}-${connection.expectedSelectionEnd}")
        when (primaryCode) {
            KeyCode.ARROW_UP, KeyCode.ARROW_DOWN -> {
                if (!bypassVerticalCursorMover && metaState == 0 && settings.current.mKeepCursorColumn
                        && verticalCursorMover.isSupported) {
                    verticalCursorMover.move(if (primaryCode == KeyCode.ARROW_UP) -1 else 1)
                    return
                }
            }
            KeyCode.ARROW_LEFT, KeyCode.ARROW_RIGHT -> verticalCursorMover.resetGoal()
            else -> if (!bypassVerticalCursorMover) verticalCursorMover.invalidate()
        }
        when (primaryCode) {
            KeyCode.DEBUG_LOG -> {
                inputLogic.finishInput()
                val report = DebugLog.takeReport(latinIME.currentInputEditorInfo?.packageName)
                latinIME.currentInputConnection?.commitText(report, 1)
                return
            }
            KeyCode.TRACKPAD -> {
                TrackpadOverlay.toggle(latinIME, floatingCursorCallbacks)
                return
            }
            KeyCode.TEXT_FIELD_TEST -> {
                inputLogic.finishInput()
                TextFieldDiagnostics.typeReport(latinIME)
                return
            }
            KeyCode.TOGGLE_AUTOCORRECT -> return settings.toggleAutoCorrect()
            KeyCode.TOGGLE_INCOGNITO_MODE -> {
                settings.toggleAlwaysIncognitoMode()
                BackgroundGatheringCache.clear()
                latinIME.setGestureDataGatheringMode(latinIME.currentInputEditorInfo, false)
                return
            }
            KeyCode.BACKGROUND_GATHERING -> {
                if (BackgroundGatheringCache.isEmpty) {
                    // only enable, no toggle
                    GestureDataGatheringSettings.setBackgroundGatheringEnabled(latinIME.prefs(), true)
                    latinIME.setGestureDataGatheringMode(latinIME.currentInputEditorInfo, false)
                } else {
                    if (GestureDataGatheringSettings.isDiscardByDefault(latinIME))
                        BackgroundGatheringCache.save(latinIME)
                    else
                        BackgroundGatheringCache.clear()
                }
                return
            }
            KeyCode.BACKGROUND_GATHERING_TEMP_OFF -> {
                GestureDataGatheringSettings.tempDisableBackgroundGathering(latinIME.prefs())
                BackgroundGatheringCache.clear()
                latinIME.setGestureDataGatheringMode(latinIME.currentInputEditorInfo, false)
                return
            }
        }
        if (Settings.getValues().mIsLocked && KeyCode.isIsBlockedWhenLocked(primaryCode))
            return
        val mkv = keyboardSwitcher.mainKeyboardView

        // checking if the character is a combining accent
        val event = if (primaryCode in combiningRange) { // todo: should this be done later, maybe in inputLogic?
            Event.createSoftwareDeadEvent(primaryCode, 0, metaState, mkv.getKeyX(x), mkv.getKeyY(y), null)
        } else {
            // todo:
            //  setting meta shift should only be done for arrow and similar cursor movement keys
            //  should only be enabled once it works more reliably (currently depends on app for some reason)
//            if (mkv.keyboard?.mId?.isAlphabetShiftedManually == true)
//                Event.createSoftwareKeypressEvent(primaryCode, metaState or KeyEvent.META_SHIFT_ON, mkv.getKeyX(x), mkv.getKeyY(y), isKeyRepeat)
//            else Event.createSoftwareKeypressEvent(primaryCode, metaState, mkv.getKeyX(x), mkv.getKeyY(y), isKeyRepeat)
            Event.createSoftwareKeypressEvent(primaryCode, metaState, mkv.getKeyX(x), mkv.getKeyY(y), isKeyRepeat)
        }
        latinIME.onEvent(event)
        metaAfterCodeInput(primaryCode)
    }

    override fun onTextInput(text: String?) = latinIME.onTextInput(text)

    override fun onContent(content: InputContentInfoCompat) {
        val editorInfo = latinIME.currentInputEditorInfo
        val editorMimeTypes = EditorInfoCompat.getContentMimeTypes(editorInfo)
        if (editorMimeTypes.any { content.description.hasMimeType(it) }) {
            connection.commitContent(content, editorInfo)
        } else if (editorMimeTypes.isEmpty()) { // make the fallback optional?
            latinIME.clipboardHistoryManager.pasteWithoutChangingClips(content)
        }
    }

    override fun onStartBatchInput() = latinIME.onStartBatchInput()

    override fun onUpdateBatchInput(batchPointers: InputPointers?) = latinIME.onUpdateBatchInput(batchPointers)

    override fun onEndBatchInput(batchPointers: InputPointers?) = latinIME.onEndBatchInput(batchPointers)

    override fun onCancelBatchInput() = latinIME.onCancelBatchInput()

    // User released a finger outside any key
    override fun onCancelInput() { }

    override fun onFinishSlidingInput() =
        keyboardSwitcher.onFinishSlidingInput(latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)

    override fun onCustomRequest(request: KeyboardActionListener.CustomAction) = when (request) {
        KeyboardActionListener.CustomAction.SHOW_INPUT_METHOD_PICKER -> latinIME.showInputPickerDialog()
        KeyboardActionListener.CustomAction.TOUCHPAD_ON -> {
            keyboardSwitcher.mainKeyboardView?.alpha = 0.5f
            true
        }
        KeyboardActionListener.CustomAction.TOUCHPAD_OFF -> {
            keyboardSwitcher.mainKeyboardView?.alpha = 1f
            true
        }
        KeyboardActionListener.CustomAction.PERFORM_HAPTIC -> {
            performHapticFeedback(HapticEvent.KEY_LONG_PRESS)
            true
        }
    }

    override fun onHorizontalSpaceSwipe(steps: Int): Boolean = when (Settings.getValues().mSpaceSwipeHorizontal) {
        KeyboardActionListener.SwipeAction.MOVE_CURSOR -> onMoveCursorHorizontally(steps)
        KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE -> onLanguageSlide(steps)
        KeyboardActionListener.SwipeAction.TOGGLE_NUMPAD -> {
            toggleLayout(LayoutDirective.Utility.NUMPAD, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
            true
        }
        KeyboardActionListener.SwipeAction.TOGGLE_DPAD -> {
            toggleLayout(LayoutDirective.Utility.DPAD, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
            true
        }
        else -> false
    }

    override fun onVerticalSpaceSwipe(steps: Int): Boolean = when (Settings.getValues().mSpaceSwipeVertical) {
        KeyboardActionListener.SwipeAction.MOVE_CURSOR -> onMoveCursorVertically(steps)
        KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE -> onLanguageSlide(steps)
        KeyboardActionListener.SwipeAction.TOGGLE_NUMPAD -> {
            toggleLayout(LayoutDirective.Utility.NUMPAD, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
            true
        }
        KeyboardActionListener.SwipeAction.TOGGLE_DPAD -> {
            toggleLayout(LayoutDirective.Utility.DPAD, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
            true
        }
        KeyboardActionListener.SwipeAction.HIDE_KEYBOARD -> {
            latinIME.requestHideSelf(0)
            true
        }
        KeyboardActionListener.SwipeAction.TOUCHPAD_MODE -> {
            // Activate touchpad mode - the actual cursor movement will be handled in PointerTracker

            // Activation and ensure enough room for navigation.
            val requiredSteps = 8

            if (abs(steps) >= requiredSteps) {
                TouchpadHandler.setTouchpadModeActive(true)
                true
            } else {
                false
            }
        }
        else -> false
    }

    override fun onEndSpaceSwipe() {
        initialSubtype = null
        subtypeSwitchCount = 0
    }

    override fun toggleLayout(layout: LayoutDirective.Utility, autoCapsFlags: Int, recapitalizeMode: RecapitalizeMode?) {
        keyboardSwitcher.toggleLayout(layout, autoCapsFlags, recapitalizeMode)
    }

    override fun onLongPressAlphaSymbolForNumpad() {
        keyboardSwitcher.onLongPressAlphaSymbolForNumpad()
    }

    override fun onMoveDeletePointer(steps: Int) {
        if (steps == 0 || !beginSelectionGesture(fromDelete = true)) return
        val wordMode = settings.current.mDeleteSwipe == 2
        moveSelectionActiveHorizontally(steps, wordMode)
    }

    private fun actualSteps(steps: Int): Int {
        val isSwipeWords = settings.current.mDeleteSwipe == 2
        val multiplier = if (isSwipeWords) 50 else 4 // arbitrary but hopefully high enough numbers
        val text = if (steps > 0) connection.getSelectedText(0) ?: return steps
        else connection.getTextBeforeCursor(-steps * multiplier, 0) ?: return steps

        // iterator could be customizable, but should be used consistently if reasonably possible
        val iterator = if (isSwipeWords) WordBreakIterator.getForSpacingAndPunctuations(settings.current.mSpacingAndPunctuations)
        else CompatBreakIterator.graphemeInstance
        return stepsToCharCount(text, steps, iterator)
    }

    override fun onUpWithDeletePointerActive() {
        selGestureActive = false
        DebugLog.log("kb delete swipe end, sel ${connection.expectedSelectionStart}-${connection.expectedSelectionEnd}")
        if (!connection.hasSelection()) return
        if (settings.current.mDeleteSwipeSelectOnly) return // keep the selection, user decides what to do with it
        inputLogic.finishInput()
        onCodeInput(KeyCode.DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    private fun sendVerticalArrows(lines: Int) {
        bypassVerticalCursorMover = true
        try {
            val code = if (lines < 0) KeyCode.ARROW_UP else KeyCode.ARROW_DOWN
            repeat(abs(lines)) {
                onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            }
        } finally {
            bypassVerticalCursorMover = false
        }
    }

    override fun onMoveShiftSelectPointer(steps: Int) {
        if (steps == 0 || !beginSelectionGesture(fromDelete = false)) return
        // same letters / words setting as delete swipe
        moveSelectionActiveHorizontally(steps, settings.current.mDeleteSwipe == 2)
    }

    override fun onMoveEdgeSelectPointer(steps: Int, fromDelete: Boolean) {
        if (steps == 0 || !beginSelectionGesture(fromDelete)) return
        moveSelectionActiveHorizontally(steps, false)
    }

    // jump to line start / end: where it jumped from, so tapping the same key again goes back
    private var jumpFrom = -1
    private var jumpTo = -1
    private var jumpToStart = false

    override fun onSelectionJumpToLineEdge(toStart: Boolean) {
        if (!selGestureActive) return
        resolveSelActive()
        val from = selActive
        val anchor = selAnchor
        if (from < 0 || anchor < 0) return
        if (jumpFrom >= 0 && jumpTo == from && jumpToStart == toStart) {
            // same key again: back to the selection before the jump
            DebugLog.log("kb jump back $from -> $jumpFrom")
            selActive = jumpFrom
            jumpFrom = -1
            performHapticFeedback(HapticEvent.KEY_PRESS)
            selectRange(selAnchor, selActive)
            return
        }
        val apply: (Int?) -> Unit = { visualEdge ->
            val target = visualEdge ?: textLineEdge(from, toStart)
            DebugLog.log("kb jump ${if (toStart) "start" else "end"}: $from -> $target (visual $visualEdge)")
            if (target != null && target != from && selAnchor == anchor) {
                jumpFrom = from
                jumpTo = target
                jumpToStart = toStart
                selActive = target
                selActiveUnknown = false
                verticalCursorMover.resetGoal()
                performHapticFeedback(HapticEvent.KEY_PRESS)
                selectRange(selAnchor, selActive)
            }
        }
        verticalCursorMover.visualLineEdge(from, toStart, apply)
    }

    /** start / end of the text line (between line breaks) containing [from] */
    private fun textLineEdge(from: Int, toStart: Boolean): Int? {
        val (text, base) = surroundingText(3000) ?: return null
        val pos = from - base
        if (pos < 0 || pos > text.length) return null
        return base + if (toStart) text.lastIndexOf('\n', pos - 1).let { if (it < 0) 0 else it + 1 }
            else text.indexOf('\n', pos).let { if (it < 0) text.length else it }
    }

    override fun onSpaceCaretStart(): Boolean {
        performHapticFeedback(HapticEvent.KEY_LONG_PRESS)
        return TrackpadOverlay.startCaret(latinIME, floatingCursorCallbacks)
    }

    override fun onSpaceCaretMove(dx: Int, dy: Int) = TrackpadOverlay.moveCaret(dx, dy)

    override fun onSpaceCaretEnd(apply: Boolean) = TrackpadOverlay.endCaret(apply)

    override fun onSpaceSwipeMoveVertically(lines: Int) {
        val code = if (lines < 0) KeyCode.ARROW_UP else KeyCode.ARROW_DOWN
        repeat(abs(lines)) {
            if (lines < 0) gestureMoveBackHaptics() else gestureMoveForwardHaptics()
            onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        }
    }

    override fun onEditMenuStart(x: Int, y: Int): Boolean {
        val shown = EditMenuOverlay.show(x, y) { performHapticFeedback(HapticEvent.KEY_PRESS) }
        if (shown) performHapticFeedback(HapticEvent.KEY_LONG_PRESS)
        return shown
    }

    override fun onEditMenuMove(x: Int, y: Int) = EditMenuOverlay.move(x, y)

    override fun onEditMenuEnd(apply: Boolean) {
        val code = EditMenuOverlay.finish() ?: return
        if (!apply) return
        onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    override fun onEndShiftSelect() {
        selGestureActive = false
    }

    override fun onMoveSelectionVertically(lines: Int, fromDelete: Boolean) {
        if (lines == 0 || !beginSelectionGesture(fromDelete)) return
        if (settings.current.mKeepCursorColumn && metaState == 0) verticalCursorMover.move(lines, selectionTarget)
        else sendShiftArrows(lines)
    }

    /**
     * Called for every step of a delete / shift selection swipe. At the start of a swipe, continue with the
     * previous swipe's anchor and moving end if the selection hasn't changed since, otherwise start from the
     * current selection: delete moves its start, shift moves its end.
     */
    private fun beginSelectionGesture(fromDelete: Boolean): Boolean {
        if (selGestureActive) return true
        inputLogic.finishInput()
        // some apps (e.g. normal Android text fields) report a backwards selection with start > end
        val start = minOf(connection.expectedSelectionStart, connection.expectedSelectionEnd)
        val end = maxOf(connection.expectedSelectionStart, connection.expectedSelectionEnd)
        if (start < 0 || end < 0) return false
        resolveSelActive()
        val continuesPrevious = selAnchor >= 0 && start != end
                && minOf(selAnchor, selActive) == start && maxOf(selAnchor, selActive) == end
        if (!continuesPrevious) {
            if (fromDelete) {
                selAnchor = end
                selActive = start
            } else {
                selAnchor = start
                selActive = end
            }
        }
        selActiveUnknown = false
        selGestureActive = true
        jumpFrom = -1
        DebugLog.log("kb selection gesture ${if (fromDelete) "delete" else "shift"}: sel $start-$end, anchor $selAnchor, moving $selActive" +
                if (continuesPrevious) " (continued)" else "")
        return true
    }

    /** sets the selection always start-first: some apps (Keep) otherwise keep a backwards selection, and then
     *  e.g. delete moves the cursor instead of deleting the selected text */
    private fun selectRange(a: Int, b: Int) {
        DebugLog.log("kb set sel $a-$b (anchor $selAnchor)")
        connection.setSelection(minOf(a, b), maxOf(a, b))
    }

    private fun resolveSelActive() {
        if (!selActiveUnknown) return
        val start = minOf(connection.expectedSelectionStart, connection.expectedSelectionEnd)
        val end = maxOf(connection.expectedSelectionStart, connection.expectedSelectionEnd)
        selActive = if (start == selAnchor) end else start
        selActiveUnknown = false
    }

    private fun moveSelectionActiveHorizontally(steps: Int, wordMode: Boolean) {
        resolveSelActive()
        val rtl = RichInputMethodManager.getInstance().currentSubtype.isRtlSubtype
        val directedSteps = if (rtl) -steps else steps
        val newActive = offsetMovedBySteps(selActive, directedSteps, wordMode)
        if (newActive == null) {
            // text around the selection not available: let the app move the selection with shift + arrow keys
            sendShiftArrowsHorizontally(directedSteps, wordMode)
            return
        }
        if (newActive == selActive) return
        if (directedSteps < 0) gestureMoveBackHaptics() else gestureMoveForwardHaptics(true)
        selActive = newActive
        verticalCursorMover.resetGoal()
        selectRange(selAnchor, selActive)
    }

    /** returns [from] moved by [steps] characters (or words), or null if the surrounding text is not available */
    private fun offsetMovedBySteps(from: Int, steps: Int, wordMode: Boolean): Int? {
        val n = abs(steps) * (if (wordMode) 50 else 4) + 16
        val text: CharSequence
        val base: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ask the app directly, the keyboard's cached text and selection may lag behind it
            val ic = latinIME.currentInputConnection ?: return null
            val surrounding = try { ic.getSurroundingText(n, n, 0) } catch (e: Exception) { null } ?: return null
            if (surrounding.offset < 0) return null
            text = surrounding.text
            base = surrounding.offset
        } else {
            val selStart = connection.expectedSelectionStart
            val selEnd = connection.expectedSelectionEnd
            if (selStart < 0 || selEnd < selStart) return null
            val before = connection.getTextBeforeCursor(n, 0) ?: ""
            val selected = if (selEnd > selStart) connection.getSelectedText(0) ?: return null else ""
            val after = connection.getTextAfterCursor(n, 0) ?: ""
            text = "$before$selected$after"
            base = selStart - before.length
        }
        val pos = from - base
        if (pos < 0 || pos > text.length) return null
        val iterator = if (wordMode) WordBreakIterator.getForSpacingAndPunctuations(settings.current.mSpacingAndPunctuations)
            else CompatBreakIterator.graphemeInstance
        val part = if (steps < 0) text.subSequence(0, pos) else text.subSequence(pos, text.length)
        return from + stepsToCharCount(part, steps, iterator)
    }

    private fun sendShiftArrowsHorizontally(steps: Int, wordMode: Boolean) {
        val ic = latinIME.currentInputConnection ?: return
        val keyCode = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        var meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (wordMode) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        repeat(abs(steps)) {
            val time = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            ic.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, meta))
        }
        if (steps < 0) gestureMoveBackHaptics() else gestureMoveForwardHaptics(true)
        verticalCursorMover.resetGoal()
        selActiveUnknown = true
    }

    private val handler = Handler(Looper.getMainLooper())
    private var shiftArrowPending = false
    private var shiftArrowQueued = 0

    /**
     * Fallback for moving the selection end by lines when the app gives no positions: send shift + up / down,
     * then read back what the app did and set the selection explicitly with our anchor. If the app didn't move
     * it (or moved it back), move by text lines (line breaks) instead.
     */
    private fun sendShiftArrows(lines: Int) {
        if (lines == 0) return
        if (shiftArrowPending) {
            shiftArrowQueued += lines
            return
        }
        val ic = latinIME.currentInputConnection ?: return
        resolveSelActive()
        val anchor = selAnchor
        val before = selActive
        if (anchor < 0 || before < 0) return
        // the selection changes the app reports, including short-lived ones (some apps move it, then move it back)
        val seen = mutableListOf<Pair<Int, Int>>()
        val listener: (Int, Int) -> Unit = { a, b -> seen.add(a to b) }
        SelectionWatcher.add(listener)
        val keyCode = if (lines < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        DebugLog.log("kb shift+${if (lines < 0) "up" else "down"} x${abs(lines)}, anchor $anchor, moving $before")
        repeat(abs(lines)) {
            val time = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            ic.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, meta))
        }
        shiftArrowPending = true
        handler.postDelayed({
            SelectionWatcher.remove(listener)
            shiftArrowPending = false
            // the first reported selection that keeps the anchor and moved the other end
            var newActive: Int? = seen.firstNotNullOfOrNull { (s, e) ->
                when {
                    s == anchor && e != before && e != anchor -> e
                    e == anchor && s != before && s != anchor -> s
                    else -> null
                }
            }
            if (newActive == null) {
                val sel = readAppSelection()
                if (sel != null) {
                    val (s, e) = sel
                    newActive = when {
                        s == anchor && e != anchor -> e
                        e == anchor && s != anchor -> s
                        else -> if (lines < 0) s else e
                    }
                }
            }
            if (newActive == null || newActive == before)
                newActive = offsetMovedByTextLines(before, lines) ?: before
            DebugLog.log("kb shift arrows: app reported $seen -> moving end $newActive")
            selActive = newActive
            selActiveUnknown = false
            selectRange(anchor, newActive)
            val queued = shiftArrowQueued
            shiftArrowQueued = 0
            if (queued != 0) sendShiftArrows(queued)
        }, 150)
    }

    /** the selection as the app has it right now (start <= end), or null if unknown */
    private fun readAppSelection(): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ic = latinIME.currentInputConnection ?: return null
            val st = try { ic.getSurroundingText(0, 0, 0) } catch (e: Exception) { null }
            if (st != null && st.offset >= 0 && st.selectionStart >= 0) {
                val a = st.offset + st.selectionStart
                val b = st.offset + st.selectionEnd
                return minOf(a, b) to maxOf(a, b)
            }
        }
        val a = connection.expectedSelectionStart
        val b = connection.expectedSelectionEnd
        if (a < 0 || b < 0) return null
        return minOf(a, b) to maxOf(a, b)
    }

    /** text around the selection: the text and the absolute offset of its first character */
    private fun surroundingText(n: Int): Pair<CharSequence, Int>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ic = latinIME.currentInputConnection ?: return null
            val st = try { ic.getSurroundingText(n, n, 0) } catch (e: Exception) { null } ?: return null
            if (st.offset < 0) return null
            return st.text to st.offset
        }
        val selStart = minOf(connection.expectedSelectionStart, connection.expectedSelectionEnd)
        val selEnd = maxOf(connection.expectedSelectionStart, connection.expectedSelectionEnd)
        if (selStart < 0) return null
        val before = connection.getTextBeforeCursor(n, 0) ?: ""
        val selected = if (selEnd > selStart) connection.getSelectedText(0) ?: return null else ""
        val after = connection.getTextAfterCursor(n, 0) ?: ""
        return "$before$selected$after" to (selStart - before.length)
    }

    /** [from] moved by [lines] text lines (separated by line breaks), keeping the character column if possible */
    private fun offsetMovedByTextLines(from: Int, lines: Int): Int? {
        val (text, base) = surroundingText(3000) ?: return null
        var pos = from - base
        if (pos < 0 || pos > text.length) return null
        repeat(abs(lines)) {
            val lineStart = text.lastIndexOf('\n', pos - 1).let { if (it < 0) 0 else it + 1 }
            val column = pos - lineStart
            if (lines < 0) {
                if (lineStart == 0) return@repeat
                val prevEnd = lineStart - 1 // the line break
                val prevStart = text.lastIndexOf('\n', prevEnd - 1).let { if (it < 0) 0 else it + 1 }
                pos = minOf(prevStart + column, prevEnd)
            } else {
                val lineEnd = text.indexOf('\n', pos).let { if (it < 0) return@repeat else it }
                val nextStart = lineEnd + 1
                val nextEnd = text.indexOf('\n', nextStart).let { if (it < 0) text.length else it }
                pos = minOf(nextStart + column, nextEnd)
            }
        }
        return pos + base
    }

    override fun onEnterSwipeShortcut(targetCode: Int): Boolean {
        if (targetCode <= 0) return false
        val code = when (Character.toLowerCase(targetCode)) {
            'a'.code -> KeyCode.CLIPBOARD_SELECT_ALL
            'c'.code -> KeyCode.CLIPBOARD_COPY
            'x'.code -> KeyCode.CLIPBOARD_CUT
            'v'.code -> KeyCode.CLIPBOARD_PASTE
            'z'.code -> KeyCode.UNDO
            'y'.code -> KeyCode.REDO
            's'.code -> KeyCode.CLIPBOARD_SELECT_WORD
            else -> return false
        }
        performHapticFeedback(HapticEvent.KEY_PRESS)
        onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        return true
    }

    override fun resetMetaState() {
        metaState = 0
    }

    private fun onLanguageSlide(steps: Int): Boolean {
        if (abs(steps) < settings.current.mLanguageSwipeDistance) return false
        val subtypes = SubtypeSettings.getEnabledSubtypes(true)
        if (subtypes.size <= 1) { // only allow if we have more than one subtype
            return false
        }
        // decide next or previous dependent on up or down
        val current = RichInputMethodManager.getInstance().currentSubtype.rawSubtype
        var wantedIndex = subtypes.indexOf(current) + if (steps > 0) 1 else -1
        wantedIndex %= subtypes.size
        if (wantedIndex < 0) {
            wantedIndex += subtypes.size
        }
        val newSubtype = subtypes[wantedIndex]

        // do not switch if we would switch to the initial subtype after cycling all other subtypes
        if (initialSubtype == null) initialSubtype = current
        if (initialSubtype == newSubtype) {
            if ((subtypeSwitchCount > 0 && steps > 0) || (subtypeSwitchCount < 0 && steps < 0)) {
                return true
            }
        }
        if (steps > 0) subtypeSwitchCount++ else subtypeSwitchCount--

        keyboardSwitcher.switchToSubtype(newSubtype)
        return true
    }

    private fun onMoveCursorVertically(steps: Int): Boolean {
        if (steps == 0) return false
        val code = if (steps < 0) {
            gestureMoveBackHaptics()
            KeyCode.ARROW_UP
        } else {
            gestureMoveForwardHaptics()
            KeyCode.ARROW_DOWN
        }
        onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        return true
    }

    private fun onMoveCursorHorizontally(rawSteps: Int): Boolean {
        if (rawSteps == 0) return false
        verticalCursorMover.resetGoal()
        // for RTL languages we want to invert pointer movement
        val rtl = RichInputMethodManager.getInstance().currentSubtype.isRtlSubtype
        val steps = if (rtl) -rawSteps else rawSteps
        val moveSteps: Int
        if (steps < 0) {
            val text = connection.getTextBeforeCursor(-steps * 4, 0) ?: return false
            moveSteps = stepsToCharCount(text, steps, CompatBreakIterator.graphemeInstance)
            if (moveSteps == 0) {
                // some apps don't return any text via input connection, and the cursor can't be moved
                // we fall back to virtually pressing the left/right key one or more times instead
                repeat(-steps) {
                    onCodeInput(if (rtl) KeyCode.ARROW_RIGHT else KeyCode.ARROW_LEFT, Constants.NOT_A_COORDINATE,
                        Constants.NOT_A_COORDINATE, false)
                }
                if (text.isNotEmpty()) {
                    gestureMoveBackHaptics()
                }
                return true
            }
            gestureMoveBackHaptics()
        } else {
            val text = connection.getTextAfterCursor(steps * 4, 0) ?: return false
            moveSteps = stepsToCharCount(text, steps, CompatBreakIterator.graphemeInstance)
            if (moveSteps == 0) {
                // some apps don't return any text via input connection, and the cursor can't be moved
                // we fall back to virtually pressing the left/right key one or more times instead
                repeat(steps) {
                    onCodeInput(if (rtl) KeyCode.ARROW_LEFT else KeyCode.ARROW_RIGHT, Constants.NOT_A_COORDINATE,
                        Constants.NOT_A_COORDINATE, false)
                }
                if (text.isNotEmpty()) {
                    gestureMoveForwardHaptics(true)
                }
                return true
            }
            gestureMoveForwardHaptics(text.isNotEmpty())
        }
        inputLogic.setExpectCursorMove()

        // the shortcut below causes issues due to horrible handling of text fields by Firefox and forks
        // issues:
        //  * setSelection "will cause the editor to call onUpdateSelection", see: https://developer.android.com/reference/android/view/inputmethod/InputConnection#setSelection(int,%20int)
        //     but Firefox is simply not doing this within the same word... WTF?
        //     https://github.com/HeliBorg/HeliBoard/issues/1139#issuecomment-2588169384
        //  * inputType is NOT of variant InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT (variant appears to always be 0)
        //     -> this is "fixed" now using AppWorkarounds.adjustInputType
        val variation = InputType.TYPE_MASK_VARIATION and Settings.getValues().mInputAttributes.mInputType
        if (variation != InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
                && inputLogic.moveCursorByAndReturnIfInsideComposingWord(moveSteps)) {
            // no need to finish input and restart suggestions if we're still in the word
            // this is a noticeable performance improvement when moving through long words
            val newPosition = connection.expectedSelectionStart + moveSteps
            connection.setSelection(newPosition, newPosition)
            return true
        }

        inputLogic.finishInput()
        val newPosition = connection.expectedSelectionStart + moveSteps
        connection.setSelection(newPosition, newPosition)
        inputLogic.restartSuggestionsOnWordTouchedByCursor(settings.current)
        return true
    }

    private fun gestureMoveBackHaptics() {
        if (connection.canDeleteCharacters()) {
            performHapticFeedback(HapticEvent.GESTURE_MOVE)
        }
    }

    // hasTextAfterCursor is used because text before the cursor is cached, going through the InputConnection can be slow
    private fun gestureMoveForwardHaptics(hasTextAfterCursor: Boolean? = null) {
        if (hasTextAfterCursor ?: connection.hasTextAfterCursor()) {
            performHapticFeedback(HapticEvent.GESTURE_MOVE)
        }
    }

    private fun performHapticFeedback(hapticEvent: HapticEvent) {
        audioAndHapticFeedbackManager.performHapticFeedback(keyboardSwitcher.visibleKeyboardView, hapticEvent)
    }

    private fun getHardwareKeyEventDecoder(deviceId: Int): HardwareEventDecoder {
        hardwareEventDecoders.get(deviceId)?.let { return it }

        // TODO: create the decoder according to the specification
        val newDecoder = HardwareKeyboardEventDecoder(deviceId)
        hardwareEventDecoders.put(deviceId, newDecoder)
        return newDecoder
    }

    // -------------------------- meta state handling -----------------------------

    // current state
    // press enables meta
    // release keeps meta enabled, unless there was a onCodeInput for a different key in between
    // onCodeInput ends the meta if it was enabled
    // long press on meta key also ends meta so popups are handled properly
    // sliding from a meta key to some other words too, though this was not intended (and there are no sliding key input graphics)

    // todo: move meta state tracking to KeyboardState? seems more suitable, also for handling sliding input
    //  but the issue is that meta state is used in Event to determine whether it's a functional Event (does not add a character)
    //  (and also it's in the hardware keyEvents which are handled by onKeyUp/Down, but that should be manageable)

    /** actual Android metaState like in KeyEvent */
    private var metaState = 0

    /** keeps track of the state of meta keys by (HeliBoard) KeyCodes */
    private val metaPressStates = SparseArray<MetaPressState>(4)

    // todo: lock and non-lock versions interact badly: when any of them is released, the meta state is removed
    //  this is not wanted, especially because the state of the other key is not affected (still looks pressed)
    private fun metaOnPressKey(primaryCode: Int) {
        val metaCode = primaryCode.toMetaState() ?: return
        if (primaryCode.isMetaLock()) {
            // if unset -> lock, otherwise set to UNSET_ON_RELEASE so it's unset on release
            if (metaPressStates[primaryCode] != MetaPressState.LOCKED) {
                metaPressStates[primaryCode] = MetaPressState.LOCKED
                keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, true)
                metaState = metaState or metaCode
            } else {
                metaPressStates[primaryCode] = MetaPressState.UNSET_ON_RELEASE
            }
            return
        }
        if (metaPressStates[primaryCode] == MetaPressState.RELEASED_BUT_ACTIVE) {
            // meta key is pressed again without other input -> should be disabled on release
            metaPressStates[primaryCode] = MetaPressState.UNSET_ON_RELEASE
        } else {
            // otherwise just press it normally
            metaPressStates[primaryCode] = MetaPressState.PRESSED
        }
        metaState = metaState or metaCode
        // pressed graphics are set anyway, no need to lock it
    }

    // looks like this is not called if there are no popups
    private fun metaOnLongPressKey(primaryCode: Int) {
        if (metaPressStates[primaryCode] != MetaPressState.PRESSED) return
        // we long-pressed a meta key that has popups -> disable so the meta state is not used for the popup
        metaPressStates[primaryCode] = MetaPressState.UNSET
        keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, false)
        val metaCode = primaryCode.toMetaState() ?: return
        metaState = metaState and metaCode.inv()
    }

    private fun metaOnReleaseKey(primaryCode: Int) {
        val metaCode = primaryCode.toMetaState() ?: return
        val metaPressState = metaPressStates[primaryCode]
        if (metaPressState == MetaPressState.UNSET_ON_RELEASE) {
            metaPressStates[primaryCode] = MetaPressState.UNSET
            metaState = metaState and metaCode.inv()
            keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, false)
        } else if (metaPressState == MetaPressState.PRESSED) {
            metaPressStates[primaryCode] = MetaPressState.RELEASED_BUT_ACTIVE
            keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, true)
        }
    }

    private fun metaAfterCodeInput(primaryCode: Int) {
        val metaCode = primaryCode.toMetaState()
        if (metaCode != null) {
            // meta key might be a popup key, we just toggle between set and unset
            val metaPressState = metaPressStates[primaryCode] ?: MetaPressState.UNSET
            if (metaPressState == MetaPressState.UNSET) {
                metaPressStates[primaryCode] = MetaPressState.SET
                metaState = metaState or metaCode
                keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, true)
            } else if (metaPressState == MetaPressState.SET) {
                metaPressStates[primaryCode] = MetaPressState.UNSET
                metaState = metaState and metaCode.inv()
                keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, false)
            }
        } else if (metaState != 0) {
            // non-meta key -> unset all set / released_but_active, and mark pressed as UNSET_ON_RELEASE
            metaPressStates.forEach { key, value ->
                if (value == MetaPressState.RELEASED_BUT_ACTIVE || value == MetaPressState.SET) {
                    metaPressStates[key] = MetaPressState.UNSET
                    keyboardSwitcher.mainKeyboardView?.updateLockState(key, false)
                    val metaCode = key.toMetaState() ?: return@forEach
                    metaState = metaState and metaCode.inv()
                } else if (value == MetaPressState.PRESSED) {
                    metaPressStates[key] = MetaPressState.UNSET_ON_RELEASE
                }
            }
        }
    }

    companion object {
        private enum class MetaPressState {
            UNSET, // default state, not active
            SET, // enabled without onPressKey (e.g. in popup)
            PRESSED, // key is pressed
            UNSET_ON_RELEASE, // key is pressed, but state will be unset on release
            RELEASED_BUT_ACTIVE, // key was released without UNSET_ON_RELEASE state, meta state is still set
            LOCKED, // key is locked and will be released only by pressing the same key again
        }

        private fun Int.toMetaState() = when (this) {
            KeyCode.CTRL, KeyCode.CTRL_LOCK -> KeyEvent.META_CTRL_ON
            KeyCode.CTRL_LEFT               -> KeyEvent.META_CTRL_LEFT_ON
            KeyCode.CTRL_RIGHT              -> KeyEvent.META_CTRL_RIGHT_ON
            KeyCode.ALT, KeyCode.ALT_LOCK   -> KeyEvent.META_ALT_ON
            KeyCode.ALT_LEFT                -> KeyEvent.META_ALT_LEFT_ON
            KeyCode.ALT_RIGHT               -> KeyEvent.META_ALT_RIGHT_ON
            KeyCode.FN, KeyCode.FN_LOCK     -> KeyEvent.META_FUNCTION_ON
            KeyCode.META, KeyCode.META_LOCK -> KeyEvent.META_META_ON
            KeyCode.META_LEFT               -> KeyEvent.META_META_LEFT_ON
            KeyCode.META_RIGHT              -> KeyEvent.META_META_RIGHT_ON
            else -> null
        }

        private fun Int.isMetaLock() = this == KeyCode.CTRL_LOCK || this == KeyCode.ALT_LOCK || this == KeyCode.FN_LOCK || this == KeyCode.META_LOCK
    }
}

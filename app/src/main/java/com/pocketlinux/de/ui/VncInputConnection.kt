package com.pocketlinux.de.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo

/**
 * Bridges the Android IME (soft keyboard) into RFB KeyEvents.
 *
 * The Android IME doesn't think in terms of keys — it thinks in terms of text
 * commits ("here's the string the user typed") and edit operations ("delete N
 * chars before the cursor"). VNC needs actual key events. So we have to fake
 * one: translate text commits into character-by-character KeyEvents synthesized
 * by ourselves.
 *
 * Approach:
 *   - For each character in commitText, send a synthetic key down + up to
 *     parent's dispatchKeyEvent. That triggers the same path hardware keyboard
 *     events take, which our activity already handles.
 *   - For deleteSurroundingText, send DEL key events for each character.
 *   - We do NOT maintain a text buffer. Modern soft keyboards work fine without
 *     one for terminal-style apps as long as we set IME_FLAG_NO_EXTRACT_UI and
 *     TYPE_NULL appropriately.
 *
 * Caveats:
 *   - Predictive text / autocorrect may glitch. We disable suggestions in
 *     EditorInfo to discourage it. Users typing in a terminal don't want
 *     autocorrect anyway.
 *   - Composing text (CJK input methods, swipe input) doesn't translate well
 *     to per-keystroke KeyEvents — we commit on finishComposingText. Power
 *     users running CJK in their Linux desktop should use an external keyboard
 *     or fcitx in the guest.
 */
class VncInputConnection(
    targetView: View,
    fullEditor: Boolean = false
) : BaseInputConnection(targetView, fullEditor) {

    private val target: View = targetView

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text == null) return true
        val s = text.toString()
        for (ch in s) {
            sendCharAsKey(ch)
        }
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        // Only "before" makes sense for a terminal — there's no concept of
        // "delete forward" from an IME perspective in most use cases.
        repeat(beforeLength) {
            sendKey(KeyEvent.KEYCODE_DEL)
        }
        // We don't bother with afterLength; the Linux side will discard
        // forward-deletes if the cursor is at end-of-line anyway.
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        // Some IMEs (gboard with hardware-key-like inputs) call this directly.
        // Forward it to the view's dispatch chain — our activity handles it.
        return target.dispatchKeyEvent(event)
    }

    override fun finishComposingText(): Boolean {
        // We don't track composing text — nothing to commit.
        return true
    }

    /**
     * Take a single character and synthesize a KeyEvent pair that flows through
     * the standard activity onKeyDown/onKeyUp path. We need a non-zero unicode
     * char so InputEncoder.keyEventToKeysym hits its printable branch.
     *
     * For most printable characters there's no exact Android KEYCODE, so we
     * use KEYCODE_UNKNOWN with the unicodeChar set in the event. KeyEvent has
     * no public ctor that accepts unicodeChar, but the standard ctor passes
     * the char through correctly when keyCode is UNKNOWN and the event is
     * synthesized with action+keycode+meta — and the unicodeChar is computed
     * from the meta state. For our purposes we go a simpler route: we send a
     * KEYCODE_ENTER for newline, KEYCODE_DEL for backspace, and for anything
     * else we send a synthetic event whose getUnicodeChar() returns our char.
     */
    private fun sendCharAsKey(ch: Char) {
        when (ch) {
            '\n', '\r' -> {
                sendKey(KeyEvent.KEYCODE_ENTER)
                return
            }
            '\b' -> {
                sendKey(KeyEvent.KEYCODE_DEL)
                return
            }
            '\t' -> {
                sendKey(KeyEvent.KEYCODE_TAB)
                return
            }
        }
        // Construct a synthetic event whose unicodeChar field returns our character.
        // KeyEvent has no constructor that lets us set unicodeChar directly, so we
        // wrap our own subclass that overrides getUnicodeChar(). This is the same
        // trick AVNC and bVNC use.
        val now = SystemClock.uptimeMillis()
        val downEvent = SyntheticUnicodeKeyEvent(
            downTime = now, eventTime = now,
            action = KeyEvent.ACTION_DOWN, ch = ch
        )
        val upEvent = SyntheticUnicodeKeyEvent(
            downTime = now, eventTime = now,
            action = KeyEvent.ACTION_UP, ch = ch
        )
        target.dispatchKeyEvent(downEvent)
        target.dispatchKeyEvent(upEvent)
    }

    private fun sendKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        target.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        target.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    /**
     * KeyEvent subclass whose getUnicodeChar() returns a fixed character.
     * This lets InputEncoder.keyEventToKeysym treat IME-committed text as if
     * it came from a hardware keyboard with the right meta state already
     * applied.
     */
    private class SyntheticUnicodeKeyEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        private val ch: Char
    ) : KeyEvent(downTime, eventTime, action, KEYCODE_UNKNOWN, 0) {
        override fun getUnicodeChar(): Int = ch.code
        override fun getUnicodeChar(metaState: Int): Int = ch.code
    }

    companion object {
        /**
         * Configure an EditorInfo for a terminal-style view. Call this from
         * View.onCreateInputConnection.
         */
        fun configureEditorInfo(outAttrs: EditorInfo) {
            // TYPE_NULL = "I'm not really a text field, but please show the keyboard"
            outAttrs.inputType = EditorInfo.TYPE_NULL
            // Don't show the extract-ui (the giant fullscreen text edit box that
            // appears in landscape on some devices) and don't auto-correct.
            outAttrs.imeOptions = (
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                EditorInfo.IME_ACTION_NONE
            )
        }
    }
}

package com.pocketlinux.de.vnc

import android.view.KeyEvent

/**
 * Maps Android key codes to X11 keysyms (which RFB uses for KeyEvent).
 *
 * X11 keysyms: https://www.x.org/releases/X11R7.7/doc/xproto/x11protocol.html#Keysyms
 *
 * We cover the common alphanumeric/punctuation/control keys. For more obscure
 * ones (media keys, function keys beyond F12) the mapping table can be
 * extended. ASCII printables are their own keysym value (e.g. 'A' -> 0x41),
 * which is convenient.
 */
object InputEncoder {

    // Common non-printable keysyms we'll need
    const val XK_BACKSPACE: Int = 0xFF08
    const val XK_TAB: Int = 0xFF09
    const val XK_RETURN: Int = 0xFF0D
    const val XK_ESCAPE: Int = 0xFF1B
    const val XK_DELETE: Int = 0xFFFF
    const val XK_HOME: Int = 0xFF50
    const val XK_END: Int = 0xFF57
    const val XK_LEFT: Int = 0xFF51
    const val XK_UP: Int = 0xFF52
    const val XK_RIGHT: Int = 0xFF53
    const val XK_DOWN: Int = 0xFF54
    const val XK_PAGE_UP: Int = 0xFF55
    const val XK_PAGE_DOWN: Int = 0xFF56
    const val XK_SHIFT_L: Int = 0xFFE1
    const val XK_CONTROL_L: Int = 0xFFE3
    const val XK_ALT_L: Int = 0xFFE9
    const val XK_SUPER_L: Int = 0xFFEB
    const val XK_F1: Int = 0xFFBE  // F1..F12 are sequential

    /**
     * Translate an Android KeyEvent to an X11 keysym. Returns 0 if we can't
     * map it — caller should drop those rather than send junk to the server.
     */
    fun keyEventToKeysym(event: KeyEvent): Int {
        // First try the printable case — getUnicodeChar gives us the resolved
        // character including shift state, which is exactly what X11 wants
        // for printable keys.
        val unicodeChar = event.unicodeChar
        if (unicodeChar != 0 && unicodeChar < 0x10000) {
            // Latin-1 range maps directly to keysyms
            if (unicodeChar in 0x20..0xFF) return unicodeChar
            // Higher Unicode: keysym = 0x01000000 | codepoint per X11 convention
            return 0x01000000 or unicodeChar
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> XK_BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> XK_DELETE
            KeyEvent.KEYCODE_TAB -> XK_TAB
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> XK_RETURN
            KeyEvent.KEYCODE_ESCAPE -> XK_ESCAPE
            KeyEvent.KEYCODE_DPAD_LEFT -> XK_LEFT
            KeyEvent.KEYCODE_DPAD_UP -> XK_UP
            KeyEvent.KEYCODE_DPAD_RIGHT -> XK_RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> XK_DOWN
            KeyEvent.KEYCODE_MOVE_HOME -> XK_HOME
            KeyEvent.KEYCODE_MOVE_END -> XK_END
            KeyEvent.KEYCODE_PAGE_UP -> XK_PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> XK_PAGE_DOWN
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> XK_SHIFT_L
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> XK_CONTROL_L
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> XK_ALT_L
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> XK_SUPER_L
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                XK_F1 + (event.keyCode - KeyEvent.KEYCODE_F1)
            else -> 0
        }
    }

    /** RFB pointer button bits — match the wire mask in PointerEvent. */
    object Buttons {
        const val LEFT = 1 shl 0
        const val MIDDLE = 1 shl 1
        const val RIGHT = 1 shl 2
        const val WHEEL_UP = 1 shl 3
        const val WHEEL_DOWN = 1 shl 4
    }
}

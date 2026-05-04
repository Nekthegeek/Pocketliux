package com.pocketlinux.de.vnc

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Wire-format constants and tiny helpers for RFB 3.8.
 *
 * Reference: https://datatracker.ietf.org/doc/html/rfc6143
 *
 * RFB is big-endian. All multi-byte integers in the spec are network byte order.
 * Java's DataInputStream/DataOutputStream are also big-endian by default, so we
 * just use those directly — no byte reversal needed.
 */
object RfbProtocol {

    // --- Security types ---------------------------------------------------
    const val SEC_INVALID: Int = 0
    const val SEC_NONE: Int = 1
    const val SEC_VNC_AUTH: Int = 2

    // --- Server-to-client message types -----------------------------------
    const val SMSG_FRAMEBUFFER_UPDATE: Int = 0
    const val SMSG_SET_COLOR_MAP_ENTRIES: Int = 1
    const val SMSG_BELL: Int = 2
    const val SMSG_SERVER_CUT_TEXT: Int = 3

    // --- Client-to-server message types -----------------------------------
    const val CMSG_SET_PIXEL_FORMAT: Int = 0
    const val CMSG_SET_ENCODINGS: Int = 2
    const val CMSG_FRAMEBUFFER_UPDATE_REQUEST: Int = 3
    const val CMSG_KEY_EVENT: Int = 4
    const val CMSG_POINTER_EVENT: Int = 5
    const val CMSG_CLIENT_CUT_TEXT: Int = 6

    // --- Encoding identifiers ---------------------------------------------
    const val ENC_RAW: Int = 0
    const val ENC_COPY_RECT: Int = 1
    // "Pseudo" encodings — not real pixel data, just signals
    const val ENC_DESKTOP_SIZE: Int = -223
    const val ENC_CURSOR: Int = -239

    /** Read exactly [n] bytes or throw — DataInputStream.read may return fewer. */
    fun readFully(input: DataInputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        input.readFully(buf)
        return buf
    }

    /** Read a length-prefixed string (uint32 length, then UTF-8 bytes). */
    fun readString(input: DataInputStream): String {
        val len = input.readInt()
        if (len <= 0) return ""
        return String(readFully(input, len), Charsets.UTF_8)
    }

    /** Pack a PointerEvent message body. */
    fun writePointerEvent(out: DataOutputStream, buttonMask: Int, x: Int, y: Int) {
        out.writeByte(CMSG_POINTER_EVENT)
        out.writeByte(buttonMask)
        out.writeShort(x)
        out.writeShort(y)
        out.flush()
    }

    /** Pack a KeyEvent message body. keysym is X11 keysym, not Android keycode. */
    fun writeKeyEvent(out: DataOutputStream, down: Boolean, keysym: Int) {
        out.writeByte(CMSG_KEY_EVENT)
        out.writeByte(if (down) 1 else 0)
        out.writeShort(0)             // padding
        out.writeInt(keysym)
        out.flush()
    }

    /** Request an update for the given rectangle. incremental=true asks for diff. */
    fun writeFramebufferUpdateRequest(
        out: DataOutputStream,
        incremental: Boolean,
        x: Int, y: Int, w: Int, h: Int
    ) {
        out.writeByte(CMSG_FRAMEBUFFER_UPDATE_REQUEST)
        out.writeByte(if (incremental) 1 else 0)
        out.writeShort(x)
        out.writeShort(y)
        out.writeShort(w)
        out.writeShort(h)
        out.flush()
    }

    /**
     * Write the SetEncodings message — tell server which encodings we
     * understand. Order matters: server prefers earlier ones.
     */
    fun writeSetEncodings(out: DataOutputStream, encodings: IntArray) {
        out.writeByte(CMSG_SET_ENCODINGS)
        out.writeByte(0)                       // padding
        out.writeShort(encodings.size)
        for (e in encodings) out.writeInt(e)
        out.flush()
    }

    /**
     * Write SetPixelFormat. We always force RGBA8888 little-endian, true-color,
     * because that's what we want to draw into our Bitmap and it saves us from
     * implementing all the colormap and depth permutations RFB allows.
     *
     * Pixel format struct (16 bytes):
     *   u8  bits-per-pixel
     *   u8  depth
     *   u8  big-endian-flag
     *   u8  true-colour-flag
     *   u16 red-max
     *   u16 green-max
     *   u16 blue-max
     *   u8  red-shift
     *   u8  green-shift
     *   u8  blue-shift
     *   u8[3] padding
     */
    fun writeSetPixelFormat(out: DataOutputStream) {
        out.writeByte(CMSG_SET_PIXEL_FORMAT)
        out.writeByte(0); out.writeByte(0); out.writeByte(0)   // padding
        out.writeByte(32)                                      // bpp
        out.writeByte(24)                                      // depth
        out.writeByte(0)                                       // little-endian
        out.writeByte(1)                                       // true-color
        out.writeShort(255); out.writeShort(255); out.writeShort(255)  // max RGB
        // Shift values: where each component lives in the 32-bit word.
        // We pick 16/8/0 (R/G/B) — Bitmap.Config.ARGB_8888 stores ARGB but
        // when written little-endian as int, byte order in memory is BGRA.
        // We adjust on the decode side.
        out.writeByte(16)  // red shift
        out.writeByte(8)   // green shift
        out.writeByte(0)   // blue shift
        out.writeByte(0); out.writeByte(0); out.writeByte(0)   // padding
        out.flush()
    }
}

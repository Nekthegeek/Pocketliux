package com.pocketlinux.de.vnc

import android.graphics.Bitmap
import java.io.DataInputStream

/**
 * Decodes the RFB CopyRect encoding.
 *
 * Body: just two uint16 values — the (srcX, srcY) of the rectangle to copy
 * from. The size and dest are already known from the rectangle header.
 *
 * This is the killer optimization: when a window scrolls or moves, the server
 * sends a CopyRect telling us "the rect you already have at (srcX, srcY) is
 * now at (dstX, dstY)" — no pixel data on the wire at all. For a localhost
 * connection this is mostly cosmetic (we have unlimited bandwidth), but it
 * also reduces bitmap-write work which IS slow on a phone CPU.
 */
class CopyRectDecoder {

    /**
     * Source and destination must be inside [bitmap]. Overlapping copies must
     * be handled correctly — Bitmap.getPixels into a temp buffer then setPixels
     * does this safely because we're not writing back into the source while
     * still reading from it.
     */
    fun decode(
        input: DataInputStream,
        bitmap: Bitmap,
        dstX: Int, dstY: Int, w: Int, h: Int
    ) {
        val srcX = input.readUnsignedShort()
        val srcY = input.readUnsignedShort()

        // For a fast non-overlapping case we could copy line-by-line in place,
        // but the simple correct path is: read source into scratch, write to
        // destination. The cost is one width*height int-array allocation.
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, srcX, srcY, w, h)
        bitmap.setPixels(pixels, 0, w, dstX, dstY, w, h)
    }
}

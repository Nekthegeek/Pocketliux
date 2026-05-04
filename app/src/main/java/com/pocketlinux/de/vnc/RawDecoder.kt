package com.pocketlinux.de.vnc

import android.graphics.Bitmap
import java.io.DataInputStream

/**
 * Decodes RFB Raw encoding directly into the framebuffer Bitmap.
 *
 * Raw encoding = exactly width*height*4 bytes of pixel data, in the pixel
 * format we negotiated (RGBA8888 little-endian per [RfbProtocol.writeSetPixelFormat]).
 *
 * The wire format with shift = 16/8/0 puts bytes in memory as B G R 0 (because
 * little-endian writes the low byte first, and B is at shift 0). Android's
 * Bitmap.Config.ARGB_8888 wants pixels packed as 0xAARRGGBB in int form, which
 * when converted to bytes via Bitmap.copyPixelsFromBuffer is also B G R A in
 * native byte order on little-endian devices. So byte-for-byte we can copy
 * directly... almost. The alpha byte is 0 in the wire format (server ignores
 * it) which would render as fully transparent. We force it to 0xFF.
 */
class RawDecoder {

    /**
     * Read width*height pixels from [input] and write them to [bitmap] at (x,y).
     *
     * We use a reusable line buffer so a 1280×720 update doesn't allocate
     * 3.5 MB of garbage per frame.
     */
    fun decode(
        input: DataInputStream,
        bitmap: Bitmap,
        x: Int, y: Int, w: Int, h: Int,
        scratch: ByteArray = ByteArray(w * 4)
    ) {
        // Ensure scratch is big enough for one row
        val rowBytes = w * 4
        val buf = if (scratch.size >= rowBytes) scratch else ByteArray(rowBytes)

        // Pixel buffer the bitmap will read from. We update it row by row so
        // memory pressure stays bounded.
        val pixels = IntArray(w)

        for (row in 0 until h) {
            input.readFully(buf, 0, rowBytes)
            // Pack 4 bytes per pixel into one int per pixel, forcing alpha=0xFF.
            // Wire bytes: B G R A   (shift 0/8/16 with little-endian write)
            // Bitmap int: 0xAARRGGBB
            for (col in 0 until w) {
                val i = col * 4
                val b = buf[i].toInt()       and 0xFF
                val g = buf[i + 1].toInt()   and 0xFF
                val r = buf[i + 2].toInt()   and 0xFF
                pixels[col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(pixels, 0, w, x, y + row, w, 1)
        }
    }
}

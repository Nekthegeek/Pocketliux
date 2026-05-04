package com.pocketlinux.de.vnc

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin RFB 3.8 client.
 *
 * Public lifecycle:
 *   - construct
 *   - call connect(host, port, password) — runs handshake, returns ServerInfo
 *   - call startReadLoop() — spawns the IO coroutine that reads updates
 *   - while running, call sendPointer / sendKey / requestUpdate
 *   - call close() to shut down
 *
 * Threading: the read loop runs on Dispatchers.IO. Callers should dispatch
 * UI work to Main when reacting to [events].
 *
 * Why our own implementation: see ARCHITECTURE.md. tl;dr — we only talk to
 * our own server on localhost. The exotic encodings we'd need libvncclient
 * for are unnecessary in that scenario.
 */
class RfbClient {

    /** Events the UI cares about. */
    sealed class Event {
        data class FrameUpdated(val x: Int, val y: Int, val w: Int, val h: Int) : Event()
        data class Resized(val w: Int, val h: Int) : Event()
        data class CutText(val text: String) : Event()
        object Bell : Event()
        data class Error(val message: String, val cause: Throwable? = null) : Event()
        object Disconnected : Event()
    }

    data class ServerInfo(val width: Int, val height: Int, val name: String)

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: Flow<Event> = _events

    /** The framebuffer. Allocated after handshake when we know dimensions. */
    @Volatile private var framebuffer: Bitmap? = null
    val bitmap: Bitmap? get() = framebuffer

    private val rawDecoder = RawDecoder()
    private val copyRectDecoder = CopyRectDecoder()

    /**
     * Connect, handshake, and authenticate. Returns server dimensions on success
     * or throws on protocol errors / wrong password.
     */
    suspend fun connect(host: String, port: Int, password: String): ServerInfo =
        withContext(Dispatchers.IO) {
            val s = Socket()
            // 5-second connect timeout to localhost is generous
            s.connect(InetSocketAddress(host, port), 5_000)
            s.tcpNoDelay = true
            socket = s
            input = DataInputStream(BufferedInputStream(s.getInputStream()))
            output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))

            handshakeProtocolVersion()
            handshakeSecurity(password)
            sendClientInit()
            val info = readServerInit()

            // Negotiate pixel format and encodings
            RfbProtocol.writeSetPixelFormat(output!!)
            RfbProtocol.writeSetEncodings(
                output!!,
                intArrayOf(
                    RfbProtocol.ENC_COPY_RECT,
                    RfbProtocol.ENC_RAW,
                    RfbProtocol.ENC_DESKTOP_SIZE
                )
            )

            framebuffer = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)

            // Kick off the first full update
            RfbProtocol.writeFramebufferUpdateRequest(
                output!!, incremental = false,
                x = 0, y = 0, w = info.width, h = info.height
            )

            info
        }

    fun startReadLoop() {
        readJob = scope.launch { readLoop() }
    }

    suspend fun sendPointer(buttonMask: Int, x: Int, y: Int) = withContext(Dispatchers.IO) {
        try { RfbProtocol.writePointerEvent(output!!, buttonMask, x, y) }
        catch (e: Exception) { reportError("sendPointer failed", e) }
    }

    suspend fun sendKey(down: Boolean, keysym: Int) = withContext(Dispatchers.IO) {
        if (keysym == 0) return@withContext
        try { RfbProtocol.writeKeyEvent(output!!, down, keysym) }
        catch (e: Exception) { reportError("sendKey failed", e) }
    }

    suspend fun requestIncrementalUpdate(w: Int, h: Int) = withContext(Dispatchers.IO) {
        try {
            RfbProtocol.writeFramebufferUpdateRequest(
                output!!, incremental = true, x = 0, y = 0, w = w, h = h
            )
        } catch (e: Exception) { reportError("update request failed", e) }
    }

    fun close() {
        readJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        scope.cancel()
    }

    // === Handshake details ===================================================

    /**
     * RFB 3.8 ProtocolVersion exchange. Server sends "RFB 003.008\n" (12 bytes);
     * we reply with the same. We don't try to negotiate down to older protocols
     * because TigerVNC speaks 3.8 and that's all we ship.
     */
    private fun handshakeProtocolVersion() {
        val srvVersion = RfbProtocol.readFully(input!!, 12)
        Log.i(TAG, "Server protocol: ${String(srvVersion).trim()}")
        output!!.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        output!!.flush()
    }

    /**
     * RFB 3.8 Security handshake.
     *
     * Server sends:  u8 numTypes, u8[numTypes] types
     * If numTypes==0, this is followed by a length-prefixed reason string and
     * the connection closes — we treat that as failure.
     *
     * We pick our preferred type (None > VncAuth) and write a single u8 reply.
     * Then for VncAuth, the auth challenge dance happens. Either way the server
     * follows up with a 32-bit SecurityResult: 0=ok, 1=failed.
     */
    private fun handshakeSecurity(password: String) {
        val numTypes = input!!.readUnsignedByte()
        if (numTypes == 0) {
            val reason = RfbProtocol.readString(input!!)
            error("Server refused connection: $reason")
        }
        val offered = ByteArray(numTypes).also { input!!.readFully(it) }
            .map { it.toInt() and 0xFF }

        val chosen = when {
            RfbProtocol.SEC_NONE in offered -> RfbProtocol.SEC_NONE
            RfbProtocol.SEC_VNC_AUTH in offered -> RfbProtocol.SEC_VNC_AUTH
            else -> error("No supported security type. Offered: $offered")
        }
        output!!.writeByte(chosen)
        output!!.flush()

        if (chosen == RfbProtocol.SEC_VNC_AUTH) {
            performVncAuth(password)
        }

        // Read SecurityResult
        val result = input!!.readInt()
        if (result != 0) {
            val reason = if (input!!.available() > 0) RfbProtocol.readString(input!!) else "Auth failed"
            error("Authentication failed: $reason")
        }
    }

    /**
     * VNC authentication: server sends 16-byte challenge, we encrypt it with
     * the password as a DES key and send the 16-byte response back.
     *
     * Two well-known weirdnesses of VNC's DES auth:
     *   1. The password is truncated/zero-padded to exactly 8 bytes
     *   2. Each byte of the password key has its bits reversed before use
     *      (an artifact of an old endian bug that became part of the spec)
     */
    private fun performVncAuth(password: String) {
        val challenge = RfbProtocol.readFully(input!!, 16)
        val key = ByteArray(8)
        val pwBytes = password.toByteArray(Charsets.US_ASCII)
        for (i in 0 until minOf(8, pwBytes.size)) {
            key[i] = reverseBits(pwBytes[i])
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        val response = cipher.doFinal(challenge)
        output!!.write(response)
        output!!.flush()
    }

    private fun reverseBits(b: Byte): Byte {
        var x = b.toInt() and 0xFF
        x = (x and 0xF0 ushr 4) or (x and 0x0F shl 4)
        x = (x and 0xCC ushr 2) or (x and 0x33 shl 2)
        x = (x and 0xAA ushr 1) or (x and 0x55 shl 1)
        return x.toByte()
    }

    /** ClientInit: 1 byte, "shared" flag. We always say yes. */
    private fun sendClientInit() {
        output!!.writeByte(1)
        output!!.flush()
    }

    /**
     * ServerInit: width(u16), height(u16), pixel-format(16 bytes), name(string).
     * We don't use the server's pixel format — we'll override it with SetPixelFormat
     * right after.
     */
    private fun readServerInit(): ServerInfo {
        val w = input!!.readUnsignedShort()
        val h = input!!.readUnsignedShort()
        // Skip the pixel format
        RfbProtocol.readFully(input!!, 16)
        val name = RfbProtocol.readString(input!!)
        Log.i(TAG, "ServerInit: ${w}x$h, name=$name")
        return ServerInfo(w, h, name)
    }

    // === Read loop ===========================================================

    private suspend fun readLoop() {
        try {
            while (scope.isActive) {
                val msg = input!!.readUnsignedByte()
                when (msg) {
                    RfbProtocol.SMSG_FRAMEBUFFER_UPDATE -> handleFramebufferUpdate()
                    RfbProtocol.SMSG_SET_COLOR_MAP_ENTRIES -> skipColorMap()
                    RfbProtocol.SMSG_BELL -> _events.emit(Event.Bell)
                    RfbProtocol.SMSG_SERVER_CUT_TEXT -> handleCutText()
                    else -> {
                        Log.w(TAG, "Unknown server message type: $msg")
                        // No way to know how many bytes to skip, so we have to bail.
                        // In practice this shouldn't happen for TigerVNC.
                        break
                    }
                }
            }
        } catch (e: Exception) {
            if (scope.isActive) reportError("read loop", e)
        } finally {
            _events.emit(Event.Disconnected)
        }
    }

    private suspend fun handleFramebufferUpdate() {
        input!!.readUnsignedByte()                // padding
        val numRects = input!!.readUnsignedShort()
        val fb = framebuffer ?: error("Framebuffer not initialized")
        repeat(numRects) {
            val x = input!!.readUnsignedShort()
            val y = input!!.readUnsignedShort()
            val w = input!!.readUnsignedShort()
            val h = input!!.readUnsignedShort()
            val encoding = input!!.readInt()
            when (encoding) {
                RfbProtocol.ENC_RAW -> {
                    rawDecoder.decode(input!!, fb, x, y, w, h)
                    _events.emit(Event.FrameUpdated(x, y, w, h))
                }
                RfbProtocol.ENC_COPY_RECT -> {
                    copyRectDecoder.decode(input!!, fb, x, y, w, h)
                    _events.emit(Event.FrameUpdated(x, y, w, h))
                }
                RfbProtocol.ENC_DESKTOP_SIZE -> {
                    // Server resized — reallocate the bitmap
                    framebuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    _events.emit(Event.Resized(w, h))
                }
                else -> {
                    // Unsupported encoding — for safety, bail. In practice the
                    // server only sends what we asked for via SetEncodings.
                    Log.e(TAG, "Server sent unsupported encoding $encoding")
                    error("Unsupported encoding: $encoding")
                }
            }
        }
    }

    private fun skipColorMap() {
        input!!.readUnsignedByte()                // padding
        input!!.readUnsignedShort()               // first-color
        val n = input!!.readUnsignedShort()
        // Each entry is 6 bytes (R,G,B as u16 each). We discard — we forced TrueColor.
        RfbProtocol.readFully(input!!, n * 6)
    }

    private suspend fun handleCutText() {
        // 3 bytes padding, then length-prefixed text
        input!!.readUnsignedByte(); input!!.readUnsignedByte(); input!!.readUnsignedByte()
        val text = RfbProtocol.readString(input!!)
        _events.emit(Event.CutText(text))
    }

    private suspend fun reportError(where: String, cause: Throwable) {
        Log.e(TAG, "RFB error in $where", cause)
        _events.emit(Event.Error("$where: ${cause.message}", cause))
    }

    companion object { private const val TAG = "RfbClient" }
}

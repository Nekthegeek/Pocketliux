package com.pocketlinux.de.bootstrap

import android.util.Log
import com.pocketlinux.de.termux.TermuxBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Starts (or kills) a VNC session inside the given distro.
 *
 * Conventions we use:
 *   - Display :1, port 5901 — VNC's standard "first display" mapping
 *   - Bound to localhost only via -localhost yes — never exposed to the network
 *   - Geometry matches the device's display in landscape
 *   - Password is fixed per-install in ~/.vnc/passwd inside the distro; the
 *     bootstrap script generates a random one and writes it to a file we read
 */
class SessionLauncher(private val termux: TermuxBridge) {

    /**
     * Start vncserver inside the distro. Returns once port 5901 is reachable
     * on localhost, or null if it didn't come up within [waitMs].
     */
    suspend fun startSession(
        profile: DistroProfile,
        widthPx: Int,
        heightPx: Int,
        waitMs: Long = 30_000L
    ): SessionInfo? = withContext(Dispatchers.IO) {

        // Kill any stale session first so we always start fresh — vncserver gets
        // confused by a previous PID file and refuses to start otherwise.
        termux.runInDistro(
            profile.prootDistroName,
            "vncserver -kill :1 >/dev/null 2>&1 || true",
            timeoutMs = 10_000L
        )

        // Use TigerVNC's `-localhost yes` so the listener binds to 127.0.0.1.
        // -SecurityTypes VncAuth keeps the standard password challenge.
        // -xstartup ~/.vnc/xstartup launches the WM (set by bootstrap script).
        val cmd = """
            export DISPLAY=:1
            vncserver :1 \
                -geometry ${widthPx}x${heightPx} \
                -depth 24 \
                -localhost yes \
                -SecurityTypes VncAuth \
                >/tmp/vnc.log 2>&1
        """.trimIndent()

        val r = termux.runInDistro(profile.prootDistroName, cmd, timeoutMs = 15_000L)
        if (r == null) {
            Log.e(TAG, "starting vncserver returned null")
            return@withContext null
        }
        Log.i(TAG, "vncserver start exit=${r.exitCode}, output: ${r.stdout}\nstderr: ${r.stderr}")

        // Read the password the bootstrap created. It's stored in plain text in
        // a file we wrote during bootstrap (NOT the obfuscated ~/.vnc/passwd
        // which is in vncpasswd's binary format).
        val pw = termux.runInDistro(
            profile.prootDistroName,
            "cat ~/.pocketlinux/vncpw 2>/dev/null"
        )?.stdout?.trim().orEmpty()
        if (pw.isEmpty()) {
            Log.e(TAG, "Could not read VNC password file — bootstrap incomplete?")
            return@withContext null
        }

        if (!waitForPort(5901, waitMs)) {
            return@withContext null
        }
        SessionInfo(host = "127.0.0.1", port = 5901, password = pw)
    }

    suspend fun stopSession(profile: DistroProfile) {
        termux.runInDistro(
            profile.prootDistroName,
            "vncserver -kill :1 >/dev/null 2>&1 || true",
            timeoutMs = 10_000L
        )
    }

    /** Try to TCP-connect to localhost:port until it accepts or we time out. */
    private suspend fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 500)
                    return true
                }
            } catch (_: Exception) {
                delay(500)
            }
        }
        return false
    }

    data class SessionInfo(val host: String, val port: Int, val password: String)

    companion object { private const val TAG = "SessionLauncher" }
}

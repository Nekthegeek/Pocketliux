package com.pocketlinux.de.termux

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * Sends commands to Termux's RunCommandService and waits for completion.
 *
 * Why this exists in this exact shape: the alternatives all have problems.
 *
 *   - PendingIntent result API: only works since Termux 0.109 and is racy
 *     under app process death
 *   - tasker plugin: requires we declare ourselves a Tasker plugin host, gross
 *   - termux-am socket: only works inside Termux itself
 *
 * Our approach is older-school but rock-solid: we tell Termux to run our shell
 * snippet, redirect its output to a known file in shared storage, and write
 * an exit code marker file when done. We poll for the marker file. This works
 * across every Termux version that supports RUN_COMMAND at all (0.95+).
 *
 * The only catch is that the user must have set allow-external-apps=true in
 * ~/.termux/termux.properties — we test for this on first run.
 */
class TermuxBridge(private val context: Context) {

    /**
     * Run a shell command inside Termux's main environment (NOT inside a proot
     * distro — for that, you wrap with `proot-distro login <name> -- <cmd>`).
     *
     * Returns:
     *   - exitCode and stdout/stderr if the command completed within [timeoutMs]
     *   - null if it timed out (either Termux didn't respond at all, meaning
     *     allow-external-apps is false, or the command genuinely took too long)
     */
    suspend fun runShell(
        command: String,
        timeoutMs: Long = 60_000L,
        background: Boolean = true
    ): Result? = withContext(Dispatchers.IO) {
        val token = UUID.randomUUID().toString()
        val resultDir = ensureResultDir()
        val stdoutFile = File(resultDir, "$token.out")
        val stderrFile = File(resultDir, "$token.err")
        val exitFile = File(resultDir, "$token.exit")

        // We wrap the user's command so that whatever it does, we get an exit
        // code marker at the end. The marker file's existence is what we poll.
        // Note: Termux home is /data/data/com.termux/files/home — accessible by
        // Termux but not by us. We use the SHARED storage path that Termux can
        // write to and we can read.
        val sharedPath = sharedDirInTermuxView()
        val wrapped = """
            ( $command ) > "$sharedPath/$token.out" 2> "$sharedPath/$token.err"
            echo $? > "$sharedPath/$token.exit"
        """.trimIndent()

        val intent = Intent().apply {
            component = ComponentName(
                "com.termux",
                "com.termux.app.RunCommandService"
            )
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrapped))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            // Don't open the Termux UI for these — we run silently.
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }

        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Termux service. Termux not installed or restricted?", e)
            return@withContext null
        }

        // Poll for the exit-code file with a 250ms tick. We read everything when
        // the marker appears. If it never appears within the timeout, we give up.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (exitFile.exists()) {
                val exit = exitFile.readText().trim().toIntOrNull() ?: -1
                val out = if (stdoutFile.exists()) stdoutFile.readText() else ""
                val err = if (stderrFile.exists()) stderrFile.readText() else ""
                // Clean up so we don't leak files
                stdoutFile.delete()
                stderrFile.delete()
                exitFile.delete()
                return@withContext Result(exit, out, err)
            }
            delay(250)
        }
        Log.w(TAG, "Termux command timed out after ${timeoutMs}ms")
        null
    }

    /**
     * Confirms that allow-external-apps=true and Termux is alive. Cheaper than
     * a full bootstrap to fail later — we send an `echo` and see what comes back.
     */
    suspend fun pingTermux(): Boolean {
        val r = runShell("echo pong", timeoutMs = 5_000L) ?: return false
        return r.exitCode == 0 && r.stdout.trim() == "pong"
    }

    /**
     * Same as [runShell] but wraps the command in `proot-distro login <distro>`
     * so it runs inside the chosen Linux container.
     */
    suspend fun runInDistro(distro: String, command: String, timeoutMs: Long = 60_000L) =
        runShell(
            "proot-distro login $distro --shared-tmp -- bash -lc ${shQuote(command)}",
            timeoutMs = timeoutMs
        )

    private fun ensureResultDir(): File {
        // We use the app's external files dir — Android Q+ scoped storage doesn't
        // restrict it, and Termux can read here without extra permissions because
        // it has the shared-storage hook (`termux-setup-storage`).
        val dir = File(context.getExternalFilesDir(null), "termux-results").apply { mkdirs() }
        return dir
    }

    /**
     * Path to our results dir as Termux sees it. Termux mounts shared storage
     * at $HOME/storage/external-1 (or ~/storage/shared) once `termux-setup-storage`
     * has been run. We arrange for that to happen during onboarding.
     */
    private fun sharedDirInTermuxView(): String {
        // The actual physical path inside Termux — see termux-setup-storage docs.
        // Maps Android/data/<our.pkg>/files/termux-results to a Termux-readable path.
        return "/storage/emulated/0/Android/data/${context.packageName}/files/termux-results"
    }

    /** Single-quote a string for safe inclusion in a bash command. */
    private fun shQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    companion object { private const val TAG = "TermuxBridge" }
}

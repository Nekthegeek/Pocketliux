package com.pocketlinux.de.bootstrap

import android.content.Context
import android.util.Log
import com.pocketlinux.de.termux.TermuxBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Drives the multi-step install of a chosen distro.
 *
 * We emit progress as a Flow so the UI can show a live log. Steps:
 *
 *   1. Termux-side prep:
 *      - termux-setup-storage (idempotent — bails if already done)
 *      - pkg install proot-distro tigervnc
 *   2. Distro install:
 *      - proot-distro install <name>   (~5 minutes on a slow phone)
 *   3. Distro provisioning:
 *      - run the bundled bootstrap script inside the distro
 *
 * Each step reports its result; on failure we surface stderr to the user.
 */
class BootstrapScript(
    private val context: Context,
    private val termux: TermuxBridge
) {

    sealed class Progress {
        data class Step(val name: String, val detail: String = "") : Progress()
        data class Output(val line: String) : Progress()
        data class Done(val success: Boolean, val errorMessage: String? = null) : Progress()
    }

    fun install(profile: DistroProfile): Flow<Progress> = flow {
        emit(Progress.Step("Setting up Termux storage access"))
        // termux-setup-storage prompts for storage permission inside Termux. If
        // already granted, this is a no-op. We can't check from outside whether
        // it's been granted — we just try and proceed.
        termux.runShell("termux-setup-storage; sleep 2", timeoutMs = 15_000L)

        emit(Progress.Step("Installing proot-distro and TigerVNC"))
        val pkgs = "pkg install -y proot-distro tigervnc xterm dbus-x11"
        val r1 = termux.runShell(pkgs, timeoutMs = 5 * 60_000L)
        if (r1 == null || r1.exitCode != 0) {
            emit(Progress.Done(false, "Failed installing proot-distro: ${r1?.stderr ?: "no response"}"))
            return@flow
        }
        emit(Progress.Output(r1.stdout.takeLast(500)))

        emit(Progress.Step("Downloading ${profile.displayName} rootfs",
            "This is the big download — ~${profile.approxSizeMb} MB"))
        val install = "proot-distro install ${profile.prootDistroName} || echo 'maybe-already-installed'"
        val r2 = termux.runShell(install, timeoutMs = 30 * 60_000L)
        if (r2 == null) {
            emit(Progress.Done(false, "rootfs download timed out"))
            return@flow
        }
        emit(Progress.Output(r2.stdout.takeLast(500)))

        emit(Progress.Step("Configuring desktop environment"))
        // Read the bootstrap script from assets and pipe it into bash inside the distro.
        val script = readAsset("distros/${profile.bootstrapAsset}")
            ?: run {
                emit(Progress.Done(false, "Missing bootstrap asset ${profile.bootstrapAsset}"))
                return@flow
            }
        // We feed the script via stdin to avoid quoting nightmares with multi-line scripts.
        // proot-distro's --shared-tmp lets us write to /tmp from the host side.
        // Note the ${'$'} escapes — in Kotlin triple-quoted strings, `$` triggers
        // template interpolation, so we have to compute the literal `$` to keep
        // bash variable references intact.
        val H = "${'$'}HOME"
        val r3 = termux.runShell(
            """
            cat > "$H/.pocketlinux-bootstrap.sh" <<'POCKETLINUX_EOF'
$script
POCKETLINUX_EOF
            chmod +x "$H/.pocketlinux-bootstrap.sh"
            proot-distro login ${profile.prootDistroName} --shared-tmp -- bash "$H/.pocketlinux-bootstrap.sh"
            """.trimIndent(),
            timeoutMs = 20 * 60_000L
        )

        if (r3 == null || r3.exitCode != 0) {
            val msg = r3?.stderr?.takeLast(800) ?: "no output"
            emit(Progress.Done(false, "Bootstrap script failed:\n$msg"))
            return@flow
        }
        emit(Progress.Output(r3.stdout.takeLast(500)))

        emit(Progress.Done(true))
    }

    private fun readAsset(path: String): String? {
        return try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "asset read failed: $path", e)
            null
        }
    }

    companion object { private const val TAG = "BootstrapScript" }
}

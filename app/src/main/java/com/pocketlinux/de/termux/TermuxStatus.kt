package com.pocketlinux.de.termux

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Detects whether Termux is in a usable state for our purposes.
 *
 * The hierarchy of "Termux is installed" goes from worst to best:
 *
 *   1. Not installed at all — user must install from F-Droid
 *   2. Installed from Play Store — abandoned build, ships with allow-external-apps=false
 *      and the file is in a directory we can't write to. Effectively useless for us.
 *      User must uninstall and reinstall from F-Droid.
 *   3. Installed from F-Droid or GitHub but allow-external-apps=false — common default,
 *      one-line fix
 *   4. Installed and allow-external-apps=true — ready to go
 *
 * We try to give a clear error and a one-tap path forward at each level.
 *
 * Why we can't just check Termux's signature: the signing keys differ between F-Droid and
 * the GitHub release, and we don't want to be in the business of maintaining a list. So
 * we use a behavioral check instead: send a no-op RUN_COMMAND, see if it works.
 */
object TermuxStatus {

    enum class State {
        NOT_INSTALLED,
        PLAY_STORE_VERSION,    // installed but unusable
        BAD_CONFIG,            // installed but allow-external-apps=false (probable)
        READY                  // confirmed working via successful no-op
    }

    private const val TAG = "TermuxStatus"
    private const val TERMUX_PKG = "com.termux"

    /**
     * Checks installation state without sending a command. This is a fast,
     * synchronous check appropriate for the splash/main activity.
     *
     * Returns READY *optimistically* — actual command-execution capability
     * has to be confirmed asynchronously via [confirmReady].
     */
    fun check(context: Context): State {
        val pm = context.packageManager
        try {
            pm.getPackageInfo(TERMUX_PKG, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return State.NOT_INSTALLED
        }

        // Heuristic for the Play Store build: its installer package is
        // com.android.vending. We avoid signature checks because the keys
        // differ between F-Droid and the GitHub release and we don't want
        // to maintain a list.
        //
        // getInstallSourceInfo is API 30+. On older devices we fall back to
        // the deprecated-but-still-working getInstallerPackageName.
        val installer: String? = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(TERMUX_PKG).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(TERMUX_PKG)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't resolve installer for Termux", e)
            null
        }
        if (installer == "com.android.vending") {
            Log.w(TAG, "Termux installed from Play Store — known broken")
            return State.PLAY_STORE_VERSION
        }

        // We can't tell from package info alone whether allow-external-apps is set.
        // Caller should follow up with confirmReady() to know for sure.
        return State.READY
    }
}

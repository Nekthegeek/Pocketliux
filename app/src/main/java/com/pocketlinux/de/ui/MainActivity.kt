package com.pocketlinux.de.ui

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pocketlinux.de.R
import com.pocketlinux.de.bootstrap.BootstrapScript
import com.pocketlinux.de.bootstrap.DistroProfile
import com.pocketlinux.de.termux.TermuxBridge
import com.pocketlinux.de.termux.TermuxStatus
import kotlinx.coroutines.launch

/**
 * Entry screen. Three states:
 *
 *   1. Termux not OK -> show fix-it card
 *   2. No distro installed -> show picker
 *   3. Distro installed -> show "Launch desktop" button
 *
 * Setup state is stored in SharedPreferences. Wiping is exposed via long-press
 * on the launch button (a small UX shortcut for testing; in v2 we add a real
 * settings screen).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var stopButton: Button
    private lateinit var logView: TextView
    private lateinit var pickerContainer: LinearLayout

    private val termux by lazy { TermuxBridge(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("pocketlinux", MODE_PRIVATE)
        statusText = findViewById(R.id.statusText)
        actionButton = findViewById(R.id.actionButton)
        stopButton = findViewById(R.id.stopButton)
        logView = findViewById(R.id.logView)
        logView.movementMethod = ScrollingMovementMethod()
        pickerContainer = findViewById(R.id.pickerContainer)

        actionButton.setOnLongClickListener {
            confirmReset()
            true
        }

        // Stop-session button: kills any running vncserver inside the
        // installed distro. We use the same TermuxBridge path as launch, just
        // sending the kill command directly. If no session is running this
        // is a harmless no-op.
        stopButton.setOnClickListener {
            val installedId = prefs.getString(KEY_INSTALLED_DISTRO, null) ?: return@setOnClickListener
            val profile = com.pocketlinux.de.bootstrap.DistroProfile.byId(installedId) ?: return@setOnClickListener
            lifecycleScope.launch {
                val launcher = com.pocketlinux.de.bootstrap.SessionLauncher(termux)
                launcher.stopSession(profile)
                android.widget.Toast.makeText(
                    this@MainActivity,
                    R.string.notice_session_stopped,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        // stopButton is only relevant when a distro is installed; default-hide
        // and let showLaunchReady be the single place that exposes it.
        stopButton.visibility = View.GONE
        when (TermuxStatus.check(this)) {
            TermuxStatus.State.NOT_INSTALLED -> showInstallTermux()
            TermuxStatus.State.PLAY_STORE_VERSION -> showWrongTermux()
            TermuxStatus.State.BAD_CONFIG -> showBadConfig()
            TermuxStatus.State.READY -> {
                lifecycleScope.launch { confirmTermuxAndContinue() }
            }
        }
    }

    private fun showInstallTermux() {
        statusText.text = getString(R.string.termux_not_installed)
        actionButton.text = getString(R.string.action_open_fdroid)
        actionButton.visibility = View.VISIBLE
        pickerContainer.visibility = View.GONE
        actionButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(F_DROID_TERMUX))
            startActivity(intent)
        }
    }

    private fun showWrongTermux() {
        statusText.text = getString(R.string.termux_play_store_warning)
        actionButton.text = getString(R.string.action_open_fdroid)
        actionButton.visibility = View.VISIBLE
        pickerContainer.visibility = View.GONE
        actionButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(F_DROID_TERMUX))
            startActivity(intent)
        }
    }

    private fun showBadConfig() {
        // We get here when ping fails. The user needs to set
        // allow-external-apps=true in ~/.termux/termux.properties.
        statusText.text = getString(R.string.termux_bad_config)
        actionButton.text = getString(R.string.action_recheck)
        actionButton.visibility = View.VISIBLE
        pickerContainer.visibility = View.GONE
        actionButton.setOnClickListener { refresh() }
    }

    private suspend fun confirmTermuxAndContinue() {
        statusText.text = getString(R.string.checking_termux)
        actionButton.visibility = View.GONE
        val ok = termux.pingTermux()
        if (!ok) {
            showBadConfig()
            return
        }

        val installedId = prefs.getString(KEY_INSTALLED_DISTRO, null)
        if (installedId == null) {
            showPicker()
        } else {
            showLaunchReady(installedId)
        }
    }

    private fun showPicker() {
        statusText.text = getString(R.string.pick_a_distro)
        actionButton.visibility = View.GONE
        pickerContainer.visibility = View.VISIBLE
        pickerContainer.removeAllViews()
        for (p in DistroProfile.ALL) {
            val item = layoutInflater.inflate(R.layout.item_distro, pickerContainer, false)
            item.findViewById<TextView>(R.id.title).text = p.displayName
            item.findViewById<TextView>(R.id.subtitle).text =
                "${p.description}\n${p.recommendedFor} · ~${p.approxSizeMb} MB"
            item.setOnClickListener { startBootstrap(p) }
            pickerContainer.addView(item)
        }
    }

    private fun startBootstrap(profile: DistroProfile) {
        pickerContainer.visibility = View.GONE
        actionButton.visibility = View.GONE
        stopButton.visibility = View.GONE
        statusText.text = getString(R.string.installing, profile.displayName)
        logView.visibility = View.VISIBLE
        logView.text = ""

        lifecycleScope.launch {
            val script = BootstrapScript(applicationContext, termux)
            script.install(profile).collect { p ->
                when (p) {
                    is BootstrapScript.Progress.Step -> {
                        logView.append("\n>> ${p.name}\n")
                        if (p.detail.isNotEmpty()) logView.append("   ${p.detail}\n")
                    }
                    is BootstrapScript.Progress.Output -> {
                        logView.append(p.line + "\n")
                    }
                    is BootstrapScript.Progress.Done -> {
                        if (p.success) {
                            prefs.edit().putString(KEY_INSTALLED_DISTRO, profile.id).apply()
                            logView.append("\nDone.\n")
                            showLaunchReady(profile.id)
                        } else {
                            logView.append("\nFAILED: ${p.errorMessage}\n")
                            statusText.text = getString(R.string.install_failed)
                            actionButton.visibility = View.VISIBLE
                            actionButton.text = getString(R.string.action_retry)
                            actionButton.setOnClickListener { startBootstrap(profile) }
                        }
                    }
                }
            }
        }
    }

    private fun showLaunchReady(profileId: String) {
        val profile = DistroProfile.byId(profileId)
        statusText.text = if (profile != null) {
            getString(R.string.ready_with, profile.displayName)
        } else {
            getString(R.string.ready_generic)
        }
        actionButton.visibility = View.VISIBLE
        actionButton.text = getString(R.string.action_launch_desktop)
        stopButton.visibility = View.VISIBLE
        pickerContainer.visibility = View.GONE
        logView.visibility = View.GONE
        actionButton.setOnClickListener {
            val intent = Intent(this, ViewerActivity::class.java)
            intent.putExtra(ViewerActivity.EXTRA_PROFILE_ID, profileId)
            startActivity(intent)
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_message)
            .setPositiveButton(R.string.reset_confirm) { _, _ ->
                prefs.edit().remove(KEY_INSTALLED_DISTRO).apply()
                Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val KEY_INSTALLED_DISTRO = "installed_distro_id"
        // F-Droid app entry — installs the working build of Termux.
        private const val F_DROID_TERMUX = "https://f-droid.org/packages/com.termux/"
    }
}

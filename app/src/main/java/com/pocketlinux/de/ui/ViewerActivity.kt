package com.pocketlinux.de.ui

import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.pocketlinux.de.R
import com.pocketlinux.de.bootstrap.DistroProfile
import com.pocketlinux.de.bootstrap.SessionLauncher
import com.pocketlinux.de.termux.TermuxBridge
import com.pocketlinux.de.vnc.InputEncoder
import com.pocketlinux.de.vnc.RfbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity that:
 *   1. Launches the VNC server inside the chosen distro
 *   2. Connects our built-in RFB client to it
 *   3. Hosts the VncCanvasView and forwards input
 *
 * Lifecycle is straightforward — onCreate kicks off the launch+connect, onDestroy
 * tears down. We're a singleTask activity so rotation doesn't recreate us.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var canvasView: VncCanvasView
    private lateinit var rfb: RfbClient
    private lateinit var profile: DistroProfile
    private lateinit var session: SessionLauncher
    private val termux by lazy { TermuxBridge(applicationContext) }

    /**
     * Sticky modifiers — the keys we've "armed" via the modifier strip but not
     * yet released. When the next non-modifier key fires, we wrap it in
     * down-modifier / down-key / up-key / up-modifier, then clear the set.
     *
     * This is the standard mobile-terminal pattern (used by Termux's own
     * extra-keys row, JuiceSSH, AVNC, and bVNC). It works around the fact that
     * mobile soft keyboards have no Ctrl/Alt/Super keys.
     */
    private val armedModifiers = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_viewer)

        canvasView = findViewById(R.id.canvas)
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
            ?: run { finish(); return }
        profile = DistroProfile.byId(profileId)
            ?: run { finish(); return }

        session = SessionLauncher(termux)
        rfb = RfbClient()

        canvasView.pointerListener = { mask, x, y ->
            lifecycleScope.launch { rfb.sendPointer(mask, x, y) }
        }

        setupKeyboardToolbar()
        connectToSession()
    }

    /**
     * Wires up the soft-keyboard FAB and the modifier strip.
     *
     * Behavior:
     *   - Tap FAB: show/hide the soft keyboard.
     *   - Long-press FAB: toggle the modifier strip (Esc/Tab/Ctrl/Alt/Super/arrows).
     *   - Tap a modifier (Ctrl/Alt/Super): "arm" it. The button visually toggles.
     *     The next non-modifier key applies the armed modifier(s) and disarms them.
     *   - Tap a non-modifier (Esc/Tab/arrows): send immediately.
     */
    private fun setupKeyboardToolbar() {
        val fab = findViewById<FloatingActionButton>(R.id.keyboardToggle)
        val strip = findViewById<LinearLayout>(R.id.modifierStrip)

        fab.setOnClickListener {
            // Toggle the soft keyboard. We use SHOW_FORCED to overcome Android's
            // reluctance to show a keyboard for a non-EditText view, then
            // HIDE_IMPLICIT_ONLY for the inverse — those flags are the most
            // reliable across OEM customizations.
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            canvasView.requestFocus()
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        }

        fab.setOnLongClickListener {
            strip.visibility = if (strip.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }

        // One-shot keys: send immediately
        wireOneShot(strip, R.id.keyEsc,   InputEncoder.XK_ESCAPE)
        wireOneShot(strip, R.id.keyTab,   InputEncoder.XK_TAB)
        wireOneShot(strip, R.id.keyLeft,  InputEncoder.XK_LEFT)
        wireOneShot(strip, R.id.keyDown,  InputEncoder.XK_DOWN)
        wireOneShot(strip, R.id.keyUp,    InputEncoder.XK_UP)
        wireOneShot(strip, R.id.keyRight, InputEncoder.XK_RIGHT)

        // Sticky modifiers: arm/disarm with visual feedback
        wireSticky(strip, R.id.keyCtrl,  InputEncoder.XK_CONTROL_L, "Ctrl")
        wireSticky(strip, R.id.keyAlt,   InputEncoder.XK_ALT_L,     "Alt")
        wireSticky(strip, R.id.keySuper, InputEncoder.XK_SUPER_L,   "Super")
    }

    private fun wireOneShot(parent: View, id: Int, keysym: Int) {
        parent.findViewById<Button>(id).setOnClickListener {
            lifecycleScope.launch {
                sendWithArmedModifiers(keysym)
            }
        }
    }

    private fun wireSticky(parent: View, id: Int, keysym: Int, label: String) {
        val btn = parent.findViewById<Button>(id)
        btn.setOnClickListener {
            if (armedModifiers.contains(keysym)) {
                armedModifiers.remove(keysym)
                btn.alpha = 1.0f
            } else {
                armedModifiers.add(keysym)
                btn.alpha = 0.5f  // dim to indicate "armed"
                Toast.makeText(this, getString(R.string.modifier_armed, label), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Send a keysym with any currently-armed modifiers wrapped around it.
     * After firing, the modifiers disarm automatically and we update their
     * button visuals.
     */
    private suspend fun sendWithArmedModifiers(keysym: Int) {
        // Snapshot now so concurrent button taps don't mutate the set mid-send
        val mods = armedModifiers.toList()
        for (m in mods) rfb.sendKey(true, m)
        rfb.sendKey(true, keysym)
        rfb.sendKey(false, keysym)
        for (m in mods.reversed()) rfb.sendKey(false, m)

        if (mods.isNotEmpty()) {
            armedModifiers.clear()
            // Restore button visuals on the main thread
            runOnUiThread { resetModifierButtons() }
        }
    }

    private fun resetModifierButtons() {
        listOf(R.id.keyCtrl, R.id.keyAlt, R.id.keySuper).forEach { id ->
            findViewById<Button>(id)?.alpha = 1.0f
        }
    }

    private fun connectToSession() {
        lifecycleScope.launch {
            // Use the actual screen size for the VNC geometry. We could pick a
            // smaller fixed size for performance, but matching the screen avoids
            // ugly upscaling.
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels

            val info = withContext(Dispatchers.IO) {
                session.startSession(profile, widthPx = w, heightPx = h)
            }
            if (info == null) {
                Toast.makeText(this@ViewerActivity, R.string.error_session_start, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            val server = try {
                rfb.connect(info.host, info.port, info.password)
            } catch (e: Exception) {
                Log.e(TAG, "RFB connect failed", e)
                Toast.makeText(this@ViewerActivity, "VNC connect failed: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            Log.i(TAG, "Connected to ${server.name} (${server.width}x${server.height})")

            canvasView.framebuffer = rfb.bitmap
            rfb.startReadLoop()

            // Start collecting events
            launch { collectRfbEvents() }
        }
    }

    private suspend fun collectRfbEvents() {
        rfb.events.collect { evt ->
            when (evt) {
                is RfbClient.Event.FrameUpdated -> {
                    // Update our cached bitmap reference (in case it changed) and
                    // invalidate just the changed region for a cheap redraw.
                    canvasView.framebuffer = rfb.bitmap
                    canvasView.invalidateFbRect(evt.x, evt.y, evt.w, evt.h)
                    // Ask for the next incremental update — we're in pull mode
                    rfb.requestIncrementalUpdate(rfb.bitmap?.width ?: 0, rfb.bitmap?.height ?: 0)
                }
                is RfbClient.Event.Resized -> {
                    canvasView.framebuffer = rfb.bitmap
                }
                is RfbClient.Event.Bell -> {
                    val vib = getSystemService(VIBRATOR_SERVICE) as? Vibrator
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        vib?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vib?.vibrate(50)
                    }
                }
                is RfbClient.Event.CutText -> {
                    // Push to Android clipboard so the user can paste in other apps.
                    // The reverse direction (Android clipboard -> remote) would need
                    // a clipboard listener; we leave that for a future pass since
                    // Android 10+ restricts background clipboard reads.
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    cm?.setPrimaryClip(
                        android.content.ClipData.newPlainText("PocketLinux", evt.text)
                    )
                }
                is RfbClient.Event.Error -> {
                    Toast.makeText(this, "VNC error: ${evt.message}", Toast.LENGTH_LONG).show()
                }
                RfbClient.Event.Disconnected -> {
                    Toast.makeText(this, R.string.notice_disconnected, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Let the back gesture finish the activity. Otherwise forward the key.
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        val sym = InputEncoder.keyEventToKeysym(event)
        if (sym != 0) {
            // If the user has armed modifiers via the strip, fire the whole
            // chord here on KEY_DOWN and ignore the matching KEY_UP — terminals
            // generally accept a complete down/up pair as a single chord and
            // splitting them across hardware key events confuses the cursor on
            // some apps. Bare keys keep the standard down/up split.
            if (armedModifiers.isNotEmpty()) {
                lifecycleScope.launch { sendWithArmedModifiers(sym) }
                return true
            }
            lifecycleScope.launch { rfb.sendKey(true, sym) }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        val sym = InputEncoder.keyEventToKeysym(event)
        if (sym != 0) {
            // If onKeyDown handled this as a chord, the modifiers will already
            // be cleared by the time KEY_UP arrives — so checking armedModifiers
            // here doesn't double-fire.
            lifecycleScope.launch { rfb.sendKey(false, sym) }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        rfb.close()
        // We deliberately do NOT call session.stopSession(profile) here.
        // The user might be backgrounding briefly, and tearing down vncserver
        // would lose all their open windows. The session persists until they
        // explicitly stop it from the main screen.
    }

    companion object {
        private const val TAG = "ViewerActivity"
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}

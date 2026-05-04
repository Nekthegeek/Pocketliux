# Roadmap

## Implemented in this scaffold

### Host app (Android)
- ✅ Termux detection: not installed / Play Store / bad config / ready
- ✅ One-tap remediation links (open F-Droid, recheck after fixing termux.properties)
- ✅ Distro picker with four combos (Alpine+i3, Debian+i3, Debian+XFCE, Ubuntu+Openbox)
- ✅ BootstrapScript with live progress flow — runs `pkg install`, `proot-distro install`,
  then the per-distro setup script
- ✅ SessionLauncher — starts vncserver bound to localhost only with random password
- ✅ Reset flow (long-press Launch button)

### VNC client (pure-Kotlin RFB 3.8)
- ✅ ProtocolVersion handshake
- ✅ Security: None and VNC Authentication (with the bit-reversed-DES-key quirk)
- ✅ ClientInit / ServerInit
- ✅ SetPixelFormat (force RGBA8888 little-endian, true-color)
- ✅ SetEncodings (Raw, CopyRect, DesktopSize)
- ✅ FramebufferUpdate handling for Raw and CopyRect encodings
- ✅ DesktopSize pseudo-encoding (server-driven resize)
- ✅ Bell (vibrate the phone)
- ✅ ServerCutText (received but not yet pushed to Android clipboard — see below)
- ✅ KeyEvent / PointerEvent encoding
- ✅ Incremental framebuffer update polling

### UI
- ✅ Pinch-to-zoom and two-finger pan in VncCanvasView
- ✅ One-finger drag = pointer move + left button held
- ✅ Soft keyboard via InputConnection (TYPE_NULL, no autocorrect)
- ✅ Floating keyboard-toggle FAB
- ✅ Long-press FAB to reveal modifier strip
- ✅ Sticky modifiers (Ctrl/Alt/Super arm-then-fire pattern)
- ✅ Direct keys for Esc, Tab, arrows
- ✅ Hardware keyboard support — keys forwarded as RFB key events

### Distro bootstraps
- ✅ Alpine + i3 setup script (apk-based, ~150 MB total)
- ✅ Debian + i3 setup script
- ✅ Debian + XFCE setup script
- ✅ Ubuntu + Openbox setup script with right-click menu

## Real-device validation needed

Everything below builds, but I can't validate on hardware from here. Items that I expect to
need tweaks on real devices:

- The exact `RUN_COMMAND` extras for headless execution. We use `RUN_COMMAND_BACKGROUND=true`
  and `RUN_COMMAND_SESSION_ACTION=0`, which the wiki documents but specific OEM Android
  variants might not honor — especially Xiaomi MIUI's aggressive background app killing.
- The `getInstallerPackageName` API on Android 7-8 may not return what we expect for
  side-loaded Termux. Needs a real test.
- Soft keyboard behavior with non-English IMEs. CJK input in particular bypasses our
  per-char path because gboard composes text before commitText. Out of scope for v1.

## Not implemented (intentionally cut from v1)

- Tight / ZRLE encoding (bandwidth optimization that doesn't matter on localhost)
- TLS / VeNCrypt (we're on 127.0.0.1, no MITM possible)
- Audio (PulseAudio over TCP works inside Termux but adds substantial complexity)
- File transfer (RFB extension support varies; users can use the shared storage that
  Termux already exposes)
- Multi-display (the AVF-style multi-monitor concerns don't apply here)
- ServerCutText → Android clipboard (the receive path exists; pushing to ClipboardManager
  is one method call we left out for v1)

## Known issues to fix before shipping

- `ViewerActivity.onDestroy` doesn't kill the VNC server. That's a *feature* — you can
  reopen later and resume — but it leaks if the user uninstalls our app while a session
  is running. Termux will keep the vncserver process alive. Add a "Stop session" button
  on the main screen.
- The bootstrap timeout is 20 minutes for the install script. On a slow phone with poor
  network, XFCE can exceed this. Make it configurable, or chunk into smaller steps each
  with their own timeout.
- We don't handle the case where the user has Termux installed but never opened it. Termux
  has to run its own bootstrap on first launch, which we can't trigger from outside. The
  TermuxStatus check should detect "installed but never opened" and tell the user to open
  Termux once.

## Where to push next

1. **ServerCutText → ClipboardManager** is a five-line change and gives you full
   bidirectional clipboard sync once paired with `wl-clipboard` in the guest.
2. **Stop-session button** on MainActivity (with a separate `Stop` action that calls
   `SessionLauncher.stopSession`) closes the leak.
3. **A "first run open Termux" prompt** that uses `PackageManager.getLaunchIntentForPackage`
   to open Termux for the user once before we send them any RUN_COMMAND.

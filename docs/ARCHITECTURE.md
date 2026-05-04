# Architecture

## Components, what each one is responsible for

### 1. `TermuxBridge` (host side)

Sends commands to Termux via `RUN_COMMAND` intents. We wait for the result by writing exit
codes to a known file under `Android/data/com.termux/files/home/.pocketlinux/` rather than
relying on the PendingIntent result API, which is flaky across Termux versions.

The bridge is responsible for:

- Detecting whether Termux is installed at all
- Detecting whether Termux is the Play Store build (broken) or F-Droid/GitHub (works)
- Detecting whether `allow-external-apps=true` has been set
- Sending shell commands to run inside Termux's PRoot environment
- Polling for completion via filesystem signals

### 2. Bootstrap (one-time install)

When the user picks a distro, we run a sequence of Termux commands that:

1. `pkg install proot-distro tigervnc <wm-packages>` (in Termux's own env)
2. `proot-distro install <distro>` (downloads the rootfs)
3. `proot-distro login <distro> -- <bootstrap-script>` (sets up user, WM, vncserver config)

The bootstrap script for each distro is bundled in `assets/distros/`. We push it through the
intent extras as a here-doc.

### 3. Session launcher

After bootstrap, every time the user taps "Launch Desktop" we:

1. `proot-distro login <distro> -- vncserver :1 -geometry $W x $H -localhost yes`
2. Wait until port 5901 on localhost is reachable
3. Start `ViewerActivity` which opens the VNC connection

`-localhost yes` is critical: it binds VNC to 127.0.0.1 only, so nothing else on the network
can connect even if the user is on hostile WiFi.

### 4. VNC viewer (built-in)

We don't link libvncclient. We don't wrap AVNC. We implement RFB 3.8 directly in Kotlin.

Why? Because:

- We only need to talk to *our own* VNC server, on localhost. Bandwidth is unlimited.
  The hard part of real-world VNC clients (efficient encodings like Tight, ZRLE, H.264)
  doesn't matter at all here.
- libvncclient via JNI means NDK builds, ABI splits, and a much bigger APK
- A pure-Kotlin client keeps the codebase auditable and simple
- Implementing only Raw + CopyRect is honestly maybe 600 lines of Kotlin

The viewer renders the framebuffer to a `VncCanvasView` (a custom `View` with a `Bitmap` it
keeps in sync with the server's framebuffer). Touch events become pointer events. Soft keyboard
input becomes key events.

## Data flow on cold launch

```
user taps icon
  → MainActivity.onCreate
  → TermuxStatus check: installed? F-Droid? allow-external-apps=true?
  → if all good: check sentinel file for "bootstrap done"
      → not done: show DistroPickerFragment
      → done: show "Launch Desktop" button
  → user taps Launch
  → SessionLauncher tells Termux to start vncserver
  → poll localhost:5901 until accept()
  → startActivity(ViewerActivity)
  → RfbClient connects, handshakes, requests full framebuffer update
  → render loop begins
```

## VNC protocol scope

We implement these parts of RFB 3.8:

- Handshake (ProtocolVersion, Security, ServerInit)
- Security types: None, VNC Authentication
- Encodings advertised: Raw, CopyRect, DesktopSize (pseudo)
- Server messages: FramebufferUpdate, SetColorMapEntries (ignored — we force TrueColor),
  Bell (vibrate the phone, naturally), ServerCutText (clipboard sync)
- Client messages: SetPixelFormat, SetEncodings, FramebufferUpdateRequest, KeyEvent,
  PointerEvent, ClientCutText

Things we don't implement:

- Tight encoding (saves bandwidth we don't need)
- ZRLE encoding (same)
- TLS / VeNCrypt (we're on localhost — no MITM is possible)
- Continuous updates pseudo-encoding (we just request after each frame finishes)
- File transfer extensions

## Threading model

- UI thread: handles input, kicks off connect, owns the bitmap
- IO thread: reads from the VNC socket in a tight loop, decodes updates, posts dirty
  rects to the UI thread
- Termux command thread: blocks on the `RUN_COMMAND` result file

We use `kotlinx.coroutines` with three dispatchers: `Main`, `IO` for VNC socket reads,
and `Default` for decode work.

## Failure modes and what we do about them

| Failure | Detection | Response |
| --- | --- | --- |
| Termux not installed | PackageManager check | Show "Install Termux" screen with F-Droid link |
| Termux is Play Store build | Version + signature check | Explain why and link to F-Droid version |
| `allow-external-apps=false` | Try a no-op RUN_COMMAND, time out | Show one-line fix instructions |
| Bootstrap fails (apt error) | Sentinel file contains non-zero | Show last 50 lines of log, "Retry" button |
| VNC server didn't start | Port 5901 not reachable after 30s | Show vncserver log, "Retry" |
| Connection drops mid-session | Read returns -1 | Auto-reconnect with backoff up to 3 attempts |

## Memory budget

The phone has finite RAM. Our budgets:

- Framebuffer: `width * height * 4` bytes — at 1280×720 that's 3.5 MB. Fine.
- Read buffer: 64 KB chunked
- Decoded rect scratch: pooled, max 1 MB

A Linux desktop with i3 + a couple of apps idles around 200-300 MB inside the proot. The
viewer adds maybe 20 MB on top. So a 2 GB phone has plenty of headroom.

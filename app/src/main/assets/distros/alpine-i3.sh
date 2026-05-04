#!/bin/bash
# PocketLinux bootstrap — Alpine + i3.
# Runs INSIDE the Alpine proot-distro container.
set -eu

apk update
apk add --no-cache \
    tigervnc dbus xterm \
    i3wm i3status i3lock \
    feh xset \
    ttf-dejavu

# Create our marker dir
mkdir -p ~/.pocketlinux

# Generate a random VNC password and store it both in vncpasswd format
# (for tigervnc) and plaintext (so the host can read it back).
PW=$(head -c 6 /dev/urandom | od -An -tx1 | tr -d ' \n' | head -c 8)
echo "$PW" > ~/.pocketlinux/vncpw
mkdir -p ~/.vnc
echo "$PW" | vncpasswd -f > ~/.vnc/passwd
chmod 600 ~/.vnc/passwd

# xstartup that launches i3 with a sensible bar.
cat > ~/.vnc/xstartup <<'EOF'
#!/bin/sh
unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS
xset -dpms
xset s off
exec i3
EOF
chmod +x ~/.vnc/xstartup

# Minimal i3 config tuned for a phone screen.
mkdir -p ~/.config/i3
cat > ~/.config/i3/config <<'EOF'
set $mod Mod1
font pango:DejaVu Sans Mono 10

bindsym $mod+Return exec xterm
bindsym $mod+Shift+q kill
bindsym $mod+h focus left
bindsym $mod+j focus down
bindsym $mod+k focus up
bindsym $mod+l focus right
bindsym $mod+v split v
bindsym $mod+b split h
bindsym $mod+f fullscreen toggle

# Workspaces 1-4 — phone screens don't need 10
bindsym $mod+1 workspace 1
bindsym $mod+2 workspace 2
bindsym $mod+3 workspace 3
bindsym $mod+4 workspace 4
bindsym $mod+Shift+1 move container to workspace 1
bindsym $mod+Shift+2 move container to workspace 2

bar {
    status_command i3status
    position top
}

# Auto-launch one terminal so the desktop isn't blank
exec xterm
EOF

echo "Alpine + i3 bootstrap complete."

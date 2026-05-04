#!/bin/bash
# PocketLinux bootstrap — Debian + i3.
set -eu
export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y --no-install-recommends \
    tigervnc-standalone-server tigervnc-common \
    dbus-x11 xterm \
    i3 i3status \
    feh fonts-dejavu-core fonts-noto-color-emoji \
    ca-certificates

mkdir -p ~/.pocketlinux

PW=$(head -c 6 /dev/urandom | od -An -tx1 | tr -d ' \n' | head -c 8)
echo "$PW" > ~/.pocketlinux/vncpw
mkdir -p ~/.vnc
echo "$PW" | vncpasswd -f > ~/.vnc/passwd
chmod 600 ~/.vnc/passwd

cat > ~/.vnc/xstartup <<'EOF'
#!/bin/sh
unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS
xset -dpms 2>/dev/null || true
xset s off 2>/dev/null || true
exec i3
EOF
chmod +x ~/.vnc/xstartup

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

exec xterm
EOF

echo "Debian + i3 bootstrap complete."

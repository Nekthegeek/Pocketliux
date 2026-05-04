#!/bin/bash
# PocketLinux bootstrap — Debian + XFCE.
set -eu
export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y --no-install-recommends \
    tigervnc-standalone-server tigervnc-common \
    dbus-x11 xfce4 xfce4-terminal \
    fonts-dejavu-core fonts-noto-color-emoji \
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
# XFCE expects D-Bus. dbus-launch is from dbus-x11.
dbus-launch --exit-with-session startxfce4
EOF
chmod +x ~/.vnc/xstartup

echo "Debian + XFCE bootstrap complete."

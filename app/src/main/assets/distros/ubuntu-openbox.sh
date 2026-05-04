#!/bin/bash
# PocketLinux bootstrap — Ubuntu + Openbox.
set -eu
export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y --no-install-recommends \
    tigervnc-standalone-server tigervnc-common \
    dbus-x11 xterm \
    openbox obconf \
    tint2 pcmanfm \
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
# Openbox + tint2 panel + a file manager available via right-click
tint2 &
exec openbox-session
EOF
chmod +x ~/.vnc/xstartup

# A minimal Openbox menu so right-click does something useful
mkdir -p ~/.config/openbox
cat > ~/.config/openbox/menu.xml <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<openbox_menu>
  <menu id="root-menu" label="Openbox">
    <item label="Terminal"><action name="Execute"><command>xterm</command></action></item>
    <item label="File Manager"><action name="Execute"><command>pcmanfm</command></action></item>
    <separator/>
    <item label="Reconfigure"><action name="Reconfigure"/></item>
    <item label="Exit"><action name="Exit"/></item>
  </menu>
</openbox_menu>
EOF

echo "Ubuntu + Openbox bootstrap complete."

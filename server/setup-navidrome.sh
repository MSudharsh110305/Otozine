#!/usr/bin/env bash
#
# Provision a free Oracle Cloud VM as an OtoZine streaming server.
#
# Installs Navidrome (Subsonic-compatible, which is what the app speaks) behind
# Caddy for automatic HTTPS. Tested against Ubuntu 22.04/24.04 on Oracle's
# always-free ARM shape (VM.Standard.A1.Flex).
#
# Run on the VM:
#     curl -fsSL <this file> -o setup.sh && sudo bash setup.sh
#
# Or copy it over and: sudo bash setup-navidrome.sh
#
set -euo pipefail

MUSIC_DIR="/opt/otozine/music"
DATA_DIR="/var/lib/navidrome"
NAVIDROME_USER="navidrome"

log()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m !  %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run with sudo"

# ---------------------------------------------------------------- inputs
read -rp "Domain pointing at this server (e.g. yourname.duckdns.org): " DOMAIN
[ -n "$DOMAIN" ] || die "a domain is required -- Let's Encrypt cannot issue for a bare IP"
read -rp "Email for certificate renewal notices: " EMAIL
read -rp "Username for the music server: " MUSIC_USER
read -rsp "Password: " MUSIC_PASS; echo
[ -n "$MUSIC_PASS" ] || die "password required"

ARCH=$(uname -m)
case "$ARCH" in
  aarch64|arm64) NAV_ARCH="arm64" ;;
  x86_64)        NAV_ARCH="amd64" ;;
  *) die "unsupported architecture: $ARCH" ;;
esac
log "architecture: $ARCH -> navidrome $NAV_ARCH"

# ------------------------------------------------------------- packages
log "installing packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ffmpeg debian-keyring debian-archive-keyring apt-transport-https

# --------------------------------------------------------------- caddy
log "installing Caddy (handles HTTPS certificates automatically)"
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
    | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
    | tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
  apt-get update -qq
  apt-get install -y -qq caddy
fi

# ----------------------------------------------------------- navidrome
log "installing Navidrome"
id -u "$NAVIDROME_USER" &>/dev/null || useradd --system --no-create-home --shell /usr/sbin/nologin "$NAVIDROME_USER"
mkdir -p /opt/navidrome "$DATA_DIR" "$MUSIC_DIR"

if [ ! -x /opt/navidrome/navidrome ]; then
  VERSION=$(curl -fsSL https://api.github.com/repos/navidrome/navidrome/releases/latest \
            | grep -oP '"tag_name":\s*"v\K[^"]+')
  [ -n "$VERSION" ] || die "could not determine latest Navidrome version"
  log "  version $VERSION"
  curl -fsSL -o /tmp/navidrome.tar.gz \
    "https://github.com/navidrome/navidrome/releases/download/v${VERSION}/navidrome_${VERSION}_linux_${NAV_ARCH}.tar.gz"
  tar -xzf /tmp/navidrome.tar.gz -C /opt/navidrome navidrome
  rm -f /tmp/navidrome.tar.gz
  chmod +x /opt/navidrome/navidrome
fi

cat > /etc/navidrome.toml <<EOF
MusicFolder = "$MUSIC_DIR"
DataFolder  = "$DATA_DIR"
# Bound to loopback on purpose: Caddy terminates TLS and proxies inward, so
# Navidrome is never reachable except over HTTPS.
Address = "127.0.0.1"
Port = 4533
ScanSchedule = "@every 1h"
# Opus passes through untouched; anything heavier is transcoded on the fly so
# mobile data stays sane.
EnableTranscodingConfig = true
LogLevel = "info"
EOF
chmod 600 /etc/navidrome.toml

chown -R "$NAVIDROME_USER:$NAVIDROME_USER" /opt/navidrome "$DATA_DIR" "$MUSIC_DIR"

cat > /etc/systemd/system/navidrome.service <<EOF
[Unit]
Description=Navidrome Music Server
After=network.target

[Service]
User=$NAVIDROME_USER
Group=$NAVIDROME_USER
ExecStart=/opt/navidrome/navidrome --configfile /etc/navidrome.toml
WorkingDirectory=/opt/navidrome
TimeoutStopSec=20
Restart=on-failure
DevicePolicy=closed
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=full
ReadWritePaths=$DATA_DIR

[Install]
WantedBy=multi-user.target
EOF

# ----------------------------------------------------------- reverse proxy
log "configuring HTTPS for $DOMAIN"
cat > /etc/caddy/Caddyfile <<EOF
{
    email $EMAIL
}

$DOMAIN {
    reverse_proxy 127.0.0.1:4533
    encode zstd gzip
}
EOF

# ---------------------------------------------------------------- firewall
# Oracle's Ubuntu images ship iptables rules that drop everything except SSH.
# This is the single most common reason a new Oracle VM "doesn't work" -- the
# cloud security list gets opened but the host firewall is forgotten.
log "opening ports 80/443 on the host firewall"
iptables -I INPUT -p tcp --dport 80  -j ACCEPT || true
iptables -I INPUT -p tcp --dport 443 -j ACCEPT || true
if command -v netfilter-persistent >/dev/null; then
  netfilter-persistent save >/dev/null 2>&1 || true
else
  apt-get install -y -qq iptables-persistent >/dev/null 2>&1 || true
  netfilter-persistent save >/dev/null 2>&1 || true
fi

warn "You must ALSO open 80 and 443 in the Oracle Cloud console:"
warn "  Networking > Virtual Cloud Networks > your VCN > Security Lists >"
warn "  Add Ingress Rules for TCP 80 and 443 from 0.0.0.0/0"

# ------------------------------------------------------------------ start
log "starting services"
systemctl daemon-reload
systemctl enable --now navidrome
systemctl restart caddy

sleep 6
systemctl is-active --quiet navidrome || die "navidrome failed to start: journalctl -u navidrome"

# ------------------------------------------------------------- first user
log "creating the music account"
# Navidrome creates the first admin through its web UI; do it over the API so
# this script stays unattended.
curl -fsS -X POST "http://127.0.0.1:4533/auth/createAdmin" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$MUSIC_USER\",\"password\":\"$MUSIC_PASS\"}" >/dev/null 2>&1 \
  && echo "  created $MUSIC_USER" \
  || warn "could not auto-create the user; open https://$DOMAIN once and create it there"

cat <<EOF

$(printf '\033[1;32m')DONE$(printf '\033[0m')

  Server      https://$DOMAIN
  Username    $MUSIC_USER
  Music dir   $MUSIC_DIR

Put those three into OtoZine under More > Streaming server.

Upload your library from the PC (run in Git Bash or WSL):

  rsync -av --progress \\
    "/g/OtoZine/audio/opus/" \\
    ubuntu@$DOMAIN:$MUSIC_DIR/

Then rescan:  ssh ubuntu@$DOMAIN 'sudo systemctl restart navidrome'

Note: the Opus tier carries the R128 gain tag the Librarian wrote, and
Navidrome passes Opus through without re-encoding -- so the loudness levelling
survives streaming intact.

EOF

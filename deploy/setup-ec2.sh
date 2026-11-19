#!/usr/bin/env bash
# One-time setup of Sadak on a fresh Ubuntu 24.04 server (for example an AWS EC2 instance).
#
#   sudo bash deploy/setup-ec2.sh [your.domain]
#
# Run it from inside the cloned repository. Without a domain it uses <public-ip>.sslip.io, a free
# name that points at this server, so HTTPS works without buying a domain.
#
# It installs Java 21, PostgreSQL and Caddy (web server with automatic HTTPS), creates the database
# with a random password, builds Sadak and runs it as a service that starts on boot and restarts
# if it stops. Safe to run again: it keeps the existing database and passwords.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Run it with sudo: sudo bash deploy/setup-ec2.sh [your.domain]" >&2
  exit 1
fi

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE=/etc/sadak/sadak.env
APP_DIR=/opt/sadak

DOMAIN="${1:-}"
if [[ -z "$DOMAIN" ]]; then
  IP="$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')"
  DOMAIN="${IP//./-}.sslip.io"
fi

step() { echo; echo "==> $*"; }

step "Swap space (lets small instances build without running out of memory)"
if ! swapon --show | grep -q .; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  echo "2 GB swap added"
else
  echo "Swap already present"
fi

step "Packages: Java 21, PostgreSQL, Caddy"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q openjdk-21-jdk-headless postgresql git curl gnupg debian-keyring debian-archive-keyring apt-transport-https
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -q
  apt-get install -y -q caddy
fi
systemctl enable --now postgresql

step "Settings in $ENV_FILE (passwords are generated once and kept)"
mkdir -p /etc/sadak
if [[ ! -f "$ENV_FILE" ]]; then
  cat > "$ENV_FILE" <<EOF
FLEET_DB_HOST=localhost
FLEET_DB_PORT=5432
FLEET_DB_NAME=sadak
FLEET_DB_USER=sadak
FLEET_DB_PASSWORD=$(openssl rand -hex 18)
FLEET_DEMO_PASSWORD=$(openssl rand -hex 8)
FLEET_BASE_URL=https://$DOMAIN
# Only Caddy talks to the app; it passes on that visitors came over HTTPS.
SERVER_ADDRESS=127.0.0.1
SERVER_FORWARD_HEADERS_STRATEGY=native
SERVER_SERVLET_SESSION_COOKIE_SECURE=true
SPRING_THYMELEAF_CACHE=true
EOF
else
  sed -i "s|^FLEET_BASE_URL=.*|FLEET_BASE_URL=https://$DOMAIN|" "$ENV_FILE"
fi
chmod 600 "$ENV_FILE"
# shellcheck disable=SC1090
source "$ENV_FILE"

step "Database"
if ! sudo -u postgres psql -tAc "select 1 from pg_roles where rolname = '$FLEET_DB_USER'" | grep -q 1; then
  sudo -u postgres psql -q -c "create role $FLEET_DB_USER login password '$FLEET_DB_PASSWORD'"
fi
if ! sudo -u postgres psql -tAc "select 1 from pg_database where datname = '$FLEET_DB_NAME'" | grep -q 1; then
  sudo -u postgres psql -q -c "create database $FLEET_DB_NAME owner $FLEET_DB_USER encoding 'UTF8'"
fi
echo "Database $FLEET_DB_NAME ready"

step "Service user and folder"
id sadak >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin sadak
mkdir -p "$APP_DIR"

step "Build (a few minutes the first time: Maven downloads its libraries)"
bash "$REPO_DIR/deploy/build.sh"

step "Service"
cat > /etc/systemd/system/sadak.service <<EOF
[Unit]
Description=Sadak
After=network-online.target postgresql.service
Requires=postgresql.service

[Service]
User=sadak
WorkingDirectory=$APP_DIR
EnvironmentFile=$ENV_FILE
ExecStart=/usr/bin/java -Xms128m -Xmx400m -jar $APP_DIR/sadak.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable sadak >/dev/null 2>&1
systemctl restart sadak

step "HTTPS with Caddy for $DOMAIN"
cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN {
    encode gzip
    reverse_proxy 127.0.0.1:8080
}
EOF
systemctl enable caddy >/dev/null 2>&1
systemctl restart caddy

step "Waiting for Sadak to start (the first start seeds the demo, about a minute)"
bash "$REPO_DIR/deploy/wait-healthy.sh"

cat <<EOF

Sadak is up:  https://$DOMAIN
  The page opens on "Try the demo": one click per role, no password.

The certificate can take a minute on the very first visit.
After you push changes, put them live with:  sudo bash deploy/update.sh
EOF

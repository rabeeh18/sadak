#!/usr/bin/env bash
# Puts the latest pushed version live: pull, build, restart.
#
#   sudo bash deploy/update.sh
#
# The data stays; database changes are applied by the app's migrations on start.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Run it with sudo: sudo bash deploy/update.sh" >&2
  exit 1
fi
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_USER="${SUDO_USER:-root}"

cd "$REPO_DIR"
sudo -u "$BUILD_USER" -H git pull --ff-only
bash "$REPO_DIR/deploy/build.sh"
systemctl restart sadak
bash "$REPO_DIR/deploy/wait-healthy.sh"

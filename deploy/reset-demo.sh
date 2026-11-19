#!/usr/bin/env bash
# Starts the demo over: deletes the database and lets Sadak seed a fresh demo day.
#
#   sudo bash deploy/reset-demo.sh
#
# Everything in the database is lost (trips, users created by hand, audit log).
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Run it with sudo: sudo bash deploy/reset-demo.sh" >&2
  exit 1
fi
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source /etc/sadak/sadak.env

read -r -p "Delete all Sadak data and start a fresh demo? Type yes: " answer
[[ "$answer" == "yes" ]] || { echo "Nothing changed."; exit 0; }

systemctl stop sadak
sudo -u postgres psql -q -c "drop database if exists $FLEET_DB_NAME with (force)"
sudo -u postgres psql -q -c "create database $FLEET_DB_NAME owner $FLEET_DB_USER encoding 'UTF8'"
systemctl start sadak
bash "$REPO_DIR/deploy/wait-healthy.sh"

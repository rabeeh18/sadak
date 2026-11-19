#!/usr/bin/env bash
# Builds the Sadak jar from this checkout and installs it as /opt/sadak/sadak.jar.
# Used by setup-ec2.sh and update.sh; tests are skipped (they need a separate test database).
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_USER="${SUDO_USER:-root}"

cd "$REPO_DIR"
# The build runs as the person who cloned the repo, so the files stay theirs.
sudo -u "$BUILD_USER" -H sh ./mvnw -q -B -DskipTests package
JAR="$(ls target/fleet-ops-*.jar | grep -v -- '-plain' | head -n 1)"
install -o sadak -g sadak -m 644 "$JAR" /opt/sadak/sadak.jar
echo "Installed $JAR"

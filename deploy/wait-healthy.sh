#!/usr/bin/env bash
# Waits up to three minutes for Sadak to answer its health check; shows the log if it doesn't.
set -uo pipefail

for _ in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"UP"'; then
    echo "Sadak is running."
    exit 0
  fi
  sleep 2
done
echo "Sadak did not start in time. Last log lines:" >&2
journalctl -u sadak -n 60 --no-pager >&2
exit 1

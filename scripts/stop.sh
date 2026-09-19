#!/usr/bin/env bash
# Tear down what scripts/start.sh left running in the background: the backend (bootRun), the Android
# emulator, and the docker compose infra. Safe to run repeatedly. Volumes are preserved (event store
# and read model survive); pass --volumes to also drop the docker volumes for a from-zero reset.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"
export PATH="$ANDROID_HOME/platform-tools:${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:$PATH"
EMULATOR_SERIAL="${SGART_EMULATOR_SERIAL:-emulator-5554}"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

stop_pid() {  # stop_pid <label> <pidfile>
  local label="$1" pidfile="$2"
  [[ -f "$pidfile" ]] || { log "$label: no pid file, skipping"; return; }
  local pid; pid="$(cat "$pidfile")"
  if kill -0 "$pid" 2>/dev/null; then
    log "Stopping $label (pid $pid)"
    kill "$pid" 2>/dev/null || true
    # Gradle's bootRun spawns a daemon child JVM; kill the process group too.
    pkill -P "$pid" 2>/dev/null || true
  else
    log "$label (pid $pid) not running"
  fi
  rm -f "$pidfile"
}

stop_pid "backend" "$RUN_DIR/backend.pid"

log "Shutting down the emulator"
adb -s "$EMULATOR_SERIAL" emu kill 2>/dev/null || true
stop_pid "emulator" "$RUN_DIR/emulator.pid"

if [[ "${1:-}" == "--volumes" ]]; then
  log "Stopping docker compose infra AND removing volumes (from-zero reset)"
  ( cd "$ROOT" && docker compose down --volumes )
else
  log "Stopping docker compose infra (volumes preserved)"
  ( cd "$ROOT" && docker compose down )
fi

log "Done."

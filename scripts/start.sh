#!/usr/bin/env bash
# Start the whole SGART dev stack for a hands-on run on the local Android emulator:
#   1. docker compose infra   — postgres, kurrentdb, keycloak (waits until all healthy)
#   2. backend                — Spring Boot bootRun with the real-run flags (background, logged)
#   3. Android emulator       — AVD sgart_pixel, then `adb reverse` for :8081/:8080 (background)
#   4. flutter run            — foreground, driving the app on the emulator (Ctrl-C to stop it)
#
# This is a DEV-ONLY convenience for the local WSL2 setup. It mirrors, in one command, the manual
# steps in docs/first-real-world-test.md (Part 1) and the emulator notes. Backend and emulator are
# left running in the background after the script exits; use scripts/stop.sh to shut them down.
#
# Usage (nested tiers — each does more than the one below it):
#   scripts/start.sh --infra    # tier 1: docker containers ONLY (no backend, no emulator)
#   scripts/start.sh --no-app   # tier 2: containers + backend + booted emulator (adb reverse wired),
#                               #         but you start the app yourself (flutter run from your IDE)
#   scripts/start.sh            # tier 3: everything above + `flutter run` in the foreground (default)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"
BACKEND_LOG="$RUN_DIR/backend.log"
EMULATOR_LOG="$RUN_DIR/emulator.log"
mkdir -p "$RUN_DIR"

# --- Local toolchain (WSL2), matching docs/first-real-world-test.md and the emulator setup notes ---
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$HOME/tools/flutter/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export DISPLAY="${DISPLAY:-:0}"

AVD_NAME="${SGART_AVD_NAME:-sgart_pixel}"
EMULATOR_SERIAL="${SGART_EMULATOR_SERIAL:-emulator-5554}"
BACKEND_HEALTH_URL="http://localhost:8081/actuator/health"

MODE="app"   # app | no-app | infra
case "${1:-}" in
  --no-app) MODE="no-app" ;;
  --infra)  MODE="infra" ;;
  "")       MODE="app" ;;
  *) echo "Unknown option: $1 (use --no-app or --infra)" >&2; exit 2 ;;
esac

log()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m warning: %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[1;31m error: %s\033[0m\n' "$*" >&2; exit 1; }

wait_for() {  # wait_for <label> <timeout-seconds> <command...>
  local label="$1" timeout="$2"; shift 2
  local deadline=$(( SECONDS + timeout ))
  printf '   waiting for %s' "$label"
  until "$@" >/dev/null 2>&1; do
    (( SECONDS < deadline )) || { printf ' — timed out after %ss\n' "$timeout"; return 1; }
    printf '.'; sleep 2
  done
  printf ' ok\n'
}

# --- Preflight ---------------------------------------------------------------
command -v docker  >/dev/null || die "docker not found on PATH"
command -v flutter >/dev/null || die "flutter not found (expected ~/tools/flutter/bin)"
[[ -f "$ROOT/.env" ]] || { log "creating .env from .env.example (dev-only credentials)"; cp "$ROOT/.env.example" "$ROOT/.env"; }

# --- 1. Infrastructure -------------------------------------------------------
# The Keycloak container mounts the Direct-Grant authenticator SPI jar (Story 7.1); build it first
# so start-dev picks it up. See docker-compose.yml (keycloak service volume comment).
log "Building Keycloak authenticator SPI jar"
( cd "$ROOT/backend" && ./gradlew --quiet :keycloak-authenticator:jar )

log "Starting docker compose infra (postgres, kurrentdb, keycloak) and waiting until healthy"
( cd "$ROOT" && docker compose up -d --wait ) || die "docker compose failed to reach a healthy state"

if [[ "$MODE" == "infra" ]]; then
  log "Tier 1 done: docker containers up. (--infra: backend, emulator and flutter run skipped.)"
  exit 0
fi

# --- 2. Backend --------------------------------------------------------------
# SGART_POSTGRES_PASSWORD must match .env's POSTGRES_PASSWORD; a plain bootRun does not read .env.
# SGART_IDENTITY_KEYCLOAK_ADMIN_ENABLED=true is mandatory or silent account provisioning no-ops and
# the app dies on first launch with a generic error (see docs/first-real-world-test.md Part 1.2).
if curl -fsS "$BACKEND_HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'; then
  log "Backend already running on :8081 — leaving it as is"
else
  log "Starting backend (bootRun) in the background — logs: $BACKEND_LOG"
  ( cd "$ROOT/backend" && \
    SGART_FLYWAY_ENABLED=true \
    SGART_PROJECTOR_AUTOSTART=true \
    SGART_POSTGRES_PASSWORD=sgart_dev_password \
    SGART_IDENTITY_KEYCLOAK_ADMIN_ENABLED=true \
    nohup ./gradlew bootRun > "$BACKEND_LOG" 2>&1 & echo $! > "$RUN_DIR/backend.pid" )
  wait_for "backend health ($BACKEND_HEALTH_URL)" 240 \
    bash -c "curl -fsS '$BACKEND_HEALTH_URL' | grep -q '\"status\":\"UP\"'" \
    || die "backend did not become healthy — check $BACKEND_LOG"
fi

# --- 3. Emulator + adb reverse ----------------------------------------------
adb start-server >/dev/null 2>&1 || true
if adb devices | grep -q "^${EMULATOR_SERIAL}[[:space:]]*device$"; then
  log "Emulator $EMULATOR_SERIAL already online — reusing it"
else
  log "Booting Android emulator '$AVD_NAME' in the background — logs: $EMULATOR_LOG"
  # -gpu swiftshader_indirect: software GL, matches the working WSLg setup. Keep the window (never
  # -no-window): the point of this run is to click through the app.
  nohup emulator -avd "$AVD_NAME" -gpu swiftshader_indirect > "$EMULATOR_LOG" 2>&1 &
  echo $! > "$RUN_DIR/emulator.pid"
  wait_for "emulator device" 180 adb -s "$EMULATOR_SERIAL" wait-for-device \
    || die "emulator did not appear — check $EMULATOR_LOG"
  wait_for "android boot to complete" 180 \
    bash -c "[[ \"\$(adb -s '$EMULATOR_SERIAL' shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')\" == 1 ]]" \
    || die "android did not finish booting — check $EMULATOR_LOG"
fi

log "Wiring adb reverse (phone/emulator localhost -> WSL host) for backend :8081 and keycloak :8080"
adb -s "$EMULATOR_SERIAL" reverse tcp:8081 tcp:8081
adb -s "$EMULATOR_SERIAL" reverse tcp:8080 tcp:8080

if [[ "$MODE" == "no-app" ]]; then
  log "Tier 2 done: containers + backend + emulator ready. Start the app yourself with:  cd app && flutter run -d $EMULATOR_SERIAL"
  exit 0
fi

# --- 4. Flutter app (foreground) --------------------------------------------
log "Launching the Flutter app on $EMULATOR_SERIAL (Ctrl-C stops the app; backend + emulator keep running)"
cd "$ROOT/app"
exec flutter run -d "$EMULATOR_SERIAL"

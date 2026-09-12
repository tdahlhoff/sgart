#!/usr/bin/env bash
# Smoke-checks the SGART stack: docker-compose infra health, the backend's actuator endpoint, and
# Keycloak's realm discovery document. Defaults to the local dev stack; override the *_URL vars to
# point at another host (e.g. a future staging/production vHost) once one is actually reachable —
# see docs/first-real-world-test.md and ADR-0002's "Production concretization" backlog for why this
# script targets local-only today.
set -euo pipefail

BACKEND_HEALTH_URL="${SGART_HEALTH_CHECK_BACKEND_URL:-http://localhost:8081/actuator/health}"
KEYCLOAK_DISCOVERY_URL="${SGART_HEALTH_CHECK_KEYCLOAK_URL:-http://localhost:8080/realms/sgart/.well-known/openid-configuration}"
COMPOSE_SERVICES=(postgres kurrentdb keycloak)

failures=0

report() {
  local label="$1" ok="$2" detail="${3:-}"
  if [[ "$ok" == "true" ]]; then
    printf '  [OK]   %s\n' "$label"
  else
    printf '  [FAIL] %s%s\n' "$label" "${detail:+ — $detail}"
    failures=$((failures + 1))
  fi
}

echo "== docker compose service health =="
for service in "${COMPOSE_SERVICES[@]}"; do
  container_id="$(docker compose ps -q "$service" 2>/dev/null || true)"
  if [[ -z "$container_id" ]]; then
    report "$service" false "not running (docker compose up -d?)"
    continue
  fi
  status="$(docker inspect --format '{{.State.Health.Status}}' "$container_id" 2>/dev/null || echo "unknown")"
  report "$service" "$([[ "$status" == "healthy" ]] && echo true || echo false)" "status=$status"
done

echo "== backend =="
if body="$(curl -fsS "$BACKEND_HEALTH_URL" 2>&1)"; then
  report "actuator health ($BACKEND_HEALTH_URL)" "$([[ "$body" == *'"status":"UP"'* ]] && echo true || echo false)" "$body"
else
  report "actuator health ($BACKEND_HEALTH_URL)" false "unreachable"
fi

echo "== keycloak =="
if curl -fsS "$KEYCLOAK_DISCOVERY_URL" >/dev/null 2>&1; then
  report "realm discovery ($KEYCLOAK_DISCOVERY_URL)" true
else
  report "realm discovery ($KEYCLOAK_DISCOVERY_URL)" false "unreachable"
fi

echo
if [[ "$failures" -eq 0 ]]; then
  echo "All checks passed."
  exit 0
else
  echo "$failures check(s) failed."
  exit 1
fi

#!/usr/bin/env bash
# Builds the project and starts the 3 campus servers + the web frontend.
# Ctrl+C stops all of them. Logs: logs/<CAMPUS>_server.log
set -euo pipefail
cd "$(dirname "$0")"

# fail fast with a clear message if an earlier instance is still holding the ports
for port in "${DRMS_SGW_PORT:-50051}" "${DRMS_LOY_PORT:-50052}" "${DRMS_WIL_PORT:-50053}" "${WEB_PORT:-8080}"; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Port $port is already in use (PID $(lsof -nP -t -iTCP:"$port" -sTCP:LISTEN | head -1))."
    echo "Stop the earlier instance first, e.g.: kill \$(lsof -t -iTCP:$port -sTCP:LISTEN)"
    exit 1
  fi
done

./gradlew -q installDist
BIN=build/install/drms/bin

pids=()
cleanup() {
  trap - INT TERM EXIT
  echo
  echo "Stopping..."
  kill "${pids[@]}" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup INT TERM EXIT

for campus in SGW LOY WIL; do
  DRMS_SEED="${DRMS_SEED:-true}" "$BIN/drms-server" "$campus" &
  pids+=($!)
done
"$BIN/drms-web" &
pids+=($!)

echo "Open http://localhost:${WEB_PORT:-8080}   (Ctrl+C to stop)"
wait

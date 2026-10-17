#!/usr/bin/env bash
set -euo pipefail

mkdir -p /workspace /output
# Copy the sources without host build output: .git, Gradle/IDE state, build directories and
# node_modules (the host's macOS/Windows node_modules would not run in this Linux container).
tar -C /source \
  --exclude=.git --exclude=.gradle --exclude=.kotlin --exclude=.idea --exclude=.intellijPlatform \
  --exclude='*/build' --exclude='*/bin' --exclude=node_modules --exclude=kuml-web/tests/.output \
  -cf - . | tar -C /workspace -xf -
cd /workspace

chmod +x gradlew

echo "Building kUML Web distribution..."
./gradlew :kuml-web:installDist --no-daemon

echo "Starting kUML Web distribution..."
touch /tmp/kuml-web.log
(
  /workspace/kuml-web/build/install/kuml-web/bin/kuml-web
) > /tmp/kuml-web.log 2>&1 &
server_pid=$!

tail -n +1 -F /tmp/kuml-web.log &
log_pid=$!

cleanup() {
  kill "$log_pid" 2>/dev/null || true
  wait "$log_pid" 2>/dev/null || true
  kill "$server_pid" 2>/dev/null || true
  wait "$server_pid" 2>/dev/null || true
  cp /tmp/kuml-web.log /output/kuml-web.log
}
trap cleanup EXIT

for attempt in $(seq 1 120); do
  if curl --fail --silent http://127.0.0.1:8080/api/health >/dev/null; then
    break
  fi

  if ! kill -0 "$server_pid" 2>/dev/null; then
    cat /tmp/kuml-web.log
    exit 1
  fi

  if (( attempt % 10 == 0 )); then
    echo "Waiting for kUML Web health endpoint (${attempt}s elapsed)..."
  fi

  sleep 1
done

if ! curl --fail --silent http://127.0.0.1:8080/api/health >/dev/null; then
  echo "kUML Web did not become healthy within 120 seconds."
  exit 1
fi

echo "kUML Web is healthy; starting browser test."

cd /workspace/kuml-web
npm run test:browser

#!/usr/bin/env bash
set -eu

script=${1:?Pass the path to server-preflight.sh}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
export DEEPSEEK_API_KEY=preflight_secret_should_not_appear

run_inventory() {
  set +e
  bash "$script" > "$tmp/report" 2>&1
  result=$?
  set -e
  if [ "$result" -ne 2 ]; then
    cat "$tmp/report"
    echo "Expected incomplete inventory (exit 2) in this minimal container; got $result" >&2
    exit 1
  fi
  grep -q '^## OS' "$tmp/report"
  grep -q '^## Listening ports' "$tmp/report"
  grep -q '^MANUAL_CHECK_REQUIRED' "$tmp/report"
  if grep -q "$DEEPSEEK_API_KEY" "$tmp/report"; then
    echo 'Inventory leaked an environment secret' >&2
    exit 1
  fi
}

run_inventory
grep -q '\[UNAVAILABLE\] docker' "$tmp/report"
echo 'PASS: minimal Linux reports missing tools and requires manual checks, without environment secrets'

mkdir "$tmp/bin"
cat > "$tmp/bin/free" <<'STUB'
#!/usr/bin/env bash
echo 'simulated permission denied' >&2
exit 1
STUB
chmod +x "$tmp/bin/free"
export PATH="$tmp/bin:$PATH"
run_inventory
grep -q '\[FAILED\] free' "$tmp/report" || { cat "$tmp/report"; exit 1; }
echo 'PASS: command failure is reported as incomplete rather than healthy'

cat > "$tmp/bin/docker" <<'STUB'
#!/usr/bin/env bash
case "$1" in
  --version) echo 'Docker test client' ;;
  compose) [ "$2" = version ] ;;
  --host)
    [ "$2" = unix:///var/run/docker.sock ] || exit 1
    printf '%s\n' "$3" >> "$PREFLIGHT_TEST_DOCKER_LOG"
    ;;
  *) echo 'Unexpected remote Docker request'; exit 1 ;;
esac
STUB
cat > "$tmp/bin/systemctl" <<'STUB'
#!/usr/bin/env bash
echo "nginx.service loaded active running $DEEPSEEK_API_KEY"
STUB
chmod +x "$tmp/bin/docker" "$tmp/bin/systemctl"
export PREFLIGHT_TEST_DOCKER_LOG="$tmp/docker-calls"
export DOCKER_HOST=tcp://unused.invalid:2375
run_inventory
grep -q '^nginx.service active running$' "$tmp/report"
[ "$(cat "$tmp/docker-calls")" = "$(printf 'info\nps')" ]
echo 'PASS: Docker queries pin the local socket; service descriptions are omitted'

cat > "$tmp/bin/java" <<'STUB'
#!/usr/bin/env bash
echo 'Java test version'
if [ -n "${JAVA_TOOL_OPTIONS:-}" ]; then echo "$JAVA_TOOL_OPTIONS"; fi
if [ -n "${JDK_JAVA_OPTIONS:-}" ]; then echo "$JDK_JAVA_OPTIONS"; fi
if [ -n "${_JAVA_OPTIONS:-}" ]; then echo "$_JAVA_OPTIONS"; fi
STUB
chmod +x "$tmp/bin/java"
export JAVA_TOOL_OPTIONS="$DEEPSEEK_API_KEY"
export JDK_JAVA_OPTIONS="$DEEPSEEK_API_KEY"
export _JAVA_OPTIONS="$DEEPSEEK_API_KEY"
run_inventory
echo 'PASS: Java version probing does not echo inherited JVM options'

# PowerShell's native pipeline appends CRLF even when the source file has LF.
for tool in free ss mysql redis-server; do
  printf '#!/bin/sh\necho "probe test result"\n' > "$tmp/bin/$tool"
  chmod +x "$tmp/bin/$tool"
done
set +e
{ cat "$script"; printf '\r\n'; } | bash > "$tmp/report" 2>&1
result=$?
set -e
if [ "$result" -ne 0 ]; then
  cat "$tmp/report"
  echo "Expected successful stdin transport to exit 0; got $result" >&2
  exit 1
fi
grep -q '^AUTOMATED_PROBES_COLLECTED' "$tmp/report"
echo 'PASS: appended PowerShell CRLF does not turn successful probes into exit 127'

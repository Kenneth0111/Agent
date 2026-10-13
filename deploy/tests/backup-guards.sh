#!/bin/sh
set -eu
scripts=${1:?Pass deploy directory}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin" "$tmp/backups"
touch "$tmp/test.env" "$tmp/calls"
export TEST_DOCKER_LOG="$tmp/calls"
cat > "$tmp/bin/docker" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" >> "$TEST_DOCKER_LOG"
case "$*" in
  *' ps -a -q backend'*) [ "${TEST_BACKEND_STATE:-absent}" = absent ] || echo deadbeef ;;
  *mysqldump*) echo '-- test dump'; exit 9 ;;
  *information_schema.tables*) echo 3 ;;
  *) exit 0 ;;
esac
STUB
chmod +x "$tmp/bin/docker"
export PATH="$tmp/bin:$PATH"

expect_failure() {
  if "$@" > "$tmp/output" 2>&1; then
    echo 'Expected refusal, command succeeded' >&2
    exit 1
  fi
}
require_message() { grep -q "$1" "$tmp/output" || { cat "$tmp/output"; exit 1; }; }
expect_failure sh "$scripts/restore.sh" creator-agent-release "$tmp/test.env" "$tmp/database.sql"
require_message 'creator-agent-restore-'
[ ! -s "$tmp/calls" ]
echo 'PASS: ordinary deployment project is refused before Docker'

printf 'SELECT 1;\n' > "$tmp/database.sql"
printf 'creator-agent-mysql-v1\ncreator-agent-source\n' > "$tmp/database.sql.source"
printf '%064d\n' 0 > "$tmp/database.sql.sha256"
expect_failure sh "$scripts/restore.sh" creator-agent-restore-test "$tmp/test.env" "$tmp/database.sql"
require_message 'checksum'
[ ! -s "$tmp/calls" ]
echo 'PASS: corrupt dump is refused before Docker'

sum=$(sha256sum "$tmp/database.sql")
printf '%s\n' "${sum%% *}" > "$tmp/database.sql.sha256"
printf 'creator-agent-mysql-v1\n' > "$tmp/database.sql.source"
expect_failure sh "$scripts/restore.sh" creator-agent-restore-test "$tmp/test.env" "$tmp/database.sql"
require_message 'source project'
[ ! -s "$tmp/calls" ]
echo 'PASS: incomplete completion marker is refused before Docker'
printf 'creator-agent-mysql-v1\ncreator-agent-source\n' > "$tmp/database.sql.source"
export TEST_BACKEND_STATE=running
expect_failure sh "$scripts/restore.sh" creator-agent-restore-test "$tmp/test.env" "$tmp/database.sql"
require_message 'absent'
if grep -q 'exec mysql' "$tmp/calls"; then
  echo 'Running backend reached database restore operations' >&2
  exit 1
fi
unset TEST_BACKEND_STATE
echo 'PASS: running backend is refused before database restore operations'

for state in restarting exited; do
  export TEST_BACKEND_STATE=$state
  expect_failure sh "$scripts/restore.sh" creator-agent-restore-test "$tmp/test.env" "$tmp/database.sql"
  require_message 'absent'
done
unset TEST_BACKEND_STATE
echo 'PASS: restarting or stopped backend containers cannot enter restore'

expect_failure sh "$scripts/restore.sh" creator-agent-restore-test "$tmp/test.env" "$tmp/database.sql"
require_message 'empty'
if grep -q 'exec mysql .*--database=creator_agent' "$tmp/calls"; then
  echo 'Nonempty target reached SQL import' >&2
  exit 1
fi
echo 'PASS: nonempty target never reaches SQL import'

expect_failure sh "$scripts/backup.sh" creator-agent-source "$tmp/test.env" "$tmp/backups"
[ -z "$(find "$tmp/backups" -type f)" ]
echo 'PASS: failed dump leaves no complete backup or partial SQL'

#!/bin/sh
# Candidate Compose stack only. SQL and encrypted credential rows are private data.
set -eu
umask 077
[ "$#" -eq 3 ] || { echo 'Usage: sh backup.sh SOURCE_PROJECT ENV_FILE BACKUP_DIRECTORY' >&2; exit 1; }
project=$1
env_file=$2
output=$3
case "$project" in ''|[!a-z0-9]*|*[!a-z0-9_-]*) echo 'Invalid project name' >&2; exit 1 ;; esac
[ -f "$env_file" ] || { echo 'Environment file missing' >&2; exit 1; }
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
dc() { docker compose --env-file "$env_file" -f "$script_dir/compose.yml" -p "$project" "$@"; }

mkdir -p "$output"
bundle=$(mktemp -d "$output/creator-agent-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")
partial="$bundle/database.sql.partial"
dump="$bundle/database.sql"
trap 'if [ -f "$partial" ]; then rm -f "$partial"; fi' EXIT
# No --databases: no CREATE DATABASE / USE statements redirecting the restore target.
# The password is taken from the existing container, never interpolated on the host.
if ! dc exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump --no-defaults --user=creator --host=127.0.0.1 --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 creator_agent' > "$partial"; then
  echo 'Database export failed; no complete backup published' >&2
  exit 1
fi
[ -s "$partial" ] || { echo 'Database export was empty' >&2; exit 1; }
mv "$partial" "$dump"
sum=$(sha256sum "$dump")
printf '%s\n' "${sum%% *}" > "$dump.sha256"
# This is the completion marker. Checksums detect corruption, not authenticity.
printf 'creator-agent-mysql-v1\n%s\n' "$project" > "$dump.source"
printf 'Backup created: %s\n' "$dump"

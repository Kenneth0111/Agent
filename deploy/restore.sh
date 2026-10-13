#!/bin/sh
# Restore only into an empty, isolated candidate Compose project without an application container.
set -eu
[ "$#" -eq 3 ] || { echo 'Usage: sh restore.sh RESTORE_PROJECT ENV_FILE SQL_FILE' >&2; exit 1; }
project=$1
env_file=$2
dump=$3
case "$project" in creator-agent-restore-?*) ;; *) echo 'Target must use creator-agent-restore- prefix' >&2; exit 1 ;; esac
case "$project" in *[!a-z0-9_-]*) echo 'Invalid project name' >&2; exit 1 ;; esac
[ -f "$dump" ] && [ -s "$dump" ] && [ -f "$dump.sha256" ] && [ -f "$dump.source" ] || { echo 'Complete backup files missing' >&2; exit 1; }
[ "$(sed -n '1p' "$dump.source")" = creator-agent-mysql-v1 ] || { echo 'Unknown backup format' >&2; exit 1; }
source_project=$(sed -n '2p' "$dump.source")
case "$source_project" in ''|[!a-z0-9]*|*[!a-z0-9_-]*) echo 'Invalid backup source project' >&2; exit 1 ;; esac
[ "$source_project" != "$project" ] || { echo 'Cannot restore into the source project' >&2; exit 1; }
IFS= read -r expected < "$dump.sha256"
case "$expected" in *[!0-9a-f]*) echo 'Invalid checksum' >&2; exit 1 ;; esac
[ "${#expected}" -eq 64 ] || { echo 'Invalid checksum' >&2; exit 1; }
sum=$(sha256sum "$dump")
[ "${sum%% *}" = "$expected" ] || { echo 'Backup checksum mismatch' >&2; exit 1; }
[ -f "$env_file" ] || { echo 'Environment file missing' >&2; exit 1; }
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
dc() { docker compose --env-file "$env_file" -f "$script_dir/compose.yml" -p "$project" "$@"; }

backend=$(dc ps -a -q backend)
if [ -n "$backend" ]; then
  echo 'Target backend container must be absent; use a new mysql-only restore project' >&2
  exit 1
fi
tables=$(dc exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --no-defaults --user=creator --host=127.0.0.1 --batch --skip-column-names --execute="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=\"creator_agent\";"')
[ "$tables" = 0 ] || { echo 'Target database must be empty; nothing imported' >&2; exit 1; }
if ! dc exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --no-defaults --user=creator --host=127.0.0.1 --default-character-set=utf8mb4 --database=creator_agent' < "$dump"; then
  echo 'Import failed; leave this isolated database paused and inspect it. Do not retry over existing tables.' >&2
  exit 1
fi
printf 'Restored into isolated project: %s. Application was not started.\n' "$project"

#!/usr/bin/env bash
# Read-only inventory. No installation, service changes, config dumps or network requests.
set -uo pipefail

if [ "$(uname -s)" != Linux ]; then
  echo 'UNSUPPORTED: run this script on the target Linux server.' >&2
  exit 2
fi

incomplete=0
probe() {
  printf '\n## %s\n' "$1"
  shift
  if ! command -v "$1" >/dev/null 2>&1; then
    printf '[UNAVAILABLE] %s; verify manually.\n' "$1"
    incomplete=1
  elif ! "$@"; then
    printf '[FAILED] %s; result unknown, verify permissions/service state manually.\n' "$1"
    incomplete=1
  fi
}

echo '# Creator Agent server inventory'
echo 'This report contains local service/path/port metadata. Keep it private.'
probe 'OS' awk '/^(PRETTY_NAME|ID|VERSION_ID)=/' /etc/os-release
probe 'Architecture' uname -m
probe 'CPU count' nproc
probe 'Memory (MiB)' free -m
probe 'Root disk usage' df -h /
if [ -d /www ]; then
  probe 'BaoTa disk usage' df -h /www
fi
probe 'Listening ports (numeric, no process arguments)' ss -H -ltnu

printf '\n## BaoTa paths\n'
if [ -d /www/server/panel ]; then
  echo 'Panel directory: present'
  for path in /www/server/panel/vhost/nginx /www/server/panel/vhost/apache; do
    if [ -d "$path" ]; then
      # Filenames identify existing sites; never read configuration contents.
      probe "Site config filenames: $path" find "$path" -maxdepth 1 -type f -name '*.conf' -printf '%f\n'
    fi
  done
else
  echo 'Panel directory: not visible at the usual path; verify in the panel.'
fi

running_services() {
  command -v systemctl >/dev/null || return 1
  systemctl list-units --type=service --state=running --no-legend --no-pager |
    awk '$1 ~ /^(nginx|apache2|httpd|mysql|mysqld|mariadb|redis[^ ]*|docker|bt[^ ]*)[.]service$/ {print $1, $3, $4}'
}
probe 'Relevant running services (names/states only)' running_services
probe 'MySQL client version (does not connect)' mysql --no-defaults --version
probe 'Redis binary version (does not start a server)' redis-server --version
# JVMs echo these inherited options, which can contain application credentials.
probe 'Java version (without inherited JVM options)' env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS java -version
probe 'Docker client version' docker --version
probe 'Compose version' docker compose version
# Pin the local socket: inherited Docker contexts/DOCKER_HOST must not contact another server.
probe 'Local Docker data directory' docker --host unix:///var/run/docker.sock info --format '{{.DockerRootDir}}'
probe 'Local running containers (no environment/inspect)' docker --host unix:///var/run/docker.sock ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}'

printf '\nMANUAL_CHECK_REQUIRED: sites/databases and ownership, domain/HTTPS, Tencent firewall, outbound access, deployment directory and free resources.\n'
if [ "$incomplete" -ne 0 ]; then
  echo 'AUTOMATED_INVENTORY_INCOMPLETE: one or more probes unavailable/failed. This is not a deployment readiness check.'
  exit 2
fi
echo 'AUTOMATED_PROBES_COLLECTED: complete manual checks before choosing deployment settings.'
# Stop before any CRLF appended by PowerShell when this script is piped to bash -s.
exit 0

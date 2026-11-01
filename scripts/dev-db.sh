#!/usr/bin/env bash
# Runs a private PostgreSQL 16 cluster for local development and tests, without Docker,
# system services or root-owned configuration. Safe to run repeatedly.
#
# Usage:
#   scripts/dev-db.sh [start]      initialise if needed, start, create the role and databases
#   scripts/dev-db.sh env          print export lines (use: eval "$(scripts/dev-db.sh env)")
#   scripts/dev-db.sh status       show whether the server is running
#   scripts/dev-db.sh stop         stop the server
#   scripts/dev-db.sh destroy --yes  stop the server and delete all its data
#
# Settings (environment variables):
#   DEV_DB_PORT        TCP port on 127.0.0.1 (default 5433, so it does not clash with a system server)
#   DEV_DB_STATE_DIR   where data, logs and the generated password live
#                      (default: /var/tmp/aiexpense-pg as root, ~/.local/share/aiexpense-pg otherwise)
#   PG_BIN             directory containing initdb/pg_ctl/postgres, if not auto-detected
#
# The application password is generated randomly on first start and kept in
# $DEV_DB_STATE_DIR/credentials.env (mode 600). Nothing secret is stored in the repository.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

REQUIRED_PG=16
APP_ROLE="aiexpense"
DEV_DB_NAME="aiexpense"
TEST_DB_NAME="aiexpense_test"
PORT="${DEV_DB_PORT:-5433}"

if [[ "$(id -u)" -eq 0 ]]; then
  STATE_DIR="${DEV_DB_STATE_DIR:-/var/tmp/aiexpense-pg}"
else
  STATE_DIR="${DEV_DB_STATE_DIR:-${XDG_DATA_HOME:-$HOME/.local/share}/aiexpense-pg}"
fi

die() {
  echo "dev-db: $*" >&2
  exit 1
}

info() {
  echo "dev-db: $*" >&2
}

# --- State directory safety -------------------------------------------------
# The state directory is created, chmod-ed and (with 'destroy') deleted by this
# script, so it must never point at the repository, a home directory, '/' or
# another system location. Every command validates it first.

MARKER_NAME=".aiexpense-dev-db"
# The only entries this script ever creates inside the state directory.
EXPECTED_ENTRIES=("$MARKER_NAME" data socket postgres.log credentials.env)

# Print an absolute path with '.', '..' and symlinks resolved, even if it does not exist yet.
resolve_path() {
  local path="$1"
  if realpath -m -- / >/dev/null 2>&1; then
    realpath -m -- "$path"
  elif [[ -d "$path" ]]; then
    (cd -- "$path" && pwd -P)
  else
    # Fallback without GNU realpath: refuse '..' so the path cannot climb upwards.
    [[ "/$path/" != *"/../"* ]] || return 1
    printf '%s\n' "$path"
  fi
}

validate_state_dir() {
  local raw="$1" resolved repo_root home_dir protected depth
  [[ -n "$raw" ]] || die "the state directory is empty."
  [[ "$raw" == /* ]] || die "DEV_DB_STATE_DIR must be an absolute path (got '$raw')."
  resolved="$(resolve_path "$raw")" || die "cannot resolve state directory '$raw'."
  repo_root="$(resolve_path "$SCRIPT_DIR/..")"
  home_dir="$(resolve_path "${HOME:-/}")"

  for protected in / /bin /boot /dev /etc /home /lib /lib64 /media /mnt /opt /proc /root \
    /run /sbin /srv /sys /tmp /usr /usr/local /var /var/lib /var/tmp \
    "$home_dir" "$home_dir/.local" "$home_dir/.local/share"; do
    [[ "$resolved" != "$protected" ]] || die "refusing to use '$resolved' as the state directory (protected location)."
  done
  if [[ "$repo_root" == "$resolved" || "$repo_root" == "$resolved"/* ]]; then
    die "refusing to use '$resolved' as the state directory: it contains the repository."
  fi
  if [[ "$resolved" == "$repo_root"/* ]]; then
    die "refusing to use '$resolved' as the state directory: it is inside the repository."
  fi
  if [[ "$home_dir" == "$resolved"/* ]]; then
    die "refusing to use '$resolved' as the state directory: it contains a home directory."
  fi
  depth="$(tr -cd '/' <<< "$resolved" | wc -c)"
  (( depth >= 2 )) || die "refusing to use '$resolved' as the state directory: too close to '/'."

  printf '%s\n' "$resolved"
}

# Fail unless every entry in the state directory is one this script creates.
require_only_expected_entries() {
  local entry name expected ok
  shopt -s nullglob dotglob
  for entry in "$STATE_DIR"/*; do
    name="$(basename -- "$entry")"
    ok=0
    for expected in "${EXPECTED_ENTRIES[@]}"; do
      [[ "$name" == "$expected" ]] && ok=1 && break
    done
    if [[ "$ok" -eq 0 ]]; then
      shopt -u nullglob dotglob
      die "$STATE_DIR contains '$name', which this script did not create; refusing to continue."
    fi
  done
  shopt -u nullglob dotglob
}

STATE_DIR="$(validate_state_dir "$STATE_DIR")" || exit 1
DATA_DIR="$STATE_DIR/data"
SOCKET_DIR="$STATE_DIR/socket"
LOG_FILE="$STATE_DIR/postgres.log"
CREDENTIALS_FILE="$STATE_DIR/credentials.env"
MARKER_FILE="$STATE_DIR/$MARKER_NAME"

PG_BINDIR="$(find_pg_bindir)" || die "PostgreSQL tools (${PG_REQUIRED_TOOLS[*]}) not found in one directory.
  This environment cannot run a local database. Database tests run in GitHub Actions instead.
  If the tools are installed elsewhere, set PG_BIN to their directory."

problem="$(pg_tools_runnable "$PG_BINDIR" "$REQUIRED_PG")" \
  || die "$problem. PostgreSQL $REQUIRED_PG is required."

# PostgreSQL refuses to run as root, so as root every server command runs as 'postgres'.
if [[ "$(id -u)" -eq 0 ]]; then
  id postgres >/dev/null 2>&1 || die "running as root but there is no 'postgres' user to run the server."
  command -v runuser >/dev/null 2>&1 || die "running as root but 'runuser' is not available."
  SERVER_USER="postgres"
  as_server() { runuser -u postgres -- "$@"; }
else
  SERVER_USER="$(id -un)"
  as_server() { "$@"; }
fi

# psql as the cluster superuser over the private Unix socket (peer authentication).
superuser_psql() {
  as_server "$PG_BINDIR/psql" -X -q -v ON_ERROR_STOP=1 \
    -h "$SOCKET_DIR" -p "$PORT" -U "$SERVER_USER" -d postgres "$@"
}

is_running() {
  [[ -f "$DATA_DIR/PG_VERSION" ]] && as_server "$PG_BINDIR/pg_ctl" -D "$DATA_DIR" status >/dev/null 2>&1
}

load_or_create_password() {
  if [[ -f "$CREDENTIALS_FILE" ]]; then
    # shellcheck disable=SC1090
    source "$CREDENTIALS_FILE"
    [[ -n "${DEV_DB_APP_PASSWORD:-}" ]] || die "$CREDENTIALS_FILE exists but has no password."
  else
    DEV_DB_APP_PASSWORD="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    (umask 077 && printf 'DEV_DB_APP_PASSWORD=%s\n' "$DEV_DB_APP_PASSWORD" > "$CREDENTIALS_FILE")
    info "generated a new local password in $CREDENTIALS_FILE"
  fi
}

init_cluster() {
  if [[ -f "$DATA_DIR/PG_VERSION" ]]; then
    local existing
    existing="$(cat "$DATA_DIR/PG_VERSION")"
    [[ "$existing" == "$REQUIRED_PG" ]] \
      || die "$DATA_DIR holds a PostgreSQL $existing cluster; run '$0 destroy --yes' first."
    require_only_expected_entries
    # Clusters created before the marker existed get one, so 'destroy' can recognise them.
    [[ -f "$MARKER_FILE" ]] || : > "$MARKER_FILE"
    return 0
  fi

  if [[ -d "$STATE_DIR" ]]; then
    # Reuse only a directory this script created (e.g. after an interrupted first run).
    require_only_expected_entries
  fi
  info "initialising a new cluster in $DATA_DIR"
  mkdir -p "$STATE_DIR" 2>/dev/null \
    || die "cannot create $STATE_DIR (no write access); set DEV_DB_STATE_DIR to a writable directory."
  : > "$MARKER_FILE"
  chmod 755 "$STATE_DIR"
  mkdir -p "$DATA_DIR" "$SOCKET_DIR"
  if [[ "$(id -u)" -eq 0 ]]; then
    chown postgres:postgres "$DATA_DIR" "$SOCKET_DIR"
  fi
  chmod 700 "$DATA_DIR"

  as_server "$PG_BINDIR/initdb" -D "$DATA_DIR" -U "$SERVER_USER" \
    --encoding=UTF8 --locale=C.UTF-8 \
    --auth-local=peer --auth-host=scram-sha-256 >/dev/null
}

start_server() {
  if is_running; then
    info "server already running on 127.0.0.1:$PORT"
    return 0
  fi
  port_is_free "$PORT" || die "port $PORT is already in use by another process; set DEV_DB_PORT."

  touch "$LOG_FILE"
  if [[ "$(id -u)" -eq 0 ]]; then
    chown postgres:postgres "$LOG_FILE"
  fi
  info "starting server on 127.0.0.1:$PORT (log: $LOG_FILE)"
  as_server "$PG_BINDIR/pg_ctl" -D "$DATA_DIR" -l "$LOG_FILE" -w -t 30 \
    -o "-c listen_addresses=127.0.0.1 -c port=$PORT -c unix_socket_directories=$SOCKET_DIR" \
    start >/dev/null || die "server did not start; see $LOG_FILE"
}

ensure_roles_and_databases() {
  # The password reaches psql through stdin, never as a command-line argument,
  # so it does not show up in the process list. It is hex, so quoting is safe.
  {
    printf "\\\\set app_password '%s'\n" "$DEV_DB_APP_PASSWORD"
    cat <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'app_role')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_role') \gexec
ALTER ROLE :"app_role" WITH LOGIN PASSWORD :'app_password';
SQL
  } | superuser_psql -v app_role="$APP_ROLE"

  local db
  for db in "$DEV_DB_NAME" "$TEST_DB_NAME"; do
    superuser_psql -v db="$db" -v app_role="$APP_ROLE" <<'SQL'
SELECT format('CREATE DATABASE %I OWNER %I', :'db', :'app_role')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db') \gexec
SQL
  done
}

print_env() {
  cat <<EOF
export DB_URL='jdbc:postgresql://127.0.0.1:$PORT/$DEV_DB_NAME'
export DB_USERNAME='$APP_ROLE'
export DB_PASSWORD='$DEV_DB_APP_PASSWORD'
export TEST_DB_URL='jdbc:postgresql://127.0.0.1:$PORT/$TEST_DB_NAME'
export TEST_DB_USERNAME='$APP_ROLE'
export TEST_DB_PASSWORD='$DEV_DB_APP_PASSWORD'
EOF
}

cmd_start() {
  init_cluster
  load_or_create_password
  start_server
  ensure_roles_and_databases
  info "ready: databases '$DEV_DB_NAME' and '$TEST_DB_NAME' owned by '$APP_ROLE'"
  info "load the connection variables with: eval \"\$($0 env)\""
}

cmd_env() {
  [[ -f "$CREDENTIALS_FILE" ]] || die "no credentials yet; run '$0 start' first."
  load_or_create_password
  print_env
}

cmd_status() {
  if is_running; then
    echo "running on 127.0.0.1:$PORT (data: $DATA_DIR)"
  elif [[ -f "$DATA_DIR/PG_VERSION" ]]; then
    echo "stopped (data: $DATA_DIR)"
    return 3
  else
    echo "not initialised (state dir: $STATE_DIR)"
    return 3
  fi
}

cmd_stop() {
  if is_running; then
    as_server "$PG_BINDIR/pg_ctl" -D "$DATA_DIR" -m fast -w stop >/dev/null
    info "server stopped"
  else
    info "server is not running"
  fi
}

cmd_destroy() {
  [[ "${1:-}" == "--yes" ]] || die "this deletes $STATE_DIR permanently; re-run with '--yes'."
  if [[ ! -e "$STATE_DIR" ]]; then
    info "nothing to delete: $STATE_DIR does not exist"
    return 0
  fi
  [[ -d "$STATE_DIR" && ! -L "$STATE_DIR" ]] || die "$STATE_DIR is not a plain directory; refusing to delete it."
  [[ -f "$MARKER_FILE" ]] \
    || die "$STATE_DIR has no '$MARKER_NAME' marker, so it was not created by this script; refusing to delete it."
  require_only_expected_entries

  cmd_stop
  rm -rf -- "$STATE_DIR"
  info "deleted $STATE_DIR"
}

case "${1:-start}" in
  start) cmd_start ;;
  env) cmd_env ;;
  status) cmd_status ;;
  stop) cmd_stop ;;
  destroy) shift; cmd_destroy "$@" ;;
  -h | --help | help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) die "unknown command '$1' (use start, env, status, stop, destroy --yes)" ;;
esac

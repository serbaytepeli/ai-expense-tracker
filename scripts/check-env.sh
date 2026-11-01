#!/usr/bin/env bash
# Reports which tools this environment provides for AI Expense Tracker development.
# It only inspects the environment; it never installs or changes anything.
#
# Exit codes:
#   0  all required tools are present (the local database may still be unavailable)
#   1  at least one required tool is missing
#
# Usage: scripts/check-env.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source-path=SCRIPTDIR source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

REQUIRED_JAVA=21
REQUIRED_PG=16
REQUIRED_NODE="$(tr -d '[:space:]v' < "$REPO_ROOT/.nvmrc" 2>/dev/null || true)"
DEV_DB_PORT="${DEV_DB_PORT:-5433}"

missing_required=0
db_ready=1

report() {
  # report <OK|MISSING|WARN|INFO> <item> <detail>
  printf '%-8s %-32s %s\n' "$1" "$2" "$3"
}

echo "AI Expense Tracker - environment check"
echo "Repository: $REPO_ROOT"
echo
echo "== Required tools =="

if command -v git >/dev/null 2>&1; then
  report OK "git" "$(git --version)"
else
  report MISSING "git" "install git"
  missing_required=1
fi

java_major="$(java_major_version)"
if [[ -z "$java_major" ]]; then
  report MISSING "java $REQUIRED_JAVA" "java not found"
  missing_required=1
elif [[ "$java_major" == "$REQUIRED_JAVA" ]]; then
  report OK "java $REQUIRED_JAVA" "found java $java_major"
else
  report MISSING "java $REQUIRED_JAVA" "found java $java_major; the Gradle toolchain needs $REQUIRED_JAVA"
  missing_required=1
fi

node_major="$(node_major_version)"
if [[ -z "$node_major" ]]; then
  report MISSING "node ${REQUIRED_NODE:-?}" "node not found"
  missing_required=1
elif [[ -z "$REQUIRED_NODE" ]]; then
  report WARN "node" "found v$node_major; .nvmrc is missing so the version was not compared"
elif [[ "$node_major" == "$REQUIRED_NODE" ]]; then
  report OK "node $REQUIRED_NODE" "found $(node --version)"
else
  report WARN "node $REQUIRED_NODE" "found $(node --version); .nvmrc expects $REQUIRED_NODE (CI uses .nvmrc)"
fi

if command -v npm >/dev/null 2>&1; then
  report OK "npm" "$(npm --version 2>/dev/null)"
else
  report MISSING "npm" "npm not found"
  missing_required=1
fi

echo
echo "== Local PostgreSQL $REQUIRED_PG (optional; GitHub Actions is the source of truth) =="

if command -v psql >/dev/null 2>&1; then
  client_version="$(psql --version 2>/dev/null || true)"
  client_major="$(pg_major_version psql)"
  if [[ -z "$client_version" ]]; then
    report WARN "psql client" "psql exists but does not run (no client package behind the wrapper?)"
    db_ready=0
  elif [[ "$client_major" == "$REQUIRED_PG" ]]; then
    report OK "psql client" "$client_version"
  else
    report WARN "psql client" "$client_version; expected major $REQUIRED_PG"
    db_ready=0
  fi
else
  report WARN "psql client" "not found"
  db_ready=0
fi

if pg_bindir="$(find_pg_bindir)"; then
  if problem="$(pg_tools_runnable "$pg_bindir" "$REQUIRED_PG")"; then
    report OK "server tools" "$pg_bindir (${PG_REQUIRED_TOOLS[*]}, version $REQUIRED_PG)"
  else
    report WARN "server tools" "$problem"
    db_ready=0
  fi
else
  report WARN "server tools" "${PG_REQUIRED_TOOLS[*]} not found together (set PG_BIN to their directory)"
  db_ready=0
fi

if [[ "$(id -u)" -eq 0 ]]; then
  if id postgres >/dev/null 2>&1 && command -v runuser >/dev/null 2>&1; then
    report OK "server user" "running as root; dev-db.sh will run the server as 'postgres' via runuser"
  else
    report WARN "server user" "running as root and no 'postgres' user/runuser; PostgreSQL refuses to run as root"
    db_ready=0
  fi
else
  report OK "server user" "running as $(id -un); dev-db.sh will run the server as this user"
fi

state_parent="${DEV_DB_STATE_DIR:-}"
if [[ -z "$state_parent" ]]; then
  if [[ "$(id -u)" -eq 0 ]]; then state_parent="/var/tmp"; else state_parent="${XDG_DATA_HOME:-$HOME/.local/share}"; fi
fi
probe_dir="$state_parent"
while [[ ! -d "$probe_dir" ]]; do probe_dir="$(dirname "$probe_dir")"; done
if [[ -w "$probe_dir" ]]; then
  report OK "writable state dir" "$state_parent"
else
  report WARN "writable state dir" "$probe_dir is not writable (set DEV_DB_STATE_DIR)"
  db_ready=0
fi

if port_is_free "$DEV_DB_PORT"; then
  report OK "port $DEV_DB_PORT" "free"
else
  report INFO "port $DEV_DB_PORT" "in use (fine if dev-db.sh already started the server; otherwise set DEV_DB_PORT)"
fi

echo
echo "== Other =="
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  report INFO "docker" "available (not required)"
else
  report INFO "docker" "not available (not required)"
fi

echo
if [[ "$missing_required" -ne 0 ]]; then
  echo "RESULT: required tools are missing. Backend or mobile commands will not work here."
elif [[ "$db_ready" -eq 1 ]]; then
  echo "RESULT: ready. Run scripts/dev-db.sh to start the local database."
else
  echo "RESULT: required tools present, but a local database is not supported here."
  echo "        Database tests cannot run in this environment; they run in GitHub Actions."
fi

exit "$missing_required"

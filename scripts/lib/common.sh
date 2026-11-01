#!/usr/bin/env bash
# Shared helpers for scripts/*.sh. Source this file; do not execute it.

# Locate the PostgreSQL server binaries (initdb, pg_ctl, postgres).
# Search order: $PG_BIN, PATH, common install locations for major version 16.
# Prints the directory on success, returns 1 if not found.
find_pg_bindir() {
  local candidates=()
  if [[ -n "${PG_BIN:-}" ]]; then
    candidates+=("$PG_BIN")
  fi
  local from_path
  from_path="$(command -v initdb 2>/dev/null || true)"
  if [[ -n "$from_path" ]]; then
    candidates+=("$(dirname "$from_path")")
  fi
  candidates+=(
    /usr/lib/postgresql/16/bin
    /usr/pgsql-16/bin
    /opt/homebrew/opt/postgresql@16/bin
    /usr/local/opt/postgresql@16/bin
  )
  local dir
  for dir in "${candidates[@]}"; do
    if [[ -x "$dir/initdb" && -x "$dir/pg_ctl" && -x "$dir/postgres" ]]; then
      echo "$dir"
      return 0
    fi
  done
  return 1
}

# Print the major version of a PostgreSQL binary ("16"), or nothing.
pg_major_version() {
  "$1" --version 2>/dev/null | sed -nE 's/.* ([0-9]+)(\.[0-9]+)?.*/\1/p' | head -n1
}

# Print the Java major version ("21"), or nothing if java is missing.
java_major_version() {
  command -v java >/dev/null 2>&1 || return 0
  java -version 2>&1 \
    | grep -E 'version "' \
    | head -n1 \
    | sed -nE 's/.*version "([0-9]+)(\.[^"]*)?".*/\1/p'
}

# Print the Node.js major version ("22"), or nothing if node is missing.
node_major_version() {
  command -v node >/dev/null 2>&1 || return 0
  node --version 2>/dev/null | sed -nE 's/^v([0-9]+).*/\1/p'
}

# Return 0 if nothing is listening on 127.0.0.1:<port>.
port_is_free() {
  local port="$1"
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    return 1
  fi
  return 0
}

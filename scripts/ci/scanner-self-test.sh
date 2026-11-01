#!/usr/bin/env bash
# Proves that the security scanners actually detect problems (docs/architecture.md section 6.3).
# A scanner that silently finds nothing (wrong flags, broken config, empty database) would make
# the security gate meaningless.
#
# Nothing secret or vulnerable is committed: the fake secret and the vulnerable lockfile are
# generated at run time in a temporary directory OUTSIDE the repository. The secret's prefix is
# assembled from parts so this file itself never matches a Gitleaks rule.
#
# Usage: scripts/ci/scanner-self-test.sh <gitleaks-binary> <trivy-binary> <gitleaks-config>
# Exit code 0 = both scanners detected the planted findings; 1 = a scanner missed its finding.

set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <gitleaks-binary> <trivy-binary> <gitleaks-config>" >&2
  exit 2
fi
gitleaks_bin="$1"
trivy_bin="$2"
gitleaks_config="$3"

work_dir="$(mktemp -d "${RUNNER_TEMP:-/tmp}/scanner-self-test.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT

failures=0

# --- Gitleaks: a random value in GitHub personal access token format (belongs to no account) ---
mkdir -p "$work_dir/secret"
prefix="gh""p_"
random_part="$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 36 || true)"
printf 'GITHUB_TOKEN=%s%s\n' "$prefix" "$random_part" > "$work_dir/secret/settings.env"

set +e
"$gitleaks_bin" dir --no-banner --redact --config "$gitleaks_config" --exit-code 1 \
  "$work_dir/secret" > "$work_dir/gitleaks.log" 2>&1
gitleaks_status=$?
set -e
if [[ $gitleaks_status -eq 1 ]]; then
  echo "OK   gitleaks detected the planted secret (with the project config)"
else
  echo "FAIL gitleaks did not detect the planted secret (exit $gitleaks_status)"
  cat "$work_dir/gitleaks.log"
  failures=$((failures + 1))
fi

# --- Trivy: a lockfile pinning lodash 4.17.15 (CVE-2020-8203, HIGH, fixed in 4.17.19) ---
mkdir -p "$work_dir/deps"
cat > "$work_dir/deps/package-lock.json" <<'JSON'
{
  "name": "scanner-self-test",
  "version": "1.0.0",
  "lockfileVersion": 3,
  "requires": true,
  "packages": {
    "": {
      "name": "scanner-self-test",
      "version": "1.0.0",
      "dependencies": { "lodash": "4.17.15" }
    },
    "node_modules/lodash": {
      "version": "4.17.15",
      "resolved": "https://registry.npmjs.org/lodash/-/lodash-4.17.15.tgz"
    }
  }
}
JSON

set +e
# Same severity filter and flags as the real dependency scan in security.yml.
"$trivy_bin" fs --quiet --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed \
  --exit-code 1 --format json --output "$work_dir/trivy.json" "$work_dir/deps" 2> "$work_dir/trivy.log"
trivy_status=$?
set -e
if [[ $trivy_status -eq 1 ]] && grep -q '"CVE-2020-8203"' "$work_dir/trivy.json"; then
  echo "OK   trivy detected CVE-2020-8203 in the planted lockfile"
else
  echo "FAIL trivy did not detect CVE-2020-8203 (exit $trivy_status)"
  cat "$work_dir/trivy.log"
  failures=$((failures + 1))
fi

if [[ $failures -gt 0 ]]; then
  echo "Scanner self-test failed: $failures scanner(s) missed their planted finding."
  exit 1
fi
echo "Scanner self-test passed."

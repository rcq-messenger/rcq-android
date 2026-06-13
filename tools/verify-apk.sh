#!/usr/bin/env bash
#
# verify-apk.sh — compare two APKs' contents, ignoring ONLY the signature, to
# check a reproducible build. The APK Signing Block lives outside the ZIP entries
# so unzip never sees it; we additionally drop the v1 signature files. Everything
# else must be byte-identical. See docs/REPRODUCIBLE-BUILDS.md.
#
# Usage: ./tools/verify-apk.sh <published.apk> <locally-built.apk>
#
# For the stronger, signature-validating check, prefer:
#   apksigcopier compare <published.apk> --unsigned <built.apk>
#
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 <published.apk> <built.apk>" >&2
  exit 2
fi
A="$1"; B="$2"

# v1 (JAR) signature files — the only entries legitimately allowed to differ.
IGNORE='META-INF/MANIFEST\.MF|META-INF/[^/]+\.(SF|RSA|EC|DSA)'

sha() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

manifest() {
  local apk="$1" tmp
  tmp=$(mktemp -d)
  unzip -qq -o "$apk" -d "$tmp" >/dev/null
  ( cd "$tmp"
    find . -type f | sed 's|^\./||' | grep -Ev "^(${IGNORE})$" | LC_ALL=C sort | while read -r f; do
      printf '%s  %s\n' "$(sha "$f")" "$f"
    done )
  rm -rf "$tmp"
}

if diff <(manifest "$A") <(manifest "$B") >/dev/null; then
  echo "MATCH: contents of $(basename "$A") and $(basename "$B") are byte-identical (signature ignored)."
  echo "       This APK was built from the published source."
else
  echo "MISMATCH — differing/extra entries (left = $(basename "$A"), right = $(basename "$B")):"
  diff <(manifest "$A") <(manifest "$B") || true
  echo
  echo "Run 'diffoscope $A $B' to see exactly what differs inside a mismatched entry."
  exit 1
fi

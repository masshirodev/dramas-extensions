#!/usr/bin/env bash
# What every commit on main must pass (the pre-commit hook runs it; so can you).
# The guards first, because they are instant; then the unit tests and a debug build.
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { echo "check: $*" >&2; exit 1; }

# ReelShort's start_play.play_info is an encrypted blob. No code may read it
# (doc/SOURCES.md): the key never appears as a string in the extensions' code.
if grep -rn --include='*.kt' '"play_info"' src/*/*/src/main core/src/main; then
  fail "code reads play_info; the extensions use only the plain video_url (doc/SOURCES.md)"
fi

# Nothing that signs may be committed (doc/SIGNING.md).
if git diff --cached --name-only | grep -Ei '\.(jks|keystore|p12|pk8|pem)$'; then
  fail "a key or keystore is staged"
fi

python3 -m py_compile scripts/make-index.py scripts/make-fixtures.py
bash -n scripts/release.sh scripts/gradle.sh scripts/make-icons.sh scripts/check.sh

scripts/gradle.sh --quiet :core:test testDebugUnitTest assembleDebug
echo "check: ok"

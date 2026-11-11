#!/usr/bin/env bash
# Builds, signs, checks and publishes every extension to the `repo` branch.
# The whole procedure, and what to do when a step refuses: doc/RELEASING.md.
#
#   scripts/release.sh            build, check, write the repo branch, push it
#   scripts/release.sh --dry-run  everything but the commit and push; leaves the
#                                 branch's new contents in a folder it names
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
DRY=0
[ "${1:-}" = "--dry-run" ] && DRY=1

BT=${ANDROID_BUILD_TOOLS:-$HOME/Android/Sdk/build-tools/36.1.0}
APKSIGNER=$BT/apksigner
export AAPT2=$BT/aapt2
export DRAMAS_JAVA_HOME=${DRAMAS_JAVA_HOME:-$HOME/.sdkman/candidates/java/17.0.18-amzn}
EXPECTED=$(python3 -c 'import json;print(json.load(open("repo-meta.json"))["signingKeyFingerprint"])')

die() { echo "release: $*" >&2; exit 1; }

# 1. Publish only source that is committed and on GitHub: the repo branch must be
#    rebuildable from main.
[ -z "$(git status --porcelain)" ] || die "the working tree is not clean"
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || die "release from main"
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || die "main is not the same as origin/main; push first"

# 2. The signing key, from outside the repository (doc/SIGNING.md).
ENV_FILE=${DRAMAS_ENV_FILE:-$HOME/.config/myconf/.env}
if [ -z "${DRAMAS_EXT_KEYSTORE_PASS:-}" ] && [ -f "$ENV_FILE" ]; then
  set -a; . "$ENV_FILE"; set +a
fi
export DRAMAS_EXT_KEYSTORE=${DRAMAS_EXT_KEYSTORE:-$HOME/.config/myconf/secrets/dramas-extensions/release.jks}
[ -f "$DRAMAS_EXT_KEYSTORE" ] || die "no keystore at $DRAMAS_EXT_KEYSTORE"
[ -n "${DRAMAS_EXT_KEYSTORE_PASS:-}" ] && [ -n "${DRAMAS_EXT_KEY_PASS:-}" ] || die "DRAMAS_EXT_KEYSTORE_PASS / DRAMAS_EXT_KEY_PASS are not set"

# 3. Tests, then the release APKs.
scripts/gradle.sh clean :core:test testDebugUnitTest assembleRelease

# 4. Every APK signed with v2 by the repository's key, or nothing is published.
pairs=()
for json in src/*/*/extension.json; do
  module=$(dirname "$json")
  apk=$(find "$module/build/outputs/apk/release" -name '*-release.apk' -print -quit)
  [ -n "$apk" ] || die "$module built no signed APK (an unsigned one means the key was not found)"
  out=$("$APKSIGNER" verify --verbose --print-certs "$apk") || die "$apk does not verify"
  grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" <<<"$out" || die "$apk is not signed with v2"
  got=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$out")
  [ "$got" = "$EXPECTED" ] || die "$apk is signed by $got, not the repository key $EXPECTED"
  echo "release: $apk verified (v2, $got)"
  pairs+=("$module=$ROOT/$apk")
done

# 5. The repo branch, in a worktree outside the repository.
work=$(mktemp -d "${TMPDIR:-/tmp}/dramas-repo.XXXXXX")
cleanup() { git -C "$ROOT" worktree remove --force "$work" 2>/dev/null || rm -rf "$work"; }
[ $DRY = 1 ] || trap cleanup EXIT
if git ls-remote --exit-code --heads origin repo >/dev/null; then
  git fetch -q origin repo
  git worktree add -q -B repo "$work" origin/repo
else
  git worktree add -q --detach "$work"
  git -C "$work" checkout -q --orphan repo
  git -C "$work" rm -rq --cached . && find "$work" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
fi
python3 -I scripts/make-index.py "$work" "${pairs[@]}"

if [ $DRY = 1 ]; then
  echo "release: dry run; the repo branch would be:"; (cd "$work" && find . -path ./.git -prune -o -type f -print | sort)
  echo "release: left in $work (remove with: git worktree remove --force $work)"
  exit 0
fi

git -C "$work" add -A
if git -C "$work" diff --cached --quiet; then
  echo "release: nothing changed on the repo branch"
  exit 0
fi
git -C "$work" commit -q -m "Release from main $(git rev-parse --short HEAD)"
git -C "$work" push -q origin repo
echo "release: pushed $(git -C "$work" rev-parse --short HEAD) to repo"
echo "release: add $(python3 -c 'import json;print(json.load(open("repo-meta.json"))["repoBranchUrl"])')index.min.json"

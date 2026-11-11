#!/usr/bin/env bash
# Points git at scripts/hooks, by absolute path, so the hook also runs in the
# worktree scripts/release.sh uses for the repo branch.
set -euo pipefail
cd "$(dirname "$0")/.."
git config core.hooksPath "$PWD/scripts/hooks"
echo "hooks: core.hooksPath = $PWD/scripts/hooks"

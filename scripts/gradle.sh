#!/usr/bin/env bash
# Runs ./gradlew and leaves no JVM behind (AGENTS.md, "Processes").
#
# The build runs without a daemon and compiles Kotlin in-process (gradle.properties),
# inside a session of its own. Whatever is still alive in that session when Gradle
# returns was started by this build, and is killed by pid. Nothing is ever killed by
# name: other JVMs on the workstation (tsundoku's live engine, other builds) are
# not ours. `./gradlew --stop` is not used either: it stops every daemon of this
# Gradle version the user owns, including other builds'.
set -uo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME=${DRAMAS_JAVA_HOME:-$HOME/.sdkman/candidates/java/17.0.18-amzn}
[ -x "$JAVA_HOME/bin/java" ] || { echo "gradle.sh: no JDK 17 at $JAVA_HOME (set DRAMAS_JAVA_HOME)" >&2; exit 1; }

sidfile=$(mktemp)
setsid -w bash -c 'echo $$ > "$1"; shift; exec ./gradlew --no-daemon "$@"' _ "$sidfile" "$@"
status=$?
sid=$(cat "$sidfile"); rm -f "$sidfile"

leftover=$(pgrep -s "$sid" || true)
if [ -n "$leftover" ]; then
  echo "gradle.sh: stopping what this build left running: $(echo $leftover)" >&2
  kill $leftover 2>/dev/null || true
  sleep 2
  leftover=$(pgrep -s "$sid" || true)
  [ -n "$leftover" ] && kill -9 $leftover 2>/dev/null || true
fi
exit $status

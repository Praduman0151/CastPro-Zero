#!/usr/bin/env sh
# CastPro-Zero Gradle bootstrap launcher. Uses the pinned Gradle distribution.
set -eu
VERSION="8.13"
if command -v gradle >/dev/null 2>&1; then exec gradle "$@"; fi
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/castpro-zero/gradle-$VERSION"
BIN="$BASE/gradle-$VERSION/bin/gradle"
if [ ! -x "$BIN" ]; then
  mkdir -p "$BASE"
  ARCHIVE="$BASE/gradle-$VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ARCHIVE"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ARCHIVE" "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
  else echo "Gradle $VERSION missing; install Gradle or curl/wget." >&2; exit 1; fi
  unzip -q -o "$ARCHIVE" -d "$BASE"
fi
exec "$BIN" "$@"

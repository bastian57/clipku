#!/bin/sh
# Minimal wrapper: prefers local gradle, falls back to distribution download via gradle-wrapper properties.
# CI workflow installs Gradle 8.7 directly and calls `gradle` so this is only for local dev.
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
else
  echo "Gradle not found. Install Gradle 8.7 or use the GitHub Actions workflow."
  exit 1
fi

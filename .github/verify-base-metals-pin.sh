#!/usr/bin/env bash
set -euo pipefail
# Historical entry point now enforces the entire native dependency lock.
exec pwsh -NoProfile -File "$(dirname "$0")/verify-native-dependencies.ps1" "$@"

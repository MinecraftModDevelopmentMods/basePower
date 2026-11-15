#!/usr/bin/env bash
set -euo pipefail
exec pwsh -NoProfile -File "$(dirname "$0")/test-native-dependencies.ps1" "$@"

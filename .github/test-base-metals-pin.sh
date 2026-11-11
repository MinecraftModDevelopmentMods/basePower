#!/usr/bin/env bash
set -euo pipefail

verify="$(cd "$(dirname "$0")" && pwd)/verify-base-metals-pin.sh"
temporary_root="$(cd "${TMPDIR:-/tmp}" && pwd -P)"
scratch="$(mktemp -d "$temporary_root/basepower-pin.XXXXXXXX")"
cleanup() {
  local resolved
  resolved="$(cd "$scratch" && pwd -P)"
  case "$resolved" in
    "$temporary_root"/basepower-pin.*) rm -rf -- "$resolved" ;;
    *) echo 'Refusing to clear an unexpected test directory' >&2; return 1 ;;
  esac
}
trap cleanup EXIT
mkdir -p "$scratch/bin" "$scratch/project"
cat > "$scratch/bin/gh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$#" == 4 && "$1" == api && "$2" == "$EXPECTED_COMPARE" && "$3" == --jq && "$4" == .status ]]
printf '%s\n' "$PIN_STATUS"
MOCK
chmod +x "$scratch/bin/gh"
export PATH="$scratch/bin:$PATH"
commit=1111111111111111111111111111111111111111
cd "$scratch/project"
for minecraft in 1.20.6 1.21.1 1.21.11; do
  printf 'minecraft_version=%s\nbasemetals_source_commit=%s\n' "$minecraft" "$commit" > gradle.properties
  export EXPECTED_COMPARE="repos/MinecraftModDevelopmentMods/BaseMetals/compare/$commit...master-$minecraft"
  export GITHUB_OUTPUT="$scratch/output"
  for PIN_STATUS in identical ahead; do
    export PIN_STATUS
    : > "$GITHUB_OUTPUT"
    bash "$verify"
    grep -qx "commit=$commit" "$GITHUB_OUTPUT"
  done
  for PIN_STATUS in behind diverged; do
    export PIN_STATUS
    : > "$GITHUB_OUTPUT"
    if bash "$verify" > "$scratch/rejected.log" 2>&1; then
      echo "Incorrectly accepted $minecraft / $PIN_STATUS" >&2
      exit 1
    fi
    test ! -s "$GITHUB_OUTPUT"
  done
done
printf 'minecraft_version=invalid\nbasemetals_source_commit=%s\n' "$commit" > gradle.properties
if bash "$verify" > "$scratch/invalid.log" 2>&1; then
  echo 'Incorrectly accepted an invalid target' >&2
  exit 1
fi
echo 'PASS: target-derived Base Metals ancestry guards and rejection controls'

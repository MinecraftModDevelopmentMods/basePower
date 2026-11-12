#!/usr/bin/env bash
set -euo pipefail

commit="$(sed -n 's/^basemetals_source_commit=//p' gradle.properties)"
if [[ ! "$commit" =~ ^[0-9a-f]{40}$ ]]; then
  echo "::error::Expected a full Base Metals source commit in gradle.properties"
  exit 1
fi

minecraft="$(sed -n 's/^minecraft_version=//p' gradle.properties)"
if [[ ! "$minecraft" =~ ^(1|[2-9][0-9])\.[0-9]+\.[0-9]+$ ]]; then
  echo "::error::Expected a complete Minecraft target version in gradle.properties"
  exit 1
fi
branch="master-$minecraft"
if ! status="$(gh api "repos/MinecraftModDevelopmentMods/BaseMetals/compare/$commit...$branch" --jq '.status')"; then
  echo "::error::Could not verify Base Metals $commit on upstream $branch"
  exit 1
fi
case "$status" in
  identical|ahead) printf 'commit=%s\n' "$commit" >> "$GITHUB_OUTPUT" ;;
  *) echo "::error::Base Metals $commit is not on upstream $branch ($status). Local-only pins cannot qualify hosted CI."
     exit 1 ;;
esac

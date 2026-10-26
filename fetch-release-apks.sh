#!/usr/bin/env bash
set -euo pipefail

ref="${1:-$(git describe --tags --abbrev=0)}"
sha=$(git rev-parse "${ref}^{commit}")
echo "fetching APKs for $ref ($sha)"

run_id=$(gh run list --workflow build.yml --commit "$sha" --status success \
  --json databaseId --jq '.[0].databaseId')
if [[ -z "$run_id" ]]; then
  echo "no successful build.yml run found for $sha" >&2
  gh run list --workflow build.yml --commit "$sha" >&2
  exit 1
fi
echo "run: https://github.com/DavidVentura/offline-translator/actions/runs/$run_id"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
gh run download "$run_id" -n apk-arm64 -n apk-armv7 -D "$tmp"

# Kept apart from gradle's own outputs so sign-apk.sh can never pick up a stale local build.
dest=app/build/outputs/apk/ci-release
rm -rf "$dest"
mkdir -p "$dest"
mv "$tmp/apk-arm64/release/app-arm64-v8a-release-unsigned.apk" "$dest/"
mv "$tmp/apk-armv7/release/app-armeabi-v7a-release-unsigned.apk" "$dest/"
ls -l "$dest"/app-*-release-unsigned.apk

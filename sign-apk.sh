#!/bin/bash

set -eu

if [ "$#" -ne 4 ]; then
    echo "Usage: $0 <keystore_path> <store_password> <key_password> <key_alias>"
    exit 1
fi
if [ -z "$1" ] || [ -z "$2" ] || [ -z "$3" ] || [ -z "$4" ]; then
    echo "Usage: $0 <keystore_path> <store_password> <key_password> <key_alias>"
    exit 1
fi

set -u

KEYSTORE_PATH="$1"
STORE_PASSWORD="$2"
KEY_PASSWORD="$3"
KEY_ALIAS="$4"

if [ -n "${SIGN_DEBUG:-}" ]; then
  ARCHS=("arm64-v8a:arm64" "armeabi-v7a:armv7" "x86_64:x86_64")
  apk_dir=app/build/outputs/apk/debug
  pattern="*-debug.apk"
  echo "Signing debug APKs"
else
  # Only the ABIs build.yml builds; fetch-release-apks.sh places them here.
  ARCHS=("arm64-v8a:arm64" "armeabi-v7a:armv7")
  apk_dir=app/build/outputs/apk/ci-release
  pattern="*-release-unsigned.apk"
  echo "Signing release APKs"
fi

mkdir -p signed
signed_count=0

for entry in "${ARCHS[@]}"; do
  abi="${entry%%:*}"
  label="${entry##*:}"

  mapfile -t candidates < <(find "$apk_dir" -name "*${abi}*" -name "$pattern" 2>/dev/null)
  if [ "${#candidates[@]}" -gt 1 ]; then
    echo "Multiple APKs found for $abi, refusing to guess:" >&2
    printf '  %s\n' "${candidates[@]}" >&2
    exit 1
  fi
  if [ "${#candidates[@]}" -eq 0 ]; then
    if [ -z "${SIGN_DEBUG:-}" ]; then
      echo "No release APK found for $abi in $apk_dir; run ./fetch-release-apks.sh first" >&2
      exit 1
    fi
    echo "No APK found for $abi, skipping"
    continue
  fi
  unsigned_apk="${candidates[0]}"

  VERSION_NAME=$(aapt dump badging "$unsigned_apk" | grep -oP "versionName='\K(.*?)'" | tr -d "'")
  echo "Signing APK: $unsigned_apk"

  aligned_apk="${unsigned_apk%.apk}-aligned.apk"
  rm -f "$aligned_apk"
  $ANDROID_SDK_ROOT/build-tools/34.0.0/zipalign 4 "$unsigned_apk" "$aligned_apk"

  signed_fname="signed/translator-${label}-${VERSION_NAME}.apk"
  rm -f "$signed_fname"
  $ANDROID_SDK_ROOT/build-tools/34.0.0/apksigner sign \
      --ks "$KEYSTORE_PATH" \
      --ks-pass pass:"$STORE_PASSWORD" \
      --ks-key-alias "$KEY_ALIAS" \
      --key-pass pass:"$KEY_PASSWORD" \
      --out "$signed_fname" \
      "$aligned_apk"

  echo "APK successfully signed: $signed_fname"
  signed_count=$((signed_count + 1))
  rm -f "$aligned_apk"
done

if [ "$signed_count" -eq 0 ]; then
  echo "No APKs found to sign"
  exit 1
fi

echo "Signed $signed_count APK(s)"

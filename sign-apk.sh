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

ARCHS=("arm64-v8a:arm64" "armeabi-v7a:armv7")

if [ -n "${SIGN_DEBUG:-}" ]; then
  pattern="*-debug.apk"
  echo "Signing debug APKs"
else
  pattern="*-release-unsigned.apk"
  echo "Signing release APKs"
fi

mkdir -p signed
signed_count=0

for entry in "${ARCHS[@]}"; do
  abi="${entry%%:*}"
  label="${entry##*:}"

  unsigned_apk=$(find app/build/outputs/apk -name "*${abi}*" -name "$pattern" 2>/dev/null | head -1)
  if [ -z "$unsigned_apk" ]; then
    echo "No APK found for $abi, skipping"
    continue
  fi

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
done

if [ "$signed_count" -eq 0 ]; then
  echo "No APKs found to sign"
  exit 1
fi

echo "Signed $signed_count APK(s)"

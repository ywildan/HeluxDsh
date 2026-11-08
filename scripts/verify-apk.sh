#!/usr/bin/env bash
#
# Verify a built HeluxDsh APK against the contract the app promises.
#
# Compiling is not proof: the manifest a library merges in, the permissions the
# app actually requests, and whether the file is signed at all are only visible
# by inspecting the artifact. Every check below is a bug that once shipped.
#
# Usage: scripts/verify-apk.sh <path-to-apk>
set -euo pipefail

APK="${1:?usage: verify-apk.sh <path-to-apk>}"
EXPECTED_VERSION_CODE="${EXPECTED_VERSION_CODE:-5}"
EXPECTED_VERSION_NAME="${EXPECTED_VERSION_NAME:-1.1.1}"

test -f "$APK" || { echo "::error::missing APK: $APK"; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "::error::ANDROID_HOME is not set"; exit 1; }

# Newest build-tools that has both tools we need.
TOOL_DIR=""
for dir in $(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V -r); do
  if [ -x "$dir/aapt2" ] && [ -x "$dir/apksigner" ]; then TOOL_DIR="$dir"; break; fi
done
[ -n "$TOOL_DIR" ] || { echo "::error::no build-tools with aapt2 + apksigner in $SDK"; exit 1; }
AAPT2="$TOOL_DIR/aapt2"
APKSIGNER="$TOOL_DIR/apksigner"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "== $APK"

# 1. The file must be signed, or Android refuses to install it.
"$APKSIGNER" verify --print-certs "$APK" > "$TMP/certs.txt" 2>&1 || {
  echo "::error::$APK is not correctly signed"
  cat "$TMP/certs.txt"
  exit 1
}
CERT_DN="$(sed -n 's/^Signer #1 certificate DN: //p' "$TMP/certs.txt" | head -1)"
echo "signed by: ${CERT_DN:-unknown}"

# 2. Version must match what the code declares, or updates silently break.
BADGING="$("$AAPT2" dump badging "$APK")"
VERSION_CODE="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<< "$BADGING" | head -1)"
VERSION_NAME="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<< "$BADGING" | head -1)"
[ "$VERSION_CODE" = "$EXPECTED_VERSION_CODE" ] || {
  echo "::error::versionCode is '$VERSION_CODE', expected '$EXPECTED_VERSION_CODE'"
  exit 1
}
[ "$VERSION_NAME" = "$EXPECTED_VERSION_NAME" ] || {
  echo "::error::versionName is '$VERSION_NAME', expected '$EXPECTED_VERSION_NAME'"
  exit 1
}
echo "version: $VERSION_NAME ($VERSION_CODE)"

# 3. Every component the app starts by explicit Intent must be declared.
#    An undeclared ScanActivity throws ActivityNotFoundException the moment the
#    user taps "Scan QR code" - a force close with no crash log of its own.
"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" > "$TMP/manifest.txt"
for component in MainActivity ScanActivity; do
  grep -q "id.helux.dsh.$component" "$TMP/manifest.txt" || {
    echo "::error::$component is missing from the APK manifest"
    exit 1
  }
done
echo "components: MainActivity, ScanActivity"

# The scanner is internal; only the launcher activity may be exported.
if sed -n '/id\.helux\.dsh\.ScanActivity/,/^ *E: /p' "$TMP/manifest.txt" | grep -q 'exported.*0xffffffff'; then
  echo "::error::ScanActivity must not be exported"
  exit 1
fi

# 4. Permissions: exactly CAMERA + INTERNET, plus the AGP-injected
#    <applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION that targetSdk 33+
#    always adds (signature-level, granted automatically, never user-visible).
#    Note: `dump permissions` lists what the app *requests*; `dump xmltree`
#    would also match android:permission attributes on library receivers.
"$AAPT2" dump permissions "$APK" > "$TMP/permissions.txt"
REQUESTED="$(sed -n "s/^uses-permission: name='\(.*\)'/\1/p" "$TMP/permissions.txt" | sort -u)"
echo "permissions: $(echo "$REQUESTED" | tr '\n' ' ')"

ALLOWED_EXTRA="id.helux.dsh.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
unexpected="$(echo "$REQUESTED" | grep -v -x -F \
  -e 'android.permission.CAMERA' \
  -e 'android.permission.INTERNET' \
  -e "$ALLOWED_EXTRA" || true)"
if [ -n "$unexpected" ]; then
  echo "::error::unexpected permissions: $(echo "$unexpected" | tr '\n' ' ')"
  exit 1
fi
for required in android.permission.CAMERA android.permission.INTERNET; do
  echo "$REQUESTED" | grep -q -x -F "$required" || {
    echo "::error::$required is missing"
    exit 1
  }
done

# 5. The app is remote-only: cleartext traffic must stay off.
grep -q 'usesCleartextTraffic.*0xffffffff' "$TMP/manifest.txt" && {
  echo "::error::usesCleartextTraffic must be false"
  exit 1
}
echo "cleartext traffic: disabled"

echo "OK: $APK ($(du -h "$APK" | cut -f1), sha256 $(sha256sum "$APK" | cut -d' ' -f1))"

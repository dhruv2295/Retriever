#!/bin/bash
# Seeds a clean simulator ("Retriever-I6" unless SIM_NAME is set) and runs the app's -selftest on it.
# Exits non-zero unless the log contains "SELFTEST PASS". The simulator is shut down at the end.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SIM_NAME="${SIM_NAME:-Retriever-I6}"
DEVICE_TYPE="${DEVICE_TYPE:-iPhone 18 Pro}"
MODELS="${MODELS:-$HOME/rummage-models}"
MODEL_FILE=embeddinggemma-2-text-vision-440m.litertlm
CORPUS="$ROOT/eval/corpus"
DERIVED="$ROOT/build/ios-selftest"
LOG="$ROOT/build/ios-selftest.log"
mkdir -p "$ROOT/build"

UDID="$(xcrun simctl list devices | grep -F "    $SIM_NAME (" | head -1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/' || true)"
[ -n "$UDID" ] || UDID="$(xcrun simctl create "$SIM_NAME" "$DEVICE_TYPE")"
trap 'xcrun simctl shutdown "$UDID" 2>/dev/null || true' EXIT

# A clean device each run: the photo library, the app's index and its model copy all start empty.
xcrun simctl shutdown "$UDID" 2>/dev/null || true
xcrun simctl erase "$UDID"
xcrun simctl boot "$UDID"
xcrun simctl bootstatus "$UDID" -b >/dev/null

# Signed ("Sign to Run Locally"): an unsigned app has no entitlements, so its Keychain calls fail.
xcodebuild -project "$ROOT/iosApp/iosApp.xcodeproj" -scheme Retriever -configuration Debug \
  -destination "id=$UDID" -derivedDataPath "$DERIVED" build >"$ROOT/build/ios-selftest-build.log" 2>&1 \
  || { tail -30 "$ROOT/build/ios-selftest-build.log"; exit 1; }
APP="$DERIVED/Build/Products/Debug-iphonesimulator/Retriever.app"
BUNDLE="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Info.plist")"
xcrun simctl install "$UDID" "$APP"

# The container only exists once the app has run once.
xcrun simctl launch "$UDID" "$BUNDLE" >/dev/null
sleep 3
xcrun simctl terminate "$UDID" "$BUNDLE" || true
DATA="$(xcrun simctl get_app_container "$UDID" "$BUNDLE" data)"
mkdir -p "$DATA/Library/Application Support/models" "$DATA/Documents/selftest"
cp "$MODELS/$MODEL_FILE" "$DATA/Library/Application Support/models/"
cp "$CORPUS/docs/Document_01.pdf" "$CORPUS/docs/Document_03.pdf" "$CORPUS/docs/Doc_A.docx" "$DATA/Documents/selftest/"

xcrun simctl privacy "$UDID" grant photos "$BUNDLE"
# IMG_1001 twice: the duplicate pair. Seven assets in all.
for n in 1001 1003 1006 1011 1012 1007 1001; do
  xcrun simctl addmedia "$UDID" "$CORPUS/photos/IMG_$n.jpg"
done

xcrun simctl launch --console-pty --terminate-running-process "$UDID" "$BUNDLE" -selftest -selftestPhotos 7 2>&1 | tee "$LOG" || true
grep -q "SELFTEST PASS" "$LOG"

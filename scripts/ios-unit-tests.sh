#!/bin/bash
# Runs the iOS unit tests (the RetrieverTests bundle, hosted by the app) on a simulator named by SIM_NAME,
# created if missing. Exits non-zero on a build or test failure. The simulator is shut down at the end.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SIM_NAME="${SIM_NAME:-Retriever-tests}"
DEVICE_TYPE="${DEVICE_TYPE:-iPhone 18 Pro}"
DERIVED="$ROOT/build/ios-unit-tests"
LOG="$ROOT/build/ios-unit-tests.log"
mkdir -p "$ROOT/build"

UDID="$(xcrun simctl list devices | grep -F "    $SIM_NAME (" | head -1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/' || true)"
[ -n "$UDID" ] || UDID="$(xcrun simctl create "$SIM_NAME" "$DEVICE_TYPE")"
trap 'xcrun simctl shutdown "$UDID" 2>/dev/null || true' EXIT

xcodebuild test -project "$ROOT/iosApp/iosApp.xcodeproj" -scheme Retriever \
  -destination "platform=iOS Simulator,name=$SIM_NAME" -derivedDataPath "$DERIVED" 2>&1 | tee "$LOG" | tail -n 60
grep -q "TEST SUCCEEDED" "$LOG"

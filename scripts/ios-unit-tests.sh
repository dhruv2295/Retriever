#!/bin/bash
# Runs RetrieverTests and RetrieverUITests on a simulator named by SIM_NAME,
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

# Optional arguments narrow the run to the test classes being changed, e.g. RetrieverTests/ViewerTests.
TESTS=(-only-testing:RetrieverTests -only-testing:RetrieverUITests)
if [ "$#" -gt 0 ]; then
  TESTS=()
  for test in "$@"; do TESTS+=("-only-testing:$test"); done
fi
xcodebuild build-for-testing -project "$ROOT/iosApp/iosApp.xcodeproj" -scheme Retriever \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath "$DERIVED" \
  "${TESTS[@]}" >"$ROOT/build/ios-unit-tests-build.log" 2>&1 \
  || { tail -60 "$ROOT/build/ios-unit-tests-build.log"; exit 1; }

# One small real library lets the viewer audits exercise Photos, PDFKit and AVKit.
if ! xcrun simctl list devices | grep -F "$UDID" | grep -q '(Booted)'; then xcrun simctl boot "$UDID"; fi
xcrun simctl bootstatus "$UDID" -b >/dev/null
xcrun simctl ui "$UDID" appearance light
xcrun simctl spawn "$UDID" defaults write com.apple.Accessibility ReduceMotionEnabled -bool YES
APP="$DERIVED/Build/Products/Debug-iphonesimulator/Retriever.app"
xcrun simctl install "$UDID" "$APP"
xcrun simctl privacy "$UDID" grant photos app.retriever
DATA="$(xcrun simctl get_app_container "$UDID" app.retriever data)"
if [ ! -f "$DATA/.ui-test-seeded" ]; then
  mkdir -p "$DATA/Documents/audit"
  cp "$ROOT/eval/corpus/docs/Document_01.pdf" "$ROOT/eval/corpus/docs/Doc_A.docx" "$DATA/Documents/audit/"
  cp "$ROOT/eval/corpus/docs/Document_01.pdf" "$DATA/Documents/audit/Document_copy.pdf"
  xcrun simctl addmedia "$UDID" "$ROOT/eval/corpus/photos/IMG_1001.jpg" "$ROOT/eval/corpus/photos/IMG_1003.jpg" "$ROOT/eval/corpus/media/talk-and-show.mp4"
  touch "$DATA/.ui-test-seeded"
fi

# Larger than DuplicatesView's 1 MB threshold; these stay inside the disposable app container.
python3 - "$DATA/Documents/audit" <<'PY'
import sys
from pathlib import Path
folder = Path(sys.argv[1])
for name in ("Audit copy A.txt", "Audit copy B.txt"):
    file = folder / name
    if not file.exists():
        file.write_bytes(b"Retriever accessibility audit duplicate fixture.\n" * 30000)
PY

# Unit tests finish seeding the index before UI tests open the live routes. Neither run creates simulator clones.
: >"$LOG"
TEST_STATUS=0
for bundle in RetrieverTests RetrieverUITests; do
  SELECTED=()
  for test in "${TESTS[@]}"; do
    case "$test" in "-only-testing:$bundle"|"-only-testing:$bundle/"*) SELECTED+=("$test");; esac
  done
  [ "${#SELECTED[@]}" -gt 0 ] || continue
  if ! xcodebuild test-without-building -project "$ROOT/iosApp/iosApp.xcodeproj" -scheme Retriever \
    -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath "$DERIVED" \
    -parallel-testing-enabled NO -maximum-concurrent-test-simulator-destinations 1 -collect-test-diagnostics never \
    "${SELECTED[@]}" 2>&1 | tee -a "$LOG" | tail -n 60; then TEST_STATUS=1; fi
  grep -q "Test Suite '$bundle.xctest'" "$LOG" || TEST_STATUS=1
done
grep -Eq 'TEST (EXECUTE )?SUCCEEDED' "$LOG" || TEST_STATUS=1
exit "$TEST_STATUS"

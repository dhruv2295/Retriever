#!/bin/bash
# The relevance-fit dump (relevance-method.md 4.6): indexes the prepared public library on a simulator with the app's own
# extractor and the real model on CPU, then copies dump-<model>-vt<vt>.bin to $OUT. Resumable: the simulator
# ("Retriever-Fit-1" unless SIM_NAME is set) is never erased, so running it again continues where the dump stopped.
# Usage: scripts/ios-dump.sh [M440|M740] [vision tokens] [limit: N files per kind and N queries, 0 for all]
# Delete the simulator when done: xcrun simctl delete "$SIM_NAME".
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODEL="${1:-M440}"
VT="${2:-70}"
LIMIT="${3:-0}"
SIM_NAME="${SIM_NAME:-Retriever-Fit-1}"
DEVICE_TYPE="${DEVICE_TYPE:-iPhone 18 Pro}"
MODELS="${MODELS:-$HOME/rummage-models}"
PREPARED="${PREPARED:-$HOME/retriever-eval-data/prepared}"
OUT="${OUT:-$ROOT/build/relevance}"
DERIVED="$ROOT/build/ios-dump"
case "$MODEL" in
  M440) MODEL_FILE=embeddinggemma-2-text-vision-440m.litertlm ;;
  M740) MODEL_FILE=embeddinggemma-2-740m.litertlm ;;
  *) echo "model is M440 or M740" >&2; exit 2 ;;
esac
SUFFIX="$MODEL-vt$VT$([ "$LIMIT" = 0 ] || echo "-limit$LIMIT")"
LOG="$OUT/dump-$SUFFIX.log"
mkdir -p "$OUT"

UDID="$(xcrun simctl list devices | grep -F "    $SIM_NAME (" | head -1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/' || true)"
[ -n "$UDID" ] || UDID="$(xcrun simctl create "$SIM_NAME" "$DEVICE_TYPE")"
trap 'xcrun simctl shutdown "$UDID" 2>/dev/null || true' EXIT
xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b >/dev/null

# Signed ("Sign to Run Locally"): an unsigned app has no entitlements. Reinstalling keeps the app's data container.
xcodebuild -project "$ROOT/iosApp/iosApp.xcodeproj" -scheme Retriever -configuration Debug \
  -destination "id=$UDID" -derivedDataPath "$DERIVED" build >"$OUT/dump-build.log" 2>&1 \
  || { tail -30 "$OUT/dump-build.log"; exit 1; }
APP="$DERIVED/Build/Products/Debug-iphonesimulator/Retriever.app"
BUNDLE="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Info.plist")"
xcrun simctl install "$UDID" "$APP"
DATA="$(xcrun simctl get_app_container "$UDID" "$BUNDLE" data 2>/dev/null || true)"
if [ -z "$DATA" ]; then
  # The container only exists once the app has run once.
  xcrun simctl launch "$UDID" "$BUNDLE" >/dev/null
  sleep 3
  xcrun simctl terminate "$UDID" "$BUNDLE" || true
  DATA="$(xcrun simctl get_app_container "$UDID" "$BUNDLE" data)"
fi
BENCH="$DATA/Library/Caches/bench"
mkdir -p "$DATA/Library/Application Support/models" "$BENCH"
[ -f "$DATA/Library/Application Support/models/$MODEL_FILE" ] || cp "$MODELS/$MODEL_FILE" "$DATA/Library/Application Support/models/"
rsync -a "$PREPARED/manifest.json" "$PREPARED/queries.json" "$PREPARED/library" "$BENCH/"

caffeinate -i xcrun simctl launch --console-pty --terminate-running-process "$UDID" "$BUNDLE" \
  -dump -dumpModel "$MODEL" -dumpVt "$VT" -dumpLimit "$LIMIT" 2>&1 | tee -a "$LOG" || true
cp "$BENCH/dump-$SUFFIX.bin" "$OUT/"
tail -5 "$LOG" | grep -q "DUMP DONE" || { echo "dump not finished; run again to resume" >&2; exit 1; }
echo "$OUT/dump-$SUFFIX.bin"

#!/bin/bash
# Runs RetrieverTests and RetrieverUITests, or the ones named, for the checkout it's called from (main or a worktree):
#   scripts/ios-unit-tests.sh                      every RetrieverTests and RetrieverUITests test
#   scripts/ios-unit-tests.sh RetrieverUITests/AccessibilityAuditTests/testHomeRoutesPassTheAccessibilityAudit RetrieverTests/ViewerTests
# UI test methods are spread over IOS_TEST_WORKERS simulators (default 3), balanced by each test's last measured
# duration (a cache shared by every checkout, refreshed from every run). Each simulator runs RetrieverTests first: the
# unit tests seed the index the UI routes read. Exits non-zero on a build or test failure; the simulators are shut down
# at the end. A full run is long, so run it in the background:
#   nohup scripts/ios-unit-tests.sh ... >build/test-ios.log 2>&1 &   then read build/ios-tests/summary.txt (written last; "RESULT: PASS|FAIL").
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME="$(basename "$ROOT")"
# Each checkout (main or a worktree) gets its own simulators, so parallel jobs never share one; the lock only
# serialises two runs from the same checkout. Remove a worktree's simulators with the worktree.
if [ -z "${RETRIEVER_IOS_TESTS_LOCKED:-}" ]; then
  RETRIEVER_IOS_TESTS_LOCKED=1 exec /usr/bin/lockf -k -t 10800 "/tmp/retriever-ios-tests-$NAME.lock" "$0" "$@"
fi
# At least 3 simulators: one per worker, so fewer only serialises the run (an agent once set 1 and waited 30 minutes).
WORKERS="${IOS_TEST_WORKERS:-3}"; [ "$WORKERS" -ge 3 ] 2>/dev/null || WORKERS=3; DEVICE_TYPE="${DEVICE_TYPE:-iPhone 18 Pro}"
# Shared by every checkout (test names match across them) and never committed.
DURATIONS="${XDG_CACHE_HOME:-$HOME/Library/Caches}/retriever-ios-test-durations.json"
OUT="$ROOT/build/ios-tests"; DERIVED="$ROOT/build/ios-unit-tests"; PROJ="$ROOT/iosApp/iosApp.xcodeproj"
mkdir -p "$OUT" "$(dirname "$DURATIONS")"; rm -f "$OUT"/summary.txt "$OUT"/w*.log "$OUT"/w*.status; rm -rf "$OUT"/w*.xcresult
missing=0
for fixture in docs/Document_01.pdf docs/Doc_A.docx photos/IMG_1001.jpg photos/IMG_1003.jpg media/talk-and-show.mp4; do
  if [ ! -s "$ROOT/eval/corpus/$fixture" ]; then echo "Missing test fixture: eval/corpus/$fixture" >&2; missing=1; fi
done
if [ "$missing" = 1 ]; then
  echo "Generate the required fixtures with eval/make-corpus.sh and eval/make-media.sh, then run this script again." >&2
  echo "RESULT: FAIL (missing test fixtures)" >"$OUT/summary.txt"
  exit 1
fi
FILTERS=("$@"); [ "${#FILTERS[@]}" -gt 0 ] || FILTERS=(RetrieverTests RetrieverUITests)
UNIT=(); UI=()
for f in "${FILTERS[@]}"; do case "$f" in RetrieverUITests*) UI+=("$f");; *) UNIT+=("$f");; esac; done

sim() { # sim <k>: the UDID of this checkout's k-th test simulator, created if missing
  local n="Retriever-tests-$NAME-$1" u
  u="$(xcrun simctl list devices | sed -nE "/    $n \(/s/.*\(([0-9A-F-]{36})\).*/\1/p" | head -1 || true)"
  [ -n "$u" ] || u="$(xcrun simctl create "$n" "$DEVICE_TYPE")"; echo "$u"
}
S1="$(sim 1)"
xcodebuild build-for-testing -project "$PROJ" -scheme Retriever -destination "platform=iOS Simulator,id=$S1" \
  -derivedDataPath "$DERIVED" >"$OUT/build.log" 2>&1 || { tail -60 "$OUT/build.log"; echo "RESULT: FAIL (build)" >"$OUT/summary.txt"; exit 1; }

# Expand UI filters to methods, then split them greedily (longest first) by their last duration.
PLAN="$OUT/plan.txt"; : >"$PLAN"
if [ "${#UI[@]}" -gt 0 ]; then
  # Filters that already name methods (Bundle/Class/method) need no listing. Otherwise list them fresh: xcodebuild
  # refuses to overwrite an old list, and a stale list once turned one requested test into the whole suite.
  rm -f "$OUT/tests.json"; NEED_LIST=0
  for f in "${UI[@]}"; do [ "$(tr -cd / <<<"$f" | wc -c)" -ge 2 ] || NEED_LIST=1; done
  if [ "$NEED_LIST" = 1 ]; then
    ARGS=(); for f in "${UI[@]}"; do ARGS+=("-only-testing:$f"); done
    xcodebuild test-without-building -project "$PROJ" -scheme Retriever -destination "platform=iOS Simulator,id=$S1" \
      -derivedDataPath "$DERIVED" "${ARGS[@]}" -enumerate-tests -test-enumeration-format json -test-enumeration-style flat \
      -test-enumeration-output-path "$OUT/tests.json" >"$OUT/enumerate.log" 2>&1 \
      || { tail -5 "$OUT/enumerate.log"; echo "RESULT: FAIL (could not list the UI tests)" >"$OUT/summary.txt"; exit 1; }
  fi
  python3 - "$OUT/tests.json" "$DURATIONS" "$WORKERS" "$PLAN" "${UI[@]}" <<'PY'
import json, sys, re, os
tests_path, dur_path, workers, plan, filters = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5:]
found = [f for f in filters if f.count("/") >= 2]
def walk(n):
    if isinstance(n, dict):
        for k in ("identifier", "testIdentifier", "name"):
            v = n.get(k)
            if isinstance(v, str) and re.match(r"^RetrieverUITests/\w+/\w+", v): found.append(v.rstrip("()").replace("()", "")); break
        for v in n.values(): walk(v)
    elif isinstance(n, list):
        for v in n: walk(v)
if os.path.exists(tests_path): walk(json.load(open(tests_path)))
tests = sorted(set(found))
if not tests: sys.exit("No UI tests matched the requested filters")
try: dur = json.load(open(dur_path))
except Exception: dur = {}
bins = [[0.0, []] for _ in range(max(1, min(workers, len(tests))))]
for t in sorted(tests, key=lambda t: -dur.get(t, 30.0)):
    b = min(bins, key=lambda b: b[0]); b[0] += dur.get(t, 30.0); b[1].append(t)
with open(plan, "w") as f:
    for i, (s, ts) in enumerate(bins, 1): f.write(f"{i} {round(s)} {' '.join(ts)}\n")
print(f"{len(tests)} UI tests over {len(bins)} simulators: " + ", ".join(f"~{round(s)} s" for s, _ in bins))
PY
fi
[ -s "$PLAN" ] || echo "1 0" >"$PLAN"   # unit tests only

run_worker() ( # run_worker <k> <ui tests...>
  local k="$1"; shift; local u="" log="$OUT/w$k.log" status=0
  trap 'code=$?; [ "$code" -eq 0 ] || status=1; echo "$status" >"$OUT/w$k.status"; [ -z "$u" ] || xcrun simctl shutdown "$u" 2>/dev/null || true' EXIT
  { u="$(sim "$k")"
    xcrun simctl list devices | grep -F "$u" | grep -q '(Booted)' || xcrun simctl boot "$u"
    xcrun simctl bootstatus "$u" -b >/dev/null; xcrun simctl ui "$u" appearance light
    # A new simulator's first boot can drop this write, so check it survives a reboot before the UI tests.
    for attempt in 1 2 3; do
      [ "$attempt" -lt 3 ] || { echo "Reduce Motion did not stick"; return 1; }
      xcrun simctl spawn "$u" defaults write com.apple.Accessibility ReduceMotionEnabled -bool YES
      xcrun simctl shutdown "$u"; xcrun simctl boot "$u"; xcrun simctl bootstatus "$u" -b >/dev/null
      [ "$(xcrun simctl spawn "$u" defaults read com.apple.Accessibility ReduceMotionEnabled 2>/dev/null)" != 1 ] || break
    done
    xcrun simctl install "$u" "$DERIVED/Build/Products/Debug-iphonesimulator/Retriever.app"
    xcrun simctl privacy "$u" grant photos app.retriever
    # One small real library lets the viewer audits exercise Photos, PDFKit and AVKit.
    local data; data="$(xcrun simctl get_app_container "$u" app.retriever data)"
    if [ ! -f "$data/.ui-test-seeded" ]; then
      mkdir -p "$data/Documents/audit"
      cp "$ROOT/eval/corpus/docs/Document_01.pdf" "$ROOT/eval/corpus/docs/Doc_A.docx" "$data/Documents/audit/"
      cp "$ROOT/eval/corpus/docs/Document_01.pdf" "$data/Documents/audit/Document_copy.pdf"
      xcrun simctl addmedia "$u" "$ROOT/eval/corpus/photos/IMG_1001.jpg" "$ROOT/eval/corpus/photos/IMG_1003.jpg" "$ROOT/eval/corpus/media/talk-and-show.mp4"
      # Larger than DuplicatesView's 1 MB threshold; these stay inside the disposable app container.
      python3 -c "
from pathlib import Path; d=Path('$data/Documents/audit')
for n in ('Audit copy A.txt','Audit copy B.txt'):
    f=d/n; f.exists() or f.write_bytes(b'Retriever accessibility audit duplicate fixture.\n'*30000)"
      touch "$data/.ui-test-seeded"
    fi
    local sel=()
    # Unit tests: the requested ones on simulator 1; on the others only as the seeding step the UI routes need.
    if [ "$k" = 1 ] && [ "${#UNIT[@]}" -gt 0 ]; then for f in "${UNIT[@]}"; do sel+=("-only-testing:$f"); done
    elif [ "$#" -gt 0 ]; then sel=(-only-testing:RetrieverTests); fi
    if [ "${#sel[@]}" -gt 0 ]; then
      xcodebuild test-without-building -project "$PROJ" -scheme Retriever -destination "platform=iOS Simulator,id=$u" \
        -derivedDataPath "$DERIVED" -parallel-testing-enabled NO -collect-test-diagnostics never \
        -resultBundlePath "$OUT/w$k-unit.xcresult" "${sel[@]}" || status=1
    fi
    if [ "$#" -gt 0 ]; then
      sel=(); for t in "$@"; do sel+=("-only-testing:$t"); done
      for attempt in 1 2; do
        rm -rf "$OUT/w$k-ui.xcresult"
        if xcodebuild test-without-building -project "$PROJ" -scheme Retriever -destination "platform=iOS Simulator,id=$u" \
          -derivedDataPath "$DERIVED" -parallel-testing-enabled NO -collect-test-diagnostics never \
          -resultBundlePath "$OUT/w$k-ui.xcresult" "${sel[@]}" >"$OUT/w$k-ui-$attempt.log" 2>&1; then break; fi
        # Bootstrap failures precede assertions: retry only a runner that never bootstrapped, and never turn a failed
        # test into a pass by running it again. Xcode can put the bootstrap reason only in the w<k>-ui.xcresult bundle.
        [ "$attempt" = 1 ] && xcrun xcresulttool get test-results summary --path "$OUT/w$k-ui.xcresult" --compact >>"$OUT/w$k-ui-1.log" 2>/dev/null || true
        if [ "$attempt" = 1 ] && grep -Eq 'Early unexpected exit, operation never finished bootstrapping|Test crashed with signal term' "$OUT/w$k-ui-1.log" &&
          ! grep -Eiq 'XCTAssert|Assertion Failure|Expectation failed|recorded an issue|Test Case |Executed [1-9]|error: -\[|testIdentifierURL' "$OUT/w$k-ui-1.log"; then continue; fi
        status=1; break
      done
      cat "$OUT"/w$k-ui-*.log
    fi
  } >"$log" 2>&1
)

while read -r k _ tests; do run_worker "$k" $tests & done <"$PLAN"
wait

# Results: failing tests by name, and fresh durations for the next split.
python3 - "$OUT" "$DURATIONS" <<'PY'
import json, subprocess, sys, os
out, dur_path = sys.argv[1], sys.argv[2]
try: dur = json.load(open(dur_path))
except Exception: dur = {}
fails, issues, counts = [], [], {"passed": 0, "failed": 0, "skipped": 0}
plan = [line.split() for line in open(os.path.join(out, "plan.txt"))]
results = [os.path.join(out, f"w{p[0]}-{kind}.xcresult") for p in plan for kind in (["unit", "ui"] if len(p) > 2 else ["unit"])]
for r in results:
    try:
        tree = json.loads(subprocess.run(["xcrun", "xcresulttool", "get", "test-results", "tests", "--path", r], capture_output=True, text=True, check=True).stdout)
        if not tree.get("testNodes"): raise ValueError("no test results")
    except Exception as e:
        issues.append(f"{os.path.basename(r)}: could not read test results ({e})")
        continue
    def walk(n, path):
        name = n.get("name", "")
        here = path + [name.replace("()", "")]
        if n.get("nodeType") == "Test Case":
            ident = "/".join(p for p in here if p and not p.endswith(".xctest") and p not in ("Retriever",))
            res = (n.get("result") or "").lower()
            counts["passed" if res == "passed" else "skipped" if res == "skipped" else "failed"] += 1
            if res not in ("passed", "skipped"): fails.append(f"{os.path.basename(r)}: {ident}")
            if "-ui" in r and n.get("durationInSeconds") is not None and ident.startswith("RetrieverUITests"):
                dur[ident] = float(n["durationInSeconds"])
        for c in n.get("children", []): walk(c, here if n.get("nodeType") in ("Unit test bundle", "UI test bundle", "Test Suite") else path)
    for n in tree.get("testNodes", []): walk(n, [])
json.dump(dur, open(dur_path, "w"), indent=1)
bad = [os.path.join(out, f"w{p[0]}.status") for p in plan]
bad = [s for s in bad if not os.path.exists(s) or open(s).read().strip() != "0"]
with open(os.path.join(out, "summary.txt"), "w") as f:
    f.write(f"passed {counts['passed']} failed {counts['failed']} skipped {counts['skipped']}\n")
    for x in fails: f.write(f"FAILED {x}\n")
    for issue in issues: f.write(f"ERROR {issue}\n")
    for s in bad: f.write(f"worker {os.path.basename(s)} failed or did not report completion; see {s[:-7]}.log\n")
    f.write("RESULT: " + ("PASS" if not fails and not issues and not bad and counts["passed"] else "FAIL") + "\n")
print(open(os.path.join(out, "summary.txt")).read())
PY
grep -q "RESULT: PASS" "$OUT/summary.txt"

#!/bin/bash
# The one verify command. Default: tests, lint, format check, notices, versions, 16 KB alignment.
#   scripts/verify.sh            default gate
#   scripts/verify.sh --full     adds benchmarks, coverage, inspections, the iOS selftest and the UI tests
#   scripts/verify.sh --format-staged   (pre-commit) formats the staged Kotlin and Swift files
# Sourced by scripts/release.sh for the version helpers; main runs only when executed.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK_DIR="$ROOT/androidApp/build/outputs/apk/release"
XCCONFIG="$ROOT/iosApp/Configuration/Config.xcconfig"
NOTICES="$ROOT/THIRD_PARTY_NOTICES.md"
PACKAGE_RESOLVED="$ROOT/iosApp/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"
# Headless inspections with the IDE's project profile (.idea is untracked; it only overrides the Preview checks).
INSPECTIONS_PROFILE="$ROOT/.idea/inspectionProfiles/Project_Default.xml"
INSPECT_SH="${INSPECT_SH:-/Applications/Android Studio.app/Contents/bin/inspect.sh}"

# Build output and agent scratch stay out of the Swift formatter (ktlint-gradle skips build/ itself).
EXCLUDED='(^|/)build/|^\.claude/'

step() { printf '\n==> %s\n' "$*"; }
fail() { printf 'verify: %s\n' "$*" >&2; return 1; }

version_name()  { sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' "$ROOT/androidApp/build.gradle.kts"; }
version_code()  { sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' "$ROOT/androidApp/build.gradle.kts"; }
xcconfig_value() { sed -nE "s/^$1[[:space:]]*=[[:space:]]*([^[:space:]]+).*/\1/p" "$XCCONFIG"; }

# versionName must equal MARKETING_VERSION. (versionCode vs CURRENT_PROJECT_VERSION is release.sh's job.)
check_versions() {
  local a i
  a="$(version_name)"; i="$(xcconfig_value MARKETING_VERSION)"
  [ -n "$a" ] && [ -n "$i" ] || { fail "cannot read versionName ('$a') or MARKETING_VERSION ('$i')"; return 1; }
  [ "$a" = "$i" ] || { fail "versionName $a != MARKETING_VERSION $i"; return 1; }
}

# Reads "group" lines from stdin; fails on any group missing from the notices file.
check_groups_in_notices() {
  local notices="${1:-$NOTICES}" g missing=0
  while IFS= read -r g; do
    [ -n "$g" ] || continue
    grep -qiE "(^|[^A-Za-z0-9._-])$(printf '%s' "$g" | sed 's/[.]/\\./g')([^A-Za-z0-9_-]|$)" "$notices" \
      || { echo "missing from THIRD_PARTY_NOTICES.md: $g" >&2; missing=1; }
  done
  return $missing
}

# The Android release graph and the Kotlin libraries linked into the iOS framework (both iOS targets share one graph).
release_groups() {
  "$ROOT/gradlew" -q -p "$ROOT" :androidApp:dependencies --configuration releaseRuntimeClasspath \
    :core:dependencies --configuration iosArm64CompileKlibraries \
    | sed -nE 's/^[ |+\\-]*([A-Za-z0-9_.-]+):[A-Za-z0-9_.-]+:.*/\1/p' | sort -u
}

swift_pins() { sed -nE 's/^[[:space:]]*"identity" : "([^"]+)".*/\1/p' "$PACKAGE_RESOLVED"; }

check_notices() {
  { release_groups; swift_pins; } | check_groups_in_notices "$NOTICES"
}

zipalign_bin() { ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"/build-tools/*/zipalign 2>/dev/null | sort -V | tail -1; }

# Every PT_LOAD segment of every native library needs p_align >= 16384 (64-bit ELF, little endian).
check_elf_16k() {
  python3 -I - "$1" <<'PY'
import struct, sys, pathlib
bad = 0
for p in sorted(pathlib.Path(sys.argv[1]).rglob("*.so")):
    b = p.read_bytes()
    if b[:4] != b"\x7fELF":
        continue
    if b[4] != 2:  # 32-bit libs are not checked by Play's 16 KB rule
        continue
    phoff, = struct.unpack_from("<Q", b, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", b, 0x36)
    aligns = [struct.unpack_from("<Q", b, phoff + i * phentsize + 0x30)[0]
              for i in range(phnum) if struct.unpack_from("<I", b, phoff + i * phentsize)[0] == 1]
    if aligns and min(aligns) < 16384:
        print(f"BAD {min(aligns):>6} {p}", file=sys.stderr); bad = 1
sys.exit(bad)
PY
}

check_16k() {
  local za tmp apk; za="$(zipalign_bin)"
  [ -n "$za" ] || { fail "zipalign not found under the Android SDK build-tools"; return 1; }
  # The trap clears itself: a RETURN trap outlives this function and fired again when main returned, where tmp is
  # unset, so set -u failed the gate after "verify: OK".
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"; trap - RETURN' RETURN
  for abi in arm64-v8a x86_64; do
    apk="$APK_DIR/androidApp-$abi-release.apk"
    [ -f "$apk" ] || { fail "no release APK at $apk"; return 1; }
    "$za" -c -P 16 -v 4 "$apk" >/dev/null || { fail "zipalign -P 16 rejected $apk"; return 1; }
    unzip -q -o "$apk" 'lib/*' -d "$tmp/$abi" 2>/dev/null || true
  done
  check_elf_16k "$tmp"
}

tracked() { # $1 = extension; tracked and new files, minus the excluded ones
  ( cd "$ROOT" && git ls-files -co --exclude-standard -- "*.$1" | grep -Ev "$EXCLUDED" || true )
}

# ktlintCheck runs in main's Gradle invocation.
format_check() {
  ( cd "$ROOT" && tracked swift | xargs xcrun swift-format lint --strict --configuration .swift-format )
}

format_staged() {
  cd "$ROOT"
  local f kt=() sw=() staged
  staged="$(git diff --cached --name-only --diff-filter=ACM | grep -Ev "$EXCLUDED" || true)"
  for f in $staged; do
    # A partly staged file would get its unstaged hunks staged by the re-add; leave it for the author.
    if ! git diff --quiet -- "$f"; then echo "pre-commit: $f has unstaged changes, not formatted" >&2; continue; fi
    case "$f" in *.kt|*.kts) kt+=("$ROOT/$f");; *.swift) sw+=("$f");; esac
  done
  if [ ${#kt[@]} -gt 0 ]; then
    local IFS=,
    "$ROOT/gradlew" -q -p "$ROOT" ktlintFormat "-PktlintFiles=${kt[*]}"
    unset IFS
    git add -- "${kt[@]}"
  fi
  if [ ${#sw[@]} -gt 0 ]; then
    xcrun swift-format format -i --configuration .swift-format "${sw[@]}"; git add -- "${sw[@]}"
  fi
}

# Needs Android Studio closed (one instance only). inspect.sh exits 0 even when it inspected nothing, so an
# empty report directory fails here instead of passing as clean (see docs/quality.md, Inspections).
inspections() {
  local d out
  for d in androidApp core; do
    out="$ROOT/build/inspections/$d"; rm -rf "$out"
    nice -n 10 "$INSPECT_SH" "$ROOT" "$INSPECTIONS_PROFILE" "$out" -v2 -d "$ROOT/$d"
    ls "$out"/*.xml >/dev/null 2>&1 || { fail "inspect.sh wrote no report for $d"; return 1; }
  done
}

main() {
  local full=0
  case "${1:-}" in
    --format-staged) format_staged; return;;
    --full) full=1;;
    "") ;;
    *) echo "usage: verify.sh [--full | --format-staged]" >&2; return 2;;
  esac
  cd "$ROOT"
  [ "$(git config --get core.hooksPath || true)" = ".githooks" ] || git config core.hooksPath .githooks

  step "versions"; check_versions
  step "gradle: tests, lint, release build, ktlint"
  # --rerun forces only the tests. Compiles, lint and R8 are up to date or come from the build cache when their inputs
  # (warnings-as-errors settings included) are unchanged, so a cached result is gated the same way.
  local flags=() tasks=(:core:testAndroidHostTest --rerun :core:iosSimulatorArm64Test --rerun :androidApp:testDebugUnitTest --rerun
    :androidApp:lintDebug :androidApp:lintRelease :androidApp:assembleRelease ktlintCheck)
  if [ $full = 1 ]; then flags=(-Pbench -Pcoverage); tasks+=(:core:koverHtmlReport :androidApp:koverHtmlReportDebug); fi
  ./gradlew ${flags[@]+"${flags[@]}"} "${tasks[@]}"
  step "ios unit tests"; scripts/ios-unit-tests.sh
  step "format"; format_check
  step "notices"; check_notices
  step "16 KB alignment"; check_16k
  if [ $full = 1 ]; then
    step "inspections"; inspections
    step "ios selftest"; scripts/ios-selftest.sh
    if [ -x scripts/ios-ui-tests.sh ]; then step "ios ui tests"; scripts/ios-ui-tests.sh; fi
  fi
  printf '\nverify: OK\n'
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi

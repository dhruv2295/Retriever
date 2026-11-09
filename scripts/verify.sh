#!/bin/bash
# Android gate; --ios adds the secondary platform (requires macOS and Xcode).
#   scripts/verify.sh --full     adds benchmarks, coverage and the bundle ABI check
#   scripts/verify.sh --ios      adds shared iOS compilation and iOS tests (--full includes UI tests)
#   scripts/verify.sh --quick    versions, formatting and notices only
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_DIR="$ROOT/androidApp/build/outputs/apk/release"
NOTICES="$ROOT/THIRD_PARTY_NOTICES.md"
PACKAGE_RESOLVED="$ROOT/iosApp/iosApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"

# Build output and agent scratch stay out of the Swift formatter (ktlint-gradle skips build/ itself).
EXCLUDED='(^|/)build/|^\.claude/'

step() { printf '\n==> %s\n' "$*"; }
fail() { printf 'verify: %s\n' "$*" >&2; return 1; }

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

# Play derives an App Bundle's ABIs from its lib/ folders: a 32-bit one would offer the app to phones that can't load the
# model. Its own Gradle run, since a bundle build turns the ABI splits off (androidApp/build.gradle.kts); about 75 s.
check_bundle_abis() {
  local aab="$ROOT/androidApp/build/outputs/bundle/release/androidApp-release.aab" bad
  "$ROOT/gradlew" -q -p "$ROOT" :androidApp:bundleRelease
  [ -f "$aab" ] || { fail "no release bundle at $aab"; return 1; }
  bad="$(unzip -Z1 "$aab" | grep -E '(^|/)lib/(armeabi-v7a|x86)/' || true)"
  [ -z "$bad" ] || { fail "32-bit libraries in $aab: $(printf '%s\n' "$bad" | head -3 | tr '\n' ' ')"; return 1; }
}

tracked() { # $1 = extension; tracked and new files, minus the excluded ones
  ( cd "$ROOT" && git ls-files -co --exclude-standard -- "*.$1" | grep -Ev "$EXCLUDED" || true )
}

# ktlintCheck runs in main's Gradle invocation.
format_check() {
  ( cd "$ROOT" && tracked swift | xargs xcrun swift-format lint --strict --configuration .swift-format )
}

main() {
  local full=0 quick=0 ios=0 arg
  for arg in "$@"; do
    case "$arg" in
      --full) full=1;;
      --quick) quick=1;;
      --ios) ios=1;;
      *) echo "usage: verify.sh [--full | --quick] [--ios]" >&2; return 2;;
    esac
  done
  [ $quick = 0 ] || [ $full = 0 ] || { fail "choose --quick or --full"; return 2; }
  if [ $ios = 1 ]; then command -v xcrun >/dev/null || { fail "--ios requires macOS and Xcode"; return 1; }; fi
  cd "$ROOT"

  if [ $quick = 1 ]; then
    step "ktlint"; ./gradlew -q ktlintCheck
    if [ $ios = 1 ]; then step "Swift format"; format_check; fi
    step "notices"; check_notices
    printf '\nverify: OK (quick)\n'; return
  fi
  step "gradle: tests, lint, release build, metadata, migrations, ktlint"
  local flags=() tasks=(:core:testAndroidHostTest :androidApp:testDebugUnitTest
    :androidApp:lintDebug :androidApp:lintRelease :androidApp:assembleRelease
    :core:compileCommonMainKotlinMetadata :core:verifyCommonMainIndexDbMigration ktlintCheck)
  if [ $ios = 1 ]; then tasks+=(:core:iosSimulatorArm64Test :core:compileIosMainKotlinMetadata); fi
  if [ $full = 1 ]; then flags=(-Pbench -Pcoverage); tasks+=(:core:koverHtmlReport :androidApp:koverHtmlReportDebug); fi
  ./gradlew ${flags[@]+"${flags[@]}"} "${tasks[@]}"
  if [ $ios = 1 ]; then
    step "iOS tests"
    if [ $full = 1 ]; then scripts/ios-unit-tests.sh; else scripts/ios-unit-tests.sh RetrieverTests; fi
    step "Swift format"; format_check
  fi
  step "notices"; check_notices
  step "16 KB alignment"; check_16k
  if [ $full = 1 ]; then
    step "bundle ABIs"; check_bundle_abis
  fi
  printf '\nverify: OK\n'
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
